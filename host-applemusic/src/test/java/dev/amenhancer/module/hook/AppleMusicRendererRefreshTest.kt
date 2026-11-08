package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.playback.PlaybackRecoveryStatus
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
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
class AppleMusicRendererRefreshTest {
    class NativeFormat
    class NativeLicense
    class Renderer {
        @JvmField var handler: Handler? = Handler(Looper.getMainLooper())
        var usable = true
        val received = mutableListOf<NativeLicense>()
        val threads = mutableListOf<Looper?>()
        fun onKeyRefreshed(license: NativeLicense) { if (usable) { received += license; threads += Looper.myLooper() } }
        fun onDisabled() { usable = false; handler = null }
        fun onPositionReset(position: Long, joining: Boolean) = Unit
        fun onReset() = Unit
        fun onStreamChanged(formats: Array<NativeFormat>, offset: Long) = Unit
    }
    private data class Hook(val callback: ModernMethodHook, val scope: HookRegistrationScope)
    private val hooks = mutableMapOf<Method, Hook>()
    private var enabled = true
    private lateinit var renderer: Renderer
    private val refresh get() = Renderer::class.java.getDeclaredMethod("onKeyRefreshed", NativeLicense::class.java)
    private fun section(): JSONObject {
        val owner = Renderer::class.java.name
        fun method(name: String, parameters: List<String>) = JSONObject().put("owner", owner).put("name", name)
            .put("parameters", org.json.JSONArray(parameters)).put("returns", "void").put("static", false)
        return JSONObject().put("contractVersion", 1)
            .put("methods", JSONObject().put("refresh", method("onKeyRefreshed", listOf(NativeLicense::class.java.name)))
                .put("disabled", method("onDisabled", emptyList())).put("reset", method("onReset", emptyList()))
                .put("positionReset", method("onPositionReset", listOf("long", "boolean")))
                .put("streamChanged", method("onStreamChanged", listOf(arrayOf<NativeFormat>().javaClass.name, "long"))))
            .put("fields", JSONObject().put("handler", JSONObject().put("owner", owner).put("name", "handler").put("type", "android.os.Handler")))
    }
    private fun install(section: JSONObject? = section()): TargetCapabilityInstall = AppleMusicRendererRefreshTarget(
        section, javaClass.classLoader!!, { enabled },
        invoke = { method, receiver, args -> call(receiver, method, args) },
        register = { method, hook, scope -> hooks[method] = Hook(hook, scope); true },
    ).install()
    private fun call(receiver: Any, method: Method, args: Array<Any?> = emptyArray()) {
        val hook = hooks[method]
        if (hook == null || !hook.scope.isActive) { method.invoke(receiver, *args); return }
        val param = ModernMethodHook.MethodHookParam::class.java.declaredConstructors.single()
            .apply { isAccessible = true }.newInstance(method, receiver, args) as ModernMethodHook.MethodHookParam
        hook.callback.beforeHookedMethod(param)
        val early = param.javaClass.getDeclaredField("returnEarly").apply { isAccessible = true }.getBoolean(param)
        if (!early) method.invoke(receiver, *args)
        hook.callback.afterHookedMethod(param)
        param.throwable?.let { throw it }
    }
    private fun drmWorker(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        val thread = Thread { try { action() } catch (error: Throwable) { failure.set(error) } }
        thread.start(); thread.join(5_000)
        assertFalse("DRM worker was blocked by renderer dispatch", thread.isAlive)
        failure.get()?.let { throw AssertionError("DRM callback failed", it) }
    }
    @Before fun setup() { hooks.clear(); enabled = true; renderer = Renderer(); PlaybackRecoveryStatus.rendererRefreshAvailable = null }

    @Test fun `DRM worker refresh is delivered once on renderer thread with the same native object`() {
        assertTrue(install() is TargetCapabilityInstall.Active)
        val license = NativeLicense()
        drmWorker { call(renderer, refresh, arrayOf(license)) }
        assertTrue(renderer.received.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, renderer.received.size); assertSame(license, renderer.received.single())
        assertSame(Looper.getMainLooper(), renderer.threads.single())
        assertEquals(true, PlaybackRecoveryStatus.rendererRefreshAvailable)
    }
    @Test fun `refresh already on renderer thread runs immediately without recursion or double delivery`() {
        install(); val license = NativeLicense()
        call(renderer, refresh, arrayOf(license))
        assertEquals(listOf(license), renderer.received)
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, renderer.received.size)
    }
    @Test fun `seek invalidates pending native callback instead of applying it to the new stream`() {
        install(); val old = NativeLicense()
        drmWorker { call(renderer, refresh, arrayOf(old)) }
        call(renderer, Renderer::class.java.getDeclaredMethod("onPositionReset", java.lang.Long.TYPE, java.lang.Boolean.TYPE), arrayOf(72_000L, false))
        shadowOf(Looper.getMainLooper()).idle(); assertTrue(renderer.received.isEmpty())
        val fresh = NativeLicense()
        drmWorker { call(renderer, refresh, arrayOf(fresh)) }
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(listOf(fresh), renderer.received)
    }
    @Test fun `disabled and re-enabled renderer never receives old pending callback`() {
        install()
        drmWorker { call(renderer, refresh, arrayOf(NativeLicense())) }
        call(renderer, Renderer::class.java.getDeclaredMethod("onDisabled"))
        renderer.handler = Handler(Looper.getMainLooper()); renderer.usable = true
        shadowOf(Looper.getMainLooper()).idle(); assertTrue(renderer.received.isEmpty())
    }
    @Test fun `switching stream invalidates old key refresh without changing the song`() {
        install()
        drmWorker { call(renderer, refresh, arrayOf(NativeLicense())) }
        call(renderer, Renderer::class.java.getDeclaredMethod("onStreamChanged", arrayOf<NativeFormat>().javaClass, java.lang.Long.TYPE),
            arrayOf(arrayOf(NativeFormat()), 0L))
        shadowOf(Looper.getMainLooper()).idle(); assertTrue(renderer.received.isEmpty())
    }
    @Test fun `different renderers keep independent callback generations`() {
        install(); val other = Renderer(); val a = NativeLicense(); val b = NativeLicense()
        drmWorker { call(renderer, refresh, arrayOf(a)); call(other, refresh, arrayOf(b)) }
        call(renderer, Renderer::class.java.getDeclaredMethod("onReset"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(renderer.received.isEmpty()); assertEquals(listOf(b), other.received)
    }
    @Test fun `disabled setting leaves native callback behavior intact`() {
        install(); enabled = false; val license = NativeLicense()
        drmWorker { call(renderer, refresh, arrayOf(license)) }
        assertEquals(listOf(license), renderer.received)
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, renderer.received.size)
    }
    @Test fun `rejected handler post preserves original callback instead of losing the refresh`() {
        install()
        renderer.handler = object : Handler(Looper.getMainLooper()) {
            override fun sendMessageAtTime(message: android.os.Message, uptimeMillis: Long): Boolean = false
        }
        val license = NativeLicense()
        drmWorker { call(renderer, refresh, arrayOf(license)) }
        assertEquals(listOf(license), renderer.received)
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, renderer.received.size)
    }
    @Test fun `unverified or mismatched contracts install no hooks`() {
        assertTrue(install(null) is TargetCapabilityInstall.Unsupported); assertTrue(hooks.isEmpty())
        val bad = section(); bad.getJSONObject("fields").getJSONObject("handler").put("type", "java.lang.String")
        assertTrue(install(bad) is TargetCapabilityInstall.Degraded); assertTrue(hooks.isEmpty())
    }
}
