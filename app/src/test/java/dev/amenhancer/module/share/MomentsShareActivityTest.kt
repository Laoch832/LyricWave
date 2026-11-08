package dev.amenhancer.module.share

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.amenhancer.module.MomentsLinkContract as Contract
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class MomentsShareActivityTest {
    private lateinit var app: Application
    private lateinit var activity: MomentsShareActivity
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts() = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun openButton() = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        val info = ApplicationInfo().apply { packageName = Contract.INTERMEDIARY_PACKAGE; uid = 12345 }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = Contract.INTERMEDIARY_PACKAGE; applicationInfo = info
        })
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(Contract.INTERMEDIARY_PACKAGE)
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = Contract.INTERMEDIARY_PACKAGE; name = "MainActivity"; applicationInfo = info; exported = true }
        })
        activity = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/song/123")
        ).setup().visible().get()
    }
    @Test fun `launching NetEase without its module receiver never reports success and shows scope diagnosis`() {
        openButton().performClick()
        assertEquals(Contract.INTERMEDIARY_PACKAGE, shadowOf(activity).nextStartedActivity?.component?.packageName)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertTrue(texts().any { "[N0]" in it && "LSPosed" in it })
        assertFalse(texts().any { it.startsWith("NetEase share panel opened") })
        assertTrue(openButton().isEnabled)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("[N0]"))
    }
    @Suppress("DEPRECATION")
    @Test fun `pending native launch is polled with one request id until real panel acknowledgement`() {
        var response = Contract.RESULT_PENDING
        val ids = mutableListOf<String?>()
        val host = app.createPackageContext(Contract.INTERMEDIARY_PACKAGE, 0)
        host.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                ids += intent.getStringExtra(Contract.REQUEST_ID)
                resultCode = response
            }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        openButton().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(ids.size >= 2)
        assertEquals(1, ids.distinct().size)
        assertFalse(texts().any { it.startsWith("NetEase share panel opened") })
        response = Contract.RESULT_OPENED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(texts().any { it.startsWith("NetEase share panel opened") })
        assertTrue(openButton().isEnabled)
        val count = ids.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(count, ids.size)
    }
}
