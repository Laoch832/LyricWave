package dev.amenhancer.module.lyrics.source

import java.net.HttpURLConnection
import java.net.URL

/** Network surface used by the lyric clients; faked in unit tests. */
data class LyricHttpResponse(
    val statusCode: Int,
    val body: ByteArray?,
    val etag: String? = null,
)

enum class LyricFetchFailure { NOT_FOUND, REQUEST, INVALID }

interface LyricHttpTransport {
    fun get(url: String): String?

    /** Raw response bytes for callers that must verify remote size and hash. */
    fun getBytes(url: String): ByteArray? = get(url)?.toByteArray(Charsets.UTF_8)

    /** Walk interchangeable public endpoints; existing transports and test fakes retain their byte validation. */
    fun getFirstBytes(urls: List<String>, totalBudgetMs: Long = 0L): ByteArray? =
        urls.firstNotNullOfOrNull { url -> runCatching { getBytes(url) }.getOrNull() }

    fun getFirstValidBytes(urls: List<String>, accept: (ByteArray) -> Boolean): ByteArray? =
        urls.firstNotNullOfOrNull { url -> runCatching { getBytes(url)?.takeIf(accept) }.getOrNull() }

    /** Optional response metadata used by catalog clients for conditional GET. */
    fun getResponse(url: String, ifNoneMatch: String? = null): LyricHttpResponse? =
        getBytes(url)?.let { bytes -> LyricHttpResponse(HttpURLConnection.HTTP_OK, bytes) }
}

/**
 * Minimal HTTP transport for lyric sources. Strict timeouts, a hard response
 * size cap and fail-open semantics: any network problem returns `null` and
 * the caller keeps the original lyrics. Runs on the background executor only,
 * never on the parser/I2 hook or the main thread.
 */
class HttpLyricTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val onFailure: ((LyricFetchFailure) -> Unit)? = null,
) : LyricHttpTransport {

    override fun get(url: String): String? =
        getResponse(url)?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }
            ?.body?.toString(Charsets.UTF_8)

    override fun getBytes(url: String): ByteArray? =
        getResponse(url)?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }?.body

    override fun getResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? =
        requestResponse(url, ifNoneMatch)

    override fun getFirstBytes(urls: List<String>, totalBudgetMs: Long): ByteArray? =
        firstBytes(urls, totalBudgetMs) { true }

    override fun getFirstValidBytes(urls: List<String>, accept: (ByteArray) -> Boolean): ByteArray? =
        firstBytes(urls, 0L, accept)

    private fun firstBytes(urls: List<String>, totalBudgetMs: Long, accept: (ByteArray) -> Boolean): ByteArray? {
        val budget = if (totalBudgetMs > 0) totalBudgetMs else 6_000L
        val started = System.nanoTime()
        for (url in urls) {
            val remaining = (budget - (System.nanoTime() - started) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (remaining <= 0) return null
            if (Thread.currentThread().isInterrupted) return null
            val body = requestResponse(url, null, minOf(connectTimeoutMs, 4_000, remaining), minOf(readTimeoutMs, 6_000, remaining))
                ?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }?.body
            if (body != null && accept(body)) return body
        }
        return null
    }

    private fun requestResponse(
        url: String, ifNoneMatch: String?,
        connectTimeoutMsOverride: Int = connectTimeoutMs, readTimeoutMsOverride: Int = readTimeoutMs,
    ): LyricHttpResponse? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMsOverride
            connection.readTimeout = readTimeoutMsOverride
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/plain, application/json;q=0.9, */*;q=0.5")
            if (!ifNoneMatch.isNullOrBlank()) {
                connection.setRequestProperty("If-None-Match", ifNoneMatch)
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@runCatching LyricHttpResponse(status, body = null, etag = connection.etag())
            }
            if (status != HttpURLConnection.HTTP_OK) {
                onFailure?.invoke(if (status == HttpURLConnection.HTTP_NOT_FOUND) LyricFetchFailure.NOT_FOUND else LyricFetchFailure.REQUEST)
                return@runCatching null
            }
            val bytes = readBounded(connection) ?: run {
                onFailure?.invoke(LyricFetchFailure.INVALID)
                return@runCatching null
            }
            LyricHttpResponse(status, bytes, connection.etag())
        } finally {
            connection.disconnect()
        }
    }.onFailure { onFailure?.invoke(LyricFetchFailure.REQUEST) }.getOrNull()

    private fun HttpURLConnection.etag(): String? = getHeaderField("ETag")?.trim()

    private fun readBounded(connection: HttpURLConnection): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val chunk = ByteArray(8192)
            while (buffer.size() < maxResponseBytes) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
            }
        }
        if (buffer.size() >= maxResponseBytes) return null
        return buffer.toByteArray()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000
        const val DEFAULT_MAX_RESPONSE_BYTES = 1 shl 20
        private const val USER_AGENT = "LyricWave/1.0"
    }
}
