package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.amenhancer.module.playback.NextAssetPreloadState as State
import dev.amenhancer.module.playback.NextAssetPreloadStatus
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/** Exact-version native asset warmup. Never creates a player/period, edits URLs or requests a license. */
internal class AppleMusicNextAssetPreloadTarget(
    private val build: TargetBuild,
    private val document: JSONObject?,
    private val loader: ClassLoader,
    private val enabled: () -> Boolean,
    private val register: (Method, ModernMethodHook, HookRegistrationScope) -> Boolean = { method, callback, scope ->
        ModernXposedRuntime.hookMethod(method, callback, scope)
    },
) : NextAssetPreloadTarget {
    private val scope = HookRegistrationScope()
    private val workers = Collections.synchronizedMap(WeakHashMap<Any, Worker>())
    private lateinit var c: Contract
    override fun install(): TargetCapabilityInstall {
        if (scope.isActive) return TargetCapabilityInstall.Active("Native next asset experiment already installed")
        if (build.packageName != "com.apple.android.music" || build.versionName != "7.0.0" || build.versionCode != 1614L || document == null) {
            NextAssetPreloadStatus.state = State.UNSUPPORTED
            return TargetCapabilityInstall.Unsupported("Native next asset experiment requires exact Apple Music 7.0.0/1614")
        }
        return try {
            c = Contract(document, loader)
            fun hook(method: Method, before: Boolean = false, action: (ModernMethodHook.MethodHookParam) -> Unit) {
                check(register(method, object : ModernMethodHook() {
                    private fun safely(param: MethodHookParam) {
                        runCatching { action(param) }.onFailure {
                            NextAssetPreloadStatus.state = State.FALLBACK
                            // No exception messages: native objects may contain signed URLs.
                            ModernXposedRuntime.log("Next asset experiment fell back: ${it.javaClass.simpleName}")
                        }
                    }
                    override fun beforeHookedMethod(param: MethodHookParam) { if (before) safely(param) }
                    override fun afterHookedMethod(param: MethodHookParam) { if (!before && param.throwable == null) safely(param) }
                }, scope))
            }
            hook(c.prepare) { p ->
                val owner = p.thisObject ?: return@hook
                workers.remove(owner)?.close()
                val handler = c.handler.get(owner) as? Handler ?: return@hook
                Worker(owner, handler).also { workers[owner] = it; it.start() }
            }
            hook(c.release, true) { p -> p.thisObject?.let { workers.remove(it)?.close() } }
            hook(c.request, true) { p ->
                if (!enabled()) return@hook
                val worker = synchronized(workers) { workers.values.firstOrNull { it.manager === p.thisObject && it.handler.looper === Looper.myLooper() } } ?: return@hook
                if (worker.ownRequest) return@hook
                val item = p.args[0] ?: return@hook
                val callback = p.args[1] ?: return@hook
                val snapshot = worker.selection() ?: return@hook
                val value = worker.warmup.take(item, snapshot.current, snapshot.next) { asset ->
                    c.validate.invoke(worker.manager, item, asset) == null
                }
                if (value == null) {
                    if (snapshot.next != null && p.args.getOrNull(2) == snapshot.next && snapshot.item === item) {
                        worker.warmup.nativeRequested(snapshot.current, snapshot.next, item)
                    }
                    return@hook
                }
                // Suppress a duplicate fetch only after validation. Native owns all subsequent audio/DRM work.
                c.complete.invoke(callback, item, value)
                p.result = null
            }
            scope.onClose { synchronized(workers) { workers.values.toList().forEach { it.close() }; workers.clear() } }
            scope.activate()
            TargetCapabilityInstall.Active("Switchable native next-asset requests, bounded to one next track; native DRM untouched")
        } catch (error: Exception) {
            scope.close(); NextAssetPreloadStatus.state = State.UNSUPPORTED
            TargetCapabilityInstall.Degraded("Native next asset contract unavailable: ${error.javaClass.simpleName}")
        }
    }

    private data class Selection(val current: Long, val next: Long?, val item: Any?)

    private inner class Worker(sourceOwner: Any, val handler: Handler) {
        private val owner = WeakReference(sourceOwner)
        val manager: Any = checkNotNull(c.manager.get(sourceOwner))
        private var closed = false
        private var sampling = false
        var ownRequest = false
        val warmup = NextAssetWarmup(SystemClock::elapsedRealtime, ::request) { NextAssetPreloadStatus.state = it }
        fun selection(): Selection? {
            check(handler.looper === Looper.myLooper())
            val source = owner.get() ?: return null
            val queue = c.queue.get(source) ?: return null
            val index = c.currentIndex.invoke(queue) as Int
            val count = c.count.invoke(queue) as Int
            if (index !in 0 until count) return null
            val current = c.at.invoke(queue, index) ?: return null
            val currentId = c.queueId.invoke(current) as Long
            val nextIndex = c.nextIndex.invoke(queue, index) as Int
            val next = if (nextIndex in 0 until count) c.at.invoke(queue, nextIndex) else null
            return Selection(currentId, next?.let { c.queueId.invoke(it) as Long }, next?.let { c.payload.invoke(it) })
        }
        private val tick = object : Runnable {
            override fun run() {
                if (closed) return
                if (owner.get() == null) { close(); return }
                try {
                    warmup.expire()
                    if (!enabled()) { warmup.update(null, 0, false, false); NextAssetPreloadStatus.state = State.WAITING }
                    else sample()
                } catch (_: Exception) { close(); NextAssetPreloadStatus.state = State.FALLBACK }
                if (!closed) handler.postDelayed(this, 1000)
            }
        }
        fun start() { handler.postDelayed(tick, 1000) }
        private fun sample() {
            if (sampling) return
            val source = owner.get() ?: run { close(); return }
            if (c.canNetwork.invoke(c.context.get(manager)) != true) { warmup.update(null, 0, false, false); return }
            val selected = selection() ?: run { warmup.clear(); return }
            val item = selected.item ?: run { warmup.clear(); return }
            val nextId = selected.next ?: return
            // A second queue occurrence of the same store ID must never cancel the currently playing request.
            val queue = c.queue.get(source)
            val currentItem = c.payload.invoke(c.at.invoke(queue, c.currentIndex.invoke(queue) as Int))
            if (c.storeId.invoke(currentItem) == c.storeId.invoke(item)) { warmup.clear(); return }
            val listener = c.listener.get(source) ?: return
            if (!c.playerOwner.isInstance(listener)) return
            val engine = c.player.get(listener) ?: return
            val applicationHandler = Handler(c.looper.invoke(engine) as Looper)
            val weakListener = WeakReference(listener)
            sampling = true
            applicationHandler.post {
                val sample = runCatching {
                    val host = weakListener.get() ?: return@runCatching null
                    val current = c.currentItem.invoke(host) ?: return@runCatching null
                    val nativeNextIndex = c.hostNextIndex.invoke(host) as Int
                    val nativeNext = if (nativeNextIndex >= 0) c.hostAt.invoke(host, nativeNextIndex) else null
                    if (c.queueId.invoke(current) != selected.current || nativeNext == null || c.queueId.invoke(nativeNext) != nextId) return@runCatching null
                    val playing = c.state.invoke(engine) == 3 && c.playing.invoke(engine) == true
                    val position = c.position.invoke(engine) as Long
                    val duration = c.duration.invoke(engine) as Long
                    if (position < 0 || duration <= 0) null else Pair(duration - position, playing)
                }.getOrNull()
                handler.post {
                    sampling = false
                    if (!closed) runCatching {
                        val latest = selection()
                        if (latest?.current != selected.current || latest.next != nextId || latest.item !== item || sample == null) warmup.clear()
                        else warmup.update(NextAssetWarmup.Candidate(selected.current, nextId, item), sample.first, enabled(), sample.second)
                    }.onFailure { close(); NextAssetPreloadStatus.state = State.FALLBACK }
                }
            }
        }
        private fun request(item: Any, queueId: Long, done: (Any?) -> Unit): () -> Unit {
            check(handler.looper === Looper.myLooper())
            check(c.canNetwork.invoke(c.context.get(manager)) == true)
            val callback = Proxy.newProxyInstance(loader, arrayOf(c.callback)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "LyricWaveNextAssetCallback"
                    "onAssetRequestComplete" -> { done(args?.get(1)); null }
                    "onAssetRequestError", "onAssetRequestCancelled" -> { done(null); null }
                    else -> null
                }
            }
            val stop: () -> Unit = {
                // Remove only OUR callback. Never cancel a request joined by a native consumer.
                synchronized(manager) {
                    @Suppress("UNCHECKED_CAST")
                    val lists = c.callbacks.get(manager) as Map<String, MutableList<Any>>
                    val key = c.key.invoke(null, item) as String
                    val callbacks = lists[key]
                    if (callbacks?.removeAll { it === callback } == true && callbacks.isEmpty()) c.cancel.invoke(manager, item)
                }
            }
            ownRequest = true
            try { c.request.invoke(manager, item, callback, queueId, true) }
            catch (error: Exception) { runCatching { stop() }; throw error }
            finally { ownRequest = false }
            return stop
        }
        fun close() {
            if (handler.looper !== Looper.myLooper()) { handler.post { close() }; return }
            if (closed) return
            closed = true; handler.removeCallbacks(tick); warmup.clear(); owner.clear()
        }
    }

    /** All private names live in the exact-version profile, not in product UI or feature code. */
    private class Contract(private val document: JSONObject, private val loader: ClassLoader) {
        private val types = document.getJSONObject("types")
        private fun type(key: String) = loader.loadClass(types.getString(key))
        private val source = type("source")
        private val managerType = type("manager")
        val callback = type("callback")
        private val item = type("item")
        private val queueItem = type("queueItem")
        private val queueType = type("queue")
        val playerOwner = type("playerOwner")
        private val engine = type("engine")
        private fun field(owner: Class<*>, key: String) = owner.getDeclaredField(document.getJSONObject("fields").getString(key)).apply { isAccessible = true }
        val handler = field(source, "handler").also { check(it.type == Handler::class.java) }
        val manager = field(source, "manager").also { check(it.type == managerType) }
        val queue = field(source, "queue").also { check(it.type == queueType) }
        val listener = field(source, "listener")
        val player = field(playerOwner, "player").also { check(it.type == engine) }
        val context = field(managerType, "context").also { check(it.type == type("context")) }
        val canNetwork = type("context").getMethod(document.getJSONObject("methods").getString("canNetwork")).also { check(it.returnType == Boolean::class.javaPrimitiveType) }
        val callbacks = field(managerType, "callbacks").also { check(Map::class.java.isAssignableFrom(it.type)) }
        private fun method(owner: Class<*>, key: String, result: Class<*>, vararg args: Class<*>) =
            owner.getDeclaredMethod(document.getJSONObject("methods").getString(key), *args).apply { check(returnType == result); isAccessible = true }
        val prepare = method(source,"prepare",Void.TYPE,type("transfer"))
        val release = method(source,"release",Void.TYPE)
        val request = method(managerType,"request",Void.TYPE,item,callback,Long::class.javaPrimitiveType!!,Boolean::class.javaPrimitiveType!!)
        val cancel = method(managerType,"cancel",Void.TYPE,item)
        val key = method(managerType,"key",String::class.java,item)
        val validate = method(managerType,"validate",java.io.IOException::class.java,item,type("asset"))
        val complete = callback.getMethod(document.getJSONObject("methods").getString("complete"),item,type("asset"))
        val currentIndex = method(queueType,"currentIndex",Int::class.javaPrimitiveType!!)
        val nextIndex = method(queueType,"nextIndex",Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!)
        val count = method(queueType,"count",Int::class.javaPrimitiveType!!)
        val at = method(queueType,"at",queueItem,Int::class.javaPrimitiveType!!)
        val queueId = queueItem.getMethod(document.getJSONObject("methods").getString("queueId"))
        val payload = queueItem.getMethod(document.getJSONObject("methods").getString("payload"))
        val storeId = item.getMethod(document.getJSONObject("methods").getString("storeId"))
        val currentItem = playerOwner.getMethod(document.getJSONObject("methods").getString("currentItem"))
        val hostNextIndex = playerOwner.getMethod(document.getJSONObject("methods").getString("hostNextIndex"))
        val hostAt = playerOwner.getMethod(document.getJSONObject("methods").getString("hostAt"),Int::class.javaPrimitiveType)
        val looper = engine.getMethod("getApplicationLooper")
        val state = engine.getMethod("getPlaybackState")
        val playing = engine.getMethod("getPlayWhenReady")
        val duration = engine.getMethod("getDuration")
        val position = engine.getMethod("getCurrentPosition")
    }
}
