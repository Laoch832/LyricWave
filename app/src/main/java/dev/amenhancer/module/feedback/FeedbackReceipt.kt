package dev.amenhancer.module.feedback

import org.json.JSONObject
import java.util.UUID

/** Acknowledgment identity only, with no diagnostic payload or credentials. */
internal data class FeedbackReceipt(val batchId: String, val acknowledgedAt: Long, val records: Int, val appVersion: String) {
    fun json(): String = JSONObject().put("schemaVersion", 1).put("batchId", batchId)
        .put("acknowledgedAt", acknowledgedAt).put("records", records).put("appVersion", appVersion).toString(2)
    companion object {
        fun parse(raw: String?): FeedbackReceipt? = runCatching {
            require(raw != null && raw.length <= 2048)
            val row = JSONObject(raw)
            require(row.optInt("schemaVersion") == 1)
            val id = row.getString("batchId")
            require(UUID.fromString(id).toString() == id)
            val at = row.getLong("acknowledgedAt"); val count = row.getInt("records"); val version = row.getString("appVersion")
            require(at > 0 && count in 1..20 && version.matches(Regex("[0-9A-Za-z.-]{1,64}")))
            FeedbackReceipt(id, at, count, version)
        }.getOrNull()
    }
}
