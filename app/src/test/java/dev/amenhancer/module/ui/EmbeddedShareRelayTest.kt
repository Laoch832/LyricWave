package dev.amenhancer.module.ui

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.MomentsSongMetadata
import dev.amenhancer.module.update.UpdateControlProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContentResolver
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class EmbeddedShareRelayTest {
    class AppleActivity : Activity() {
        override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?, resultReceiver: BroadcastReceiver?,
            scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {
            throw AssertionError("Apple Music must not send the signature-protected broadcast")
        }
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun `embedded host shares through module identity without opening a module Activity`() {
        val app = RuntimeEnvironment.getApplication()
        val provider = Robolectric.buildContentProvider(UpdateControlProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("${BuildConfig.APPLICATION_ID}.update-control", provider)
        val info = ApplicationInfo().apply { packageName = Contract.INTERMEDIARY_PACKAGE; uid = 23456 }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = info.packageName; applicationInfo = info })
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(info.packageName), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = info.packageName; name = "MainActivity"; applicationInfo = info; exported = true }
        })
        var received = 0
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                received++; assertEquals("https://music.apple.com/cn/song/123", intent.getStringExtra(Contract.URL))
                assertEquals("Shared title", intent.getStringExtra(Contract.TITLE)); resultCode = Contract.RESULT_OPENED
            }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        val controller = Robolectric.buildActivity(AppleActivity::class.java).setup()
        val activity = controller.get()
        val relay = Intent(Intent.ACTION_SEND).setComponent(ComponentName(ModuleConstants.MODULE_PACKAGE, "dev.amenhancer.module.share.MomentsShareActivity"))
            .putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/song/123").putExtra(Contract.TITLE, "Shared title").putExtra(Contract.ARTIST, "Artist")
        assertTrue(EmbeddedShareRuntime.showPanel(activity, Intent.createChooser(Intent(Intent.ACTION_SEND), "Share").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(relay))) {
            MomentsSongMetadata("Shared title", "Artist", "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg")
        })
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val open = views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }
        repeat(200) { shadowOf(Looper.getMainLooper()).idle(); if (!open.isEnabled) Thread.sleep(5) }
        assertTrue(open.isEnabled); open.performClick()
        var launched: Intent? = null
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (launched == null) launched = shadowOf(activity).nextStartedActivity
            if (launched == null) Thread.sleep(5)
        }
        assertEquals(Contract.INTERMEDIARY_PACKAGE, launched?.component?.packageName)
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        repeat(200) { shadowOf(Looper.getMainLooper()).idle(); if (received == 0) Thread.sleep(5) }
        assertEquals(1, received)
        assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("NetEase share panel opened") })
        dialog.dismiss(); controller.pause().stop().destroy()
    }
    @Test fun `untrusted UID cannot use the module as a share deputy`() {
        val provider = Robolectric.buildContentProvider(UpdateControlProvider::class.java).create().get()
        try {
            ShadowBinder.setCallingUid(44555)
            assertThrows(SecurityException::class.java) { provider.call("share_request", null, Bundle()) }
        } finally { ShadowBinder.setCallingUid(android.os.Process.myUid()) }
    }
    @Test fun `a stalled artwork lookup does not block edited details or the share relay`() {
        val app = RuntimeEnvironment.getApplication()
        val provider = Robolectric.buildContentProvider(UpdateControlProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("${BuildConfig.APPLICATION_ID}.update-control", provider)
        val info = ApplicationInfo().apply { packageName = Contract.INTERMEDIARY_PACKAGE; uid = 23456 }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = info.packageName; applicationInfo = info })
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(info.packageName), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = info.packageName; name = "MainActivity"; applicationInfo = info; exported = true }
        })
        val metadataStarted = CountDownLatch(1); val gate = CountDownLatch(1)
        var received = false
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                assertEquals("Manual title", intent.getStringExtra(Contract.TITLE))
                received = true; resultCode = Contract.RESULT_OPENED
            }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        val controller = Robolectric.buildActivity(AppleActivity::class.java).setup()
        val relay = Intent(Intent.ACTION_SEND).setComponent(ComponentName(ModuleConstants.MODULE_PACKAGE, "dev.amenhancer.module.share.MomentsShareActivity"))
            .putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/song/123").putExtra(Contract.TITLE, "Initial title")
        assertTrue(EmbeddedShareRuntime.showPanel(controller.get(), Intent.createChooser(Intent(Intent.ACTION_SEND), "Share")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(relay))) {
            metadataStarted.countDown(); gate.await(10, TimeUnit.SECONDS); MomentsSongMetadata("Late title", "Artist")
        })
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        try {
            assertTrue(metadataStarted.await(5, TimeUnit.SECONDS))
            views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().first().setText("Manual title")
            views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }.performClick()
            repeat(200) { shadowOf(Looper.getMainLooper()).idle(); if (shadowOf(controller.get()).nextStartedActivity == null) Thread.sleep(5) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            repeat(200) { shadowOf(Looper.getMainLooper()).idle(); if (!received) Thread.sleep(5) }
            assertTrue("Sharing must not wait for artwork", received)
            assertEquals(1L, gate.count)
        } finally { gate.countDown(); dialog.dismiss(); controller.pause().stop().destroy() }
    }
}
