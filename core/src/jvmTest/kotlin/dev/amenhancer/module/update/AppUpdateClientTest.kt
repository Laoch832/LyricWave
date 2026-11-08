package dev.amenhancer.module.update

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateClientTest {
    private val tag = "v1.0.0-beta.10"
    private val apk = "LyricWave-1.0.0-beta.10.apk"
    private fun asset(id: Int, name: String, kind: String) = JSONObject().put("id", id).put("name", name).put("size", 12345)
        .put("kind", kind).put("downloadUrl", "/api/v1/download/$id")
        .put("upstreamUrl", "https://github.com/Laoch832/LyricWave/releases/download/$tag/$name")
    private fun response() = JSONObject().put("schemaVersion", 1).put("repository", AppUpdateClient.REPOSITORY).put("channel", "latest")
        .put("release", JSONObject().put("tag", tag).put("name", "Release 10").put("notes", "Public notes")
            .put("publishedAt", "2026-11-08T00:00:00Z").put("prerelease", true).put("versionCode", JSONObject.NULL)
            .put("htmlUrl", "https://github.com/Laoch832/LyricWave/releases/tag/$tag")
            .put("assets", JSONArray().put(asset(1, "SHA256SUMS.txt", "other"))
                .put(asset(2, "build-metadata.json", "other")).put(asset(3, apk, "apk"))
                .put(asset(4, "LyricWave-1.0.0-beta.10-debug.apk", "apk"))))
    private fun manifest() = JSONObject().put("versionName", "1.0.0-beta.10").put("versionCode", 130)
        .put("applicationId", "dev.amenhancer.module").put("certificateSha256", AppUpdateClient.CERTIFICATE_SHA256)
        .put("apk", apk).put("apkSha256", "a".repeat(64)).put("commit", "b".repeat(40))
    private fun client(api: JSONObject = response(), metadata: JSONObject? = manifest(), calls: MutableList<String> = mutableListOf()) =
        AppUpdateClient(read = { url, _ ->
            calls += url
            if (url == AppUpdateClient.VERSION_URL) api.toString().toByteArray()
            else metadata?.toString()?.toByteArray() ?: throw AppUpdateException("http", 503)
        }, clock = { 123L })

    @Test fun `compares authoritative integer codes rather than prerelease tag text`() {
        val result = client().check(129)
        assertEquals(UpdateComparison.NEWER, result.comparison)
        assertEquals(130L, result.build?.versionCode)
        assertEquals(listOf(apk), result.apks.map { it.name })
        assertTrue(result.prerelease)
        assertEquals(123L, result.checkedAt)
        assertEquals(UpdateComparison.CURRENT, client().check(130).comparison)
        assertEquals(UpdateComparison.AHEAD, client().check(131).comparison)
    }
    @Test fun `missing manifest never infers an upgrade from the tag or API versionCode`() {
        val api = response()
        api.getJSONObject("release").put("versionCode", 999999)
        val result = client(api, null).check(1)
        assertEquals(UpdateComparison.UNKNOWN, result.comparison)
        assertEquals(apk, result.apks.single().name)
    }
    @Test fun `wrong package certificate file tag or hash cannot establish an upgrade`() {
        for ((key, value) in listOf("applicationId" to "com.apple.android.music", "certificateSha256" to "0".repeat(64),
            "apkSha256" to "bad", "apk" to "LyricWave-unsigned.apk", "versionName" to "another-version",
            "commit" to "unknown", "versionCode" to "130")) {
            assertEquals(key, UpdateComparison.UNKNOWN, client(metadata = manifest().put(key, value)).check(1).comparison)
        }
        assertEquals(UpdateComparison.UNKNOWN, client().check(1, "dev.amenhancer.module.debug").comparison)
    }
    @Test fun `a broken proxy manifest falls back once to the official release asset`() {
        val api = response(); val calls = mutableListOf<String>()
        val instance = AppUpdateClient(read = { url, _ ->
            calls += url
            when {
                url == AppUpdateClient.VERSION_URL -> api.toString().toByteArray()
                url.startsWith(AppUpdateClient.ORIGIN) -> throw AppUpdateException("http", 502)
                else -> manifest().toString().toByteArray()
            }
        })
        assertEquals(UpdateComparison.NEWER, instance.check(129).comparison)
        assertEquals(3, calls.size)
        assertTrue(calls.last().startsWith("https://github.com/Laoch832/LyricWave/releases/download/"))
    }
    @Test fun `unsafe links and unsupported protocols are rejected`() {
        val api = response(); val assets = api.getJSONObject("release").getJSONArray("assets")
        assets.getJSONObject(2).put("upstreamUrl", "https://github.com.evil.test/$apk")
        assets.getJSONObject(3).put("downloadUrl", "https://elsewhere.test/file")
        assertTrue(client(api).check(1).apks.isEmpty())
        for (invalid in listOf(response().put("schemaVersion", 2), response().put("repository", "someone/else"))) {
            try { client(invalid).check(1); fail("Invalid protocol accepted") }
            catch (expected: AppUpdateException) { assertEquals("protocol", expected.reason) }
        }
    }
    @Test fun `transport limits bodies and blocks redirects outside release hosts`() {
        var opened = 0
        val transport = AppUpdateHttpTransport { url ->
            opened++
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 302
                override fun getHeaderField(name: String?) = if (name == "Location") "https://example.com/private" else null
            }
        }
        try { transport.get(AppUpdateClient.VERSION_URL, 20); fail("Unsafe redirect accepted") }
        catch (expected: AppUpdateException) { assertEquals("redirect", expected.reason) }
        assertEquals(1, opened)
        val large = AppUpdateHttpTransport { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(ByteArray(100))
            }
        }
        try { large.get(AppUpdateClient.VERSION_URL, 20); fail("Oversized response accepted") }
        catch (expected: AppUpdateException) { assertEquals("size", expected.reason) }
    }
}
