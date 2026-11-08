package dev.amenhancer.module.update

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One operation owns its sockets. Cancellation also unblocks an outstanding read. */
class UpdateDownloadCancellation {
    @Volatile var cancelled = false; private set
    private val connections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    fun cancel() { cancelled = true; synchronized(connections) { connections.toList() }.forEach { it.disconnect() } }
    internal fun check() { if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException() }
    internal fun add(connection: HttpURLConnection) { connections.add(connection); check() }
    internal fun remove(connection: HttpURLConnection) { connections.remove(connection); connection.disconnect() }
}

data class UpdateDownloadProgress(val bytes: Long, val total: Long, val connections: Int)

/** Streaming, bounded ranges; no external download app, credentials, or retained signed URLs. */
class AppUpdateDownloader(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val parallelThreshold: Long = 4L * 1024 * 1024,
) {
    fun download(asset: UpdateAsset, build: UpdateBuild, directory: File,
        cancellation: UpdateDownloadCancellation, official: Boolean = false,
        progress: (UpdateDownloadProgress) -> Unit = {},
    ): File {
        if (asset.name != build.apk || asset.id <= 0 || asset.size !in 1..MAX_APK_BYTES ||
            !build.sha256.matches(Regex("[a-f0-9]{64}"))) throw AppUpdateException("metadata")
        if (!directory.isDirectory && !directory.mkdirs()) throw AppUpdateException("storage")
        val temporary = File.createTempFile("update-", ".part", directory)
        try {
            val url = if (official) asset.upstreamUrl else asset.downloadUrl
            val bytes = AtomicLong()
            val probe = open(url, "bytes=0-0", null, cancellation)
            var useRanges = false
            var validator: String? = null
            try {
                when (probe.responseCode) {
                    200 -> stream(probe, temporary, 0, asset.size, cancellation) {
                        progress(UpdateDownloadProgress(bytes.addAndGet(it.toLong()), asset.size, 1))
                    }
                    206 -> {
                        range(probe, 0, 0, asset.size)
                        // A probe must contain exactly one byte too; discard it before disjoint full ranges.
                        probe.inputStream.use { if (it.read() < 0 || it.read() != -1) throw AppUpdateException("size") }
                        useRanges = true
                        validator = probe.getHeaderField("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                    }
                    else -> throw AppUpdateException("http", probe.responseCode)
                }
            } finally { cancellation.remove(probe) }
            if (useRanges) {
                val count = if (asset.size >= parallelThreshold) 4 else 1
                val pool = Executors.newFixedThreadPool(count)
                val segmentCancellation = UpdateDownloadCancellation()
                // Parent cancellation is checked during every read; sockets also have finite timeouts.
                try {
                    val tasks = (0 until count).map { index -> pool.submit {
                        val start = asset.size * index / count
                        val end = asset.size * (index + 1) / count - 1
                        cancellation.check()
                        val connection = open(url, "bytes=$start-$end", validator, cancellation)
                        try {
                            segmentCancellation.add(connection)
                            if (connection.responseCode != 206) throw AppUpdateException("range")
                            range(connection, start, end, asset.size)
                            if (validator != null && connection.getHeaderField("ETag") != validator) throw AppUpdateException("range")
                            stream(connection, temporary, start, end - start + 1, cancellation) {
                                progress(UpdateDownloadProgress(bytes.addAndGet(it.toLong()), asset.size, count))
                            }
                        } finally { segmentCancellation.remove(connection); cancellation.remove(connection) }
                    } }
                    tasks.forEach { it.get() }
                } catch (error: Exception) {
                    segmentCancellation.cancel()
                    pool.shutdownNow()
                    // Wait until no worker can write the old file before the single-stream replacement.
                    while (!pool.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS)) cancellation.check()
                    cancellation.check()
                    bytes.set(0)
                    progress(UpdateDownloadProgress(0, asset.size, 1))
                    temporary.outputStream().use { }
                    val single = open(url, null, null, cancellation)
                    try {
                        if (single.responseCode != 200) throw AppUpdateException("http", single.responseCode)
                        stream(single, temporary, 0, asset.size, cancellation) {
                            progress(UpdateDownloadProgress(bytes.addAndGet(it.toLong()), asset.size, 1))
                        }
                    } finally { cancellation.remove(single) }
                } finally { pool.shutdownNow() }
            }
            cancellation.check()
            if (temporary.length() != asset.size) throw AppUpdateException("size")
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { cancellation.check(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != build.sha256) throw AppUpdateException("checksum")
            val complete = File(directory, "update-${asset.id}-${build.sha256.take(12)}.apk")
            if (complete.exists() && !complete.delete()) throw AppUpdateException("storage")
            if (!temporary.renameTo(complete)) throw AppUpdateException("storage")
            return complete
        } finally { temporary.delete() }
    }

    private fun open(url: String, range: String?, validator: String?, cancel: UpdateDownloadCancellation): HttpURLConnection {
        var next = URI(url)
        repeat(5) {
            cancel.check()
            if (!AppUpdateHttpTransport.allowed(next)) throw AppUpdateException("redirect")
            val connection = connect(next.toURL())
            try {
                cancel.add(connection)
                connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "LyricWave-update/1")
                connection.setRequestProperty("Cookie", ""); connection.setRequestProperty("Authorization", "")
                range?.let { connection.setRequestProperty("Range", it) }
                validator?.let { connection.setRequestProperty("If-Range", it) }
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    next = next.resolve(connection.getHeaderField("Location") ?: throw AppUpdateException("redirect"))
                    cancel.remove(connection)
                } else return connection
            } catch (error: Exception) { cancel.remove(connection); throw error }
        }
        throw AppUpdateException("redirect")
    }

    private fun range(connection: HttpURLConnection, start: Long, end: Long, total: Long) {
        if (connection.getHeaderField("Content-Range") != "bytes $start-$end/$total") throw AppUpdateException("range")
    }

    private fun stream(connection: HttpURLConnection, file: File, offset: Long, expected: Long,
        cancel: UpdateDownloadCancellation, progress: (Int) -> Unit,
    ) {
        if (connection.getHeaderField("Content-Encoding")?.let { !it.equals("identity", true) } == true)
            throw AppUpdateException("encoding")
        if (connection.responseCode == 200 && connection.getHeaderField("Content-Range") != null) throw AppUpdateException("range")
        connection.getHeaderField("Content-Length")?.let {
            if (it.toLongOrNull() != expected) throw AppUpdateException("size")
        }
        connection.inputStream.use { input -> RandomAccessFile(file, "rw").use { output ->
            output.seek(offset)
            var remaining = expected
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                cancel.check()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw AppUpdateException("size")
                output.write(buffer, 0, n); remaining -= n; progress(n)
            }
            if (input.read() != -1) throw AppUpdateException("size")
        } }
    }

    companion object { const val MAX_APK_BYTES = 512L * 1024 * 1024 }
}
