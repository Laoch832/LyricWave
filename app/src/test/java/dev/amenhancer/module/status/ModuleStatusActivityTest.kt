package dev.amenhancer.module.status

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.ModuleConstants
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ModuleStatusActivityTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun text(activity: Activity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun button(activity: Activity, label: String) = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == label }
    @Before fun installedHost() {
        assertEquals("Unit tests must not start production feedback", android.app.Application::class.java, RuntimeEnvironment.getApplication().javaClass)
        val pm = RuntimeEnvironment.getApplication().packageManager
        val app = ApplicationInfo().apply { packageName = ModuleConstants.TARGET_PACKAGE; uid = 12345 }
        shadowOf(pm).installPackage(PackageInfo().apply {
            packageName = app.packageName; applicationInfo = app; versionName = "7.0.0"; versionCode = 1614
        })
        shadowOf(pm).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = app.packageName; name = "MainActivity"; applicationInfo = app; exported = true }
        })
    }
    private fun evidence(code: Long = BuildConfig.VERSION_CODE.toLong()) = ActivationEvidence(
        connected = true, api = 102, framework = "LSPosed 2.0", scope = setOf(ModuleConstants.TARGET_PACKAGE),
        targets = listOf(LoadedHost(12345, 71, ModuleConstants.TARGET_PACKAGE, "UP_TO_DATE", code)),
    )
    @Test fun `launcher shows verified activation stale host refresh and real open action`() {
        var result = evidence(); var reads = 0
        val monitor = ActivationMonitor({ it() }, { it() }, { _, _ -> {} })
        val framework = Any(); monitor.connected(framework) { reads++; result }
        val controller = Robolectric.buildActivity(ModuleStatusActivity::class.java)
        controller.get().monitorOverride = monitor
        controller.setup().visible()
        try {
            assertTrue(text(controller.get()).contains("Activated"))
            assertTrue(text(controller.get()).contains("LSPosed 2.0 · API 102"))
            result = evidence(BuildConfig.VERSION_CODE - 1L)
            button(controller.get(), "Refresh status").performClick()
            assertTrue(reads >= 2)
            assertTrue(text(controller.get()).contains("Restart Apple Music"))
            assertFalse(text(controller.get()).contains("Activated"))
            button(controller.get(), "Open Apple Music").performClick()
            assertEquals(ModuleConstants.TARGET_PACKAGE, shadowOf(controller.get()).nextStartedActivity!!.component!!.packageName)
            controller.pause().stop()
            val stopped = text(controller.get())
            monitor.disconnected(framework)
            assertEquals(stopped, text(controller.get()))
        } finally { controller.destroy() }
    }
    @Test fun `no framework is unverified and host package alone never turns the card green`() {
        val controller = Robolectric.buildActivity(ModuleStatusActivity::class.java)
        controller.get().monitorOverride = ActivationMonitor({ it() }, { it() }, { _, _ -> {} })
        controller.setup().visible()
        try {
            assertTrue(text(controller.get()).contains("Activation not verified"))
            assertFalse(text(controller.get()).contains("Activated"))
            assertTrue(text(controller.get()).contains("7.0.0 (1614)"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun `real launcher manifest resolves the status page and adaptive brand icon`() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val launcher = pm.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
        assertEquals(ModuleStatusActivity::class.java.name, launcher!!.component!!.className)
        assertTrue(RuntimeEnvironment.getApplication().getDrawable(dev.amenhancer.module.R.mipmap.ic_launcher) is android.graphics.drawable.AdaptiveIconDrawable)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-night-xxhdpi")
    fun `render status dashboard for visual review`() {
        val monitor = ActivationMonitor({ it() }, { it() }, { _, _ -> {} })
        monitor.connected(Any()) { evidence() }
        val controller = Robolectric.buildActivity(ModuleStatusActivity::class.java)
        controller.get().monitorOverride = monitor
        controller.setup().visible()
        try {
            val root = controller.get().window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2340)
            val bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            // Optional local preview; CI does not write outside its test reports.
            (System.getenv("LYRICWAVE_STATUS_PREVIEW") ?: System.getProperty("lyricwave.status.preview"))?.let { path ->
                File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
            assertTrue(text(controller.get()).contains(dev.amenhancer.module.i18n.ModuleText.STATUS_ACTIVE.text()))
        } finally { controller.pause().stop().destroy() }
    }
}
