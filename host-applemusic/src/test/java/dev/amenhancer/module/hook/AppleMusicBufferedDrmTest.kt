package dev.amenhancer.module.hook

import android.os.Looper
import dev.amenhancer.module.playback.BufferedDrmRecoveryStatus
import dev.amenhancer.module.playback.DrmRoundTripRecovery
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppleMusicBufferedDrmTest {
    interface Sink {
        fun configure(encoding: Int, channels: Int, rate: Int, size: Int, map: IntArray?, delay: Int, padding: Int)
        fun disableTunneling(); fun enableTunnelingV21(session: Int); fun flush()
        fun getCurrentPositionUs(ended: Boolean): Long; fun getPlaybackParameters(): Any
        fun handleBuffer(buffer: ByteBuffer, pts: Long): Boolean; fun handleDiscontinuity()
        fun hasPendingData(): Boolean; fun isEnded(): Boolean; fun pause(); fun play(); fun playToEndOfStream(); fun reset()
        fun setAudioAttributes(value: Any); fun setAudioSessionId(id: Int); fun setAuxEffectInfo(value: Any)
        fun setListener(value: Any); fun setPlaybackParameters(value: Any); fun setVolume(volume: Float)
        fun supportsOutput(channels: Int, encoding: Int): Boolean
    }
    class Physical : Sink {
        @JvmField var inputBuffer: ByteBuffer? = null
        val positions = mutableListOf<Boolean>()
        val volumes = mutableListOf<Float>()
        var flushes = 0
        var failWrites = false
        val bytes = mutableListOf<Byte>(); var blocked = true; var resets = 0; var pauses = 0; var configs = 0
        override fun configure(encoding: Int, channels: Int, rate: Int, size: Int, map: IntArray?, delay: Int, padding: Int) { configs++ }
        override fun disableTunneling() {} ; override fun enableTunnelingV21(session: Int) {}; override fun flush() { flushes++; inputBuffer = null }
        override fun getCurrentPositionUs(ended: Boolean): Long { positions += ended; return 1_000_000L }
        override fun getPlaybackParameters() = Any()
        override fun handleBuffer(buffer: ByteBuffer, pts: Long): Boolean {
            if (failWrites) throw IllegalStateException("simulated native write")
            inputBuffer?.let { assertSame("Native pending buffer changed", it, buffer) }
            if(blocked) { inputBuffer = buffer; return false }
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            while(buffer.hasRemaining()) bytes += buffer.get()
            inputBuffer = null
            return true
        }
        override fun handleDiscontinuity() {}; override fun hasPendingData() = false; override fun isEnded() = true
        override fun pause() { pauses++ }; override fun play() {}; override fun playToEndOfStream() {}; override fun reset() { resets++; inputBuffer = null }
        override fun setAudioAttributes(value: Any) {}; override fun setAudioSessionId(id: Int) {}; override fun setAuxEffectInfo(value: Any) {}
        override fun setListener(value: Any) {}; override fun setPlaybackParameters(value: Any) {}; override fun setVolume(volume: Float) { volumes += volume }
        override fun supportsOutput(channels: Int, encoding: Int) = true
    }
    class Format {
        @JvmField var encoderDelay = 0
        @JvmField var encoderPadding = 0
        @JvmField var sampleMimeType = "audio/pcm"
    }
    class Asset(var id: Long = 81) { fun getStoreId() = id * 100; fun getPeriodId() = 0L }
    class Stream(val info: Asset = Asset()) { fun getAssetInfo() = info }
    interface Caps { fun getTrackType(): Int; fun supportsFormat(format: Format): Int }
    class Group(private val format: Format = Format()) { @JvmField val length = 1; fun getFormat(index: Int) = format }
    class Selector { fun findRenderer(caps: Array<Caps>, group: Group, counts: IntArray, preferEmpty: Boolean) = 1 }
    open class Base : Caps {
        @JvmField var streamOffsetUs = 1_000_000L
        val sample = Stream()
        fun getStream() = sample
        fun getState() = 2
        fun getIndex() = 0
        override fun getTrackType() = 1
        override fun supportsFormat(format: Format) = 4
    }
    class Renderer : Base() {
        val physical = Physical()
        @JvmField var audioSink: Sink = physical
        @JvmField var inputFormat = Format()
        @JvmField var decoderOutputSampleRate = 8000
        @JvmField var decoderOutputNumOfChannels = 1
        @JvmField var decoderOutputBitsPerChannel = 16
        @JvmField var outputBufferTs = 1_000_000L
        @JvmField var isSoundCheckEnabled = true
        @JvmField var soundCheckGain = 0.5f
        var master = 1f
        fun initializeAudioSink(format: Format) {
            audioSink.configure(2, 1, 8000, 0, null, 0, 0)
            soundCheckGain = if (sample.info.id == 17L) 0.1f else 0.5f
            audioSink.setVolume(master * soundCheckGain)
        }
        fun render(position: Long, elapsed: Long) {}
    }
    class DrmException(val component: Int = 0) : RuntimeException() { fun getComponentErrorCode() = component }
    class Fault(cause: Throwable = DrmException()) : Exception(cause) {
        companion object { @JvmStatic fun createForUnexpected(cause: RuntimeException) = Fault(cause) }
    }
    class FadeControl { var enabled = false; fun isCrossFadeEnabled() = enabled }
    class Metadata : Base() { override fun getTrackType() = 5 }
    class Internal(renderer: Renderer) {
        @JvmField val audioFadeControl = FadeControl()
        @JvmField var enabledRenderers: Array<Base> = arrayOf(renderer)
        @JvmField var playWhenReady = true
        fun getExoPlaybackExceptionMessage(error: Fault) = "native"
        fun stopInternal(reset: Boolean, resetPosition: Boolean, acknowledge: Boolean) {}
    }
    class Engine(renderer: Renderer) {
        @JvmField val internalPlayer = Internal(renderer)
        @JvmField val renderers: Array<Base> = arrayOf(renderer)
        var wants = true; @JvmField var playbackState = 3; var error: Fault? = null
        fun getApplicationLooper(): Looper = Looper.getMainLooper()
        val seeks = mutableListOf<Pair<Int, Long>>()
        fun seekTo(index: Int, position: Long) { seeks += index to position }
        fun getPlaybackState() = playbackState
        fun getPlaybackError() = error
        fun getPlayWhenReady() = wants
        fun setPlayWhenReady(value: Boolean) { wants = value }
        fun setPlayWhenReady(value: Boolean, reason: Int) { wants = value }
        fun release() {}
    }
    class Media(var store: Long) { var offline = false; fun isDownloadedAsset() = offline; fun getPlaybackStoreId() = store.toString(); fun getType() = 1 }
    class Item(val id: Long) { val media = Media(id * 100); fun getPlaybackQueueId() = id; fun getItem() = media }
    class Queue { val uuid = UUID.randomUUID(); fun getQueueUUID(): UUID = uuid }
    class Host(val renderer: Renderer = Renderer()) {
        @JvmField val player = Engine(renderer)
        @JvmField val trackSelector = Selector()
        var chooseRenderer: (() -> Int)? = null
        val selectedRenderers = mutableListOf<Int>()
        @JvmField var playWhenReady = true
        @JvmField var playWhenQueuePrepared = false
        val items = listOf(Item(81), Item(99), Item(17)); val queueOwner = Queue()
        var selected = 81L; var evictions = 0; var recoveredFeeds = 0; var noRefill = false; var playCalls = 0
        val requests = mutableListOf<Long>(); val seeks = mutableListOf<Long>()
        fun onPlayerError(error: Fault) { player.error = error; player.playbackState = 1 }
        fun getCurrentItem() = items.first { it.id == selected }
        fun getPlaybackQueueManager() = queueOwner
        fun getNextItemIndex() = 2 // Native shuffle: next is 17, not item 99.
        fun getQueueItemAtIndex(index: Int) = items[index]
        fun getQueueItemIndex(id: Long) = items.indexOfFirst { it.id == id }
        fun setPlaybackIndexFromQueueId(id: Long): Boolean {
            requests += id; renderer.audioSink.pause(); renderer.audioSink.flush(); renderer.audioSink.reset()
            selected = id; renderer.sample.info.id = id
            renderer.streamOffsetUs = if(id == 17L) 8_000_000 else 2_000_000
            return true
        }
        fun play() {
            chooseRenderer?.let { selectedRenderers += it() }
            playCalls++; player.error = null; player.playbackState = 3
            playWhenReady = true; renderer.initializeAudioSink(renderer.inputFormat); renderer.audioSink.play()
            if (selected == 17L) assertFalse(renderer.audioSink.handleBuffer(pcm(9), renderer.streamOffsetUs))
            else if (!noRefill) {
                repeat(4) { assertTrue(renderer.audioSink.handleBuffer(pcm(99), 2_000_000L + it * 1_000_000)) }
                assertTrue(renderer.audioSink.handleBuffer(pcm(5), 6_000_000)); recoveredFeeds++
            }
        }
        fun setVolume(value: Float) { renderer.master = value; renderer.audioSink.setVolume(value * renderer.soundCheckGain) }
        fun pause() { playWhenReady = false; renderer.audioSink.pause() }
        fun stop() { playWhenReady = false; renderer.audioSink.reset() }
        fun release() { renderer.audioSink.reset() }
        fun seekToPosition(position: Long) { seeks += position }
        fun setPlaybackIndex(index: Int) {}; fun changePlaybackIndexBy(delta: Int) {}
        fun removeCache(media: Media) { assertFalse(media.offline); evictions++ }
        fun resetQueue() {}; fun removeQueueItemWithId(id: Long) {}; fun moveQueueItemWithId(id: Long, after: Long, index: Int) {}
        fun addQueueItems(provider: Any, index: Int) {}; fun prepare(provider: Any) {}; fun prepare(provider: Any, start: Boolean) {}
        fun prepare(provider: Any, index: Int, start: Boolean, reason: Int) {}
    }
    private data class Hook(val callback: ModernMethodHook, val scope: HookRegistrationScope)
    private val hooks = mutableMapOf<Method, Hook>()
    private var enabled = true
    private lateinit var host: Host
    private fun profile(): JSONObject {
        val original = JSONObject(javaClass.getResourceAsStream("/host-profiles/7.0.0-1614.json")!!.bufferedReader().readText()).getJSONObject("bufferedDrmRecovery")
        val names = mapOf("SVAudioRendererV2" to Renderer::class.java, "BaseRenderer" to Base::class.java,
            "RendererCapabilities" to Caps::class.java, "TrackGroup" to Group::class.java, "PlayerTrackSelector" to Selector::class.java,
            "AudioFadeControl" to FadeControl::class.java, "Format" to Format::class.java, "DefaultAudioSink" to Physical::class.java, "AudioSink" to Sink::class.java, "ExoMediaPlayer" to Host::class.java,
            "ExoPlayer" to Engine::class.java, "ExoPlayerImpl" to Engine::class.java, "ExoPlayerImplInternal" to Internal::class.java,
            "ExoPlaybackException" to Fault::class.java, "Renderer" to Base::class.java,
            "SampleStream" to Stream::class.java, "ProtectedSampleStream" to Stream::class.java,
            "MediaAssetInfo" to Asset::class.java, "PlayerQueueItem" to Item::class.java, "PlayerMediaItem" to Media::class.java,
            "PlaybackQueueManager" to Queue::class.java)
        fun replace(value: String): String {
            if (!value.startsWith("com.") && !value.startsWith("[Lcom.")) return value
            val simple = value.substringAfterLast('.').removeSuffix(";")
            val type = names[simple] ?: Any::class.java
            return if (value.startsWith("[L")) "[L${type.name};" else type.name
        }
        fun walk(value: Any): Any = when(value) {
            is JSONObject -> JSONObject().also { out -> value.keys().forEach { key -> out.put(key, walk(value.get(key))) } }
            is JSONArray -> JSONArray().also { out -> repeat(value.length()) { out.put(walk(value.get(it))) } }
            is String -> replace(value)
            else -> value
        }
        return walk(original) as JSONObject
    }
    private fun invoke(receiver: Any, method: Method, args: Array<out Any?>): Any? {
        val h = hooks[method]
        if (h == null || !h.scope.isActive) return try { method.invoke(receiver, *args) } catch (e: InvocationTargetException) { throw e.cause!! }
        val p = ModernMethodHook.MethodHookParam::class.java.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(method, receiver, args) as ModernMethodHook.MethodHookParam
        h.callback.beforeHookedMethod(p)
        val early = p.javaClass.getDeclaredField("returnEarly").apply { isAccessible = true }.getBoolean(p)
        val result = if (early) p.result else method.invoke(receiver, *args)
        if (!early) p.result = result
        h.callback.afterHookedMethod(p); p.throwable?.let { throw it }; return p.result
    }
    private fun call(receiver: Any, name: String, vararg args: Any?): Any? {
        val m = receiver.javaClass.methods.single { it.name == name && it.parameterCount == args.size }
        return invoke(receiver, m, args)
    }
    private fun install(code: Long = 1614, document: JSONObject = profile()): TargetCapabilityInstall = AppleMusicBufferedDrmTarget(
        TargetBuild("com.apple.android.music", "7.0.0", code), document, javaClass.classLoader!!, { enabled },
        invokeNative = { method, target, args -> invoke(target, method, args) },
        register = { method, hook, scope -> hooks[method] = Hook(hook, scope); true },
    ).install()
    private fun tick(ms: Long = 110) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun fill() {
        call(host.renderer, "initializeAudioSink", host.renderer.inputFormat)
        host.renderer.audioSink.play()
        repeat(4) { assertTrue(host.renderer.audioSink.handleBuffer(pcm(it + 1), 1_000_000L + it * 1_000_000)) }
    }
    private fun fault() {
        val e = Fault(); call(host.player.internalPlayer, "stopInternal", true, false, false)
        host.renderer.audioSink.pause(); host.renderer.audioSink.flush(); host.renderer.audioSink.reset()
        call(host, "onPlayerError", e)
    }
    @Before fun setup() { host = Host(); hooks.clear()
        host.chooseRenderer = { call(host.trackSelector, "findRenderer", arrayOf<Caps>(host.renderer, Renderer()), Group(), intArrayOf(0, 0), false) as Int }
        enabled = true; BufferedDrmRecoveryStatus.available = false
        BufferedDrmRecoveryStatus.bufferedMs = 0; BufferedDrmRecoveryStatus.stage = DrmRoundTripRecovery.Stage.IDLE
        BufferedDrmRecoveryStatus.lastResult = null
    }
    @Test fun `real sink proxy preserves forward PCM through native teardown and complete next back refill`() {
        val result = install(); assertTrue(result.toString(), result is TargetCapabilityInstall.Active)
        fill(); fault(); tick(500)
        assertEquals(listOf(17L, 81L), host.requests); assertEquals(listOf(0L), host.seeks)
        assertEquals(listOf(0, 0), host.selectedRenderers)
        assertEquals(listOf(0 to 0L), host.player.seeks)
        assertEquals(DrmRoundTripRecovery.Stage.COMPLETE, BufferedDrmRecoveryStatus.stage)
        assertEquals(0, host.renderer.physical.resets); assertEquals(0, host.renderer.physical.pauses)
        host.renderer.physical.blocked = false; tick()
        assertEquals((1..5).flatMap { v -> List(16000) { v.toByte() } }, host.renderer.physical.bytes)
        assertEquals(0, host.evictions)
    }
    @Test fun `default off keeps the original sink and unsupported exact versions install no hooks`() {
        enabled = false; assertTrue(install() is TargetCapabilityInstall.Active)
        val original = host.renderer.audioSink; call(host.renderer, "initializeAudioSink", host.renderer.inputFormat)
        assertSame(original, host.renderer.audioSink)
        hooks.clear(); assertTrue(install(1613) is TargetCapabilityInstall.Unsupported); assertTrue(hooks.isEmpty())
    }
    @Test fun `missing native contract fails before any hooks are registered`() {
        val broken = profile(); broken.getJSONObject("methods").remove("sinkHandleBuffer")
        assertTrue(install(document = broken) is TargetCapabilityInstall.Degraded); assertTrue(hooks.isEmpty())
    }
    @Test fun `pause cancels pending restoration and no later task can force playback`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); fault(); tick(20)
        assertEquals(listOf(17L), host.requests)
        call(host, "pause"); tick(2000)
        assertFalse(host.playWhenReady); assertEquals(81L, host.selected)
        assertTrue(host.renderer.physical.pauses > 0)
        host.renderer.physical.blocked = false; tick(); assertTrue(host.renderer.physical.bytes.isEmpty())
        assertEquals(listOf(17L, 81L), host.requests)
        assertEquals(DrmRoundTripRecovery.Stage.CANCELLED, BufferedDrmRecoveryStatus.stage)
    }

    @Test fun `live enable wraps an already playing renderer and pumps without waiting for a new track`() {
        enabled = false; assertTrue(install() is TargetCapabilityInstall.Active)
        call(host.renderer, "initializeAudioSink", host.renderer.inputFormat)
        val original = host.renderer.audioSink
        enabled = true; call(host.renderer, "render", 0L, 0L)
        assertNotSame(original, host.renderer.audioSink)
        assertEquals("Live opt-in must not configure the native trimmer again", 1, host.renderer.physical.configs)
        assertTrue(host.renderer.audioSink.handleBuffer(pcm(6), 1_000_000))
        host.renderer.physical.blocked = false; tick()
        assertEquals(List(16000) { 6.toByte() }, host.renderer.physical.bytes)
    }
    @Test fun `second confirmed original DRM failure evicts temporary cache once and stops after Plan B`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); host.noRefill = true; fault(); tick(500)
        call(host, "onPlayerError", Fault()); tick(500)
        assertEquals(listOf(17L, 81L, 17L, 81L), host.requests); assertEquals(1, host.evictions)
        call(host, "onPlayerError", Fault()); tick(1000)
        assertEquals(DrmRoundTripRecovery.Stage.FAILED, BufferedDrmRecoveryStatus.stage)
        tick(10000); assertEquals(4, host.requests.size); assertEquals(1, host.evictions)
    }
    @Test fun `a slow active request never has its streaming files evicted under it`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); host.noRefill = true; fault(); tick(10000)
        assertEquals(listOf(17L, 81L), host.requests); assertEquals(0, host.evictions)
        assertEquals(DrmRoundTripRecovery.Stage.FAILED, BufferedDrmRecoveryStatus.stage)
    }
    @Test fun `offline downloads are never evicted or retried by Plan B`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); host.getCurrentItem().media.offline = true
        host.noRefill = true; fault(); tick(500)
        call(host, "onPlayerError", Fault()); tick(1000)
        assertEquals(listOf(17L, 81L), host.requests); assertEquals(0, host.evictions)
        assertEquals(DrmRoundTripRecovery.Stage.FAILED, BufferedDrmRecoveryStatus.stage)
    }
    @Test fun `releasing the engine cancels queued recovery before any later native requests`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); fault(); tick(20)
        call(host.player, "release"); tick(10000)
        assertEquals(listOf(17L), host.requests)
        assertEquals(DrmRoundTripRecovery.Stage.CANCELLED, BufferedDrmRecoveryStatus.stage)
    }

    @Test fun `authorization revocation immediately stops reserved audio without another play or eviction`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); fault(); tick(20)
        val denial = Fault(DrmException(-42587))
        call(host.player.internalPlayer, "getExoPlaybackExceptionMessage", denial)
        host.renderer.audioSink.reset()
        call(host, "onPlayerError", denial); tick(1000)
        host.renderer.physical.blocked = false; tick()
        assertEquals(81L, host.selected); assertEquals(1, host.playCalls)
        assertEquals(0, host.evictions); assertTrue(host.renderer.physical.bytes.isEmpty())
        assertTrue(host.renderer.physical.resets > 0)
    }

    @Test fun `native proxy exposes a real thirty second forward reserve with bounded backpressure`() {
        assertTrue(install() is TargetCapabilityInstall.Active)
        call(host.renderer, "initializeAudioSink", host.renderer.inputFormat); host.renderer.audioSink.play()
        repeat(30) { assertTrue(host.renderer.audioSink.handleBuffer(pcm(it), 1_000_000L + it * 1_000_000)) }
        val last = pcm(31); assertFalse(host.renderer.audioSink.handleBuffer(last, 31_000_000)); assertEquals(0, last.position())
        tick(300); assertEquals(30_000L, BufferedDrmRecoveryStatus.bufferedMs)
        assertTrue(host.renderer.physical.bytes.isEmpty())
    }
    @Test fun `format transition waits until old accepted PCM has reached the native sink`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill()
        val sink = host.renderer.audioSink
        sink.configure(2, 2, 8000, 0, null, 0, 0)
        val stereo = ByteBuffer.wrap(ByteArray(32000) { 6 })
        assertFalse(sink.handleBuffer(stereo, 5_000_000)); assertEquals(1, host.renderer.physical.configs)
        host.renderer.physical.blocked = false; tick()
        assertEquals(64000, host.renderer.physical.bytes.size); assertEquals(2, host.renderer.physical.configs)
        assertTrue(sink.handleBuffer(stereo, 5_000_000)); tick()
        assertEquals(96000, host.renderer.physical.bytes.size)
    }

    @Test fun `source DRM errors use the fatal stop path without renderer logging`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill()
        call(host.player.internalPlayer, "stopInternal", false, false, false)
        host.renderer.audioSink.pause(); host.renderer.audioSink.reset()
        call(host, "onPlayerError", Fault()); tick(500)
        assertEquals(listOf(17L, 81L), host.requests)
        assertEquals(DrmRoundTripRecovery.Stage.COMPLETE, BufferedDrmRecoveryStatus.stage)
    }
    @Test fun `ordinary user stop never arms a reserve and unrelated source failures never retry`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill()
        call(host.player.internalPlayer, "stopInternal", true, false, true)
        host.renderer.audioSink.reset(); assertEquals(1, host.renderer.physical.resets)
        fill(); call(host.player.internalPlayer, "stopInternal", false, false, false)
        host.renderer.audioSink.reset()
        call(host, "onPlayerError", Fault(java.io.IOException())); tick(2000)
        assertTrue(host.requests.isEmpty()); assertEquals(1, host.renderer.physical.resets)
        assertTrue(host.renderer.physical.flushes > 0); assertTrue(host.renderer.physical.pauses > 0)
        host.renderer.physical.blocked = false; tick()
        assertTrue(host.renderer.physical.bytes.isEmpty())
    }

    @Test fun `a seek between error callback and queued recovery cancels before the first switch`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); fault()
        call(host, "seekToPosition", 7000L); tick(2000)
        assertTrue(host.requests.isEmpty()); assertEquals(listOf(7000L), host.seeks)
    }
    @Test fun `a native recovery that already cleared the error never triggers a stale next back`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); fault()
        host.player.error = null; host.player.playbackState = 3
        tick(1000); assertTrue(host.requests.isEmpty())
    }

    @Test fun `live adoption finishes the original native partial write before using queue copies`() {
        enabled = false; assertTrue(install() is TargetCapabilityInstall.Active)
        call(host.renderer, "initializeAudioSink", host.renderer.inputFormat)
        val first = pcm(1).apply { position(8000) }
        host.renderer.physical.inputBuffer = first
        host.renderer.physical.bytes.addAll(List(8000) { 1.toByte() })
        enabled = true; call(host.renderer, "render", 0L, 0L)
        assertEquals(1, host.renderer.physical.configs)
        host.renderer.physical.blocked = false
        assertTrue(host.renderer.audioSink.handleBuffer(first, 1_000_000))
        assertTrue(host.renderer.audioSink.handleBuffer(pcm(2), 2_000_000)); tick()
        assertEquals(List(16000) { 1.toByte() } + List(16000) { 2.toByte() }, host.renderer.physical.bytes)
    }

    @Test fun `temporary next song normalization cannot change original audio but user volume and mute work`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); fault(); tick(20)
        assertEquals(0.5f, host.renderer.physical.volumes.last())
        call(host, "setVolume", 0.4f); tick(20)
        assertEquals(0.2f, host.renderer.physical.volumes.last())
        call(host, "setVolume", 0f); tick(20)
        assertEquals(0f, host.renderer.physical.volumes.last())
        tick(500); assertEquals(DrmRoundTripRecovery.Stage.COMPLETE, BufferedDrmRecoveryStatus.stage)
        assertEquals(0f, host.renderer.physical.volumes.last())
    }

    @Test fun `native EOS clock is forwarded after the reserve drains and never before`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill()
        host.renderer.audioSink.getCurrentPositionUs(true)
        assertFalse(host.renderer.physical.positions.last())
        host.renderer.physical.blocked = false; tick()
        host.renderer.audioSink.getCurrentPositionUs(true)
        assertTrue(host.renderer.physical.positions.last())
    }

    @Test fun `a pump failure reenters native error handling instead of silently stalling the audio looper`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill()
        host.renderer.physical.blocked = false; host.renderer.physical.failWrites = true
        tick()
        try { call(host.renderer, "render", 0L, 0L); fail("Native engine must receive the output failure") }
        catch (error: Fault) { assertTrue(error.cause is IllegalStateException) }
        assertTrue(host.requests.isEmpty())
    }

    @Test fun `metadata renderers do not block single audio recovery and store IDs remain distinct from queue IDs`() {
        assertTrue(install() is TargetCapabilityInstall.Active)
        host.player.internalPlayer.enabledRenderers = arrayOf(host.renderer, Metadata())
        fill(); fault(); tick(500)
        assertEquals(listOf(17L, 81L), host.requests)
        assertEquals(DrmRoundTripRecovery.Stage.COMPLETE, BufferedDrmRecoveryStatus.stage)
    }
    @Test fun `crossfade never starts a competing native output during buffered recovery`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill()
        host.player.internalPlayer.audioFadeControl.enabled = true
        fault(); tick(2000)
        assertTrue(host.requests.isEmpty()); assertTrue(host.renderer.physical.resets > 0)
    }
    @Test fun `a duplicate next catalog item is not confused with the original queue occurrence`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill()
        host.items.last().media.store = host.getCurrentItem().media.store
        fault(); tick(2000)
        assertTrue(host.requests.isEmpty())
    }

    @Test fun `temporary selection stays on one physical output and native policy is restored afterward`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); fault(); tick(20)
        val caps = arrayOf<Caps>(host.renderer, Renderer())
        assertEquals(0, call(host.trackSelector, "findRenderer", caps, Group(), intArrayOf(1, 0), true))
        val unsupported = arrayOf<Caps>(object : Base() { override fun supportsFormat(format: Format) = 2 }, Renderer())
        assertEquals(2, call(host.trackSelector, "findRenderer", unsupported, Group(), intArrayOf(0, 0), true))
        tick(500)
        assertEquals(1, call(host.trackSelector, "findRenderer", caps, Group(), intArrayOf(0, 0), true))
    }
    @Test fun `seek during the temporary next request explicitly masks the original window and requested position`() {
        assertTrue(install() is TargetCapabilityInstall.Active); fill(); fault(); tick(20)
        call(host, "seekToPosition", 7000L); tick(500)
        assertEquals(81L, host.selected)
        assertEquals(listOf(0 to 7000L), host.player.seeks)
        assertEquals(1, host.playCalls)
        assertEquals(DrmRoundTripRecovery.Stage.CANCELLED, BufferedDrmRecoveryStatus.stage)
        assertEquals(0L, BufferedDrmRecoveryStatus.bufferedMs)
    }
    companion object { private fun pcm(value: Int) = ByteBuffer.wrap(ByteArray(16000) { value.toByte() }) }
}
