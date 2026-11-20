package dev.amenhancer.module.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** Keeps incomplete media invisible and deletes it if publishing fails. */
internal object MediaStorePublisher {
    fun publish(context: Context, input: File, artist: String, title: String): String {
        require(Build.VERSION.SDK_INT >= 29) { "android_storage_unsupported" }
        require(input.isFile && input.length() > 0) { "empty_output" }
        val clean = Regex("[\\p{Cntrl}/\\\\:*?\"<>|]").let { invalid ->
            (artist.take(72) + " - " + title.take(96)).replace(invalid, "_").trim().ifBlank { "Apple Music" }
        }
        val name = clean + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/LyricWave/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("media_store_insert_failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                input.inputStream().use { source -> source.copyTo(output, 1024 * 1024) }
            } ?: error("media_store_open_failed")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return name
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    fun publishVideo(context: Context, input: File, artist: String, title: String): String {
        require(Build.VERSION.SDK_INT >= 29) { "android_storage_unsupported" }
        require(input.isFile && input.length() > 0) { "empty_video_output" }
        val invalid = Regex("[\\p{Cntrl}/\\\\:*?\"<>|]")
        val name = (artist.take(72) + " - " + title.take(96))
            .replace(invalid, "_").trim().ifBlank { "Apple Music MV" } + ".mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LyricWave/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("video_store_insert_failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                input.inputStream().use { source -> source.copyTo(output, 1024 * 1024) }
            } ?: error("video_store_open_failed")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return name
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
