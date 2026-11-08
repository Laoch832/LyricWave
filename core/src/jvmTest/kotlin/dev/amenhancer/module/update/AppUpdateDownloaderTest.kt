package dev.amenhancer.module.update

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val body = ByteArray(4096) { (it % 127).toByte() }
    private val asset = UpdateAsset(42, "LyricWave-test.apk", body.size.toLong(),
        AppUpdateClient.ORIGIN + "/api/v1/download/42", "https://github.com/Laoch832/LyricWave/releases/download/vtest/LyricWave-test.apk")
    private val build = UpdateBuild(130, "test", asset.name,
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) })
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())
    private fun transport(ignoreRange: Boolean = false, brokenSegments: Boolean = false,
        redirect: String? = null, truncate: Boolean = false, cancel: UpdateDownloadCancellation? = null,
    ): (URL) -> HttpURLConnection = { url -> object : HttpURLConnection(url) {
        private val requested by lazy { getRequestProperty("Range").also { ranges.add(it) } }
        private val endpoints by lazy { requested?.removePrefix("bytes=")?.split('-')?.map(String::toInt) }
        private val partial by lazy { requested != null && !ignoreRange && !(brokenSegments && requested != "bytes=0-0") }
        override fun getResponseCode() = if (redirect != null) 302 else if (partial) 206 else 200
        override fun getHeaderField(name: String): String? = when (name) {
            "Location" -> redirect
            "ETag" -> "\"fixed\""
            "Content-Range" -> if (partial) "bytes ${endpoints!![0]}-${endpoints!![1]}/${body.size}" else null
            "Content-Length" -> (if (partial) endpoints!![1] - endpoints!![0] + 1 else body.size).toString()
            else -> null
        }
        override fun getInputStream(): ByteArrayInputStream {
            val bytes = if (partial) body.copyOfRange(endpoints!![0], endpoints!![1] + 1) else body
            cancel?.cancel()
            return ByteArrayInputStream(if (truncate) bytes.copyOf(bytes.size / 2) else bytes)
        }
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    } }
    @Test fun `four ranges produce exact verified bytes`() {
        val states = Collections.synchronizedList(mutableListOf<UpdateDownloadProgress>())
        val file = AppUpdateDownloader(transport(), 1, 4, 1).download(asset, build, temporary.root, UpdateDownloadCancellation(), progress = states::add)
        assertArrayEquals(body, file.readBytes())
        assertEquals(setOf("bytes=0-0", "bytes=0-1023", "bytes=1024-2047", "bytes=2048-3071", "bytes=3072-4095"), ranges.toSet())
        assertTrue(states.any { it.connections == 4 && it.bytes == asset.size })
    }
    @Test fun `ignored probe range streams the same response without another download`() {
        val file = AppUpdateDownloader(transport(ignoreRange = true), 1, 4, 1).download(asset, build, temporary.root, UpdateDownloadCancellation())
        assertArrayEquals(body, file.readBytes()); assertEquals(listOf("bytes=0-0"), ranges)
    }
    @Test fun `range rejected mid-download restarts with a single complete response`() {
        val file = AppUpdateDownloader(transport(brokenSegments = true), 1, 4, 1).download(asset, build, temporary.root, UpdateDownloadCancellation())
        assertArrayEquals(body, file.readBytes()); assertEquals(1, ranges.count { it == null })
    }
    @Test fun `hash failure never leaves an installable apk`() {
        val error = runCatching { AppUpdateDownloader(transport()).download(asset, build.copy(sha256 = "0".repeat(64)), temporary.root, UpdateDownloadCancellation()) }.exceptionOrNull()
        assertEquals("checksum", (error as AppUpdateException).reason)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
    @Test fun `truncated response is rejected and cleaned`() {
        assertTrue(runCatching { AppUpdateDownloader(transport(ignoreRange = true, truncate = true)).download(asset, build, temporary.root, UpdateDownloadCancellation()) }.isFailure)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
    @Test fun `untrusted redirects never reach the connector`() {
        val error = runCatching { AppUpdateDownloader(transport(redirect = "https://attacker.invalid/app.apk")).download(asset, build, temporary.root, UpdateDownloadCancellation()) }.exceptionOrNull()
        assertEquals("redirect", (error as AppUpdateException).reason)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
    @Test fun `cancellation discards partial files`() {
        val cancellation = UpdateDownloadCancellation()
        assertTrue(runCatching { AppUpdateDownloader(transport(ignoreRange = true, cancel = cancellation)).download(asset, build, temporary.root, cancellation) }.exceptionOrNull() is CancellationException)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
    @Test fun `128 connections are bounded and produce the exact verified file`() {
        val file = AppUpdateDownloader(transport(), 1, 999, 1).download(asset, build, temporary.root, UpdateDownloadCancellation())
        assertArrayEquals(body, file.readBytes())
        assertEquals(129, ranges.size) // one probe plus at most 128 disjoint segments
        assertEquals(129, ranges.distinct().size)
    }
    @Test fun `automatic schedule throttles success failure and clock rollback`() {
        val now = 100_000_000L
        assertTrue(UpdateCheckSchedule.due(now, 0, 0))
        assertFalse(UpdateCheckSchedule.due(now + UpdateCheckSchedule.SUCCESS_INTERVAL - 1, now, now))
        assertTrue(UpdateCheckSchedule.due(now + UpdateCheckSchedule.SUCCESS_INTERVAL, now, now))
        assertFalse(UpdateCheckSchedule.due(now + UpdateCheckSchedule.FAILURE_INTERVAL - 1, now, now - 1))
        assertTrue(UpdateCheckSchedule.due(now + UpdateCheckSchedule.FAILURE_INTERVAL, now, now - 1))
        assertTrue(UpdateCheckSchedule.due(now - 1, now, now))
    }
}
