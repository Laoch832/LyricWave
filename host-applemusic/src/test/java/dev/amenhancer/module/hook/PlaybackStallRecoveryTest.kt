package dev.amenhancer.module.hook

import android.os.Looper
import dev.amenhancer.module.playback.*
import java.io.IOException
import java.lang.reflect.Method
import java.net.SocketTimeoutException
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackStallRecoveryTest {
    class Item(var id: Long = 42) { fun getPlaybackQueueId() = id }
    class Fault(@JvmField val type: Int, val failure: IOException) : Exception() { fun getSourceException() = failure; fun getRendererException() = failure; fun getUnexpectedException() = failure }
    class HttpFault(@JvmField val responseCode: Int) : IOException()
    class Engine {
        var state = 1; var position = 73_000L; var window = 5; var wantsPlay = true; var fault: Fault? = null; var retries = 0
        fun getPlaybackState() = state
        fun getCurrentWindowIndex() = window
        fun getPlaybackError(): Fault? = fault
        fun getPlayWhenReady() = wantsPlay
        fun getApplicationLooper(): Looper = Looper.getMainLooper()
        fun retry() { retries++; fault = null; state = 2 }
    }
    class Host(private val player: Engine) {
        val item = Item()
        fun getCurrentItem() = item
        fun onPlayerError(error: Fault) = Unit
        fun onPlayerStateChanged(ready: Boolean, state: Int) { player.state = state }
        fun play() { player.wantsPlay = true } // Native play leaves a failed source IDLE.
        fun seekToPosition(position: Long) { player.position = position } // Native seek alone cannot reprepare it.
    }
    private lateinit var engine: Engine
    private lateinit var host: Host
    private val hooks = mutableMapOf<String, Pair<Method, ModernMethodHook>>()
    private var enabled = true
    @Before fun setup() {
        engine = Engine(); host = Host(engine); enabled = true; hooks.clear(); PlaybackRecoveryStatus.latest = null
        PlaybackRecoveryStatus.currentFaultActive = null; PlaybackRecoveryStatus.observedAtElapsedMs = null
        val mapping = mapOf("com.apple.android.music.playback.player.ExoMediaPlayer" to Host::class.java,
            "com.google.android.exoplayer2.ExoPlayer" to Engine::class.java,
            "com.google.android.exoplayer2.ExoPlaybackException" to Fault::class.java,
            "com.apple.android.music.playback.model.PlayerQueueItem" to Item::class.java,
            "com.google.android.exoplayer2.upstream.HttpDataSource\$InvalidResponseCodeException" to HttpFault::class.java)
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = mapping[name] ?: super.loadClass(name)
        }
        val target = AppleMusicPlaybackStallTarget(loader, { enabled }) { method, hook, _ -> hooks[method.name] = method to hook; true }
        assertTrue(target.install() is TargetCapabilityInstall.Active)
    }
    private fun call(name: String, vararg args: Any?) = callOn(host, name, *args)
    private fun callOn(receiver: Host, name: String, vararg args: Any?) {
        val (method, hook) = hooks.getValue(name)
        method.invoke(receiver, *args)
        val param = ModernMethodHook.MethodHookParam::class.java.declaredConstructors.single()
            .apply { isAccessible = true }.newInstance(method, receiver, args) as ModernMethodHook.MethodHookParam
        hook.afterHookedMethod(param)
    }
    private fun fault(error: IOException = SocketTimeoutException()) {
        engine.state = 1; engine.fault = Fault(0, error)
        call("onPlayerError", engine.fault)
    }
    private fun waitForRetry() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)) }
    @Test fun `network failure recovers once without changing queue position or play intent`() {
        fault(); waitForRetry()
        assertEquals(1, engine.retries); assertEquals(73_000L, engine.position); assertEquals(5, engine.window)
        assertEquals(42L, host.item.id); assertTrue(engine.wantsPlay)
        call("onPlayerStateChanged", true, 3)
        assertEquals(PlaybackRecoveryAction.READY, PlaybackRecoveryStatus.latest?.action)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        fault(); waitForRetry(); assertEquals(1, engine.retries)
    }
    @Test fun `pausing or changing song cancels delayed recovery`() {
        fault(); engine.wantsPlay = false; waitForRetry(); assertEquals(0, engine.retries)
        engine.wantsPlay = true; fault(); host.item.id = 99; waitForRetry(); assertEquals(0, engine.retries)
    }
    @Test fun `explicit seek can reprepare the failed source at the chosen position`() {
        engine.wantsPlay = false
        fault(); waitForRetry(); assertEquals(0, engine.retries)
        call("seekToPosition", 91_000L)
        assertEquals(1, engine.retries); assertEquals(91_000L, engine.position); assertFalse(engine.wantsPlay)
    }
    @Test fun `authorization faults and ordinary buffering never cause retries`() {
        fault(HttpFault(403)); waitForRetry(); call("play"); call("seekToPosition", 10L)
        assertEquals(0, engine.retries)
        engine.state = 2; engine.fault = null
        call("play"); call("seekToPosition", 11L); assertEquals(0, engine.retries)
    }
    @Test fun `disabling recovery before the scheduled attempt leaves playback untouched`() {
        fault(); enabled = false; waitForRetry(); assertEquals(0, engine.retries)
    }
    class DrmSessionException(cause: IOException) : IOException(cause)
    @Test fun `DRM renderer and runtime failures report the real cause and native state without retry`() {
        for ((type, cause, category) in listOf(
            Triple(1, DrmSessionException(IOException()), PlaybackFailure.DRM),
            Triple(1, IOException(), PlaybackFailure.RENDERER),
            Triple(2, IOException(), PlaybackFailure.UNEXPECTED))) {
            engine.state = 1; engine.fault = Fault(type, cause)
            call("onPlayerError", engine.fault)
            assertEquals(category, PlaybackRecoveryStatus.latest?.failure)
            assertEquals(1, PlaybackRecoveryStatus.nativeState)
            assertTrue(PlaybackRecoveryStatus.latest?.diagnostic?.startsWith("T$type:") == true)
            waitForRetry(); call("play"); call("seekToPosition", 12L)
            assertEquals(0, engine.retries)
        }
    }
    @Test fun `a new renderer failure cancels scheduled network recovery`() {
        fault()
        engine.fault = Fault(1, DrmSessionException(IOException()))
        call("onPlayerError", engine.fault)
        waitForRetry()
        call("onPlayerStateChanged", true, 3)
        assertEquals(0, engine.retries)
        assertEquals(PlaybackFailure.DRM, PlaybackRecoveryStatus.latest?.failure)
        assertEquals(PlaybackRecoveryAction.OBSERVED, PlaybackRecoveryStatus.latest?.action)
    }
    @Test fun `another native player cannot overwrite the failing players state`() {
        fault(DrmLicenseKeyException())
        assertEquals(true, PlaybackRecoveryStatus.currentFaultActive)
        assertNotNull(PlaybackRecoveryStatus.observedAtElapsedMs)
        val another = Host(Engine())
        callOn(another, "onPlayerStateChanged", true, 3)
        assertEquals(1, PlaybackRecoveryStatus.nativeState)
        assertEquals(true, PlaybackRecoveryStatus.currentFaultActive)
        engine.fault = null
        call("onPlayerStateChanged", true, 3)
        assertEquals(false, PlaybackRecoveryStatus.currentFaultActive)
        assertEquals(PlaybackRecoveryAction.OBSERVED, PlaybackRecoveryStatus.latest?.action)
    }
    class DrmLicenseKeyException : IOException("Private license URI and key response") { fun getErrorCode() = -84 }
    @Test fun `license error exposes numeric code without retrying authorization`() {
        fault(DrmLicenseKeyException())
        assertEquals("T0:DrmLicenseKeyException / E-84", PlaybackRecoveryStatus.latest?.diagnostic)
        waitForRetry(); call("play"); call("seekToPosition", 22L)
        assertEquals(0, engine.retries)
        assertEquals(42L, host.item.id)
    }
    class AudioRendererException : IOException("Private playback URL and credentials") { fun getErrorCode() = -6 }
    @Test fun `native audio error code is reported without the exception message`() {
        engine.state = 1; engine.fault = Fault(1, AudioRendererException())
        call("onPlayerError", engine.fault)
        assertEquals("T1:AudioRendererException / E-6", PlaybackRecoveryStatus.latest?.diagnostic)
        waitForRetry()
        assertEquals(0, engine.retries)
    }
}
