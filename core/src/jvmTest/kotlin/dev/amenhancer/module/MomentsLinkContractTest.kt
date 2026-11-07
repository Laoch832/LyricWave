package dev.amenhancer.module

import org.junit.Assert.*
import org.junit.Test

class MomentsLinkContractTest {
    @Test fun `retains Apple URL path query and country without allowing lookalike hosts`() {
        val url = "https://music.apple.com/cn/album/a/123?i=456"
        assertEquals(url, MomentsLinkContract.extract("Listen to this $url"))
        for (bad in listOf("https://music.apple.com.evil.test/song", "https://music.apple.com@evil.test/song", "javascript:alert(1)", "http://music.apple.com/song", "https://music.apple.com:999/song")) {
            assertNull(MomentsLinkContract.validate(bad))
        }
        assertNull(MomentsLinkContract.validate("https://music.apple.com/" + "x".repeat(4096)))
    }
}
