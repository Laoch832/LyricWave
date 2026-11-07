package dev.amenhancer.module.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sun.net.httpserver.HttpServer
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.hook.LlmLyricsSource
import dev.amenhancer.module.hook.LlmLyricsSourceState
import dev.amenhancer.module.hook.SettingsActivityMatcher
import dev.amenhancer.module.hook.SettingsViewBridge
import dev.amenhancer.module.lyrics.FileLlmLyricsActivityStore
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.model.ModuleSettings
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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

/** Actual Android settings button -> HTTP -> persistent cache/journal -> visible statistics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class AiLyricsActivityUiTest {
    private lateinit var server: HttpServer
    private lateinit var host: EmbeddedSettingsHost
    private lateinit var app: Application
    private lateinit var activity: Activity
    private lateinit var root: LinearLayout
    private val requests = AtomicInteger()
    private var responseGate: CountDownLatch? = null
    private lateinit var cache: FileLlmLyricsCache
    private lateinit var journal: FileLlmLyricsActivityStore

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                requests.incrementAndGet()
                responseGate?.await(10, TimeUnit.SECONDS)
                val content = JSONObject().put("lines", JSONArray().put(
                    JSONObject().put("id", "L1").put("translation", "你好").put("background", JSONArray())))
                val bytes = JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", content.toString()))))
                    .put("usage", JSONObject().put("prompt_tokens", 11).put("completion_tokens", 7).put("total_tokens", 18))
                    .toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        app = RuntimeEnvironment.getApplication()
        File(app.filesDir, FileLlmLyricsCache.DIRECTORY).deleteRecursively()
        File(app.filesDir, FileLlmLyricsActivityStore.DIRECTORY).deleteRecursively()
        cache = FileLlmLyricsCache(File(app.filesDir, FileLlmLyricsCache.DIRECTORY))
        journal = FileLlmLyricsActivityStore(File(app.filesDir, FileLlmLyricsActivityStore.DIRECTORY))
        val settings = ModuleSettings(customLyricsEnabled = true, automaticLyricsEnabled = false,
            llmLyricsEnabled = true, llmLyricsBaseUrl = "http://127.0.0.1:${server.address.port}", llmLyricsModel = "test-model")
        val controller = object : EmbeddedSettingsController {
            override fun currentSettings() = settings
            override fun saveOrdinarySettings(settings: ModuleSettings) = true
            override fun currentSongDetails() = CurrentSongDetails(42, "Test song", "Artist")
            override fun readLyrics(appleMusicId: Long) = ORIGINAL.takeIf { appleMusicId == 42L }
        }
        host = EmbeddedSettingsHost.install(app, controller, activityMatcher = object : SettingsActivityMatcher {
            override fun roleFor(activity: Activity): EmbeddedHostActivityRole? = null
            override fun isPlayerActivity(activity: Activity) = false
            override fun isMainContentActivity(activity: Activity) = false
        }, nativeBridgeFactory = { _ -> object : SettingsViewBridge {
            override fun fragmentView(fragment: Any): ViewGroup? = null
            override fun findSettingsListOverlayContainer(root: View): ViewGroup? = null
            override fun injectNativeSettingsPreference(fragment: Any, activity: Activity) = false
            override fun findSettingsInsertionContainer(root: ViewGroup): ViewGroup? = null
            override fun refreshNativePreferenceAdapter(root: ViewGroup?) = Unit
        } })
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(root)
        LlmLyricsSourceState.observe(LlmLyricsSource(42, ORIGINAL, "Test song", "Artist"))
        host.renderEmbeddedAiLyricsActivityPage(activity, root, settings)
    }

    @After fun tearDown() {
        responseGate?.countDown()
        if (::root.isInitialized) root.removeAllViews()
        if (::app.isInitialized && ::host.isInitialized) app.unregisterActivityLifecycleCallbacks(host)
        if (::activity.isInitialized) activity.finish()
        if (::server.isInitialized) server.stop(0)
        LlmLyricsSourceState.clear(null)
    }

    @Test fun `manual button works with online completion disabled and a second use makes no API request`() {
        await { texts().any { it.contains("API requests: 0") } }
        button("Translate current song now").performClick()
        await { journal.snapshot().successful == 1L && cache.songs().size == 1 }
        await { texts().any { it.contains("1 cached songs") } && texts().any { it.contains("Reported tokens: 18") } }
        assertTrue(texts().any { it.contains("Test song") })
        assertEquals("你好", cache.preview(42))
        button("Translate current song now").performClick()
        await { journal.snapshot().cacheHits >= 1 }
        assertEquals(1, requests.get())
        assertEquals(1L, journal.snapshot().apiCalls)
    }

    @Test fun `closing the page does not discard a user requested translation or its statistics`() {
        responseGate = CountDownLatch(1)
        await { texts().any { it.contains("API requests: 0") } }
        button("Translate current song now").performClick()
        await { requests.get() == 1 && journal.snapshot().running == 1 }
        root.removeAllViews()
        responseGate!!.countDown()
        await { journal.snapshot().successful == 1L && cache.songs().size == 1 }
        assertEquals(0, journal.snapshot().running)
        assertEquals("你好", cache.preview(42))
    }

    private fun texts(): List<String> = views(root).filterIsInstance<TextView>().map { it.text.toString() }
    private fun button(label: String) = views(root).filterIsInstance<Button>().first { it.text.toString() == label && it.isEnabled }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            if (condition()) return
            Thread.sleep(20)
        }
        fail("UI/HTTP condition timed out; visible text: ${texts()}")
    }

    private companion object {
        const val ORIGINAL = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en"><body><div><p begin="00:00:01.000" end="00:00:02.000">Hello</p></div></body></tt>"""
    }
}
