package dev.amenhancer.module.share

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, private drafts keyed by the exact original sharing URL. */
internal class ShareDraftStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "lyricwave-share-draft-v1.json"))

    fun save(draft: ShareDraft) = synchronized(lock) {
        val row = JSONObject(ShareDraftCodec.encode(draft))
        val now = clock()
        requireNotNull(ShareDraftCodec.decode(row.toString(), now))
        val otherDrafts = readRows(now).filter { it.getString("url") != draft.url }
        writeRows((listOf(row) + otherDrafts).take(MAX_DRAFTS))
    }

    fun load(url: String): ShareDraft? = synchronized(lock) {
        val now = clock()
        readRows(now).firstOrNull { it.getString("url") == url }
            ?.let { ShareDraftCodec.decode(it.toString(), now) }
    }

    fun clear(url: String) = synchronized(lock) {
        val rows = readRows(clock())
        val retained = rows.filter { it.getString("url") != url }
        if (retained.size != rows.size) writeRows(retained)
    }

    private fun readRows(now: Long): List<JSONObject> {
        val rows = try {
            val root = file.openRead().buffered().use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_BYTES) {
                    val count = it.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                require(output.size() <= MAX_BYTES)
                JSONObject(output.toString(Charsets.UTF_8.name()))
            }
            when (root.opt("schemaVersion")) {
                1 -> listOf(root)
                2 -> root.getJSONArray("drafts").let { array ->
                    require(array.length() <= MAX_DRAFTS)
                    (0 until array.length()).mapNotNull(array::optJSONObject)
                }
                else -> emptyList()
            }
        } catch (_: FileNotFoundException) {
            return emptyList()
        } catch (_: org.json.JSONException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        return rows.filter { ShareDraftCodec.decode(it.toString(), now) != null }
            .also { if (it.isEmpty()) file.delete() }
    }

    private fun writeRows(rows: List<JSONObject>) {
        if (rows.isEmpty()) {
            file.delete()
            return
        }
        val bytes = JSONObject().put("schemaVersion", 2).put("drafts", JSONArray(rows))
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val MAX_DRAFTS = 12
        private const val MAX_BYTES = MAX_DRAFTS * 32 * 1024 + 128
        // Activity replacement can leave an older controller's queued write in flight.
        private val lock = Any()
        internal val io = java.util.concurrent.Executors.newSingleThreadExecutor {
            Thread(it, "LyricWave-share-draft").apply { isDaemon = true }
        }
    }
}
