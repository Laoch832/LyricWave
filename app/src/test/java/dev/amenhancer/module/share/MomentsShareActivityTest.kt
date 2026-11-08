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
import android.widget.EditText
import android.widget.Button
import dev.amenhancer.module.MomentsSongMetadata
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                .putExtra(Contract.TITLE, "Despacito").putExtra(Contract.ARTIST, "Luis Fonsi")
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
                assertEquals("Despacito", intent.getStringExtra(Contract.TITLE))
                assertEquals("Luis Fonsi", intent.getStringExtra(Contract.ARTIST))
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
    @Test fun `URL-only share looks up the exact track and displays editable song title and artist`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lookupUrl = ""
        val responseGate = CountDownLatch(1)
        server.createContext("/lookup") { exchange ->
            responseGate.await()
            val bytes = """{"results":[{"kind":"song","trackId":999,"trackName":"Wrong song","artistName":"Wrong artist"},{"kind":"song","trackId":123,"trackName":"Despacito","artistName":"Luis Fonsi"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/album/album-name/111?i=123"))
        val client = AppleSongMetadataLookup { requested ->
            lookupUrl = requested.toString()
            URL("http://127.0.0.1:${server.address.port}/lookup").openConnection() as HttpURLConnection
        }
        controller.get().metadataResolver = client::lookup
        try {
            activity = controller.setup().visible().get()
            assertFalse(openButton().isEnabled)
            responseGate.countDown()
            var loaded = false
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (openButton().isEnabled) loaded = true else Thread.sleep(10)
            }
            assertTrue(loaded)
            assertTrue("id=123&country=cn" in lookupUrl)
            assertTrue(texts().contains("Despacito")); assertTrue(texts().contains("Luis Fonsi"))
            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
            fields.first().setText("My title")
            assertTrue(texts().contains("My title"))
        } finally { responseGate.countDown(); controller.pause().stop().destroy(); server.stop(0) }
    }
    @Test fun `missing metadata permits manual entry and a late lookup cannot overwrite edits`() {
        val gate = CountDownLatch(1)
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/song/123"))
        controller.get().metadataResolver = {
            gate.await(2, TimeUnit.SECONDS)
            MomentsSongMetadata("Fetched title", "Fetched artist")
        }
        activity = controller.setup().visible().get()
        val fields = views(activity.window.decorView).filterIsInstance<EditText>()
        fields[0].setText("User title"); fields[1].setText("User artist")
        gate.countDown()
        for (attempt in 0 until 200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (texts().none { it.startsWith("Loading song title") }) break
            Thread.sleep(10)
        }
        assertFalse(texts().any { it.startsWith("Loading song title") })
        assertTrue(texts().contains("User title")); assertTrue(texts().contains("User artist"))
        assertTrue(openButton().isEnabled)
        controller.pause().stop().destroy()
    }

}
