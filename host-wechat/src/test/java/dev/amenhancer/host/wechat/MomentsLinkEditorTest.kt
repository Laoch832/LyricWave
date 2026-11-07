package dev.amenhancer.host.wechat

import dev.amenhancer.module.MomentsLinkContract as Contract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MomentsLinkEditorTest {
    @Test fun `native editor is a clickable link card with original Apple URL`() {
        val url = "https://music.apple.com/cn/album/title/1447401620?i=1447401621"
        val intent = MomentsLinkEditor.intent(url, "Song - Artist")
        assertEquals(Contract.WECHAT_PACKAGE, intent.component?.packageName)
        assertEquals("com.tencent.mm.plugin.sns.ui.SnsUploadUI", intent.component?.className)
        assertEquals(1, intent.getIntExtra("Ksnsupload_type", -1))
        assertEquals(url, intent.getStringExtra("Ksnsupload_link"))
        assertEquals("Song - Artist", intent.getStringExtra("Ksnsupload_title"))
        assertFalse(intent.hasExtra(android.content.Intent.EXTRA_STREAM))
        assertEquals("", intent.getStringExtra("Kdescription"))
    }
    @Test fun `rejects external URLs instead of creating an image fallback`() {
        assertThrows(IllegalArgumentException::class.java) { MomentsLinkEditor.intent("https://example.com/song", "Song") }
    }
}
