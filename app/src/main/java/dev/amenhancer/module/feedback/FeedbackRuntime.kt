package dev.amenhancer.module.feedback

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.playback.PlaybackRecoveryStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class FeedbackStatus(val count: Int = 0, val total: Int = 0, val sending: Boolean = false, val lastSuccess: Long = 0, val failed: Boolean = false)

/** Structured diagnostics only. Exception messages, URLs, request bodies, account details and lyrics never enter the journal. */
internal object FeedbackRuntime {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "LyricWave-feedback").apply { isDaemon = true } }
    private val mutable = MutableStateFlow(FeedbackStatus())
    val status = mutable.asStateFlow()
    @Volatile private var application: Context? = null
    @Volatile private var consent: () -> Boolean = { true }
    @Volatile private var overrideConsent: Boolean? = null
    @Volatile private var connection: HttpURLConnection? = null
    private var scheduled: ScheduledFuture<*>? = null
    private val identifier = Regex("[A-Za-z0-9_.$<>-]{1,180}")
    @Synchronized fun initialize(context: Context, enabled: () -> Boolean = { true }) {
        consent = enabled
        if (application != null) return
        application = context.applicationContext
        runCatching { ModernXposedRuntime.errorObserver = { message, error ->
            val category = when {
                message.contains("playback", true) || message.contains("DRM", true) -> "playback"
                message.contains("share", true) -> "share"
                message.contains("plugin", true) -> "plugin"
                message.contains("lyric", true) -> "lyrics"
                message.contains("update", true) -> "update"
                else -> "module"
            }
            record(category, error)
        }
        }
        PlaybackRecoveryStatus.errorObserver = { report, error ->
            record("playback", error, mapOf("native" to report.nativeErrorCode, "component" to report.componentErrorCode,
                "http" to report.httpStatus, "state" to PlaybackRecoveryStatus.nativeState))
        }
        worker.execute { refresh(); schedule(5) }
    }
    fun changed(enabled: Boolean) { overrideConsent = enabled; if (!enabled) { connection?.disconnect(); scheduled?.cancel(false) } else schedule(5) }
    private fun enabled() = overrideConsent ?: runCatching(consent).getOrDefault(false)
    fun record(category: String, error: Throwable? = null, codes: Map<String, Int?> = emptyMap()) {
        val app = application ?: return
        // Capture class/line identities before dispatch, never Throwable objects with private messages.
        val packageInfo = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val hostCode = packageInfo?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0
        val record = sanitizedRecord(category, error, codes, app.packageName, packageInfo?.versionName, hostCode)
        worker.execute {
            runCatching {
                val rows = read(); rows.put(record)
                var values = (0 until rows.length()).map { rows.getJSONObject(it) }.takeLast(200)
                while (JSONArray(values).toString().toByteArray().size > 512 * 1024 && values.size > 1) values = values.drop(1)
                write(JSONArray(values)); refresh(); schedule(5)
            }
        }
    }
    internal fun sanitizedRecord(category: String, error: Throwable?, codes: Map<String, Int?>,
        hostPackage: String, hostVersion: String?, hostCode: Long = 0): JSONObject {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val frames = JSONArray()
        // Root causes must survive even when ExoPlayer adds a long outer stack.
        chain.asReversed().flatMap { it.stackTrace.take(8) }.distinct().take(32).forEach { frame ->
            if (identifier.matches(frame.className) && identifier.matches(frame.methodName)) frames.put(JSONObject()
                .put("owner", frame.className).put("method", frame.methodName).put("line", frame.lineNumber))
        }
        val values = JSONObject(); codes.forEach { (key, value) -> if (key in setOf("native", "component", "http", "state", "result") && value != null) values.put(key, value) }
        return JSONObject().put("id", UUID.randomUUID().toString()).put("at", System.currentTimeMillis())
            .put("category", category.takeIf { it in setOf("module", "playback", "share", "download", "update", "lyrics", "plugin") } ?: "module")
            .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE).put("hostPackage", hostPackage).put("hostVersionCode", hostCode)
            .put("hostVersion", hostVersion?.takeIf { it.matches(Regex("[0-9A-Za-z._-]{1,64}")) } ?: "unknown")
            .put("sdk", Build.VERSION.SDK_INT).put("codes", values).put("frames", frames)
            .put("exceptions", JSONArray(chain.map { it.javaClass.name }.filter(identifier::matches)))
    }
    @Synchronized private fun schedule(seconds: Long) {
        if (!enabled() || scheduled?.isDone == false) return
        scheduled = worker.schedule({ scheduled = null; upload(false) }, seconds, TimeUnit.SECONDS)
    }
    fun sendNow() { worker.execute { upload(true) } }
    fun clear() { worker.execute { write(JSONArray()); refresh() } }
    fun recent(): List<JSONObject> = synchronized(this) { val rows = read(); (0 until rows.length()).map { rows.getJSONObject(it) }.takeLast(30).reversed() }
    fun exportRecent(): String = exportRecords(recent())
    internal fun exportRecords(records: List<JSONObject>): String {
        val allowed = listOf("id", "at", "category", "appVersion", "appVersionCode", "hostPackage", "hostVersion",
            "hostVersionCode", "sdk", "codes", "exceptions", "frames")
        var selected = records.take(20).map { row -> JSONObject().apply {
            allowed.forEach { key -> if (row.has(key)) put(key, row.get(key)) }
        } }
        fun encode() = JSONObject().put("schemaVersion", 1).put("records", JSONArray(selected)).toString(2)
        while (encode().toByteArray(Charsets.UTF_8).size > 60 * 1024 && selected.isNotEmpty()) selected = selected.dropLast(1)
        return encode()
    }
    private fun journal() = AtomicFile(File(requireNotNull(application).filesDir, "lyricwave-errors-v1.json"))
    @Synchronized private fun read(): JSONArray = runCatching {
        val file = journal(); if (!file.baseFile.exists() || file.baseFile.length() > 512 * 1024) JSONArray()
        else JSONArray(file.readFully().toString(Charsets.UTF_8))
    }.getOrDefault(JSONArray())
    @Synchronized private fun write(rows: JSONArray) {
        val file = journal(); val output = file.startWrite()
        try { output.write(rows.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun refresh() {
        val app = application ?: return
        val preferences = app.getSharedPreferences("lyricwave_feedback_state", Context.MODE_PRIVATE)
        val rows = read()
        mutable.value = mutable.value.copy(count = (0 until rows.length()).count { !rows.getJSONObject(it).optBoolean("_sent") }, total = rows.length(), lastSuccess = preferences.getLong("success", 0))
    }
    private fun upload(manual: Boolean) {
        val app = application ?: return
        if (!manual && !enabled()) return
        val preferences = app.getSharedPreferences("lyricwave_feedback_state", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis(); val next = preferences.getLong("next", 0)
        if (!manual && next > now && next - now < 24 * 60 * 60 * 1000L) { schedule(((next - now) / 1000).coerceAtLeast(1)); return }
        val all = read(); if (all.length() == 0) return
        var selected = (0 until all.length()).map { all.getJSONObject(it) }.filterNot { it.optBoolean("_sent") }.take(20)
        if (selected.isEmpty()) return
        fun body(): JSONObject {
            val id = UUID.nameUUIDFromBytes(selected.joinToString { it.getString("id") }.toByteArray()).toString()
            return JSONObject().put("schemaVersion", 1).put("batchId", id).put("records", JSONArray(selected.map { JSONObject(it.toString()).apply { remove("_sent") } }))
        }
        while (body().toString().toByteArray().size > 60 * 1024 && selected.size > 1) selected = selected.dropLast(1)
        val payload = body(); val bytes = payload.toString().toByteArray()
        mutable.value = mutable.value.copy(sending = true, failed = false)
        var retry = 15 * 60L
        try {
            val active = URL("https://lyricwave.canz-monkey.cn/api/v1/feedback").openConnection() as HttpURLConnection
            connection = active
            active.requestMethod = "POST"; active.instanceFollowRedirects = false
            active.connectTimeout = 5000; active.readTimeout = 8000; active.doOutput = true
            active.setRequestProperty("Content-Type", "application/json"); active.setRequestProperty("Cookie", ""); active.setRequestProperty("Authorization", "")
            active.setFixedLengthStreamingMode(bytes.size); active.outputStream.use { it.write(bytes) }
            if (active.responseCode != 202) {
                retry = active.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(60, 21600) ?: retry
                throw IllegalStateException("Upload not acknowledged")
            }
            val response = active.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
                while (output.size() <= 8192) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                require(output.size() <= 8192); JSONObject(output.toString("UTF-8"))
            }
            require(response.optBoolean("accepted") && response.optString("batchId") == payload.getString("batchId"))
            val ids = selected.map { it.getString("id") }.toSet()
            val remaining = (0 until all.length()).map { all.getJSONObject(it).apply { if (getString("id") in ids) put("_sent", true) } }
            write(JSONArray(remaining)); preferences.edit().putLong("success", now).putLong("next", now + 60_000).apply()
            mutable.value = mutable.value.copy(lastSuccess = now, failed = false)
            retry = 60
        } catch (_: Exception) {
            preferences.edit().putLong("next", now + retry * 1000).apply()
            mutable.value = mutable.value.copy(failed = true)
        } finally { connection?.disconnect(); connection = null; mutable.value = mutable.value.copy(sending = false); refresh() }
        val remaining = read()
        if ((0 until remaining.length()).any { !remaining.getJSONObject(it).optBoolean("_sent") }) schedule(retry)
    }
}
