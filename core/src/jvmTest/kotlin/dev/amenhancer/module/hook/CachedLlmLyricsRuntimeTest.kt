package dev.amenhancer.module.hook

import com.sun.net.httpserver.HttpServer
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import java.io.File
import java.net.InetSocketAddress
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
    private lateinit var config: LlmLyricsConfig
    private var requests = 0
    private var responseStatus = 200
    private var answer = "你好"

    @Before fun setUp() {
        directory = temporary.newFolder("translations")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            requests++
            val content = JSONObject().put("lines", JSONArray().put(
                JSONObject().put("id", "L1").put("translation", answer)
                    .put("background", JSONArray()),
            )).toString()
            val bytes = JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", content)),
            )).toString().toByteArray(Charsets.UTF_8)
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

    @After fun tearDown() { server.stop(0) }

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

    @Test fun `host rejection never persists a generated translation`() {
        assertNull(translatedByNewSession(ORIGINAL, accept = false))
        assertNull(FileLlmLyricsCache(directory).read(42, ORIGINAL, config))
        assertNotNull(translatedByNewSession(ORIGINAL))
        assertEquals(2, requests)
    }

    private fun translatedByNewSession(original: String, accept: Boolean = true): String? {
        val tasks = java.util.ArrayDeque<Runnable>()
        val runtime = createCachedLlmLyricsRuntime(
            cache = FileLlmLyricsCache(directory), configuration = { config },
            executor = Executor { tasks.add(it) },
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
            session.onNativeSelected(42)
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
