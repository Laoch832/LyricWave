@file:Suppress("NewApi", "DEPRECATION")
package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Scoped to the verified lyrics recycler, never changes global display settings or native text paints. */
internal class AppleMusicLyricsHdrTarget(
    private val symbols: TargetSymbolResolver,
    private val config: TargetConfigClient,
) : LyricsHdrTarget {
    private val scope = HookRegistrationScope()
    private val sessions = WeakHashMap<Any, WeakReference<LyricsHdrSession>>()
    private val owners = WeakHashMap<TextView, WeakReference<LyricsHdrSession>>()
    private val hookedDraws = mutableSetOf<Method>()
    private val bypass = ThreadLocal.withInitial { false }
    private val incompatible = WeakHashMap<TextView, Boolean>()
    override fun install(): TargetCapabilityInstall {
        if (scope.isActive) return TargetCapabilityInstall.Active("HDR lyric sessions already installed")
        val fragment = symbols.resolve(AppleMusicSymbols.LyricsFragment).valueOrNull()
            ?: return TargetCapabilityInstall.Unsupported("Verified lyrics fragment was not found")
        val getter = runCatching { fragment.getMethod("getRecyclerView") }.getOrNull()
            ?: return TargetCapabilityInstall.Unsupported("Verified lyrics recycler was not found")
        return try {
            val resume = lifecycle(fragment, "onResume") ?: error("Missing lyrics resume lifecycle")
            val pause = lifecycle(fragment, "onPause") ?: error("Missing lyrics pause lifecycle")
            check(ModernXposedRuntime.hookMethod(resume, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject?.takeIf(fragment::isInstance) ?: return
                    if (param.throwable != null) return
                    val root = runCatching { getter.invoke(owner) as? ViewGroup }.getOrNull() ?: return
                    sessions.remove(owner)?.get()?.close()
                    val session = LyricsHdrSession(root, config::settings) { text, session ->
                        val fresh = owners[text]?.get() !== session
                        owners[text] = WeakReference(session)
                        runCatching { installDraw(text.javaClass) }.onFailure { incompatible[text] = true }
                        if (fresh) text.invalidate()
                    }
                    sessions[owner] = WeakReference(session)
                    session.start()
                }
            }, scope))
            check(ModernXposedRuntime.hookMethod(pause, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { sessions.remove(param.thisObject)?.get()?.close() }
            }, scope))
            scope.onClose { sessions.values.mapNotNull { it.get() }.forEach(LyricsHdrSession::close); sessions.clear(); owners.clear() }
            scope.activate()
            if (Build.VERSION.SDK_INT >= 34) TargetCapabilityInstall.Active("HDR gainmap text rendering; lyric-only window brightness ownership")
            else TargetCapabilityInstall.Degraded("HDR requires Android 14; flashbang supports ordinary window brightness only")
        } catch (error: Throwable) {
            scope.close()
            TargetCapabilityInstall.Degraded("HDR lyric lifecycle unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun installDraw(type: Class<*>) {
        if (Build.VERSION.SDK_INT < 34) return
        val method = generateSequence(type) { it.superclass }.mapNotNull { owner ->
            runCatching { owner.getDeclaredMethod("onDraw", Canvas::class.java) }.getOrNull()
        }.firstOrNull() ?: return
        if (!hookedDraws.add(method)) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val text = param.thisObject as? TextView ?: return
                if (bypass.get() == true || incompatible[text] == true) return
                val canvas = param.args.firstOrNull() as? Canvas ?: return
                if (!canvas.isHardwareAccelerated) return // Nested super.onDraw and offscreen draws stay untouched.
                val session = owners[text]?.get() ?: return
                val frame = runCatching { session.capture(text, canvas) }.getOrNull() ?: return
                param.extras["hdr-frame"] = frame
                param.args[0] = frame.canvas
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val frame = param.extras["hdr-frame"] as? HdrTextFrame ?: return
                param.args[0] = frame.destination
                if (param.throwable == null) runCatching { frame.submit() }
                else {
                    // A host glyph renderer requiring a GPU canvas falls back to its original draw.
                    val text = param.thisObject as? TextView ?: return
                    incompatible[text] = true
                    bypass.set(true)
                    try {
                        method.isAccessible = true
                        runCatching { method.invoke(text, frame.destination) }.onSuccess { param.result = null }
                    } finally { bypass.set(false) }
                }
            }
        }, scope)
    }
    private fun lifecycle(type: Class<*>, name: String): Method? = generateSequence(type) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredMethod(name) }.getOrNull() }.firstOrNull()
}

/** Gainmaps encode relative luminance without replacing the native glyph layout, spans or outer blur. */
internal class HdrTextFrame(val bitmap: Bitmap, val canvas: Canvas, val destination: Canvas) {
    companion object { private val paint = Paint(Paint.FILTER_BITMAP_FLAG) }
    fun submit() { destination.drawBitmap(bitmap, 0f, 0f, paint) }
}

internal class LyricsHdrWindow(private val window: Window) : AutoCloseable {
    private val oldBrightness = window.attributes.screenBrightness
    private val oldMode = window.colorMode
    private val oldHeadroom = if (Build.VERSION.SDK_INT >= 35) window.desiredHdrHeadroom else 0f
    private var appliedBrightness: Float? = null
    private var appliedMode: Int? = null
    private var appliedHeadroom: Float? = null
    fun apply(hdr: Boolean, ratio: Float, flashbang: Boolean) {
        if (flashbang && window.attributes.screenBrightness != 1f) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
            appliedBrightness = 1f
        } else if (!flashbang && appliedBrightness != null) {
            if (window.attributes.screenBrightness == appliedBrightness)
                window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            appliedBrightness = null
        }
        if (hdr) {
            if (window.colorMode != ActivityInfo.COLOR_MODE_HDR) window.colorMode = ActivityInfo.COLOR_MODE_HDR
            appliedMode = ActivityInfo.COLOR_MODE_HDR
            if (Build.VERSION.SDK_INT >= 35) {
                val desired = if (flashbang) 0f else ratio // 0 asks the system for automatic maximum headroom.
                if (window.desiredHdrHeadroom != desired) window.desiredHdrHeadroom = desired
                appliedHeadroom = desired
            }
        }
    }
    override fun close() {
        if (appliedBrightness == window.attributes.screenBrightness)
            window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
        if (appliedMode == window.colorMode && window.colorMode != oldMode) window.colorMode = oldMode
        if (Build.VERSION.SDK_INT >= 35 && appliedHeadroom == window.desiredHdrHeadroom && window.desiredHdrHeadroom != oldHeadroom) window.desiredHdrHeadroom = oldHeadroom
        appliedBrightness = null; appliedMode = null; appliedHeadroom = null
    }
}

internal class LyricsHdrSession(
    private val root: ViewGroup,
    private val settings: () -> ModuleSettings,
    private val register: (TextView, LyricsHdrSession) -> Unit,
) : AutoCloseable, View.OnAttachStateChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private val buffers = if (Build.VERSION.SDK_INT >= 34) HdrGlyphBuffers() else null
    private var window: LyricsHdrWindow? = null
    private var active = false
    private var hdr = false
    private var ratio = 1f
    private var lastSettings: ModuleSettings? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            runCatching { update() }.onFailure { error ->
                close()
                ModernXposedRuntime.log("HDR lyrics session stopped: ${error.javaClass.simpleName}")
            }
            if (active) handler.postDelayed(this, 300)
        }
    }
    fun start() { active = true; root.addOnAttachStateChangeListener(this); handler.post(tick) }
    private fun activity(): Activity? = generateSequence(root.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    private fun visible() = root.isAttachedToWindow && root.isShown && root.windowVisibility == View.VISIBLE && root.hasWindowFocus()
    private fun update() {
        val value = settings()
        val activity = activity()
        if (!value.lyricsHdrEnabled || !visible() || activity == null || activity.isFinishing || activity.isDestroyed) {
            window?.close(); window = null; hdr = false; return
        }
        hdr = Build.VERSION.SDK_INT >= 34 && root.display?.isHdr == true && root.isHardwareAccelerated
        ratio = if (value.lyricsHdrFlashbangEnabled) {
            if (Build.VERSION.SDK_INT >= 36) runCatching { root.display.highestHdrSdrRatio.takeIf { it.isFinite() && it > 1f }?.coerceAtMost(16f) ?: 8f }.getOrDefault(8f)
            else 8f
        } else value.lyricsHdrBrightnessPercent.coerceIn(100, 800) / 100f
        if (!ratio.isFinite()) ratio = 1f
        val resumed = window == null
        if (resumed) window = LyricsHdrWindow(activity.window)
        window?.apply(hdr, ratio, value.lyricsHdrFlashbangEnabled)
        visit(root, resumed)
        if (lastSettings != value) { buffers?.invalidateViews(); lastSettings = value }
    }
    private fun visit(view: View, invalidate: Boolean) {
        if (view is TextView) { register(view, this); if (invalidate) view.invalidate() }
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), invalidate)
    }
    fun capture(view: TextView, destination: Canvas): HdrTextFrame? {
        if (!active || !hdr || ratio <= 1f || !visible() || view.width <= 0 || view.height <= 0) return null
        return buffers?.frame(view, destination, ratio)
    }
    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) { window?.close(); window = null; hdr = false; buffers?.clear() }
    override fun close() {
        active = false; hdr = false; handler.removeCallbacks(tick); root.removeOnAttachStateChangeListener(this)
        window?.close(); window = null
        buffers?.invalidateViews(); buffers?.clear()
    }
}

/** One bounded reusable SDR glyph raster per live view; only luminance metadata is extended. */
internal class HdrGlyphBuffers {
    private data class Entry(val bitmap: Bitmap, var ratio: Float)
    private val entries = WeakHashMap<TextView, Entry>()
    private var bytes = 0L
    private val gain by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) } }
    fun frame(view: TextView, destination: Canvas, ratio: Float): HdrTextFrame? {
        if (!ratio.isFinite() || ratio <= 1f || view.width <= 0 || view.height <= 0) return null
        val size = view.width.toLong() * view.height * 4
        if (size > 4 * 1024 * 1024) return null
        var entry = entries[view]
        if (entry == null || entry.bitmap.width != view.width || entry.bitmap.height != view.height) {
            if (bytes + size > 16 * 1024 * 1024) clear()
            entry = Entry(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888), 0f)
            entries[view] = entry; bytes += size
        }
        val bitmap = entry.bitmap
        bitmap.eraseColor(Color.TRANSPARENT)
        if (entry.ratio != ratio) {
            bitmap.gainmap = Gainmap(gain).apply {
                setRatioMin(1f, 1f, 1f); setRatioMax(ratio, ratio, ratio)
                setGamma(1f, 1f, 1f); setEpsilonSdr(0f, 0f, 0f); setEpsilonHdr(0f, 0f, 0f)
                minDisplayRatioForHdrTransition = 1f
                displayRatioForFullHdr = ratio
            }
            entry.ratio = ratio
        }
        return HdrTextFrame(bitmap, Canvas(bitmap), destination)
    }
    fun invalidateViews() { entries.keys.toList().forEach(View::invalidate) }
    fun clear() { entries.clear(); bytes = 0 }
}
