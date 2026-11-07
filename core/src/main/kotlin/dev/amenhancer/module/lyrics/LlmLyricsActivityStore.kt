package dev.amenhancer.module.lyrics

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

enum class LlmActivityKind { TRANSLATION, CONNECTION_TEST, CACHE_HIT, CACHE_SAVED, CACHE_SAVE_FAILED, SOURCE_CHANGED, SETTINGS_CHANGED, SKIPPED, PLAYER_REJECTED }
enum class LlmActivityStatus { RUNNING, SUCCESS, FAILED, CANCELLED }

data class LlmActivityRecord(
    val id: String, val kind: LlmActivityKind, val status: LlmActivityStatus,
    val startedAt: Long, val songId: Long?, val title: String?, val artist: String?,
    val model: String, val host: String, val elapsedMs: Long? = null,
    val httpStatus: Int? = null, val reason: String? = null,
    val promptTokens: Long? = null, val completionTokens: Long? = null, val totalTokens: Long? = null,
    internal val process: String = "",
) {
    val isApiCall: Boolean get() = kind == LlmActivityKind.TRANSLATION || kind == LlmActivityKind.CONNECTION_TEST
}

data class LlmActivitySnapshot(
    val apiCalls: Long = 0, val successful: Long = 0, val failed: Long = 0, val cancelled: Long = 0,
    val cacheHits: Long = 0, val cacheSaves: Long = 0,
    val reportedTokens: Long = 0, val callsWithoutUsage: Long = 0,
    val records: List<LlmActivityRecord> = emptyList(),
) {
    val running: Int get() = records.count { it.isApiCall && it.status == LlmActivityStatus.RUNNING }
}

/** Bounded local activity journal. No credentials, prompts, response bodies or lyrics enter this file. */
class FileLlmLyricsActivityStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val file = File(directory, "activity.json")
    private val lock = locks.getOrPut(directory.canonicalPath) { Any() }

    fun snapshot(): LlmActivitySnapshot = synchronized(lock) { load() }

    fun begin(kind: LlmActivityKind, config: LlmLyricsConfig, songId: Long? = null,
              title: String? = null, artist: String? = null): String = synchronized(lock) {
        require(kind == LlmActivityKind.TRANSLATION || kind == LlmActivityKind.CONNECTION_TEST)
        val record = record(kind, LlmActivityStatus.RUNNING, config, songId, title, artist)
        val previous = load()
        save(previous.copy(apiCalls = add(previous.apiCalls, 1), records = retain(previous.records + record)))
        record.id
    }

    fun complete(id: String?, result: LlmLyricsClient.CallResult) = synchronized(lock) {
        if (id == null) return@synchronized
        val previous = load()
        val old = previous.records.firstOrNull { it.id == id && it.status == LlmActivityStatus.RUNNING }
            ?: return@synchronized
        val status = when (result.failureReason) {
            null -> LlmActivityStatus.SUCCESS
            "cancelled" -> LlmActivityStatus.CANCELLED
            else -> LlmActivityStatus.FAILED
        }
        val finished = old.copy(status = status, elapsedMs = result.elapsedMs,
            httpStatus = result.httpStatus, reason = safeReason(result.failureReason),
            promptTokens = result.promptTokens, completionTokens = result.completionTokens, totalTokens = result.totalTokens)
        save(previous.copy(
            successful = add(previous.successful, if (status == LlmActivityStatus.SUCCESS) 1 else 0),
            failed = add(previous.failed, if (status == LlmActivityStatus.FAILED) 1 else 0),
            cancelled = add(previous.cancelled, if (status == LlmActivityStatus.CANCELLED) 1 else 0),
            reportedTokens = add(previous.reportedTokens, result.totalTokens ?: 0),
            callsWithoutUsage = add(previous.callsWithoutUsage, if (result.totalTokens == null) 1 else 0),
            records = previous.records.map { if (it.id == id) finished else it },
        ))
    }

    fun event(kind: LlmActivityKind, config: LlmLyricsConfig, songId: Long, title: String? = null,
              artist: String? = null, reason: String? = null) = synchronized(lock) {
        require(kind != LlmActivityKind.TRANSLATION && kind != LlmActivityKind.CONNECTION_TEST)
        val previous = load()
        val record = record(kind, if (kind == LlmActivityKind.CACHE_SAVE_FAILED || kind == LlmActivityKind.PLAYER_REJECTED)
            LlmActivityStatus.FAILED else LlmActivityStatus.SUCCESS, config, songId, title, artist).copy(reason = safeReason(reason))
        save(previous.copy(
            cacheHits = add(previous.cacheHits, if (kind == LlmActivityKind.CACHE_HIT) 1 else 0),
            cacheSaves = add(previous.cacheSaves, if (kind == LlmActivityKind.CACHE_SAVED) 1 else 0),
            records = retain(previous.records + record),
        ))
    }

    /** Keep in-flight requests so finishing one cannot resurrect cleared history. */
    fun clearCompleted() = synchronized(lock) {
        val pending = runCatching { load().records.filter { it.status == LlmActivityStatus.RUNNING } }.getOrDefault(emptyList())
        save(LlmActivitySnapshot(apiCalls = pending.size.toLong(), records = pending))
    }

    private fun record(kind: LlmActivityKind, status: LlmActivityStatus, config: LlmLyricsConfig,
                       songId: Long?, title: String?, artist: String?): LlmActivityRecord {
        fun text(value: String?): String? = value?.let {
            val redacted = if (config.apiKey.isNotBlank()) it.replace(config.apiKey, "[redacted]") else it
            redacted.filterNot(Char::isISOControl).take(180).takeIf(String::isNotBlank)
        }
        return LlmActivityRecord(UUID.randomUUID().toString(), kind, status, now(), songId?.takeIf { it > 0 },
            text(title), text(artist), text(config.normalizedModel).orEmpty(),
            text(runCatching { config.endpoint?.let(::URI)?.host }.getOrNull()).orEmpty(), process = processId)
    }

    private fun retain(records: List<LlmActivityRecord>): List<LlmActivityRecord> {
        val running = records.filter { it.status == LlmActivityStatus.RUNNING }.takeLast(MAX_RECORDS)
        return (records.filterNot { it.status == LlmActivityStatus.RUNNING }.takeLast(MAX_RECORDS - running.size) + running)
            .sortedBy { it.startedAt }
    }

    private fun load(): LlmActivitySnapshot {
        if (!file.exists()) return LlmActivitySnapshot()
        check(file.length() in 1L..MAX_BYTES.toLong()) { "Activity storage is unreadable" }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        check(root.optInt("version") == 1) { "Unsupported activity storage" }
        val rawRecords = root.optJSONArray("records") ?: JSONArray()
        val records = (0 until rawRecords.length()).mapNotNull { i -> decode(rawRecords.optJSONObject(i)) }
        var interrupted = 0L
        val restored = records.map {
            if (it.status == LlmActivityStatus.RUNNING && it.process != processId) {
                interrupted++
                it.copy(status = LlmActivityStatus.CANCELLED, reason = "interrupted")
            } else it
        }
        return LlmActivitySnapshot(root.optLong("apiCalls").coerceAtLeast(0), root.optLong("successful").coerceAtLeast(0),
            root.optLong("failed").coerceAtLeast(0), add(root.optLong("cancelled").coerceAtLeast(0), interrupted),
            root.optLong("cacheHits").coerceAtLeast(0), root.optLong("cacheSaves").coerceAtLeast(0),
            root.optLong("reportedTokens").coerceAtLeast(0), add(root.optLong("callsWithoutUsage").coerceAtLeast(0), interrupted), retain(restored))
    }

    private fun decode(raw: JSONObject?): LlmActivityRecord? = runCatching {
        if (raw == null) return@runCatching null
        fun optional(name: String): Long? = if (raw.isNull(name)) null else raw.optLong(name).takeIf { it >= 0 }
        fun string(name: String): String? = raw.optString(name).takeIf { it.isNotBlank() && it != "null" }?.take(180)
        LlmActivityRecord(raw.getString("id"), LlmActivityKind.valueOf(raw.getString("kind")),
            LlmActivityStatus.valueOf(raw.getString("status")), raw.getLong("startedAt"), optional("songId"),
            string("title"), string("artist"), string("model").orEmpty(), string("host").orEmpty(),
            optional("elapsedMs"), optional("httpStatus")?.toInt(), safeReason(string("reason")), optional("promptTokens"),
            optional("completionTokens"), optional("totalTokens"), string("process").orEmpty())
    }.getOrNull()

    private fun save(snapshot: LlmActivitySnapshot) {
        check(directory.isDirectory || directory.mkdirs()) { "Activity storage is unavailable" }
        val rows = JSONArray()
        snapshot.records.forEach { row -> rows.put(JSONObject()
            .put("id", row.id).put("kind", row.kind.name).put("status", row.status.name).put("startedAt", row.startedAt)
            .put("songId", row.songId).put("title", row.title).put("artist", row.artist).put("model", row.model).put("host", row.host)
            .put("elapsedMs", row.elapsedMs).put("httpStatus", row.httpStatus).put("reason", row.reason)
            .put("promptTokens", row.promptTokens).put("completionTokens", row.completionTokens).put("totalTokens", row.totalTokens)
            .put("process", row.process)) }
        val bytes = JSONObject().put("version", 1).put("apiCalls", snapshot.apiCalls).put("successful", snapshot.successful)
            .put("failed", snapshot.failed).put("cancelled", snapshot.cancelled).put("cacheHits", snapshot.cacheHits)
            .put("cacheSaves", snapshot.cacheSaves).put("reportedTokens", snapshot.reportedTokens)
            .put("callsWithoutUsage", snapshot.callsWithoutUsage).put("records", rows).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val pending = File.createTempFile("activity-", ".tmp", directory)
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            try { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { pending.delete() }
    }

    private fun safeReason(value: String?): String? = value?.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: value?.let { "unknown" }
    private fun add(left: Long, right: Long): Long = if (Long.MAX_VALUE - left < right.coerceAtLeast(0)) Long.MAX_VALUE else left + right.coerceAtLeast(0)
    companion object {
        const val DIRECTORY = "lyricwave-ai-activity"
        private const val MAX_RECORDS = 200
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Any>()
        private val processId = UUID.randomUUID().toString()
    }
}
