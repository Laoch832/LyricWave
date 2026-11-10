package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.playback.NextAssetPreloadState as State
import dev.amenhancer.module.playback.NextAssetPreloadStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.lang.reflect.Method
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppleMusicNextAssetPreloadTest {
    class Item(private val id: String) { fun getPlaybackStoreId() = id }
    class QueueItem(val id: Long, val payload: Item) { fun getPlaybackQueueId() = id; fun getItem() = payload }
    class Queue {
        val items = listOf(QueueItem(1, Item("11")), QueueItem(2, Item("22")), QueueItem(3, Item("33")))
        var current = 0; var next = 2
        fun getCurrentIndex() = current
        fun nextItemIndexForIndex(index: Int) = next
        fun getItemCount() = items.size
        fun getItemAtIndex(index: Int) = items[index]
    }
    class Engine {
        var position = 85_000L; var playing = true
        fun getApplicationLooper(): Looper = Looper.getMainLooper()
        fun getPlaybackState() = 3
        fun getPlayWhenReady() = playing
        fun getDuration() = 100_000L
        fun getCurrentPosition() = position
    }
    class Player(val queue: Queue) {
        @JvmField val player = Engine()
        fun getCurrentItem() = queue.items[queue.current]
        fun getNextItemIndex() = queue.next
        fun getQueueItemAtIndex(index: Int) = queue.items[index]
    }
    class Asset
    interface Transfer
    interface Callback {
        fun onAssetRequestComplete(item: Item, asset: Asset)
        fun onAssetRequestError(item: Item, error: IOException)
        fun onAssetRequestCancelled(item: Item)
    }
    class Context { var allowed = true; fun canMakeNetworkRequestWithCurrentConditions() = allowed }
    class Manager {
        @JvmField val playerContext = Context()
        @JvmField val callbacksByMediaItem = mutableMapOf<String, MutableList<Callback>>()
        var fetches = 0; var cancelled = 0; var denied = false
        fun requestAsset(item: Item, callback: Callback, queueId: Long, cache: Boolean) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            if (!callbacksByMediaItem.containsKey(keyForMediaItem(item))) fetches++
            callbacksByMediaItem.getOrPut(keyForMediaItem(item)) { mutableListOf() }.add(callback)
        }
        fun cancelAssetRequest(item: Item) { cancelled++; callbacksByMediaItem.remove(keyForMediaItem(item))?.forEach { it.onAssetRequestCancelled(item) } }
        fun shouldAssetRequestCompletionContinue(item: Item, asset: Asset): IOException? = if (denied) IOException() else null
        fun finish(item: Item, asset: Asset) { callbacksByMediaItem.remove(keyForMediaItem(item))?.toList()?.forEach { it.onAssetRequestComplete(item, asset) } }
        companion object { @JvmStatic fun keyForMediaItem(item: Item) = item.getPlaybackStoreId() }
    }
    class Source {
        @JvmField val playbackHandler = Handler(Looper.getMainLooper())
        @JvmField val playbackQueueManager = Queue()
        @JvmField val assetRequestManager = Manager()
        @JvmField val sourcePrepareListener = Player(playbackQueueManager)
        fun prepareSourceInternal(listener: Transfer?) {}
        fun releaseSourceInternal() {}
    }
    private val profile = JSONObject(javaClass.getResourceAsStream("/host-profiles/7.0.0-1614.json")!!.bufferedReader().readText()).getJSONObject("nextAssetPreload")
    private val types = mapOf("source" to Source::class.java, "manager" to Manager::class.java, "queue" to Queue::class.java,
        "queueItem" to QueueItem::class.java, "item" to Item::class.java, "callback" to Callback::class.java,
        "context" to Context::class.java, "asset" to Asset::class.java, "playerOwner" to Player::class.java, "engine" to Engine::class.java, "transfer" to Transfer::class.java)
    private val loader = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = types.entries.firstOrNull { profile.getJSONObject("types").getString(it.key) == name }?.value ?: super.loadClass(name)
    }
    private val hooks = mutableMapOf<Method, ModernMethodHook>()
    private var enabled = false
    private lateinit var source: Source
    private fun install(version: Long = 1614): TargetCapabilityInstall = AppleMusicNextAssetPreloadTarget(
        TargetBuild("com.apple.android.music", "7.0.0", version), profile, loader, { enabled },
        register = { method, hook, _ -> hooks[method] = hook; true },
    ).install()
    private fun call(receiver: Any, name: String, vararg args: Any?) {
        val method = receiver.javaClass.methods.single { it.name == name }
        val param = ModernMethodHook.MethodHookParam::class.java.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(method, receiver, args) as ModernMethodHook.MethodHookParam
        hooks[method]?.beforeHookedMethod(param)
        if (!param.javaClass.getDeclaredField("returnEarly").apply { isAccessible = true }.getBoolean(param)) method.invoke(receiver, *args)
        hooks[method]?.afterHookedMethod(param)
    }
    private fun tick() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100)) }
    @Before fun setup() { source = Source(); enabled = false; hooks.clear() }
    @Test fun `live opt in requests shuffled next on native looper and avoids duplicate fetch on handover`() {
        assertTrue(install() is TargetCapabilityInstall.Active)
        call(source, "prepareSourceInternal", null); tick(); assertEquals(0, source.assetRequestManager.fetches)
        enabled = true; tick(); assertEquals(1, source.assetRequestManager.fetches)
        val next = source.playbackQueueManager.items[2].payload
        val asset = Asset(); source.assetRequestManager.finish(next, asset); assertEquals(State.READY, NextAssetPreloadStatus.state)
        var delivered: Asset? = null
        call(source.assetRequestManager, "requestAsset", next, object : Callback {
            override fun onAssetRequestComplete(item: Item, asset: Asset) { delivered = asset }
            override fun onAssetRequestError(item: Item, error: IOException) { fail() }
            override fun onAssetRequestCancelled(item: Item) { fail() }
        }, 3L, false)
        assertSame(asset, delivered); assertEquals(1, source.assetRequestManager.fetches)
        tick(); assertEquals(1, source.assetRequestManager.fetches)
        assertEquals(0, source.playbackQueueManager.current); assertTrue(source.sourcePrepareListener.player.playing)
        call(source, "releaseSourceInternal"); tick(); assertEquals(1, source.assetRequestManager.fetches)
    }
    @Test fun `disable detaches only module callback and preserves an existing native consumer`() {
        enabled = true; assertTrue(install() is TargetCapabilityInstall.Active)
        call(source, "prepareSourceInternal", null); tick()
        val next = source.playbackQueueManager.items[2].payload
        var nativeDelivered = false
        // A legitimate native request can already be coalesced by the manager, without passing our hook.
        source.assetRequestManager.requestAsset(next, object : Callback {
            override fun onAssetRequestComplete(item: Item, asset: Asset) { nativeDelivered = true }
            override fun onAssetRequestError(item: Item, error: IOException) {}
            override fun onAssetRequestCancelled(item: Item) { fail("must not cancel native playback") }
        }, 3L, false)
        enabled = false; tick(); assertEquals(0, source.assetRequestManager.cancelled)
        assertEquals(1, source.assetRequestManager.callbacksByMediaItem.values.single().size)
        source.assetRequestManager.finish(next, Asset()); assertTrue(nativeDelivered)
        call(source, "releaseSourceInternal")
    }
    @Test fun `queue change cancels unused request and release stops polling`() {
        enabled = true; install(); call(source, "prepareSourceInternal", null); tick()
        source.playbackQueueManager.next = 1; tick(); assertEquals(1, source.assetRequestManager.cancelled)
        assertEquals(2, source.assetRequestManager.fetches)
        call(source, "releaseSourceInternal"); assertEquals(2, source.assetRequestManager.cancelled)
        tick(); assertEquals(2, source.assetRequestManager.fetches)
    }
    @Test fun `native network restrictions prevent warmup and cancel only its unused request`() {
        enabled = true; install(); source.assetRequestManager.playerContext.allowed = false
        call(source, "prepareSourceInternal", null); tick(); assertEquals(0, source.assetRequestManager.fetches)
        source.assetRequestManager.playerContext.allowed = true; tick(); assertEquals(1, source.assetRequestManager.fetches)
        source.assetRequestManager.playerContext.allowed = false; tick(); assertEquals(1, source.assetRequestManager.cancelled)
        call(source, "releaseSourceInternal")
    }
    @Test fun `native lookahead request prevents a later redundant prefetch`() {
        enabled = true; install(); source.sourcePrepareListener.player.position = 0
        call(source, "prepareSourceInternal", null)
        val next = source.playbackQueueManager.items[2].payload
        call(source.assetRequestManager, "requestAsset", next, object : Callback {
            override fun onAssetRequestComplete(item: Item, asset: Asset) {}
            override fun onAssetRequestError(item: Item, error: IOException) {}
            override fun onAssetRequestCancelled(item: Item) {}
        }, 3L, false)
        source.assetRequestManager.finish(next, Asset())
        source.sourcePrepareListener.player.position = 50_000
        tick(); assertEquals(1, source.assetRequestManager.fetches)
        call(source, "releaseSourceInternal")
    }
    @Test fun `unsupported exact version installs no hooks`() {
        assertTrue(install(1615) is TargetCapabilityInstall.Unsupported); assertTrue(hooks.isEmpty())
    }
}
