package dev.amenhancer.module.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.amenhancer.module.MomentsSongMetadata
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Read-only preview of the validated artwork passed to the native share flow. */
internal object ShareCoverPreview {
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun load(value: String): Bitmap? {
        val url = MomentsSongMetadata.artwork(value) ?: return null
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200 || connection.contentLength > MAX_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_BYTES && !Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (Thread.currentThread().isInterrupted || bytes.size > MAX_BYTES) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val edge = maxOf(options.outWidth, options.outHeight)
            if (options.outWidth <= 0 || options.outHeight <= 0 || edge > 8192) return null
            options.inJustDecodeBounds = false
            options.inSampleSize = Integer.highestOneBit(((edge.toLong() + 511) / 512).toInt() * 2 - 1)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }
}
