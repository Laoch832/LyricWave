package dev.amenhancer.module.share

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.MomentsSongMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class ShareDraftStoreTest {
    private val url = "https://music.apple.com/tr/album/%E6%AD%8C/111?i=123&ls=1"
    private val cover = "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg"
    private fun draft(now: Long) = ShareDraft(url, "My title", null, cover, true, now)
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); if (System.nanoTime() > end) fail("Draft UI timeout"); Thread.sleep(10) }
    }
    @Test fun `a recreated store restores private content and preserves a different song draft`() {
        val app = RuntimeEnvironment.getApplication(); val now = System.currentTimeMillis()
        ShareDraftStore(app) { now }.save(draft(now))
        val recreated = ShareDraftStore(app) { now + 1000 }
        assertNull(recreated.load("https://music.apple.com/cn/song/456"))
        assertEquals(draft(now), recreated.load(url)); recreated.clear(); assertNull(recreated.load(url))
    }
    @Test fun `failed writes retain previous content and bad or expired storage is discarded`() {
        val app = RuntimeEnvironment.getApplication(); val now = System.currentTimeMillis(); val store = ShareDraftStore(app) { now }
        store.save(draft(now))
        assertThrows(IllegalArgumentException::class.java) { store.save(draft(now).copy(url = "https://evil.example")) }
        File(app.filesDir, "lyricwave-share-draft-v1.json.new").writeText("partial write")
        assertEquals(draft(now), store.load(url))
        assertNull(ShareDraftStore(app) { now + ShareDraftCodec.RETENTION_MS }.load(url))
        val file = File(app.filesDir, "lyricwave-share-draft-v1.json")
        assertFalse(file.exists()); file.writeText("x".repeat(32769)); assertNull(store.load(url)); assertFalse(file.exists())
    }
    @Test fun `overlapping live stores cannot invalidate each others atomic writes`() {
        val app = RuntimeEnvironment.getApplication(); val now = System.currentTimeMillis()
        val stores = listOf(ShareDraftStore(app) { now }, ShareDraftStore(app) { now })
        stores[0].save(draft(now))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val jobs = stores.mapIndexed { index, store -> pool.submit {
                repeat(25) { turn ->
                    store.save(draft(now).copy(title = "Writer$index-$turn"))
                    assertNotNull(store.load(url))
                }
            } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(stores[0].load(url)!!.title.startsWith("Writer"))
        } finally { pool.shutdownNow() }
    }
    @Test fun `user restores saves and clears a draft without native sending or late metadata overwriting edits`() {
        val app = RuntimeEnvironment.getApplication(); val store = ShareDraftStore(app)
        store.save(draft(System.currentTimeMillis()))
        val gate = CountDownLatch(1)
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, url).putExtra(Contract.TITLE, "Initial title"))
        controller.get().metadataResolver = { gate.await(4, TimeUnit.SECONDS); MomentsSongMetadata("Late title", "Late artist", cover) }
        val activity = controller.setup().visible().get()
        fun button(text: String) = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == text }
        try {
            await { button("Restore saved draft").visibility == View.VISIBLE }
            button("Restore saved draft").performClick()
            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
            assertEquals("My title", fields[0].text.toString()); assertEquals("", fields[1].text.toString())
            assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.Switch>().single().isChecked)
            fields[1].setText("New artist"); gate.countDown()
            repeat(50) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertEquals("My title", fields[0].text.toString()); assertEquals("New artist", fields[1].text.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
            button("Save draft").performClick(); await { store.load(url)?.artist == "New artist" }
            button("Clear saved draft").performClick(); await { store.load(url) == null && button("Restore saved draft").visibility == View.GONE }
            assertEquals("My title", fields[0].text.toString()); assertNull(shadowOf(activity).nextStartedActivity)
        } finally { gate.countDown(); controller.pause().stop().destroy() }
    }
}
