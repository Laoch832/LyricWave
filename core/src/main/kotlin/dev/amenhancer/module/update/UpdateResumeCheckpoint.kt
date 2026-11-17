package dev.amenhancer.module.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

/** Development-only foundation: no network URLs, credentials or caller-provided file paths. */
data class UpdateResumeBlock(val start: Long, val length: Long, val sha256: String)

class UpdateResumeCheckpoint private constructor(
    val assetId: Long, val size: Long, val sha256: String,
    val versionCode: Long, val versionName: String, val blocks: List<UpdateResumeBlock>,
) {
    init {
        require(assetId > 0 && size in 1..AppUpdateDownloader.MAX_APK_BYTES && HASH.matches(sha256))
        require(versionCode > 0 && versionName.length in 1..100 && blocks.size <= 128)
        var end = 0L
        blocks.forEach {
            require(it.start >= end && it.start < size && it.length in 1..(size - it.start) && HASH.matches(it.sha256))
            end = it.start + it.length
        }
    }
    fun encode(): String = JSONObject().put("schemaVersion", 1).put("assetId", assetId).put("size", size)
        .put("sha256", sha256).put("versionCode", versionCode).put("versionName", versionName)
        .put("blocks", JSONArray().also { rows -> blocks.forEach {
            rows.put(JSONObject().put("start", it.start).put("length", it.length).put("sha256", it.sha256))
        } }).toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    /** Stored byte counts are never evidence: rehash each range before it can be reused. */
    fun verifiedBlocks(file: File, checkCancelled: () -> Unit = {}): List<UpdateResumeBlock> {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > size) return emptyList()
        return RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(64 * 1024)
            blocks.filter { block ->
                checkCancelled()
                if (input.length() < block.start + block.length) false else {
                    input.seek(block.start)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var remaining = block.length
                    while (remaining > 0) {
                        checkCancelled()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        digest.update(buffer, 0, count); remaining -= count
                    }
                    remaining == 0L && digest.digest().joinToString("") { "%02x".format(it) } == block.sha256
                }
            }
        }
    }
    companion object {
        private val HASH = Regex("[a-f0-9]{64}")
        private const val MAX_BYTES = 32 * 1024
        fun create(asset: UpdateAsset, build: UpdateBuild, blocks: List<UpdateResumeBlock>): UpdateResumeCheckpoint {
            require(asset.name == build.apk)
            return UpdateResumeCheckpoint(asset.id, asset.size, build.sha256, build.versionCode, build.versionName,
                blocks.toList().sortedBy { it.start })
        }
        fun decode(raw: String, asset: UpdateAsset, build: UpdateBuild): UpdateResumeCheckpoint? = runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && asset.name == build.apk)
            val row = JSONObject(raw)
            fun integer(value: JSONObject, key: String): Long {
                val number = value.get(key); require(number is Number)
                return requireNotNull(number.toString().toLongOrNull())
            }
            require(row.opt("schemaVersion") == 1)
            val rows = row.getJSONArray("blocks"); require(rows.length() <= 128)
            val checkpoint = UpdateResumeCheckpoint(integer(row, "assetId"), integer(row, "size"), row.getString("sha256"),
                integer(row, "versionCode"), row.getString("versionName"), (0 until rows.length()).map {
                    rows.getJSONObject(it).let { part -> UpdateResumeBlock(integer(part, "start"), integer(part, "length"), part.getString("sha256")) }
                })
            require(checkpoint.assetId == asset.id && checkpoint.size == asset.size && checkpoint.sha256 == build.sha256 &&
                checkpoint.versionCode == build.versionCode && checkpoint.versionName == build.versionName)
            checkpoint
        }.getOrNull()
    }
}
