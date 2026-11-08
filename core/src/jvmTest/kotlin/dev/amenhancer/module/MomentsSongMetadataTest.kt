package dev.amenhancer.module

import org.junit.Assert.*
import org.junit.Test

class MomentsSongMetadataTest {
    @Test fun `album links use the selected track id and never the album slug`() {
        assertEquals(456L, MomentsSongMetadata.songId("https://music.apple.com/cn/album/album-name/123?i=456"))
        assertEquals(456L, MomentsSongMetadata.songId("https://music.apple.com/us/song/song-name/456"))
        assertNull(MomentsSongMetadata.songId("https://music.apple.com/cn/album/album-name/123"))
        assertNull(MomentsSongMetadata.songId("https://music.apple.com/cn/playlist/name/123?i=456"))
        assertNull(MomentsSongMetadata.songId("https://evil.test/cn/song/456"))
        assertNull(MomentsSongMetadata.songId("https://music.apple.com/cn/album/name/123?i=456&i=789"))
    }
    @Test fun `current metadata must belong to the shared track`() {
        val url = "https://music.apple.com/cn/album/album-name/123?i=456"
        assertNull(MomentsSongMetadata.fromCurrent(url, CurrentSongDetails(789, "Wrong song", "Wrong artist")))
        assertEquals(MomentsSongMetadata("Despacito", "Luis Fonsi"), MomentsSongMetadata.fromCurrent(url,
            CurrentSongDetails(456, "Despacito", "Luis Fonsi")))
        assertNull(MomentsSongMetadata.title("Apple Music"))
        assertNull(MomentsSongMetadata.title("  "))
    }
}
