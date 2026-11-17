package dev.amenhancer.module.update

import android.app.Notification
import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundDownloadTest {
    private val asset = UpdateAsset(42, "LyricWave-9.0.0.apk", 4, "https://example.test", "https://example.test")
    private val build = UpdateBuild(999, "9.0.0", asset.name, "a".repeat(64))
    private val release = AppUpdateRelease("v9.0.0", "9.0.0", "", "2026-11-08T00:00:00Z", false, AppUpdateClient.RELEASES_URL,
        listOf(asset), build, UpdateComparison.NEWER, 1)
    @Before fun reset() { UpdateTransfer.state = DownloadState(DownloadPhase.READY); UpdateTransfer.file = null; UpdateTransfer.build = null; UpdateTransfer.controlToken = "" }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); if (System.nanoTime() > end) fail("Timeout"); Thread.sleep(10) }
    }
    @Test fun `background transfer uses foreground notification and does not open a module Activity`() {
        val controller = Robolectric.buildService(UpdateDownloadService::class.java).create()
        val service = controller.get(); val started = CountDownLatch(1); val releaseGate = CountDownLatch(1)
        service.checkRelease = { release }
        service.fetchFile = { _, _, directory, _, request, progress ->
            assertEquals(128, request.connections)
            progress(UpdateDownloadProgress(2, 4, 128)); started.countDown(); releaseGate.await(5, TimeUnit.SECONDS)
            File(directory, "verified-test.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        }
        var verified = false; service.verifyFile = { _, _ -> verified = true }
        val ticket = UpdateTransfer.issue(42, 128, false)
        service.onStartCommand(Intent(service, UpdateDownloadService::class.java).setAction("begin").putExtra("ticket", ticket), 0, 1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val notification = shadowOf(service).lastForegroundNotification
        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(128, UpdateTransfer.state.connections)
        assertNull(shadowOf(service).nextStartedActivity)
        releaseGate.countDown(); waitUntil { UpdateTransfer.state.phase == DownloadPhase.VERIFIED }
        assertTrue(verified); assertNotNull(UpdateTransfer.file)
        controller.destroy()
    }
    @Test @Config(sdk = [36]) fun `Android 16 download requests promoted live progress notification`() {
        val controller = Robolectric.buildService(UpdateDownloadService::class.java).create()
        val service = controller.get(); val started = CountDownLatch(1); val gate = CountDownLatch(1)
        service.checkRelease = { started.countDown(); gate.await(5, TimeUnit.SECONDS); release }
        val ticket = UpdateTransfer.issue(42, 8, false)
        try {
            service.onStartCommand(Intent(service, UpdateDownloadService::class.java).setAction("begin").putExtra("ticket", ticket), 0, 1)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val notification = shadowOf(service).lastForegroundNotification
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
            assertEquals("android.app.Notification\$ProgressStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            service.onStartCommand(Intent(service, UpdateDownloadService::class.java).setAction("cancel").putExtra("ticket", ticket), 0, 2)
        } finally { gate.countDown(); controller.destroy() }
    }
    @Test fun `active transfer notification exposes actual speed and remaining seconds`() {
        val controller = Robolectric.buildService(UpdateDownloadService::class.java).create()
        val service = controller.get()
        UpdateTransfer.state = DownloadState(DownloadPhase.DOWNLOADING, asset.name, 2048, 8192, 8)
        service.recordTransferRate(0, 8192, 100)
        service.recordTransferRate(2048, 8192, 1100)
        val subtext = service.notification().extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()
        assertTrue(subtext, subtext.contains("2 KB/s"))
        assertTrue(subtext, subtext.contains("3"))
        UpdateTransfer.state = DownloadState(DownloadPhase.FAILED, reason = "network")
        assertNull(service.notification().extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        controller.destroy()
    }
    @Test fun `forged transfer ticket never reaches network or starts a foreground transfer`() {
        val controller = Robolectric.buildService(UpdateDownloadService::class.java).create()
        val service = controller.get(); service.checkRelease = { fail("Unexpected network"); release }
        service.onStartCommand(Intent(service, UpdateDownloadService::class.java).setAction("begin").putExtra("ticket", "forged"), 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf); assertNull(shadowOf(service).lastForegroundNotification)
        controller.destroy()
    }
    @Test fun `unverified cached file never creates an installer action`() {
        val provider = Robolectric.buildContentProvider(UpdateControlProvider::class.java).create().get()
        val app = RuntimeEnvironment.getApplication()
        UpdateTransfer.file = File(app.cacheDir, "tampered.apk").apply { writeText("not a signed APK") }
        UpdateTransfer.build = build
        assertTrue(runCatching { provider.call("install", null, null) }.isFailure)
        UpdateTransfer.file?.delete()
    }
    @Test fun `real downloader failover reaches foreground source status and still verifies the APK`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val routedAsset = asset.copy(downloadUrl = AppUpdateClient.ORIGIN + "/api/v1/download/42",
            upstreamUrl = "https://github.com/Laoch832/LyricWave/releases/download/v9.0.0/" + asset.name)
        val routedBuild = build.copy(sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
        val controller = Robolectric.buildService(UpdateDownloadService::class.java).create()
        val service = controller.get(); val started = CountDownLatch(1); val gate = CountDownLatch(1)
        service.checkRelease = { release.copy(apks = listOf(routedAsset), build = routedBuild) }
        service.fetchFile = { a, b, directory, cancellation, request, progress ->
            val downloader = AppUpdateDownloader(connect = { url -> object : java.net.HttpURLConnection(url) {
                override fun getResponseCode() = if (url.host == "github.com") 200 else 503
                override fun getHeaderField(name: String): String? = if (name == "Content-Length") "4" else null
                override fun getInputStream() = java.io.ByteArrayInputStream(data)
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            } })
            val file = downloader.download(a, b, directory, cancellation, request.official, progress)
            started.countDown(); gate.await(5, TimeUnit.SECONDS); file
        }
        var verified = false
        service.verifyFile = { file, metadata -> assertArrayEquals(data, file.readBytes()); assertEquals(routedBuild, metadata); verified = true }
        try {
            val ticket = UpdateTransfer.issue(42, 8, false)
            service.onStartCommand(Intent(service, UpdateDownloadService::class.java).setAction("begin").putExtra("ticket", ticket), 0, 1)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(UpdateTransfer.snapshot(service).getBoolean("official"))
            assertTrue(service.notification().extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("GitHub"))
            assertNull(shadowOf(service).nextStartedActivity)
            gate.countDown(); waitUntil { UpdateTransfer.state.phase == DownloadPhase.VERIFIED }
            assertTrue(verified)
        } finally { gate.countDown(); controller.destroy() }
    }

    @Test fun `late lower parallel progress cannot move the notification backwards`() {
        val controller=Robolectric.buildService(UpdateDownloadService::class.java).create()
        val service=controller.get();val started=CountDownLatch(1);val gate=CountDownLatch(1)
        service.checkRelease={ release }
        service.fetchFile={ _,_,directory,_,request,progress ->
            assertFalse(request.resumable)
            progress(UpdateDownloadProgress(2,4,8));progress(UpdateDownloadProgress(4,4,8))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(400))
            progress(UpdateDownloadProgress(3,4,8));started.countDown();gate.await(5,TimeUnit.SECONDS)
            File(directory,"test.apk").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        }
        service.verifyFile={ _,_-> }
        try {
            val ticket=UpdateTransfer.issue(42,8,false,resumable=false)
            service.onStartCommand(Intent(service,UpdateDownloadService::class.java).setAction("begin").putExtra("ticket",ticket),0,1)
            assertTrue(started.await(5,TimeUnit.SECONDS));assertEquals(4L,UpdateTransfer.state.bytes)
            assertTrue(service.notification().extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("100%"))
            gate.countDown();waitUntil { UpdateTransfer.state.phase==DownloadPhase.VERIFIED }
        } finally { gate.countDown();controller.destroy() }
    }

}
