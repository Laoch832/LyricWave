package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.playback.PlaybackRecoveryStatus
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import org.json.JSONObject

/** Deliver an unchanged native refresh on the renderer's own looper, never from the DRM worker. */
internal class AppleMusicRendererRefreshTarget(
    private val names: JSONObject?, private val loader: ClassLoader, private val enabled: () -> Boolean,
    private val invoke: (Method, Any, Array<Any?>) -> Unit = { method, receiver, args -> method.invoke(receiver, *args); Unit },
    private val register: (Method, ModernMethodHook, HookRegistrationScope) -> Boolean = { method, callback, scope ->
        ModernXposedRuntime.hookMethod(method, callback, scope)
    },
) : PlaybackStallTarget {
    private val scope = HookRegistrationScope()
    private val generations = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    override fun install(): TargetCapabilityInstall {
        if (scope.isActive) return TargetCapabilityInstall.Active("Native renderer refresh threading already installed")
        if (names?.optInt("contractVersion") != 1) return TargetCapabilityInstall.Unsupported("No verified renderer refresh contract for this exact host")
        return try {
            // Resolve every descriptor before registering any hook. Only profiles with verified native code opt in.
            val members = FragmentProfileMembers(loader, names, "playbackRendererRefresh", FragmentMemberRequirements(
                methods = setOf("refresh", "disabled", "positionReset", "reset", "streamChanged"), fields = setOf("handler")))
            val refresh = members.method("refresh")
            val handlerField = members.field("handler")
            check(handlerField.type == Handler::class.java && refresh.returnType == Void.TYPE && refresh.parameterCount == 1)
            check(listOf("disabled", "positionReset", "reset", "streamChanged").all { members.method(it).declaringClass == refresh.declaringClass })
            check(handlerField.declaringClass == refresh.declaringClass)
            for (role in listOf("disabled", "positionReset", "reset", "streamChanged")) {
                check(register(members.method(role), object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val receiver = param.thisObject ?: return
                        synchronized(generations) { generations[receiver] = (generations[receiver] ?: 0) + 1 }
                    }
                }, scope))
            }
            check(register(refresh, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || !enabled()) return
                    val receiver = param.thisObject ?: return
                    val target = runCatching { handlerField.get(receiver) as? Handler }.getOrNull() ?: return
                    if (target.looper === Looper.myLooper()) return // Re-entry invokes the unmodified native body once.
                    val generation = generations[receiver] ?: 0
                    val reference = WeakReference(receiver)
                    val arguments = param.args.copyOf() // Opaque native object; no inspection, copying or storage of key bytes.
                    val queued = target.post {
                        val current = reference.get() ?: return@post
                        // onDisabled removes pending callbacks too, but this guards a post that races with that removal.
                        if (handlerField.get(current) !== target || (generations[current] ?: 0) != generation) return@post
                        runCatching { invoke(refresh, current, arguments) }.onFailure {
                            PlaybackRecoveryStatus.rendererRefreshAvailable = false
                            ModernXposedRuntime.log("Native renderer refresh dispatch failed: ${it.javaClass.simpleName}")
                        }
                    }
                    if (queued) param.result = null // Suppress only after successful queueing; failed post preserves native behavior.
                }
            }, scope))
            scope.onClose { generations.clear() }
            scope.activate()
            PlaybackRecoveryStatus.rendererRefreshAvailable = true
            TargetCapabilityInstall.Active("Native key-refresh callbacks serialized on the owning renderer looper; stale lifecycle callbacks discarded")
        } catch (error: Throwable) {
            scope.close()
            PlaybackRecoveryStatus.rendererRefreshAvailable = false
            TargetCapabilityInstall.Degraded("Native renderer refresh contract unavailable: ${error.javaClass.simpleName}")
        }
    }
}
