package dev.amenhancer.module.update

import dev.amenhancer.module.ModuleConstants
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.Locale
import org.json.JSONObject

enum class UpdateComparison { NEWER, CURRENT, AHEAD, UNKNOWN }
data class UpdateAsset(val id: Long, val name: String, val size: Long, val downloadUrl: String, val upstreamUrl: String)
data class UpdateBuild(val versionCode: Long, val versionName: String, val apk: String, val sha256: String)
data class AppUpdateRelease(
    val tag: String, val name: String, val notes: String, val publishedAt: String, val prerelease: Boolean,
    val htmlUrl: String, val apks: List<UpdateAsset>, val build: UpdateBuild?,
    val comparison: UpdateComparison, val checkedAt: Long,
)
class AppUpdateException(val reason: String, val httpStatus: Int? = null) : Exception(reason)

/** Public v1 API plus the release workflow's authoritative build manifest; tags never order versions. */
class AppUpdateClient(
    private val read: (String, Int) -> ByteArray = AppUpdateHttpTransport()::get,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun check(localVersionCode: Long, localApplicationId: String = ModuleConstants.MODULE_PACKAGE): AppUpdateRelease {
        val root = try { JSONObject(read(VERSION_URL, MAX_VERSION_BYTES).toString(Charsets.UTF_8)) }
            catch (error: AppUpdateException) { throw error }
            catch (_: Exception) { throw AppUpdateException("protocol") }
        if (root.optInt("schemaVersion") != 1 || root.optString("repository") != REPOSITORY || root.optString("channel") != "latest")
            throw AppUpdateException("protocol")
        val release = root.optJSONObject("release") ?: throw AppUpdateException("protocol")
        val tag = release.optString("tag").takeIf { it.isNotBlank() && it.length <= 300 } ?: throw AppUpdateException("protocol")
        val htmlUrl = release.optString("htmlUrl")
        if (!officialUrl(htmlUrl, "/$REPOSITORY/releases/tag/")) throw AppUpdateException("protocol")
        val published = release.optString("publishedAt")
        if (runCatching { Instant.parse(published) }.isFailure || release.opt("prerelease") !is Boolean)
            throw AppUpdateException("protocol")
        val assets = release.optJSONArray("assets") ?: throw AppUpdateException("protocol")
        val decoded = (0 until assets.length()).mapNotNull { index -> assets.optJSONObject(index)?.let(::asset) }
        val apks = decoded.filter { compatibleApk(it.name) }
        val manifest = decoded.singleOrNull { it.name == "build-metadata.json" }
        val build = manifest?.let { entry ->
            // One proxy attempt and one official fallback, with no persistent signed redirect URLs.
            sequenceOf(entry.downloadUrl, entry.upstreamUrl).mapNotNull { url ->
                runCatching { parseBuild(JSONObject(read(url, MAX_METADATA_BYTES).toString(Charsets.UTF_8)), tag, apks) }.getOrNull()
            }.firstOrNull()
        }
        val comparison = when {
            build == null || localApplicationId != ModuleConstants.MODULE_PACKAGE -> UpdateComparison.UNKNOWN
            build.versionCode > localVersionCode -> UpdateComparison.NEWER
            build.versionCode == localVersionCode -> UpdateComparison.CURRENT
            else -> UpdateComparison.AHEAD
        }
        return AppUpdateRelease(tag, release.optString("name").take(300).ifBlank { tag }, release.optString("notes").take(20_000),
            published, release.optBoolean("prerelease"), htmlUrl, apks, build, comparison, clock())
    }

    private fun asset(row: JSONObject): UpdateAsset? {
        val id = positiveLong(row.opt("id")) ?: return null
        val name = row.optString("name").takeIf { it.length in 1..300 && it.none { c -> c.isISOControl() || c == '/' || c == '\\' } } ?: return null
        val size = positiveLong(row.opt("size")) ?: return null
        val download = row.optString("downloadUrl")
        if (download != "/api/v1/download/$id") return null
        val upstream = row.optString("upstreamUrl")
        if (!officialUrl(upstream, "/$REPOSITORY/releases/download/") || runCatching { URI(upstream).path.substringAfterLast('/') }.getOrNull() != name)
            return null
        if (name.endsWith(".apk", true) && row.optString("kind") != "apk") return null
        return UpdateAsset(id, name, size, ORIGIN + download, upstream)
    }

    private fun parseBuild(row: JSONObject, tag: String, apks: List<UpdateAsset>): UpdateBuild? {
        val version = row.optString("versionName")
        if (tag != version && tag != "v$version") return null // association only, never version ordering
        if (row.optString("applicationId") != ModuleConstants.MODULE_PACKAGE ||
            row.optString("certificateSha256").lowercase(Locale.ROOT) != CERTIFICATE_SHA256) return null
        val code = positiveLong(row.opt("versionCode"))?.takeIf { it <= Int.MAX_VALUE } ?: return null
        val apk = row.optString("apk")
        if (apks.count { it.name == apk } != 1) return null
        val hash = row.optString("apkSha256").lowercase(Locale.ROOT).takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        if (!row.optString("commit").matches(Regex("[0-9a-fA-F]{40}"))) return null
        return UpdateBuild(code, version, apk, hash)
    }

    companion object {
        const val ORIGIN = "https://lyricwave.canz-monkey.cn"
        const val REPOSITORY = "Laoch832/LyricWave"
        const val VERSION_URL = "$ORIGIN/api/v1/version?channel=latest"
        const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
        const val CERTIFICATE_SHA256 = "4ec2f441139585083e476b596a503db7e6c459ec1ca1d9bcf59d815b6981da53"
        const val MAX_VERSION_BYTES = 512 * 1024
        const val MAX_METADATA_BYTES = 64 * 1024
        private fun positiveLong(value: Any?): Long? = (value as? Number)?.toString()?.toLongOrNull()?.takeIf { it > 0 }
        private fun compatibleApk(name: String): Boolean = name.startsWith("LyricWave-") && name.endsWith(".apk", true) &&
            !name.contains("debug", true) && !name.contains("unsigned", true)
        private fun officialUrl(value: String, prefix: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "github.com" && uri.port in listOf(-1, 443) &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.startsWith(prefix)
        }.getOrDefault(false)
    }
}

/** Bounded public reads. No account tokens, cookies or release-asset signed URLs are retained. */
class AppUpdateHttpTransport(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun get(url: String, maxBytes: Int): ByteArray {
        var next = URI(url)
        repeat(5) {
            if (!allowed(next)) throw AppUpdateException("redirect")
            val connection = try { connect(next.toURL()) } catch (_: Exception) { throw AppUpdateException("transport") }
            try {
                connection.connectTimeout = 5_000; connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "LyricWave-update/1")
                connection.setRequestProperty("Cookie", "")
                connection.setRequestProperty("Authorization", "")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw AppUpdateException("redirect")
                    next = try { next.resolve(location) } catch (_: Exception) { throw AppUpdateException("redirect") }
                } else {
                    if (status != 200) throw AppUpdateException("http", status)
                    val body = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= maxBytes) {
                            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (body.size > maxBytes) throw AppUpdateException("size")
                    return body
                }
            } catch (error: AppUpdateException) { throw error }
            catch (_: Exception) { throw AppUpdateException("transport") }
            finally { connection.disconnect() }
        }
        throw AppUpdateException("redirect")
    }

    companion object {
    fun allowed(uri: URI): Boolean = uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
        uri.rawFragment == null && when (uri.host) {
            "lyricwave.canz-monkey.cn" -> uri.path.startsWith("/api/v1/")
            "github.com" -> uri.path.startsWith("/${AppUpdateClient.REPOSITORY}/releases/download/")
            "release-assets.githubusercontent.com" -> true
            else -> false
        }
    }
}
