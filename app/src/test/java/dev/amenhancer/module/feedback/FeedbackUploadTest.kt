package dev.amenhancer.module.feedback

import android.content.Context
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FeedbackUploadTest {
    private fun row(at: Long) = FeedbackRuntime.sanitizedRecord("playback", RuntimeException("private-message"),
        mapOf("state" to 1), "com.apple.android.music", "7.0.0", 1614).put("at", at)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { if (System.nanoTime() >= deadline) fail("Feedback worker timeout"); Thread.sleep(10) }
    }
    @Test fun `time windows match the server without deleting historical diagnostics`() {
        val now = System.currentTimeMillis()
        val rows = JSONArray(listOf(row(now - 30 * 86_400_000L - 1), row(now - 30 * 86_400_000L),
            row(now), row(now + 86_400_000L), row(now + 86_400_000L + 1), row(now).put("_sent", true)))
        assertEquals(3, FeedbackRuntime.pendingRecords(rows, now).size)
        assertEquals(6, rows.length())
    }
    @Test fun `expired records cannot block fresh uploads and only matching acknowledgments create receipts`() {
        val app = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val old = row(now - 31 * 86_400_000L)
        val fresh = row(now)
        File(app.filesDir, "lyricwave-errors-v1.json").writeText(JSONArray(listOf(old, fresh)).toString())
        val preferences = app.getSharedPreferences("lyricwave_feedback_state", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val received = AtomicReference<JSONObject>()
        val wrongAck = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feedback") { exchange ->
            val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            received.set(request)
            val rows = request.getJSONArray("records")
            val invalid = (0 until rows.length()).any { rows.getJSONObject(it).getLong("at") < System.currentTimeMillis() - 30 * 86_400_000L }
            val reply = JSONObject().put("accepted", !invalid).put("batchId", if (wrongAck.get()) "wrong-batch" else request.getString("batchId")).toString().toByteArray()
            exchange.sendResponseHeaders(if (invalid) 400 else 202, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        val original = FeedbackRuntime.openConnection
        try {
            FeedbackRuntime.openConnection = { requested ->
                assertEquals("https://lyricwave.canz-monkey.cn/api/v1/feedback", requested.toString())
                URL("http://127.0.0.1:${server.address.port}/feedback").openConnection() as HttpURLConnection
            }
            FeedbackRuntime.initialize(app) { false }; FeedbackRuntime.changed(false)
            FeedbackRuntime.sendNow()
            await { FeedbackRuntime.status.value.receipt != null && !FeedbackRuntime.status.value.sending }
            val state = FeedbackRuntime.status.value
            assertEquals(0, state.count); assertEquals(1, state.outsideWindow); assertEquals(2, state.total)
            assertEquals(1, received.get().getJSONArray("records").length())
            assertEquals(fresh.getString("id"), received.get().getJSONArray("records").getJSONObject(0).getString("id"))
            val receipt = requireNotNull(state.receipt)
            assertEquals(received.get().getString("batchId"), receipt.batchId)
            assertEquals(1, receipt.records)
            assertEquals(receipt, FeedbackReceipt.parse(preferences.getString("receipt", null)))
            assertFalse(receipt.json().contains("private-message")); assertFalse(receipt.json().contains("frames"))
            val history = JSONArray(File(app.filesDir, "lyricwave-errors-v1.json").readText())
            assertFalse(history.getJSONObject(0).optBoolean("_sent")); assertTrue(history.getJSONObject(1).getBoolean("_sent"))
            wrongAck.set(true)
            FeedbackRuntime.record("playback", RuntimeException("another-private-message"))
            FeedbackRuntime.sendNow()
            await { FeedbackRuntime.status.value.failed && !FeedbackRuntime.status.value.sending }
            assertEquals(1, FeedbackRuntime.status.value.count)
            assertEquals(receipt, FeedbackRuntime.status.value.receipt)
            assertNull(FeedbackReceipt.parse("{\"schemaVersion\":1,\"batchId\":\"invalid\"}"))
        } finally { FeedbackRuntime.changed(false); FeedbackRuntime.openConnection = original; server.stop(0) }
    }
}
