package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.amenhancer.module.playback.*
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Repairs the native error-idle gap: normal play/seek do not reprepare a failed Exo source. */
internal class AppleMusicPlaybackStallTarget(
    private val loader: ClassLoader, private val enabled: () -> Boolean,
    private val rendererRefresh: (() -> TargetCapabilityInstall)? = null,
    private val register: (Method, ModernMethodHook, HookRegistrationScope) -> Boolean = { method, callback, scope ->
        ModernXposedRuntime.hookMethod(method, callback, scope)
    },
) : PlaybackStallTarget {
    private val scope = HookRegistrationScope()
    private val budgets = Collections.synchronizedMap(WeakHashMap<Any, PlaybackRetryBudget>())
    private val pending = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    private val retrying = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private var reportOwner = WeakReference<Any>(null)
    private var report: PlaybackRecoveryReport? = null
    private var reportFault = WeakReference<Any>(null)
    private var reportItemKey: String? = null
    override fun install(): TargetCapabilityInstall {
        if (scope.isActive) return TargetCapabilityInstall.Active("Playback source recovery already installed")
        return try {
            val owner = loader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")
            val exo = loader.loadClass("com.google.android.exoplayer2.ExoPlayer")
            val error = loader.loadClass("com.google.android.exoplayer2.ExoPlaybackException")
            val queueItem = loader.loadClass("com.apple.android.music.playback.model.PlayerQueueItem")
            val player = owner.getDeclaredField("player").apply { isAccessible = true }
            check(exo.isAssignableFrom(player.type))
            val current = owner.getMethod("getCurrentItem").also { check(queueItem.isAssignableFrom(it.returnType)) }
            val queueId = queueItem.getMethod("getPlaybackQueueId").also { check(it.returnType == java.lang.Long.TYPE) }
            val state = exo.getMethod("getPlaybackState").also { check(it.returnType == java.lang.Integer.TYPE) }
            val window = exo.getMethod("getCurrentWindowIndex").also { check(it.returnType == java.lang.Integer.TYPE) }
            val errorNow = exo.getMethod("getPlaybackError").also { check(error.isAssignableFrom(it.returnType)) }
            val wantsPlay = exo.getMethod("getPlayWhenReady").also { check(it.returnType == java.lang.Boolean.TYPE) }
            val looper = exo.getMethod("getApplicationLooper").also { check(it.returnType == Looper::class.java) }
            val retry = exo.getMethod("retry").also { check(it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)) }
            val type = error.getField("type").also { check(it.type == java.lang.Integer.TYPE) }
            val source = error.getMethod("getSourceException")
            val renderer = error.getMethod("getRendererException")
            val unexpected = error.getMethod("getUnexpectedException")
            val invalidHttp = loader.loadClass("com.google.android.exoplayer2.upstream.HttpDataSource\$InvalidResponseCodeException")
            val responseCode = invalidHttp.getField("responseCode").also { check(it.type == java.lang.Integer.TYPE) }
            fun category(fault: Any): PlaybackRecoveryReport {
                val kind = type.getInt(fault)
                val cause = when (kind) {
                    0 -> source.invoke(fault) as? Throwable
                    1 -> renderer.invoke(fault) as? Throwable
                    2 -> unexpected.invoke(fault) as? Throwable
                    else -> (fault as? Throwable)?.cause
                }
                val code = generateSequence(cause) { it.cause }.take(8).firstOrNull(invalidHttp::isInstance)?.let(responseCode::getInt)
                val nativeCode = generateSequence(cause) { it.cause }.take(8).firstNotNullOfOrNull { item ->
                    if (item.javaClass.simpleName !in setOf("DrmException", "DrmLicenseKeyException", "AudioRendererException", "ErrorConditionException")) null
                    else runCatching {
                        item.javaClass.getMethod("getErrorCode").takeIf { it.returnType == Int::class.javaPrimitiveType }
                            ?.invoke(item) as? Int
                    }.getOrNull()
                }
                val componentCode = generateSequence(cause) { it.cause }.take(8).firstNotNullOfOrNull { item ->
                    if (item.javaClass.simpleName !in setOf("DrmException", "AudioRendererException")) null
                    else runCatching { item.javaClass.getMethod("getComponentErrorCode").takeIf {
                        it.returnType == Int::class.javaPrimitiveType }?.invoke(item) as? Int }.getOrNull()
                }
                return PlaybackRecoveryReport(PlaybackFailurePolicy.classify(kind, cause, code, componentCode),
                    PlaybackRecoveryAction.OBSERVED, code, PlaybackFailurePolicy.diagnostic(kind, cause) +
                        (nativeCode?.let { " / E$it" } ?: "") + (componentCode?.let { " / C$it" } ?: ""),
                    nativeErrorCode = nativeCode, componentErrorCode = componentCode)
            }
            fun itemKey(host: Any, engine: Any): String? {
                val item = current.invoke(host) ?: return null
                val index = window.invoke(engine) as? Int ?: return null
                if (index < 0) return null
                return "${queueId.invoke(item)}:$index"
            }
            fun publish(value: PlaybackRecoveryReport) {
                report = value
                PlaybackRecoveryStatus.latest = report
            }
            fun attempt(host: Any, expectedError: Any?, expectedKey: String?, automatic: Boolean) {
                if (!scope.isActive || !enabled()) return
                val engine = player.get(host) ?: return
                if (looper.invoke(engine) !== Looper.myLooper()) return
                if (state.invoke(engine) != 1) return // Only confirmed fatal IDLE, never ordinary BUFFERING.
                val fault = errorNow.invoke(engine) ?: return
                if (expectedError != null && fault !== expectedError) return
                val key = itemKey(host, engine) ?: return
                if (expectedKey != null && expectedKey != key) return
                if (automatic && wantsPlay.invoke(engine) != true) return
                val observed = category(fault)
                val failure = observed.failure
                val code = observed.httpStatus
                if (!PlaybackFailurePolicy.recoverable(failure)) return
                val budget = budgets.getOrPut(host) { PlaybackRetryBudget(SystemClock::elapsedRealtime) }
                if (!budget.claim(key, automatic)) return
                // ExoPlayer.retry reuses the same source with resetPosition=false/resetState=false.
                // No queue/index/position/playWhenReady/DRM credentials are rewritten.
                retrying[host] = key
                reportOwner = WeakReference(host)
                publish(observed.copy(action = PlaybackRecoveryAction.RETRY_REQUESTED))
                retry.invoke(engine)
                ModernXposedRuntime.log("Playback source recovery requested: mode=${if (automatic) "once" else "user"}, category=$failure, http=${code ?: 0}")
            }
            fun safely(action: () -> Unit) { runCatching(action).onFailure {
                ModernXposedRuntime.log("Playback source recovery skipped: ${it.javaClass.simpleName}")
            } }
            val onError = owner.getMethod("onPlayerError", error)
            check(register(onError, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = safely {
                    if (param.throwable != null || !enabled()) return@safely
                    val host = param.thisObject ?: return@safely
                    val fault = param.args.firstOrNull() ?: return@safely
                    // A new failure supersedes any pending recovery, including another player's report.
                    pending.remove(host); retrying.remove(host)
                    reportOwner = WeakReference(host)
                    val observed = category(fault)
                    publish(observed)
                    PlaybackRecoveryStatus.observedAtElapsedMs = SystemClock.elapsedRealtime()
                    reportFault = WeakReference(fault)
                    val engine = player.get(host) ?: return@safely
                    PlaybackRecoveryStatus.nativeState = state.invoke(engine) as? Int
                    runCatching { PlaybackRecoveryStatus.errorObserver?.invoke(observed, fault as? Throwable) }
                    reportItemKey = itemKey(host, engine)
                    PlaybackRecoveryStatus.currentFaultActive = errorNow.invoke(engine) === fault && reportItemKey != null
                    if (!PlaybackFailurePolicy.recoverable(observed.failure)) return@safely
                    val key = itemKey(host, engine) ?: return@safely
                    val handler = Handler(looper.invoke(engine) as Looper)
                    val token = Any()
                    pending[host] = token
                    handler.postDelayed({ safely {
                        if (pending[host] === token) { pending.remove(host); attempt(host, fault, key, true) }
                    } }, 750)
                }
            }, scope))
            for (method in listOf(owner.getMethod("play"), owner.getMethod("seekToPosition", java.lang.Long.TYPE))) {
                check(method.returnType == Void.TYPE)
                check(register(method, object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = safely {
                        if (param.throwable == null) param.thisObject?.let { attempt(it, null, null, false) }
                    }
                }, scope))
            }
            check(register(owner.getMethod("onPlayerStateChanged", java.lang.Boolean.TYPE, java.lang.Integer.TYPE),
                object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = safely {
                        if (param.throwable != null) return@safely
                        val host = param.thisObject ?: return@safely
                        if (reportOwner.get() != null && reportOwner.get() !== host) return@safely
                        PlaybackRecoveryStatus.nativeState = param.args.getOrNull(1) as? Int
                        val engine = player.get(host) ?: return@safely
                        if (reportOwner.get() === host) {
                            PlaybackRecoveryStatus.currentFaultActive = reportFault.get()?.let {
                                errorNow.invoke(engine) === it && reportItemKey == itemKey(host, engine)
                            } ?: false
                        }
                        if (param.args.getOrNull(0) == true && param.args.getOrNull(1) == 3 &&
                            reportOwner.get() === host && report?.action == PlaybackRecoveryAction.RETRY_REQUESTED &&
                            retrying[host] == itemKey(host, engine) && errorNow.invoke(engine) == null) {
                            retrying.remove(host)
                            report?.let { publish(it.copy(action = PlaybackRecoveryAction.READY)) }
                        }
                    }
                }, scope))
            scope.onClose { pending.clear(); budgets.clear(); retrying.clear(); reportOwner.clear(); reportFault.clear(); reportItemKey = null }
            scope.activate()
            PlaybackRecoveryStatus.available = true
            val refreshResult = rendererRefresh?.invoke()
            val detail = "Confirmed network-error IDLE recovery; one attempt per item, no skip or queue edits" +
                (refreshResult?.let { "; ${it.message}" } ?: "")
            if (refreshResult is TargetCapabilityInstall.Degraded) TargetCapabilityInstall.Degraded(detail)
            else TargetCapabilityInstall.Active(detail)
        } catch (error: Throwable) {
            scope.close()
            PlaybackRecoveryStatus.available = false
            TargetCapabilityInstall.Degraded("Playback source recovery contract unavailable: ${error.javaClass.simpleName}")
        }
    }
}
