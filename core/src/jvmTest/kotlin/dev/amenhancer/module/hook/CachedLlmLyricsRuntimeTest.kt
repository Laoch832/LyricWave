package dev.amenhancer.module.hook

import com.sun.net.httpserver.HttpServer
import dev.amenhancer.module.lyrics.FileLlmLyricsActivityStore
import dev.amenhancer.module.lyrics.LlmActivityKind
import dev.amenhancer.module.lyrics.LlmActivityStatus
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the production cache/client/session path with a local OpenAI-compatible provider. */
class CachedLlmLyricsRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var directory: File
    private lateinit var activity: FileLlmLyricsActivityStore
    private val requestBodies = mutableListOf<String>()
    private var reportUsage = true
    private lateinit var config: LlmLyricsConfig
    private var requestArrived = CountDownLatch(1)
    private var responseGate: CountDownLatch? = null
    private var requests = 0
    private var responseStatus = 200
    private var answer = "你好"

    @Before fun setUp() {
        directory = temporary.newFolder("translations")
        activity = FileLlmLyricsActivityStore(temporary.newFolder("activity"))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requestBodies += exchange.requestBody.bufferedReader().use { it.readText() }
            requests++
            requestArrived.countDown()
            responseGate?.await(10, TimeUnit.SECONDS)
            val content = JSONObject().put("lines", JSONArray().put(
                JSONObject().put("id", "L1").put("translation", answer)
                    .put("background", JSONArray()),
            )).toString()
            val bytes = JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", content)),
            )).apply {
                if (reportUsage) put("usage", JSONObject().put("prompt_tokens", 11).put("completion_tokens", 7).put("total_tokens", 18))
            }.toString().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        config = LlmLyricsConfig(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            model = "lyrics-model", apiKey = "test-key-never-store",
        )
    }

    @After fun tearDown() { responseGate?.countDown(); server.stop(0) }

    @Test fun `accepted translation is saved and a new session reuses it without a request`() {
        val first = translatedByNewSession(ORIGINAL)
        assertTrue(first!!.contains("你好"))
        assertEquals(1, requests)
        assertEquals(first, translatedByNewSession(ORIGINAL))
        assertEquals(1, requests)
        assertFalse(directory.listFiles()!!.any { it.readText().contains(config.apiKey) })
    }

    @Test fun `changed vocals replace the old cache and are reused on the next visit`() {
        translatedByNewSession(ORIGINAL)
        val updated = ORIGINAL.replace("Hello", "Goodbye")
        answer = "再见"
        val translated = translatedByNewSession(updated)
        assertTrue(translated!!.contains("Goodbye"))
        assertTrue(translated.contains("再见"))
        assertEquals(2, requests)
        assertEquals(translated, translatedByNewSession(updated))
        assertEquals(2, requests)
    }

    @Test fun `timing changes invalidate the cache even when vocals are unchanged`() {
        translatedByNewSession(ORIGINAL)
        val updated = ORIGINAL.replace("00:00:01.000", "00:00:01.500")
        val translated = translatedByNewSession(updated)
        assertTrue(translated!!.contains("00:00:01.500"))
        assertEquals(2, requests)
        assertEquals(translated, translatedByNewSession(updated))
        assertEquals(2, requests)
    }

    @Test fun `native translation arriving later suppresses AI and the old cached result`() {
        val translated = translatedByNewSession(ORIGINAL)!!
        assertNull(translatedByNewSession(translated))
        assertEquals(1, requests)
    }

    @Test fun `failed provider response is not cached and the next visit can retry`() {
        responseStatus = 503
        assertNull(translatedByNewSession(ORIGINAL))
        assertNull(FileLlmLyricsCache(directory).read(42, ORIGINAL, config))
        responseStatus = 200
        assertNotNull(translatedByNewSession(ORIGINAL))
        assertEquals(2, requests)
    }

    @Test fun `completed translation survives host rejection and later playback reuses it`() {
        assertNull(translatedByNewSession(ORIGINAL, accept = false))
        assertNotNull(FileLlmLyricsCache(directory).read(42, ORIGINAL, config))
        assertTrue(activity.snapshot().records.any { it.kind == LlmActivityKind.PLAYER_REJECTED })
        assertNotNull(translatedByNewSession(ORIGINAL))
        assertEquals(1, requests)
    }

    @Test fun `manual and online selected sources translate without falling back to native`() {
        val manual = ORIGINAL.replace("Hello", "Custom vocals")
        val native = ORIGINAL.replace("Hello", "Different native vocals")
        val selected = selectedLlmLyricsSource(manual, null, native, nativeReady = true)!!
        assertTrue(translatedByNewSession(selected)!!.contains("Custom vocals"))
        assertTrue(requestBodies.single().contains("Custom vocals"))
        assertFalse(requestBodies.single().contains("Different native vocals"))
        assertNull(selectedLlmLyricsSource(null, null, native, nativeReady = false))
        val online = ORIGINAL.replace("Hello", "Online vocals")
        assertEquals(online, selectedLlmLyricsSource(null, online, native, nativeReady = true))
    }

    @Test fun `API statistics reflect real HTTP calls and cache hits survive new runtime instances`() {
        translatedByNewSession(ORIGINAL)
        translatedByNewSession(ORIGINAL)
        val snapshot = activity.snapshot()
        assertEquals(1, requests)
        assertEquals(1L, snapshot.apiCalls)
        assertEquals(1L, snapshot.successful)
        assertEquals(1L, snapshot.cacheHits)
        assertEquals(1L, snapshot.cacheSaves)
        assertEquals(18L, snapshot.reportedTokens)
        assertEquals(0L, snapshot.callsWithoutUsage)
        val record = snapshot.records.single { it.isApiCall }
        assertEquals(200, record.httpStatus)
        assertEquals(11L, record.promptTokens)
        assertEquals(7L, record.completionTokens)
        assertEquals(LlmActivityStatus.SUCCESS, record.status)
        val cache = FileLlmLyricsCache(directory)
        assertEquals(1, cache.songs().size)
        assertTrue(cache.preview(42)!!.contains("你好"))
    }

    @Test fun `failed HTTP status and absent usage are visible without fabricated tokens`() {
        responseStatus = 429
        assertNull(translatedByNewSession(ORIGINAL))
        assertEquals(429, activity.snapshot().records.single { it.isApiCall }.httpStatus)
        assertEquals(1L, activity.snapshot().failed)
        responseStatus = 200
        reportUsage = false
        assertNotNull(translatedByNewSession(ORIGINAL))
        val snapshot = activity.snapshot()
        assertEquals(2L, snapshot.apiCalls)
        assertEquals(1L, snapshot.successful)
        assertEquals(2L, snapshot.callsWithoutUsage)
        assertNull(snapshot.records.last { it.isApiCall }.totalTokens)
    }

    @Test fun `manual and automatic calls for one song share one real request`() {
        responseGate = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        fun runtime() = createCachedLlmLyricsRuntime(FileLlmLyricsCache(directory), { config }, Executor { it.run() }, activity = activity)
        val request = LlmLyricsRequest(42, ORIGINAL, config)
        val secondEntered = CountDownLatch(1)
        try {
            val first = workers.submit<String?> { runtime().translate(request) { false } }
            assertTrue(requestArrived.await(3, TimeUnit.SECONDS))
            val second = workers.submit<String?> { runtime().translate(request) { secondEntered.countDown(); false } }
            assertTrue(secondEntered.await(3, TimeUnit.SECONDS))
            Thread.sleep(100)
            responseGate!!.countDown()
            assertNotNull(first.get(5, TimeUnit.SECONDS))
            assertEquals(first.get(), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, requests)
            assertEquals(1L, activity.snapshot().apiCalls)
            assertEquals(1L, activity.snapshot().cacheHits)
        } finally { responseGate!!.countDown(); workers.shutdownNow() }
    }

    @Test fun `cancelled waiter does not block playback behind a manual request`() {
        responseGate = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val cancelled = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        fun runtime() = createCachedLlmLyricsRuntime(FileLlmLyricsCache(directory), { config }, Executor { it.run() }, activity = activity)
        val request = LlmLyricsRequest(42, ORIGINAL, config)
        try {
            val manual = workers.submit<String?> { runtime().translate(request) { false } }
            assertTrue(requestArrived.await(3, TimeUnit.SECONDS))
            val automatic = workers.submit<String?> { runtime().translate(request) { entered.countDown(); cancelled.get() } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            cancelled.set(true)
            assertNull(automatic.get(1, TimeUnit.SECONDS))
            assertFalse(manual.isDone)
            responseGate!!.countDown()
            assertNotNull(manual.get(5, TimeUnit.SECONDS))
            assertEquals(1L, activity.snapshot().apiCalls)
        } finally { responseGate!!.countDown(); workers.shutdownNow() }
    }

    private fun translatedByNewSession(original: String, accept: Boolean = true): String? {
        val tasks = java.util.ArrayDeque<Runnable>()
        val runtime = createCachedLlmLyricsRuntime(
            cache = FileLlmLyricsCache(directory), configuration = { config },
            executor = Executor { tasks.add(it) }, activity = activity,
        )
        val session = LlmLyricsReplacementSession(
            runtime = runtime,
            parseTtml = { if (accept) Pointer(it) else null },
            isAlive = { it is Pointer }, verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).id },
            bindAdamId = { pointer, id -> (pointer as Pointer).id = id; true },
            isAllowed = { true }, onReplacementPublished = {},
        )
        try {
            session.onSongChanged(42)
            session.onSourceSelected(42)
            assertNull(session.replacementFor(42, original))
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            return (session.replacementFor(42, original) as? Pointer)?.ttml
        } finally {
            session.close()
        }
    }

    private data class Pointer(val ttml: String, var id: Long = 0)

    private companion object {
        const val ORIGINAL = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en"><body><div><p begin="00:00:01.000" end="00:00:02.000">Hello</p></div></body></tt>"""
    }
}
