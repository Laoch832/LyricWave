package dev.amenhancer.module.update

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.amenhancer.module.i18n.ModuleText
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateDownloadActivityTest {
    private val originalFactory = UpdateDownloadActivity.createSession
    private val asset = UpdateAsset(42, "LyricWave-test.apk", 4, AppUpdateClient.ORIGIN + "/api/v1/download/42",
        "https://github.com/Laoch832/LyricWave/releases/download/vtest/LyricWave-test.apk")
    private val build = UpdateBuild(130, "test", asset.name, "a".repeat(64))
    private val release = AppUpdateRelease("vtest", "test", "", "2026-11-08T00:00:00Z", true,
        AppUpdateClient.RELEASES_URL, listOf(asset), build, UpdateComparison.NEWER, 1)
    @After fun reset() { UpdateDownloadActivity.createSession = originalFactory }
    private fun button(root: View, text: String): Button? {
        if (root is Button && root.text.toString() == text && root.visibility == View.VISIBLE) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) button(root.getChildAt(index), text)?.let { return it }
        return null
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("Timed out waiting for UI")
            Thread.sleep(10)
        }
    }
    @Test fun `actual start button downloads internally and exposes install only after archive verification`() {
        val fetched = AtomicInteger(); val verified = AtomicInteger()
        UpdateDownloadActivity.createSession = { context, id, official ->
            UpdateDownloadSession(context, id, official, check = { release },
                fetch = { _, _, directory, _, _, progress ->
                    fetched.incrementAndGet(); progress(UpdateDownloadProgress(4, 4, 1))
                    File(directory, "test.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
                }, archiveCheck = { file, _ -> assertEquals(4L, file.length()); verified.incrementAndGet() })
        }
        val intent = Intent(RuntimeEnvironment.getApplication(), UpdateDownloadActivity::class.java).putExtra("asset_id", 42L)
        val controller = Robolectric.buildActivity(UpdateDownloadActivity::class.java, intent).setup()
        val activity = controller.get(); val root = activity.window.decorView
        await { button(root, ModuleText.UPDATE_START.text()) != null }
        assertEquals(0, fetched.get()) // An exported Activity cannot silently start a download.
        assertNull(button(root, ModuleText.UPDATE_INSTALL.text()))
        button(root, ModuleText.UPDATE_START.text())!!.performClick()
        await { button(root, ModuleText.UPDATE_INSTALL.text()) != null }
        assertEquals(1, fetched.get()); assertEquals(1, verified.get())
        assertNull(shadowOf(activity).nextStartedActivity) // No browser or external downloader.
        controller.pause().stop().destroy()
    }
    @Test fun `foreign asset id never reaches download and cannot expose install`() {
        val fetched = AtomicInteger()
        lateinit var session: UpdateDownloadSession
        UpdateDownloadActivity.createSession = { context, id, official ->
            UpdateDownloadSession(context, id, official, check = { release },
                fetch = { _, _, directory, _, _, _ -> fetched.incrementAndGet(); File(directory, "bad.apk") },
                archiveCheck = { _, _ -> fail("Unexpected archive") }).also { session = it }
        }
        val controller = Robolectric.buildActivity(UpdateDownloadActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), UpdateDownloadActivity::class.java).putExtra("asset_id", 999L)).setup()
        await { session.state.phase == DownloadPhase.FAILED }
        assertEquals(0, fetched.get()); assertNull(button(controller.get().window.decorView, ModuleText.UPDATE_INSTALL.text()))
        controller.pause().stop().destroy()
    }
    @Test fun `cancel button stops active operation and closes page`() {
        val started = CountDownLatch(1); val stopped = CountDownLatch(1)
        UpdateDownloadActivity.createSession = { context, id, official ->
            UpdateDownloadSession(context, id, official, check = { release },
                fetch = { _, _, _, cancellation, _, _ ->
                    started.countDown()
                    try { while (!cancellation.cancelled) Thread.sleep(10) }
                    finally { stopped.countDown() }
                    throw java.util.concurrent.CancellationException()
                }, archiveCheck = { _, _ -> fail("Cancelled") })
        }
        val controller = Robolectric.buildActivity(UpdateDownloadActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), UpdateDownloadActivity::class.java).putExtra("asset_id", 42L)).setup()
        val activity = controller.get(); val root = activity.window.decorView
        await { button(root, ModuleText.UPDATE_START.text()) != null }
        button(root, ModuleText.UPDATE_START.text())!!.performClick()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        await { button(root, ModuleText.UPDATE_CANCEL.text()) != null }
        button(root, ModuleText.UPDATE_CANCEL.text())!!.performClick()
        assertTrue(stopped.await(5, TimeUnit.SECONDS)); assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
