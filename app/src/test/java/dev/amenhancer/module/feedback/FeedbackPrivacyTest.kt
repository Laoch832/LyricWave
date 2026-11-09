package dev.amenhancer.module.feedback

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FeedbackPrivacyTest {
    @Test fun `diagnostics never contain exception messages URLs lyrics keys or filenames`() {
        val error = IllegalStateException("secret-token https://example.test/license?token=secret private lyrics sk-private")
        error.stackTrace = arrayOf(StackTraceElement("NativeRenderer", "decode", "/private/account/secret.java", 42))
        val record = FeedbackRuntime.sanitizedRecord("playback", error, mapOf("native" to -25, "component" to -1, "unsafe" to 666), "com.apple.android.music", "7.0.0")
        val json = record.toString()
        for (secret in listOf("secret-token", "example.test", "private lyrics", "sk-private", "/private", "secret.java", "unsafe")) assertFalse(secret, json.contains(secret))
        assertEquals(-25, record.getJSONObject("codes").getInt("native"))
        assertEquals("NativeRenderer", record.getJSONArray("frames").getJSONObject(0).getString("owner"))
        assertEquals(42, record.getJSONArray("frames").getJSONObject(0).getInt("line"))
        assertEquals("java.lang.IllegalStateException", record.getJSONArray("exceptions").getString(0))
    }

    @Test fun `root cause frames survive long wrapper stacks and copied diagnostics stay sanitized`() {
        val root = RuntimeException("secret-license-token")
        root.stackTrace = arrayOf(StackTraceElement("LicenseEngine", "openSession", "private.java", 27))
        val error = IllegalStateException("secret-account", root)
        error.stackTrace = (0 until 80).map { StackTraceElement("ExoWrapper", "frame$it", "private.java", it) }.toTypedArray()
        val record = FeedbackRuntime.sanitizedRecord("playback", error, mapOf("state" to 1), "com.apple.android.music", "7.0.0")
        assertEquals("LicenseEngine", record.getJSONArray("frames").getJSONObject(0).getString("owner"))
        assertTrue(record.getJSONArray("frames").length() <= 32)
        record.put("_sent", true).put("message", "secret-journal-metadata")
        val copy = FeedbackRuntime.exportRecords(listOf(record))
        for (secret in listOf("secret-license-token", "secret-account", "private.java", "_sent", "secret-journal-metadata")) assertFalse(copy.contains(secret))
        assertEquals(1, org.json.JSONObject(copy).getJSONArray("records").getJSONObject(0).getJSONObject("codes").getInt("state"))
    }
}
