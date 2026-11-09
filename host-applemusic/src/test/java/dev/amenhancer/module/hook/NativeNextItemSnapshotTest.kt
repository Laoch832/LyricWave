package dev.amenhancer.module.hook

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NativeNextItemSnapshotTest {
    class Engine { fun getApplicationLooper(): Looper = Looper.getMainLooper() }
    class Media(private val id: String) { fun getSubscriptionStoreId() = id }
    class Item(private val key: Long, private val id: String) {
        fun getPlaybackQueueId() = key
        fun getItem() = Media(id)
    }
    class Player {
        @JvmField val player = Engine()
        var next = 2
        var reads = 0
        val items = listOf(Item(10, "100"), Item(11, "200"), Item(12, "300"))
        fun getCurrentItem() = items[0]
        fun getNextItemIndex(): Int { reads++; return next }
        fun getQueueItemAtIndex(index: Int) = items[index]
    }
    private fun reader(): NativeNextItemReader1614 {
        val classes = mapOf("com.apple.android.music.playback.player.ExoMediaPlayer" to Player::class.java,
            "com.apple.android.music.playback.model.PlayerQueueItem" to Item::class.java,
            "com.apple.android.music.playback.model.PlayerMediaItem" to Media::class.java,
            "com.google.android.exoplayer2.ExoPlayer" to Engine::class.java)
        return NativeNextItemReader1614(object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = classes[name] ?: super.loadClass(name)
        })
    }
    @Test fun `uses native shuffled next index and never assumes adjacent playlist order`() {
        val host = Player()
        assertEquals(NativeNextItemSnapshot(10, 12, 300), reader().read(host, "7.0.0", 1614, 100))
        host.next = 0
        assertNull(reader().read(host, "7.0.0", 1614, 100))
        host.next = -1
        assertNull(reader().read(host, "7.0.0", 1614, 100))
    }
    @Test fun `unverified versions and leading metadata do not traverse the queue`() {
        val host = Player(); val reader = reader()
        assertNull(reader.read(host, "7.0.0", 9999, 100))
        assertNull(reader.read(host, "7.0.0", 1614, 999))
        assertEquals(0, host.reads)
    }
    @Test fun `a caller outside the player looper cannot read the queue`() {
        val host = Player(); val reader = reader()
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread = Thread { try { assertNull(reader.read(host, "7.0.0", 1614, 100)) } catch (failure: Throwable) { error.set(failure) } }
        thread.start(); thread.join(5000)
        assertFalse(thread.isAlive); assertNull(error.get()); assertEquals(0, host.reads)
    }
}
