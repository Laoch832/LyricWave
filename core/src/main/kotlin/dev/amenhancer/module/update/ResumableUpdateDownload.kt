package dev.amenhancer.module.update

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One durable owner per exact APK. Only completed, rehashed ranges survive a failed request. */
internal class ResumableUpdateDownload(private val transport: AppUpdateDownloader, private val parallelism: Int, private val storage: File? = null) {
    fun download(asset: UpdateAsset, build: UpdateBuild, directory: File, cancellation: UpdateDownloadCancellation,
        official: Boolean, progress: (UpdateDownloadProgress) -> Unit): File {
        if (!(asset.id > 0 && asset.name == build.apk && asset.size in 1..AppUpdateDownloader.MAX_APK_BYTES &&
            build.sha256.matches(Regex("[a-f0-9]{64}")))) throw AppUpdateException("metadata")
        if (!directory.isDirectory && !directory.mkdirs()) throw AppUpdateException("storage")
        val checkpointDir = storage ?: directory
        if (!checkpointDir.isDirectory && !checkpointDir.mkdirs()) throw AppUpdateException("storage")
        val stem = "resume-${asset.id}-${build.sha256}"
        val part = File(checkpointDir, "$stem.part")
        val journal = File(checkpointDir, "$stem.json")
        val lockFile = File(checkpointDir, "$stem.lock")
        if (Files.isSymbolicLink(lockFile.toPath())) throw AppUpdateException("storage")
        prune(checkpointDir, stem)
        RandomAccessFile(lockFile, "rw").use { owner ->
            val lease = try { owner.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                ?: throw AppUpdateException("busy")
            lease.use {
                cancellation.check()
                val existing = File(directory, "update-${asset.id}-${build.sha256.take(12)}.apk")
                if (Files.isSymbolicLink(existing.toPath())) throw AppUpdateException("storage")
                if (existing.isFile) {
                    if (existing.length() == asset.size && hash(existing, 0, asset.size, cancellation) == build.sha256) {
                        part.delete(); journal.delete()
                        progress(UpdateDownloadProgress(asset.size, asset.size, 1, official, asset.size)); return existing
                    }
                    existing.delete()
                }
                // Symlink targets are never used as cache storage or installed files.
                if (Files.isSymbolicLink(part.toPath()) || Files.isSymbolicLink(journal.toPath())) throw AppUpdateException("storage")
                if (part.length() > asset.size) { part.delete(); journal.delete() }
                val saved = if (journal.isFile && journal.length() <= 32768) runCatching {
                    UpdateResumeCheckpoint.decode(journal.readText(), asset, build)?.verifiedBlocks(part, cancellation::check)
                }.getOrNull() else null
                if (cancellation.cancelled && cancellation.retainsCheckpoint) cancellation.check()
                val blocks = (saved ?: emptyList()).toMutableList()
                if (saved == null) { part.delete(); journal.delete() }
                val bytes = AtomicLong(blocks.sumOf { it.length })
                val count = ((asset.size + BLOCK_BYTES - 1) / BLOCK_BYTES).coerceIn(1, 128).toInt()
                val ranges = (0 until count).map { index ->
                    val start = asset.size * index / count
                    start to (asset.size * (index + 1) / count - start)
                }
                // A different segmentation is safe: only exact verified blocks can be skipped.
                blocks.removeAll { b -> ranges.none { it.first == b.start && it.second == b.length } }
                bytes.set(blocks.sumOf { it.length })
                val reused = bytes.get()
                val workers = minOf(parallelism.coerceIn(1, 128), count)
                fun report() = progress(UpdateDownloadProgress(bytes.get(), asset.size, workers, official, reused))
                report()
                if (reused == asset.size && part.length() == asset.size && hash(part, 0, asset.size, cancellation) == build.sha256) {
                    val result = File(directory, "update-${asset.id}-${build.sha256.take(12)}.apk")
                    if (result.exists() && !result.delete()) throw AppUpdateException("storage")
                    if (!part.renameTo(result)) throw AppUpdateException("storage")
                    journal.delete(); return result
                }
                val url = if (official) asset.upstreamUrl else asset.downloadUrl
                val peers = UpdateDownloadCancellation()
                val pool = Executors.newFixedThreadPool(workers)
                var success = false
                try {
                    val probe = transport.open(url, "bytes=0-0", null, cancellation)
                    val validator: String?
                    try {
                        if (probe.responseCode == 200) {
                            // Ranges are unavailable; the established verified single-stream path owns this transfer.
                            return transport.downloadWithoutResume(asset, build, directory, cancellation, official, progress)
                                .also { part.delete(); journal.delete() }
                        }
                        if (probe.responseCode != 206) throw AppUpdateException("http", probe.responseCode)
                        transport.range(probe, 0, 0, asset.size)
                        probe.inputStream.use { if (it.read() < 0 || it.read() != -1) throw AppUpdateException("size") }
                        validator = probe.getHeaderField("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                    } finally { cancellation.remove(probe) }
                    val writeLock = Any()
                    val tasks = ranges.filter { range -> blocks.none { it.start == range.first && it.length == range.second } }.map { (start, length) ->
                        pool.submit {
                            cancellation.check(); peers.check()
                            val connection = transport.open(url, "bytes=$start-${start+length-1}", validator, cancellation)
                            try {
                                peers.add(connection)
                                if (connection.responseCode != 206) throw AppUpdateException("http", connection.responseCode)
                                transport.range(connection, start, start+length-1, asset.size)
                                if (validator != null && connection.getHeaderField("ETag") != validator) throw AppUpdateException("range")
                                transport.stream(connection, part, start, length, cancellation) { n -> bytes.addAndGet(n.toLong()); report() }
                                cancellation.check(); peers.check()
                                RandomAccessFile(part, "rw").use { it.fd.sync() }
                                val block = UpdateResumeBlock(start, length, hash(part, start, length, cancellation))
                                synchronized(writeLock) {
                                    blocks.add(block)
                                    atomicWrite(journal, UpdateResumeCheckpoint.create(asset, build, blocks).encode())
                                }
                            } finally { peers.remove(connection); cancellation.remove(connection) }
                        }
                    }
                    try { tasks.forEach { it.get() } }
                    catch (error: Exception) {
                        peers.cancel(); pool.shutdownNow()
                        while (!pool.awaitTermination(100, TimeUnit.MILLISECONDS)) cancellation.check()
                        cancellation.check()
                        throw ((error as? java.util.concurrent.ExecutionException)?.cause ?: error)
                    }
                    cancellation.check()
                    if (part.length() != asset.size || hash(part, 0, asset.size, cancellation) != build.sha256) {
                        part.delete(); journal.delete(); throw AppUpdateException("checksum")
                    }
                    val result = File(directory, "update-${asset.id}-${build.sha256.take(12)}.apk")
                    if (result.exists() && !result.delete()) throw AppUpdateException("storage")
                    if (!part.renameTo(result)) throw AppUpdateException("storage")
                    journal.delete(); success = true
                    progress(UpdateDownloadProgress(asset.size, asset.size, workers, official, reused))
                    return result
                } finally {
                    peers.cancel(); pool.shutdownNow()
                    // Never release ownership while a late worker can still write or checkpoint old bytes.
                    var interrupted = false
                    while (!pool.isTerminated) try { pool.awaitTermination(100, TimeUnit.MILLISECONDS) }
                        catch (_: InterruptedException) { interrupted = true }
                    if (cancellation.cancelled && !cancellation.retainsCheckpoint) { part.delete(); journal.delete() }
                    if (success) journal.delete()
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }
    private fun prune(directory: File, current: String) {
        val journals = directory.listFiles().orEmpty().filter { it.name.matches(Regex("resume-[0-9]+-[a-f0-9]{64}\\.(json|part)")) && !it.name.startsWith("$current.") }
            .sortedByDescending { it.lastModified() }.distinctBy { it.name.substringBeforeLast('.') }
        for (entry in journals.drop(2)) {
            val stem = entry.name.substringBeforeLast('.')
            runCatching { RandomAccessFile(File(directory, "$stem.lock"), "rw").use { file ->
                file.channel.tryLock()?.use { File(directory, "$stem.json").delete(); File(directory, "$stem.part").delete() }
            } }
        }
    }
    private fun hash(file: File, offset: Long, size: Long, cancellation: UpdateDownloadCancellation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset); var left = size; val buffer = ByteArray(64*1024)
            while (left > 0) {
                cancellation.check(); val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (n < 0) throw AppUpdateException("size")
                digest.update(buffer, 0, n); left -= n
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun atomicWrite(file: File, text: String) {
        val pending = File.createTempFile("checkpoint-", ".tmp", file.parentFile)
        try {
            java.io.FileOutputStream(pending).use { it.write(text.toByteArray()); it.fd.sync() }
            try { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (_: java.io.IOException) { throw AppUpdateException("storage") }
        finally { pending.delete() }
    }
    private companion object { const val BLOCK_BYTES = 256 * 1024L }
}
