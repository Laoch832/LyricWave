package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.TtmlInputPolicy

/** Process-local handoff. A track must match before its document can be read. */
object IslandLyricsSourceState {
    private data class Document(val songId: Long, val ttml: String)
    @Volatile private var native: Document? = null
    @Volatile private var selected: Document? = null

    fun observeNative(songId: Long, ttml: String) {
        native = document(songId, ttml)
    }

    fun observeSelected(songId: Long, ttml: String) {
        selected = document(songId, ttml)
    }

    fun read(songId: Long): String? = selected?.takeIf { it.songId == songId }?.ttml
        ?: native?.takeIf { it.songId == songId }?.ttml

    fun resetFor(songId: Long?) {
        if (native?.songId != songId) native = null
        if (selected?.songId != songId) selected = null
    }

    private fun document(songId: Long, ttml: String) =
        Document(songId, ttml).takeIf { songId > 0 && ttml.length <= TtmlInputPolicy.MAX_TTML_BYTES }
}
