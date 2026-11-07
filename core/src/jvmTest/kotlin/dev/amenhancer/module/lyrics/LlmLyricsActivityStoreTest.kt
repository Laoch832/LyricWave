package dev.amenhancer.module.lyrics

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlmLyricsActivityStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = LlmLyricsConfig(baseUrl = "https://provider.example/v1", model = "model-a", apiKey = "secret-not-in-records")

    @Test fun `real calls hits and persistence are separate and missing usage stays unknown`() {
        val folder = temp.newFolder()
        val store = FileLlmLyricsActivityStore(folder)
        val request = store.begin(LlmActivityKind.TRANSLATION, config, 42, "Song", "Artist")
        assertEquals(1, store.snapshot().running)
        store.complete(request, LlmLyricsClient.CallResult(httpStatus = 200, elapsedMs = 50,
            promptTokens = 11, completionTokens = 7, totalTokens = 18))
        store.event(LlmActivityKind.CACHE_SAVED, config, 42)
        store.event(LlmActivityKind.CACHE_HIT, config, 42)
        val check = store.begin(LlmActivityKind.CONNECTION_TEST, config)
        store.complete(check, LlmLyricsClient.CallResult("http_status", 429, 123))
        val reloaded = FileLlmLyricsActivityStore(folder).snapshot()
        assertEquals(2L, reloaded.apiCalls)
        assertEquals(1L, reloaded.successful)
        assertEquals(1L, reloaded.failed)
        assertEquals(1L, reloaded.cacheHits)
        assertEquals(1L, reloaded.cacheSaves)
        assertEquals(18L, reloaded.reportedTokens)
        assertEquals(1L, reloaded.callsWithoutUsage)
        assertNull(reloaded.records.last().totalTokens)
        assertFalse(File(folder, "activity.json").readText().contains(config.apiKey))
    }

    @Test fun `different instances cannot lose concurrent request completions`() {
        val folder = temp.newFolder()
        val workers = Executors.newFixedThreadPool(4)
        val finished = CountDownLatch(40)
        try {
            repeat(40) {
                workers.execute {
                    try {
                        val store = FileLlmLyricsActivityStore(folder)
                        val id = store.begin(LlmActivityKind.TRANSLATION, config, 42)
                        store.complete(id, LlmLyricsClient.CallResult(httpStatus = 200, totalTokens = 2))
                    } finally { finished.countDown() }
                }
            }
            assertTrue(finished.await(15, TimeUnit.SECONDS))
            val result = FileLlmLyricsActivityStore(folder).snapshot()
            assertEquals(40L, result.apiCalls)
            assertEquals(40L, result.successful)
            assertEquals(80L, result.reportedTokens)
            assertEquals(0, result.running)
        } finally { workers.shutdownNow() }
    }

    @Test fun `bounded history retains aggregate totals and clearing keeps active requests`() {
        val folder = temp.newFolder()
        val store = FileLlmLyricsActivityStore(folder)
        val running = store.begin(LlmActivityKind.TRANSLATION, config, 99)
        repeat(220) { store.event(LlmActivityKind.CACHE_HIT, config, 42) }
        assertEquals(200, store.snapshot().records.size)
        assertEquals(220L, store.snapshot().cacheHits)
        assertEquals(1, store.snapshot().running)
        store.clearCompleted()
        assertEquals(1, store.snapshot().records.size)
        assertEquals(0L, store.snapshot().cacheHits)
        store.complete(running, LlmLyricsClient.CallResult(httpStatus = 200, totalTokens = 3))
        assertEquals(1L, store.snapshot().successful)
        assertEquals(1L, store.snapshot().apiCalls)
    }

    @Test fun `interrupted process requests are not displayed as running forever`() {
        val folder = temp.newFolder()
        val store = FileLlmLyricsActivityStore(folder)
        store.begin(LlmActivityKind.TRANSLATION, config, 42)
        val file = File(folder, "activity.json")
        val raw = JSONObject(file.readText())
        raw.getJSONArray("records").getJSONObject(0).put("process", "previous-process")
        file.writeText(raw.toString())
        val state = FileLlmLyricsActivityStore(folder).snapshot()
        assertEquals(0, state.running)
        assertEquals(1L, state.cancelled)
        assertEquals("interrupted", state.records.single().reason)
        assertEquals(LlmActivityStatus.CANCELLED, state.records.single().status)
    }

    @Test fun `endpoint credentials and response text cannot enter journal metadata`() {
        val folder = temp.newFolder()
        val store = FileLlmLyricsActivityStore(folder)
        val unusual = config.copy(baseUrl = "https://provider.example/private-route", model = "${config.apiKey}-model")
        val id = store.begin(LlmActivityKind.TRANSLATION, unusual, 42, config.apiKey)
        store.complete(id, LlmLyricsClient.CallResult("error body with secret-not-in-records", 400))
        val text = File(folder, "activity.json").readText()
        assertFalse(text.contains(config.apiKey))
        assertFalse(text.contains("private-route"))
        assertFalse(text.contains("error body"))
        assertEquals("unknown", store.snapshot().records.single().reason)
    }
}
