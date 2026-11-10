package dev.amenhancer.module.ui

import android.app.Activity
import android.content.*
import android.content.pm.*
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.MomentsSongMetadata
import dev.amenhancer.module.share.MomentsShareHandoff
import dev.amenhancer.module.share.MomentsShareHandoffActivity
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import java.security.cert.CertificateFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS", shadows = [EmbeddedShareRelayTest.FrameworkResultReceiverShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class EmbeddedShareRelayTest {
    /** Robolectric's default shadow calls onReceiveResult directly and drops remote receivers.
     * Execute Android's actual send implementation to exercise the parcelled Binder capability. */
    @org.robolectric.annotation.Implements(ResultReceiver::class)
    class FrameworkResultReceiverShadow {
        @org.robolectric.annotation.RealObject private lateinit var receiver: ResultReceiver
        @org.robolectric.annotation.Implementation
        protected fun send(code: Int, data: Bundle?) {
            org.robolectric.shadow.api.Shadow.directlyOn<Any, ResultReceiver>(receiver, ResultReceiver::class.java, "send",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, code),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Bundle::class.java, data))
        }
    }

    class AppleActivity : Activity() {
        override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?, resultReceiver: BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {
            throw AssertionError("Apple Music must not impersonate the module broadcast sender")
        }
    }
    class UnavailableProvider : ContentProvider() {
        var calls = 0
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? { calls++; throw IllegalArgumentException("Provider not visible to host") }
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(u: Uri): String? = null
        override fun insert(u: Uri, v: ContentValues?): Uri? = null
        override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
    private val url = "https://music.apple.com/tr/album/title/111?i=123&ls=1"
    private val expected = url.replace("/tr/", "/cn/")
    private val artwork = "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg"
    private lateinit var provider: UnavailableProvider
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun drain() { repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) } }
    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        provider = Robolectric.buildContentProvider(UnavailableProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("${BuildConfig.APPLICATION_ID}.update-control", provider)
        val apple = PackageInfo().apply {
            packageName = "com.apple.android.music"
            applicationInfo = ApplicationInfo().apply { packageName = "com.apple.android.music"; uid = 23456 }
            signingInfo = SigningInfo().also { signing ->
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(javaClass.getResourceAsStream("/certificates/apple-music.pem"))
                shadowOf(signing).setSignatures(arrayOf(Signature(cert.encoded)))
            }
        }
        shadowOf(app.packageManager).installPackage(apple)
        val info = ApplicationInfo().apply { packageName = Contract.INTERMEDIARY_PACKAGE; uid = 34567 }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = info.packageName; applicationInfo = info })
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(info.packageName), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = info.packageName; name = "MainActivity"; applicationInfo = info; exported = true }
        })
    }
    private fun editor(resolver: (String) -> MomentsSongMetadata? = { MomentsSongMetadata("Title", "Artist", artwork) }): org.robolectric.android.controller.ActivityController<AppleActivity> {
        val controller = Robolectric.buildActivity(AppleActivity::class.java).setup().visible()
        val relay = Intent(Intent.ACTION_SEND).setComponent(ComponentName(ModuleConstants.MODULE_PACKAGE, "dev.amenhancer.module.share.MomentsShareActivity"))
            .putExtra(Intent.EXTRA_TEXT, url).putExtra(Contract.TITLE, "Title").putExtra(Contract.ARTIST, "Artist").putExtra(Contract.CHINA_STOREFRONT, true)
        assertTrue(EmbeddedShareRuntime.showPanel(controller.get(), Intent.createChooser(Intent(Intent.ACTION_SEND), "Share")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(relay)), resolver))
        return controller
    }
    private fun handoff(host: Activity): org.robolectric.android.controller.ActivityController<MomentsShareHandoffActivity> {
        val launched = shadowOf(host).nextStartedActivityForResult
        shadowOf(host).nextStartedActivity
        assertEquals(MomentsShareHandoff.REQUEST_CODE, launched.requestCode)
        assertEquals(BuildConfig.APPLICATION_ID, launched.intent.component!!.packageName)
        assertEquals(MomentsShareHandoffActivity::class.java.name, launched.intent.component!!.className)
        // Exercise framework Parcelable IPC rather than sharing the sender's subclass instance.
        val parcel = Parcel.obtain(); launched.intent.writeToParcel(parcel, 0); parcel.setDataPosition(0)
        val input = Intent.CREATOR.createFromParcel(parcel); parcel.recycle()
        val controller = Robolectric.buildActivity(MomentsShareHandoffActivity::class.java, input)
        shadowOf(controller.get()).setCallingPackage("com.apple.android.music")
        return controller.setup().visible().postResume()
    }
    @Test fun `framework result receiver survives parcel roundtrip`() {
        var result = -99
        val original = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) { result = code }
        }
        val parcel = Parcel.obtain(); original.writeToParcel(parcel, 0); parcel.setDataPosition(0)
        val restored = ResultReceiver.CREATOR.createFromParcel(parcel); parcel.recycle()
        restored.send(100, Bundle()); drain()
        assertEquals(100, result)
    }
    @Test fun `confirmed edited sharing works without any provider and has no second editor`() {
        var delivered: Intent? = null
        RuntimeEnvironment.getApplication().registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { delivered = intent; resultCode = Contract.RESULT_OPENED }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        val host = editor(); val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val root = dialog.window!!.decorView
        try {
            views(root).filterIsInstance<EditText>()[0].setText("Edited title")
            views(root).filterIsInstance<EditText>()[1].setText("Edited artist")
            views(root).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }.performClick()
            val module = handoff(host.get())
            drain()
            try {
                drain()
                assertTrue(views(module.get().window.decorView).filterIsInstance<EditText>().isEmpty())
                val launched = shadowOf(module.get()).nextStartedActivity
                assertNotNull("no launch; approved=" + module.get().javaClass.getDeclaredField("approved").apply { isAccessible = true }.get(module.get()) + "; callback=" + module.get().javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(module.get()), launched)
                assertEquals(Contract.INTERMEDIARY_PACKAGE, launched!!.component!!.packageName)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); drain()
                assertEquals(expected, delivered!!.getStringExtra(Contract.URL))
                assertEquals("Edited title", delivered!!.getStringExtra(Contract.TITLE))
                assertEquals("Edited artist", delivered!!.getStringExtra(Contract.ARTIST))
                assertTrue(views(root).filterIsInstance<TextView>().joinToString { it.text.toString() }, views(root).filterIsInstance<TextView>().any { it.text.toString().startsWith("NetEase share panel opened") })
                assertTrue(module.get().isFinishing)
                assertFalse("confirmed native handoff must retire the inline editor", dialog.isShowing)
                assertEquals(0, provider.calls)
            } finally { module.pause().stop().destroy() }
        } finally { dialog.dismiss(); host.pause().stop().destroy() }
    }
    @Test fun `stopping before module startup cancels content dispatch and ignores late outcomes`() {
        var sends = 0
        RuntimeEnvironment.getApplication().registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { sends++; resultCode = Contract.RESULT_OPENED }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        val host = editor(); val dialog = ShadowAlertDialog.getLatestAlertDialog(); val root = dialog.window!!.decorView
        try {
            views(root).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }.performClick()
            views(root).filterIsInstance<Button>().single { it.text == "Stop waiting" }.performClick()
            val module = handoff(host.get())
            drain()
            try {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)); drain()
                assertEquals(0, sends); assertTrue(module.get().isFinishing)
                assertFalse(views(root).filterIsInstance<TextView>().any { it.text.toString().startsWith("NetEase share panel opened") })
                assertTrue(views(root).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }.isEnabled)
            } finally { module.pause().stop().destroy() }
        } finally { dialog.dismiss(); host.pause().stop().destroy() }
    }
    @Test fun `no receiver acknowledgement reaches the shared deadline rather than reporting success`() {
        val host = editor(); val dialog = ShadowAlertDialog.getLatestAlertDialog(); val root = dialog.window!!.decorView
        views(root).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }.performClick()
        val module = handoff(host.get())
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31)); drain()
            assertFalse(views(root).filterIsInstance<TextView>().any { it.text.toString().startsWith("NetEase share panel opened") })
            assertTrue(module.get().isFinishing); assertEquals(0, provider.calls)
        } finally { module.pause().stop().destroy(); dialog.dismiss(); host.pause().stop().destroy() }
    }
    @Test fun `slow artwork lookup does not block confirmed sharing or replace manual edits`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var delivered: Intent? = null
        RuntimeEnvironment.getApplication().registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { delivered = intent; resultCode = Contract.RESULT_OPENED }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        val host = editor { entered.countDown(); release.await(5, TimeUnit.SECONDS); MomentsSongMetadata("Late title", "Late artist", artwork) }
        val dialog = ShadowAlertDialog.getLatestAlertDialog(); val root = dialog.window!!.decorView
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            views(root).filterIsInstance<EditText>()[0].setText("Manual")
            views(root).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }.performClick()
            val module = handoff(host.get()); drain()
            try {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); drain()
                assertEquals("Manual", delivered!!.getStringExtra(Contract.TITLE))
                release.countDown(); drain()
                assertEquals("Manual", views(root).filterIsInstance<EditText>()[0].text.toString())
            } finally { module.pause().stop().destroy() }
        } finally { release.countDown(); dialog.dismiss(); host.pause().stop().destroy() }
    }
    @Test fun `official package name with an untrusted certificate cannot auto handoff`() {
        val info = PackageInfo().apply {
            packageName = "com.apple.android.music"
            applicationInfo = ApplicationInfo().apply { packageName = "com.apple.android.music"; uid = 23456 }
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(1, 2, 3)))) }
        }
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(info)
        val input = MomentsShareHandoff.intent(url, "Title", "Artist", null, "request-1", SystemClock.elapsedRealtime() + 30_000,
            object : ResultReceiver(Handler(Looper.getMainLooper())) {})
        val module = Robolectric.buildActivity(MomentsShareHandoffActivity::class.java, input)
        shadowOf(module.get()).setCallingPackage("com.apple.android.music")
        module.setup().visible().postResume(); drain()
        try { assertTrue(module.get().isFinishing); assertNull(shadowOf(module.get()).nextStartedActivity) }
        finally { module.pause().stop().destroy() }
    }
    @Test fun `forged automatic handoff cannot open NetEase`() {
        val input = MomentsShareHandoff.intent(url, "Title", "Artist", artwork, "request-1", SystemClock.elapsedRealtime() + 30000,
            object : ResultReceiver(Handler(Looper.getMainLooper())) {})
        val module = Robolectric.buildActivity(MomentsShareHandoffActivity::class.java, input)
        shadowOf(module.get()).setCallingPackage("evil.app")
        module.setup().visible().postResume()
        try { assertTrue(module.get().isFinishing); assertNull(shadowOf(module.get()).nextStartedActivity) }
        finally { module.pause().stop().destroy() }
    }
}
