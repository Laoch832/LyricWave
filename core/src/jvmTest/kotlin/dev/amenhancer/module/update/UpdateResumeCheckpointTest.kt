package dev.amenhancer.module.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class UpdateResumeCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val bytes = ByteArray(64) { it.toByte() }
    private val asset = UpdateAsset(17, "LyricWave-1.7.8.apk", 64, "https://private.invalid/signed", "https://private.invalid/token")
    private val build = UpdateBuild(140, "1.7.8", asset.name, hash(bytes))
    private val first = UpdateResumeBlock(0, 32, hash(bytes.copyOfRange(0, 32)))
    private val second = UpdateResumeBlock(32, 32, hash(bytes.copyOfRange(32, 64)))
    @Test fun `checkpoint recreates exact identity and does not serialize transport secrets`() {
        val raw = UpdateResumeCheckpoint.create(asset, build, listOf(first, second)).encode()
        val restored = requireNotNull(UpdateResumeCheckpoint.decode(raw, asset, build))
        assertEquals(listOf(first, second), restored.blocks)
        assertFalse(raw.contains("https")); assertFalse(raw.contains("private.invalid"))
        assertNull(UpdateResumeCheckpoint.decode(raw, asset.copy(id = 18), build))
        assertNull(UpdateResumeCheckpoint.decode(raw, asset, build.copy(sha256 = "a".repeat(64))))
        assertNull(UpdateResumeCheckpoint.decode(raw, asset, build.copy(versionCode = 141)))
    }
    @Test fun `corrupt or incomplete blocks are excluded without discarding verified neighbours`() {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val checkpoint = UpdateResumeCheckpoint.create(asset, build, listOf(first, second))
        assertEquals(listOf(first, second), checkpoint.verifiedBlocks(file))
        RandomAccessFile(file, "rw").use { it.seek(40); it.write(127) }
        assertEquals(listOf(first), checkpoint.verifiedBlocks(file))
        RandomAccessFile(file, "rw").use { it.setLength(40) }
        assertEquals(listOf(first), checkpoint.verifiedBlocks(file))
    }
    @Test fun `overlap overflow fractional offsets and excessive checkpoints fail closed`() {
        val raw = UpdateResumeCheckpoint.create(asset, build, listOf(first, second)).encode()
        for (start in listOf<Any>(-1, 31, Long.MAX_VALUE, 32.5)) {
            val row = JSONObject(raw); row.getJSONArray("blocks").getJSONObject(1).put("start", start)
            assertNull(UpdateResumeCheckpoint.decode(row.toString(), asset, build))
        }
        assertNull(UpdateResumeCheckpoint.decode(" ".repeat(32769), asset, build))
    }
    @Test fun `cancellation during local validation is not converted into reusable bytes`() {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val checkpoint = UpdateResumeCheckpoint.create(asset, build, listOf(first))
        assertThrows(CancellationException::class.java) { checkpoint.verifiedBlocks(file) { throw CancellationException() } }
    }
}
