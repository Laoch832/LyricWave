package dev.amenhancer.module.hook

import android.os.Looper

/** Candidate reader only; no hooks install this and no source/DRM preparation is performed. */
internal data class NativeNextItemSnapshot(val currentQueueId: Long, val nextQueueId: Long, val nextCatalogId: Long)

/** Exact 1614 read contract. Never infers the next item from index + 1 or modifies the queue. */
internal class NativeNextItemReader1614(loader: ClassLoader) {
    private val owner = loader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")
    private val item = loader.loadClass("com.apple.android.music.playback.model.PlayerQueueItem")
    private val media = loader.loadClass("com.apple.android.music.playback.model.PlayerMediaItem")
    private val engine = loader.loadClass("com.google.android.exoplayer2.ExoPlayer")
    private val player = owner.getDeclaredField("player").apply { check(engine.isAssignableFrom(type)); isAccessible = true }
    private val looper = engine.getMethod("getApplicationLooper").also { check(it.returnType == Looper::class.java) }
    private val nextIndex = owner.getMethod("getNextItemIndex").also { check(it.returnType == Int::class.javaPrimitiveType) }
    private val atIndex = owner.getMethod("getQueueItemAtIndex", Int::class.javaPrimitiveType).also { check(it.returnType == item) }
    private val current = owner.getMethod("getCurrentItem").also { check(it.returnType == item) }
    private val queueId = item.getMethod("getPlaybackQueueId").also { check(it.returnType == Long::class.javaPrimitiveType) }
    private val payload = item.getMethod("getItem").also { check(it.returnType == media) }
    private val catalogId = media.getMethod("getSubscriptionStoreId").also { check(it.returnType == String::class.java) }

    fun read(host: Any, versionName: String, versionCode: Long, verifiedCurrentCatalogId: Long): NativeNextItemSnapshot? {
        if (versionName != "7.0.0" || versionCode != 1614L || !owner.isInstance(host)) return null
        return runCatching {
            val nativePlayer = player.get(host) ?: return null
            if (looper.invoke(nativePlayer) !== Looper.myLooper()) return null
            fun id(queueItem: Any): Long? = payload.invoke(queueItem)?.let {
                (catalogId.invoke(it) as? String)?.toLongOrNull()?.takeIf { value -> value > 0 }
            }
            val now = current.invoke(host) ?: return null
            if (id(now) != verifiedCurrentCatalogId) return null
            val index = nextIndex.invoke(host) as Int
            if (index < 0) return null
            val next = atIndex.invoke(host, index) ?: return null
            val currentKey = queueId.invoke(now) as Long
            val nextKey = queueId.invoke(next) as Long
            if (currentKey == nextKey || currentKey <= 0 || nextKey <= 0) return null
            NativeNextItemSnapshot(currentKey, nextKey, id(next) ?: return null)
        }.getOrNull()
    }
}
