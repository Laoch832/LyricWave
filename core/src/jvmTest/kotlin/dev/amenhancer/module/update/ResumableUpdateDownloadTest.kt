package dev.amenhancer.module.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableUpdateDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val body = ByteArray(600000) { (it % 127).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val asset = UpdateAsset(71, "LyricWave-9.0.0.apk", body.size.toLong(), AppUpdateClient.ORIGIN+"/api/v1/download/71", "https://github.com/Laoch832/LyricWave/releases/download/v9.0.0/LyricWave-9.0.0.apk")
    private val build = UpdateBuild(900, "9.0.0", asset.name, hash(body))
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private fun transport(failing: Boolean = false, ignoreRange: Boolean = false): (URL) -> HttpURLConnection = { url -> object : HttpURLConnection(url) {
        private val requested by lazy { getRequestProperty("Range").orEmpty().also { requests.add(it) } }
        private val bounds by lazy { requested.removePrefix("bytes=").split('-').map(String::toInt) }
        override fun getResponseCode() = if (failing && bounds[0] >= 200000) 503 else if (ignoreRange) 200 else 206
        override fun getHeaderField(name: String): String? = when(name) {
            "ETag" -> "\"stable\""
            "Content-Range" -> if (ignoreRange) null else "bytes ${bounds[0]}-${bounds[1]}/${body.size}"
            "Content-Length" -> if (ignoreRange) body.size.toString() else (bounds[1]-bounds[0]+1).toString()
            else -> null
        }
        override fun getInputStream() = ByteArrayInputStream(if (ignoreRange) body else body.copyOfRange(bounds[0], bounds[1]+1))
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    } }
    private fun downloader(failing: Boolean = false, ignoreRange: Boolean = false) = AppUpdateDownloader(transport(failing, ignoreRange), parallelism=1, resumable=true)
    private fun interrupted() {
        assertTrue(runCatching { downloader(failing=true).download(asset, build, temporary.root, UpdateDownloadCancellation(), official=true) }.isFailure)
        assertTrue(temporary.root.listFiles()!!.any { it.extension == "json" })
    }
    @Test fun `new downloader instance rehashes and reuses completed blocks after failure`() {
        interrupted(); requests.clear()
        val progress = mutableListOf<UpdateDownloadProgress>()
        val apk = downloader().download(asset, build, temporary.root, UpdateDownloadCancellation(), official=true, progress=progress::add)
        assertArrayEquals(body, apk.readBytes()); assertFalse(requests.contains("bytes=0-199999"))
        assertEquals(200000L, progress.first().resumedBytes)
        assertFalse(temporary.root.listFiles()!!.any { it.extension in listOf("json","part") })
    }
    @Test fun `corrupted retained block is fetched again instead of trusted`() {
        interrupted()
        val part = temporary.root.listFiles()!!.single { it.extension == "part" }
        RandomAccessFile(part,"rw").use { it.seek(0); it.write(127) }; requests.clear()
        val apk = downloader().download(asset, build, temporary.root, UpdateDownloadCancellation(), official=true)
        assertArrayEquals(body, apk.readBytes()); assertTrue(requests.contains("bytes=0-199999"))
    }
    @Test fun `mismatched version never reuses checkpoint bytes`() {
        interrupted(); requests.clear()
        val updates=mutableListOf<UpdateDownloadProgress>()
        downloader().download(asset,build.copy(versionCode=901),temporary.root,UpdateDownloadCancellation(),official=true,progress=updates::add)
        assertEquals(0L,updates.first().resumedBytes); assertTrue(requests.contains("bytes=0-199999"))
    }
    @Test fun `cancel while restoring removes unfinished cache without a network request`() {
        interrupted(); requests.clear();val cancellation=UpdateDownloadCancellation()
        assertTrue(runCatching { downloader().download(asset,build,temporary.root,cancellation,official=true) { cancellation.cancel() } }.exceptionOrNull() is CancellationException)
        assertTrue(requests.isEmpty()); assertFalse(temporary.root.listFiles()!!.any { it.extension in listOf("json","part","apk") })
    }
    @Test fun `range unavailable degrades to verified single stream`() {
        val apk=downloader(ignoreRange=true).download(asset,build,temporary.root,UpdateDownloadCancellation(),official=true)
        assertArrayEquals(body,apk.readBytes());assertFalse(temporary.root.listFiles()!!.any { it.extension in listOf("json","part") })
    }
    @Test fun `cached complete file can be verified without network and explicit cancellation still wins`() {
        val apk=downloader().download(asset,build,temporary.root,UpdateDownloadCancellation(),official=true)
        val noNetwork=AppUpdateDownloader(connect={ throw AssertionError("Network must not be used") },resumable=true)
        val updates=mutableListOf<UpdateDownloadProgress>()
        assertEquals(apk,noNetwork.download(asset,build,temporary.root,UpdateDownloadCancellation(),official=true,progress=updates::add))
        assertEquals(asset.size,updates.single().resumedBytes)
        val cancelled=UpdateDownloadCancellation().apply { cancel() }
        assertTrue(runCatching { noNetwork.download(asset,build,temporary.root,cancelled) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `only one writer may own an exact asset`() {
        val file=File(temporary.root,"resume-${asset.id}-${build.sha256}.lock")
        RandomAccessFile(file,"rw").use { owner -> owner.channel.lock().use {
            val error=runCatching { downloader().download(asset,build,temporary.root,UpdateDownloadCancellation()) }.exceptionOrNull()
            assertEquals("busy",(error as AppUpdateException).reason); assertTrue(requests.isEmpty())
        } }
    }
    @Test fun `checksum failure removes all unverified payloads`() {
        val error=runCatching { downloader().download(asset,build.copy(sha256="0".repeat(64)),temporary.root,UpdateDownloadCancellation(),official=true) }.exceptionOrNull()
        assertEquals("checksum",(error as AppUpdateException).reason)
        assertFalse(temporary.root.listFiles()!!.any { it.extension in listOf("part","json","apk") })
    }
    @Test fun `shared durable blocks remain separate from each session output`() {
        val staging=temporary.newFolder("staging");val first=temporary.newFolder("first");val second=temporary.newFolder("second")
        val active=File(first,"active.apk").apply { writeText("active foreground transfer") }
        assertTrue(runCatching { AppUpdateDownloader(transport(failing=true),parallelism=1,resumable=true,resumeDirectory=staging)
            .download(asset,build,first,UpdateDownloadCancellation(),official=true) }.isFailure)
        val updates=mutableListOf<UpdateDownloadProgress>();requests.clear()
        val result=AppUpdateDownloader(transport(),parallelism=1,resumable=true,resumeDirectory=staging)
            .download(asset,build,second,UpdateDownloadCancellation(),official=true,progress=updates::add)
        assertEquals(second,result.parentFile);assertEquals("active foreground transfer",active.readText())
        assertEquals(200000L,updates.first().resumedBytes);assertFalse(requests.contains("bytes=0-199999"))
        assertArrayEquals(body,result.readBytes())
    }

}
