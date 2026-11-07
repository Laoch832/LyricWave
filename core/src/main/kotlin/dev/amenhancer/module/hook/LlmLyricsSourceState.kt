package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.TtmlInputPolicy
import java.util.concurrent.CopyOnWriteArrayList

data class LlmLyricsSource(val appleMusicId: Long, val ttml: String, val title: String?, val artist: String?)

/** One verified current source, shared with explicit settings actions; no disk I/O in the hook. */
object LlmLyricsSourceState {
    @Volatile var current: LlmLyricsSource? = null
        private set
    fun observe(source: LlmLyricsSource) {
        if (source.appleMusicId > 0 && source.ttml.length <= TtmlInputPolicy.MAX_TTML_BYTES) current = source
    }
    fun clear(appleMusicId: Long?) { if (current?.appleMusicId != appleMusicId) current = null }
}

/** Use exactly the selected document; an already translated manual source never falls through to native. */
fun selectedLlmLyricsSource(manual: String?, automatic: String?, native: String?, nativeReady: Boolean): String? =
    manual ?: automatic ?: native.takeIf { nativeReady }

object LlmLyricsCacheUpdates {
    private val listeners = CopyOnWriteArrayList<(Long, Boolean) -> Unit>()
    fun subscribe(listener: (Long, Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
    fun publish(appleMusicId: Long, refresh: Boolean = true) {
        listeners.forEach { listener -> runCatching { listener(appleMusicId, refresh) } }
    }
}
