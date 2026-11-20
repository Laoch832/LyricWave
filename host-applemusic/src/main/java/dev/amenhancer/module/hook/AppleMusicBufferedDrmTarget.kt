package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.amenhancer.module.playback.BufferedDrmRecoveryStatus
import dev.amenhancer.module.playback.BufferedPcmOutput
import dev.amenhancer.module.playback.DrmRoundTripRecovery
import dev.amenhancer.module.playback.PlaybackFailure
import dev.amenhancer.module.playback.PlaybackFailurePolicy
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

/** Exact-host, opt-in PCM output bridge. Native Apple code exclusively owns licensing and decoding. */
internal class AppleMusicBufferedDrmTarget(
    private val build: TargetBuild,
    private val document: JSONObject?,
    private val loader: ClassLoader,
    private val enabled: () -> Boolean,
    private val invokeNative: (Method, Any, Array<out Any?>) -> Any? = { method, target, args -> method.invoke(target, *args) },
    private val register: (Method, ModernMethodHook, HookRegistrationScope) -> Boolean = { method, hook, scope ->
        ModernXposedRuntime.hookMethod(method, hook, scope)
    },
) : PlaybackStallTarget {
    private val scope = HookRegistrationScope()
    private val sessions = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<AudioSession>>())
    private val armed = Collections.synchronizedMap(WeakHashMap<Any, AudioSession>())
    private val selectorConstraints = Collections.synchronizedMap(WeakHashMap<Any, AudioSession>())
    private val mutedRenderers = Collections.synchronizedMap(WeakHashMap<Any, AudioSession>())
    private data class CodecConfiguration(val native: WeakReference<Any>, val args: Array<out Any?>)
    private val codecConfigurations = Collections.synchronizedMap(WeakHashMap<Any, CodecConfiguration>())
    private val configuringCodec = ThreadLocal<Any?>()
    private fun sinkField(renderer: Any): Field? = when {
        field("codecSink").declaringClass.isInstance(renderer) -> field("codecSink")
        field("sink").declaringClass.isInstance(renderer) -> field("sink")
        else -> null
    }
    private fun copyArguments(args: Array<out Any?>) = args.map { if (it is IntArray) it.clone() else it }.toTypedArray()
    private fun unmute(audio: AudioSession) {
        synchronized(mutedRenderers) { mutedRenderers.keys.toList().forEach { if (mutedRenderers[it] === audio) mutedRenderers.remove(it) } }
    }
    private val pendingStarts = Collections.synchronizedMap(WeakHashMap<Any, AudioSession>())
    private val lastAttempt = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val workers = Collections.synchronizedMap(WeakHashMap<Any, Worker>())
    private lateinit var c: FragmentProfileMembers
    private fun method(role: String) = c.method(role)
    private fun field(role: String) = c.field(role)
    private fun call(role: String, target: Any, vararg args: Any?): Any? = invoke(method(role), target, args)
    private fun invoke(method: Method, target: Any, args: Array<out Any?>): Any? = try { invokeNative(method, target, args) }
        catch (error: InvocationTargetException) { throw (error.cause ?: error) }

    override fun install(): TargetCapabilityInstall {
        if (scope.isActive) return TargetCapabilityInstall.Active("Buffered native recovery installed")
        val contract = document?.takeIf { it.matchesPlaybackBuild(build) }
            ?: return TargetCapabilityInstall.Unsupported("No verified buffered recovery contract for this Apple Music build")
        return try {
            c = FragmentProfileMembers(loader, contract, "bufferedDrmRecovery", FragmentMemberRequirements(METHODS, FIELDS))
            fun hook(role: String, before: Boolean, action: (ModernMethodHook.MethodHookParam) -> Unit) {
                check(register(method(role), object : ModernMethodHook() {
                    private fun safely(p: MethodHookParam) {
                        runCatching { action(p) }.onFailure {
                            ModernXposedRuntime.log("Buffered native recovery callback failed: ${it.javaClass.simpleName}")
                        }
                    }
                    override fun beforeHookedMethod(param: MethodHookParam) { if (before) safely(param) }
                    override fun afterHookedMethod(param: MethodHookParam) { if (!before && param.throwable == null) safely(param) }
                }, scope))
            }
            fun attach(renderer: Any, existing: Any? = null) {
                if (!enabled() || sessions[renderer]?.get() != null) return
                val binding = sinkField(renderer) ?: return
                val original = binding.get(renderer) ?: return
                if (!field("nativeInput").declaringClass.isInstance(original)) return
                val handler = Handler(checkNotNull(Looper.myLooper()))
                val codec = binding == field("codecSink")
                val session = AudioSession(renderer, original, handler, codec)
                if (existing != null && !(if (codec) session.attachCodecExisting(existing as Array<out Any?>) else session.attachExisting(existing))) return
                binding.set(renderer, session.proxy)
                sessions[renderer] = WeakReference(session)
                session.begin()
            }
            hook("initializeSink", true) { p -> p.thisObject?.let { attach(it) } }
            hook("render", true) { p ->
                val renderer = p.thisObject ?: return@hook
                sessions[renderer]?.get()?.renderFailure()?.let { failure ->
                    p.throwable = failure; return@hook
                }
                if (enabled() && sessions[renderer]?.get() == null) {
                    field("inputFormat").get(renderer)?.let { attach(renderer, it) }
                }
            }
            // Capture the exact native configure arguments even while the experiment is off. This
            // allows live opt-in without applying encoder trimming or channel mapping a second time.
            check(register(method("codecFormat"), object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val renderer = param.thisObject ?: return
                    configuringCodec.set(renderer)
                    runCatching { attach(renderer) }.onFailure {
                        ModernXposedRuntime.log("Buffered codec attachment failed: ${it.javaClass.simpleName}")
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) { configuringCodec.remove() }
            }, scope))
            hook("nativeConfigure", false) { p ->
                val renderer = configuringCodec.get() ?: return@hook
                val original = p.thisObject ?: return@hook
                if (field("codecSink").get(renderer) === original)
                    codecConfigurations[renderer] = CodecConfiguration(WeakReference(original), copyArguments(p.args))
            }
            hook("codecRender", true) { p ->
                val renderer = p.thisObject ?: return@hook
                if (!field("codecSink").declaringClass.isInstance(renderer)) return@hook
                sessions[renderer]?.get()?.renderFailure()?.let { p.throwable = it; return@hook }
                if (enabled() && sessions[renderer]?.get() == null) {
                    val original = field("codecSink").get(renderer) ?: return@hook
                    val saved = codecConfigurations[renderer]?.takeIf { it.native.get() === original } ?: return@hook
                    if (field("nativePendingConfig").get(original) == null) attach(renderer, saved.args)
                }
            }
            // All three fatal catch branches call stopInternal(resetRenderers, false, false),
            // including source IO exceptions which never call the renderer error-message helper.
            // User stop acknowledges the operation (third argument true); release uses resetInternal.
            // Preserve for at most 1.5s pending error classification; no next/back until confirmed DRM.
            hook("fatal", true) { p ->
                if (!enabled()) return@hook
                val internal = p.thisObject ?: return@hook
                if (p.args.getOrNull(1) != false || p.args.getOrNull(2) != false) return@hook
                val time = SystemClock.elapsedRealtime()
                if (lastAttempt[internal]?.let { time < it || time - it < 120000 } == true) return@hook
                if (field("internalWants").getBoolean(internal).not()) return@hook
                val fade = field("fadeControl").get(internal) ?: return@hook
                if (call("fadeEnabled", fade) == true) return@hook
                val nativeRenderers = (field("internalEnabled").get(internal) as Array<*>).filterNotNull()
                val types = nativeRenderers.map { call("rendererType", it) as Int }
                if (2 in types) return@hook // Videos use additional output paths.
                val audio = nativeRenderers.filterIndexed { index, _ -> types[index] == 1 }
                val renderer = audio.singleOrNull() ?: return@hook
                val session = sessions[renderer]?.get() ?: return@hook
                if (session.arm()) armed[internal] = session
            }
            hook("chooseRenderer", false) { p ->
                val held = selectorConstraints[p.thisObject] ?: return@hook
                val capabilities = p.args[0] as Array<*>
                val chosen = p.result as? Int ?: return@hook
                val selected = capabilities.getOrNull(chosen) ?: return@hook
                if (call("capType", selected) != 1) return@hook
                p.result = capabilities.size
                // The temporary item may use a different codec. Its renderer decodes under normal
                // native control, but its sink cannot produce audio. Only the original item must
                // return to the exact held renderer so its physical clock and reserve remain valid.
                val index = if (held.restoringOriginal) held.rendererIndex else chosen
                if (index !in held.permittedRenderers) return@hook
                val renderer = capabilities.getOrNull(index) ?: return@hook
                if (call("capType", renderer) != 1) return@hook
                val group = p.args[1] ?: return@hook
                val supported = (0 until field("groupLength").getInt(group)).any { n ->
                    val format = call("groupFormat", group, n) ?: return@any false
                    ((call("capSupport", renderer, format) as Int) and 7) >= 3
                }
                if (supported) p.result = index
            }
            hook("hostError", false) { p ->
                val host = p.thisObject ?: return@hook
                val error = p.args.firstOrNull() as? Throwable ?: return@hook
                val kind = failure(error)
                val engine = field("hostPlayer").get(host) ?: return@hook
                val internal = field("engineInternal").get(engine) ?: return@hook
                val provisional = armed.remove(internal)
                workers[host]?.let { existing ->
                    existing.controller.fault(kind == PlaybackFailure.DRM, kind == PlaybackFailure.AUTHORIZATION)
                    if (kind != PlaybackFailure.DRM) existing.audio.abort()
                    return@hook
                }
                if (!enabled() || kind != PlaybackFailure.DRM) { provisional?.abort(); pendingStarts.remove(host)?.abort(); return@hook }
                val session = provisional ?: return@hook
                val handler = Handler(call("engineLoop", engine) as Looper)
                pendingStarts[host] = session
                // Let Apple's error callback finish before making a native next/back request.
                handler.post { runCatching {
                    if (pendingStarts.remove(host) !== session) return@runCatching
                    if (call("engineState", engine) != 1 || call("engineError", engine) !== error) {
                        session.finish(cancelled = true, pause = call("engineWants", engine) != true)
                        return@runCatching
                    }
                    if (!scope.isActive || workers[host] != null || !enabled()) { session.abort(); return@runCatching }
                    val time = SystemClock.elapsedRealtime()
                    if (lastAttempt[internal]?.let { time < it || time - it < 120000 } == true) { session.abort(); return@runCatching }
                    val worker = Worker(host, engine, session, handler)
                    workers[host] = worker
                    if (worker.begin()) { lastAttempt[internal] = time } else { workers.remove(host); session.abort() }
                }.onFailure {
                    workers.remove(host); session.abort()
                    ModernXposedRuntime.log("Buffered recovery start failed: ${it.javaClass.simpleName}")
                } }
            }
            hook("hostVolume", true) { p ->
                val host = p.thisObject ?: return@hook
                val engine = field("hostPlayer").get(host) ?: return@hook
                val value = (p.args.firstOrNull() as? Float)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return@hook
                (field("engineRenderers").get(engine) as Array<*>).forEach { sessions[it]?.get()?.userVolume(value) }
            }
            for (role in USER_ACTIONS) hook(role, true) { p ->
                val host = p.thisObject ?: return@hook
                pendingStarts.remove(host)?.finish(cancelled = true, pause = role in setOf("hostPause", "hostStop", "hostRelease"))
                val worker = workers[host]
                worker?.userAction(keepOriginal = role in setOf("hostPause", "hostSeek"), seekMs = if (role == "hostSeek") p.args.firstOrNull() as? Long else null)
                // Also handle a pause/release arriving between fatal teardown and the error callback.
                val engine = field("hostPlayer").get(host)
                engine?.let { armed.remove(field("engineInternal").get(it))?.finish() }
                if (role == "hostRelease" && engine != null) closeEngine(engine)
            }
            for (role in listOf("enginePlayWhen", "enginePlayWhen2")) hook(role, true) { p ->
                if (p.args.firstOrNull() != false) return@hook
                synchronized(workers) { workers.values.toList() }.filter { it.engine === p.thisObject }.forEach {
                    it.userAction(keepOriginal = true)
                }
                synchronized(pendingStarts) { pendingStarts.keys.toList() }.filter { field("hostPlayer").get(it) === p.thisObject }.forEach {
                    pendingStarts.remove(it)?.finish(cancelled = true, pause = true)
                }
                p.thisObject?.let { armed.remove(field("engineInternal").get(it))?.finish() }
            }
            hook("engineRelease", true) { p -> p.thisObject?.let(::closeEngine) }
            scope.onClose {
                synchronized(workers) { workers.values.toList() }.forEach { it.disable() }
                synchronized(sessions) { sessions.values.mapNotNull { it.get() } }.forEach { it.close() }
                workers.clear(); sessions.clear(); armed.clear(); pendingStarts.clear(); lastAttempt.clear(); selectorConstraints.clear(); mutedRenderers.clear(); codecConfigurations.clear(); configuringCodec.remove()
                BufferedDrmRecoveryStatus.available = false; BufferedDrmRecoveryStatus.bufferedMs = 0
            }
            scope.activate()
            BufferedDrmRecoveryStatus.available = true
            TargetCapabilityInstall.Active("Opt-in forward PCM bridge and bounded native next/back recovery; exact 1614 only")
        } catch (error: Exception) {
            scope.close()
            TargetCapabilityInstall.Degraded("Buffered native recovery contract unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun failure(error: Throwable): PlaybackFailure {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        // Native numeric subscription/revocation errors take precedence over a generic DRM wrapper.
        val component = chain.firstNotNullOfOrNull { item ->
            if (item.javaClass.simpleName !in setOf("DrmException", "DrmLicenseKeyException")) null else runCatching {
                item.javaClass.getMethod("getComponentErrorCode").invoke(item) as? Int
            }.getOrNull()
        }
        val http = chain.firstNotNullOfOrNull { item ->
            if (item.javaClass.simpleName != "InvalidResponseCodeException") null
            else runCatching { item.javaClass.getField("responseCode").getInt(item) }.getOrNull()
        }
        return PlaybackFailurePolicy.classify(0, error, http, component)
    }
    private fun closeEngine(engine: Any) {
        synchronized(pendingStarts) { pendingStarts.keys.toList() }.filter { field("hostPlayer").get(it) === engine }.forEach {
            pendingStarts.remove(it)?.finish(cancelled = true, pause = true)
        }
        synchronized(workers) { workers.values.toList() }.filter { it.engine === engine }.forEach { it.userAction() }
        lastAttempt.remove(field("engineInternal").get(engine))
        val renderers = field("engineRenderers").get(engine) as Array<*>
        renderers.forEach { renderer -> sessions.remove(renderer)?.get()?.close() }
        armed.remove(field("engineInternal").get(engine))?.close()
    }

    private inner class AudioSession(renderer: Any, private val native: Any, val handler: Handler, private val codec: Boolean) {
        private val owner = WeakReference(renderer)
        private var adopting = field("nativeInput").get(native) as? ByteBuffer
        private val adoptingPts = if (!codec) field("pendingTs").getLong(renderer) else {
            val info = field("codecOutputInfo").get(renderer) as android.media.MediaCodec.BufferInfo
            val last = field("codecLastInput").getLong(renderer)
            if (field("codecEosWorkaround").getBoolean(renderer) && info.presentationTimeUs == 0L && info.flags and 4 != 0 && last >= 0) last
            else info.presentationTimeUs
        }
        val rendererIndex = call("rendererIndex", renderer) as Int
        @Volatile var permittedRenderers = emptySet<Int>()
        @Volatile var restoringOriginal = true
        @Volatile private var siblingPcm = false
        private var closed = false
        private var scheduled = false
        private var running = call("rendererState", renderer) == 2 && mutedRenderers[renderer] == null
        private var heldTeardown = false
        private var streamTrack: Long? = null
        private var output: BufferedPcmOutput? = null
        private var pendingConfiguration: Array<out Any?>? = null
        private var configured: Array<out Any?>? = null
        private var latestConfiguration: Array<out Any?>? = null
        private var writeFailure: Throwable? = null
        private var leaseUntil = 0L
        private var originalGain = 1f
        private var settingsAt = 0L
        private var desiredBuffering = true
        @Volatile var held: BufferedPcmOutput.Hold? = null; private set
        @Volatile var audibleMs: Long? = null; private set
        @Volatile var fresh = false; private set
        @Volatile var next = false; private set
        @Volatile var failed = false; private set
        @Volatile var bufferedMs = 0L; private set
        val proxy: Any = Proxy.newProxyInstance(loader, arrayOf(field("sink").type)) { self, m, args ->
            when (m.name) {
                "equals" -> self === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "LyricWavePcmOutput"
                else -> dispatch(m, args ?: emptyArray())
            }
        }
        private fun native(role: String, vararg args: Any?) = call(role, native, *args)
        private fun updateStream() {
            val renderer = owner.get() ?: return
            val stream = call("stream", renderer)
            val id = if (stream != null && method("asset").declaringClass.isInstance(stream)) {
                call("asset", stream)?.let { call("assetStore", it) as Long }
            } else null
            streamTrack = id
            output?.stream(id, field("offset").getLong(renderer))
        }
        private fun format(args: Array<out Any?>): BufferedPcmOutput.Format? {
            val bytes = when (args[0] as Int) { 2 -> 2; 0x20000000 -> 3; 0x30000000 -> 4; else -> return null }
            val channels = args[1] as Int; val rate = args[2] as Int
            if (channels !in 1..8 || rate !in 8000..192000) return null
            return BufferedPcmOutput.Format(rate, channels, bytes)
        }
        private fun configure(args: Array<out Any?>) {
            latestConfiguration = copyArguments(args)
            val value = format(args)
            val out = output
            if (held != null && out != null) {
                if (streamTrack != held?.track) return
                if (args.contentDeepEquals(configured)) return
                // A changed format cannot be spliced into preserved PCM. Revert to native preparation,
                // which is already seeking the original audible position, instead of playing bad data.
                failed = true; held = null; out.finishHold(); out.reset()
            }
            val copy = copyArguments(args)
            if (out != null && !out.isDrained) { pendingConfiguration = copy; schedule(); return }
            applyConfiguration(copy, value)
        }
        private fun applyConfiguration(args: Array<out Any?>, value: BufferedPcmOutput.Format? = format(args)) {
            if (value == null) { output?.release(); output = null }
            invoke(method("sinkConfigure"), native, args)
            configured = args; pendingConfiguration = null
            if (value == null) return
            prepareOutput(value, existing = false)
        }
        private fun prepareOutput(value: BufferedPcmOutput.Format, existing: Boolean) {
            val created = output == null
            val out = output ?: BufferedPcmOutput(object : BufferedPcmOutput.Sink {
                override fun write(buffer: ByteBuffer, presentationUs: Long) = native("sinkHandleBuffer", buffer, presentationUs) as Boolean
                override fun positionUs(sourceEnded: Boolean) = (native("sinkGetCurrentPositionUs", sourceEnded) as Long).takeUnless { it < 0 }
                override fun play() { native("sinkPlay") }
                override fun pause() { native("sinkPause") }
                override fun flush() { native("sinkFlush") }
                override fun reset() { native("sinkReset") }
                override fun end() { native("sinkPlayToEndOfStream") }
                override fun pending() = native("sinkHasPendingData") as Boolean
                override fun ended() = native("sinkIsEnded") as Boolean
            }).also { output = it }
            updateStream(); check(out.configure(value))
            if (created) {
                if (existing) out.adoptPlayingState(running) else if (running) out.play()
            }
            adopting?.let { pending ->
                if (field("nativeInput").get(native) === pending) out.adoptNativeWrite(pending, adoptingPts)
                adopting = null
            }
            desiredBuffering = enabled(); settingsAt = SystemClock.elapsedRealtime() + 250
            if (desiredBuffering) out.enable() else out.disable()
        }
        fun attachExisting(input: Any): Boolean {
            val renderer = owner.get() ?: return false
            val channels = field("channels").getInt(renderer)
            val rate = field("rate").getInt(renderer)
            val encoding = when (field("bits").getInt(renderer)) { 16 -> 2; 24 -> 0x20000000; 32 -> 0x30000000; else -> return false }
            if (channels !in 1..8 || rate !in 8000..192000) return false
            // Mirror only the verified PCM format contract, without configuring the native sink again.
            // Reconfiguring an active AAC trimmer would apply encoder delay twice during live opt-in.
            val delay = field("encoderDelay").getInt(input).coerceAtLeast(0).let {
                if (it == 0 && field("mime").get(input) == "audio/mp4a-latm") 2112 else it
            }
            val padding = field("encoderPadding").getInt(input).coerceAtLeast(0)
            val args = arrayOf<Any?>(encoding, channels, rate, 0, null, delay, padding)
            configured = args
            prepareOutput(checkNotNull(format(args)), existing = true)
            return true
        }
        fun attachCodecExisting(args: Array<out Any?>): Boolean {
            val value = format(args) ?: return false
            configured = copyArguments(args); latestConfiguration = configured
            prepareOutput(value, existing = true)
            return true
        }
        fun begin() {
            if (owner.get()?.let { mutedRenderers[it] }?.let { it !== this } == true) {
                output?.pause() ?: native("sinkPause"); running = false
            } else if (running) schedule()
        }
        private fun dispatch(m: Method, args: Array<out Any?>): Any? {
            check(handler.looper === Looper.myLooper())
            if (closed) return invoke(m, native, args)
            updateStream()
            val out = output
            val heldOwner = owner.get()?.let { mutedRenderers[it] }?.takeUnless { it === this }
            if (heldOwner != null) {
                when (m) {
                    method("sinkHandleBuffer") -> {
                        if (streamTrack != heldOwner.held?.track) { heldOwner.siblingPcm = true; heldOwner.next = true }
                        return false // No copy, no native write and no temporary next-track AudioTrack.
                    }
                    method("sinkPlay") -> { out?.pause() ?: native("sinkPause"); running = false; return null }
                    method("sinkGetCurrentPositionUs") -> return owner.get()?.let { field("offset").getLong(it) } ?: 0L
                    method("sinkIsEnded") -> return false
                }
            }
            return when (m) {
                method("sinkConfigure") -> { configure(args); null }
                method("sinkHandleBuffer") -> {
                    writeFailure?.let { throw it }
                    try {
                        if (pendingConfiguration != null && out?.nativeWritePending != true) false
                        else out?.offer(args[0] as ByteBuffer, args[1] as Long) ?: invoke(m, native, args)
                    } catch (error: Throwable) {
                        failed = true; writeFailure = error; throw error
                    }
                }
                method("sinkPause") -> { if (held == null) running = false; out?.pause() ?: invoke(m, native, args); null }
                method("sinkPlay") -> { running = true; heldTeardown = false; out?.play() ?: invoke(m, native, args); schedule(); null }
                method("sinkReset") -> { if (held != null) heldTeardown = true; if (held == null) { running = false; writeFailure = null; failed = false }; out?.reset() ?: invoke(m, native, args); reportBuffer(); null }
                method("sinkFlush") -> { if (held != null) heldTeardown = true; out?.flush() ?: invoke(m, native, args); if (held == null) writeFailure = null; reportBuffer(); null }
                method("sinkPlayToEndOfStream") -> { out?.requestEnd() ?: invoke(m, native, args); schedule(); null }
                method("sinkHasPendingData") -> out?.pending ?: invoke(m, native, args)
                method("sinkIsEnded") -> (out == null || out.isDrained) && native("sinkIsEnded") == true
                method("sinkGetCurrentPositionUs") -> out?.positionUs(args[0] as Boolean) ?: invoke(m, native, args)
                method("sinkSetVolume") -> if (held != null && streamTrack != held?.track) null else invoke(m, native, args)
                method("sinkHandleDiscontinuity"), method("sinkSetAudioSessionId") -> if (held == null) invoke(m, native, args) else null
                else -> invoke(m, native, args)
            }.also { if (running) schedule() }
        }
        private val pump = Runnable {
            scheduled = false
            if (!closed) {
                try {
                    val out = output
                    val now = SystemClock.elapsedRealtime()
                    val refreshStatus = now >= settingsAt
                    if (refreshStatus) { desiredBuffering = enabled(); settingsAt = now + 250 }
                    if (!desiredBuffering) out?.disable() else out?.enable()
                    if (held != null && now >= leaseUntil) abortOnAudio()
                    out?.pump()
                    if (out?.isDrained != false) pendingConfiguration?.let { applyConfiguration(it) }
                    held = out?.hold
                    if (held != null) audibleMs = out?.audibleOriginalUs()?.div(1000)
                    fresh = out?.freshOriginalPcm == true; next = out?.nextPcmSeen == true || siblingPcm
                    if (held == null && heldTeardown && out?.pending == false) { out.pause(); running = false }
                    bufferedMs = (out?.bufferedUs ?: 0) / 1000
                    if (refreshStatus || !running) reportBuffer()
                } catch (error: Throwable) {
                    failed = true; writeFailure = error; running = false
                    ModernXposedRuntime.log("Buffered audio output stopped: ${error.javaClass.simpleName}")
                }
                if (running) schedule()
            }
        }
        private fun reportBuffer() {
            bufferedMs = (output?.bufferedUs ?: 0) / 1000
            BufferedDrmRecoveryStatus.bufferedMs = synchronized(sessions) {
                sessions.values.mapNotNull { it.get()?.takeUnless { it.closed }?.bufferedMs }.maxOrNull() ?: 0
            }
        }
        private fun schedule() { if (!closed && !scheduled) { scheduled = true; handler.postDelayed(pump, 10) } }
        fun arm(): Boolean {
            check(handler.looper === Looper.myLooper())
            if (failed || writeFailure != null) return false
            updateStream()
            val renderer = owner.get() ?: return false
            originalGain = if (!codec && field("soundCheck").getBoolean(renderer)) field("gain").getFloat(renderer) else 1f
            if (!originalGain.isFinite() || originalGain < 0) return false
            val value = output?.arm() ?: return false
            held = value; audibleMs = value.audibleUs / 1000; fresh = false; next = false
            leaseUntil = SystemClock.elapsedRealtime() + 1500
            schedule(); return true
        }
        fun renderFailure(): Throwable? {
            val error = writeFailure ?: return null
            val renderer = owner.get() ?: return null
            // A Handler callback must never crash the audio Looper or leave the renderer spinning.
            // Re-enter Exo's normal exception handling on its next render call, with no error content logged.
            return call("unexpected", renderer, IllegalStateException("Buffered PCM output failed: ${error.javaClass.simpleName}")) as Throwable
        }
        fun prepareNextHop() {
            next = false; siblingPcm = false
            if (handler.looper === Looper.myLooper()) output?.resetNextPcmSignal()
            else handler.post { output?.resetNextPcmSignal(); next = false }
        }
        fun userVolume(value: Float) {
            handler.post { if (!closed && held != null) native("sinkSetVolume", value * originalGain) }
        }
        fun confirm() { handler.post { if (held != null) leaseUntil = SystemClock.elapsedRealtime() + 23000 } }
        private fun finishOnAudio(cancelled: Boolean = false, pause: Boolean = false) {
            val original = held?.track
            updateStream()
            output?.finishHold(); held = null
            if (cancelled && streamTrack != original) {
                output?.reset(); running = false
                // A native selection may already have adopted the temporary next song. Its initial
                // configure was deferred during the bridge; rebuild using its actual renderer format.
                val renderer = owner.get()
                val value = renderer?.let { field(if (codec) "codecInputFormat" else "inputFormat").get(it) }
                if (renderer != null && value != null) runCatching {
                    if (codec) latestConfiguration?.let { applyConfiguration(it) }
                    else call("initializeSink", renderer, value)
                    if (!pause && call("rendererState", renderer) == 2) { output?.play(); running = true; schedule() }
                }.onFailure { failed = true; writeFailure = it; running = false }
            }
            else if (pause) { output?.pause(); running = false }
            reportBuffer()
        }
        fun finish(cancelled: Boolean = false, pause: Boolean = false) {
            if (handler.looper === Looper.myLooper()) finishOnAudio(cancelled, pause)
            else handler.post { finishOnAudio(cancelled, pause) }
        }
        private fun abortOnAudio() {
            audibleMs = output?.audibleOriginalUs()?.div(1000) ?: audibleMs
            output?.abortOutput(); held = null; pendingConfiguration = null
            running = false; failed = true; reportBuffer()
        }
        fun abort() {
            if (handler.looper === Looper.myLooper()) abortOnAudio() else handler.post { abortOnAudio() }
        }
        fun close() { handler.post { closed = true; handler.removeCallbacks(pump); output?.release(); output = null; held = null; reportBuffer() } }
    }

    private data class UserAction(val keepOriginal: Boolean, val seekMs: Long?)

    private inner class Worker(host: Any, val engine: Any, val audio: AudioSession, private val handler: Handler) {
        private val owner = WeakReference(host)
        private val selector = checkNotNull(field("selector").get(host))
        private val originalContentId = audio.held?.track
        private val originalId = call("hostCurrent", host)?.let { call("queueId", it) as Long }
        private val pendingUserAction = AtomicReference<UserAction?>(null)
        private fun applyUserAction(action: UserAction) {
            if (action.seekMs != null) controller.userSeek(action.seekMs)
            else controller.userAction(action.keepOriginal)
        }
        private fun drainUserAction() { pendingUserAction.getAndSet(null)?.let(::applyUserAction) }
        fun userAction(keepOriginal: Boolean = false, seekMs: Long? = null) {
            val action = UserAction(keepOriginal, seekMs)
            if (handler.looper === Looper.myLooper()) applyUserAction(action)
            else {
                // A selection/queue edit wins; otherwise preserve the latest explicit seek target.
                pendingUserAction.accumulateAndGet(action) { old, new ->
                    when {
                        old?.keepOriginal == false || new?.keepOriginal == false -> UserAction(false, null)
                        new?.seekMs != null -> new
                        else -> old ?: new
                    }
                }
                handler.post { drainUserAction() }
            }
        }
        fun disable() {
            if (handler.looper === Looper.myLooper()) { drainUserAction(); controller.disable() }
            else handler.post { drainUserAction(); controller.disable() }
        }
        val controller = DrmRoundTripRecovery(object : DrmRoundTripRecovery.Port {
            override fun snapshot(): DrmRoundTripRecovery.Snapshot? {
                val host = owner.get() ?: return null
                val item = call("hostCurrent", host) ?: return null
                val queue = call("hostQueue", host) ?: return null
                val id = call("queueId", item) as Long
                val nextIndex = call("hostNext", host) as Int
                val nextItem = if (nextIndex >= 0) call("hostAt", host, nextIndex) else null
                val media = call("payload", item)
                val content = media?.let { call("itemStore", it) }
                val next = nextItem?.takeIf { candidate ->
                    val nextMedia = call("payload", candidate)
                    val nextContent = nextMedia?.let { call("itemStore", it) }
                    media != null && nextMedia != null && call("itemType", media) == 1 && call("itemType", nextMedia) == 1 &&
                        content != null && nextContent != null && content != nextContent
                }
                val position = audio.audibleMs ?: return null
                val wants = field("hostWants").getBoolean(host) || field("hostPending").getBoolean(host)
                return DrmRoundTripRecovery.Snapshot(call("queueUuid", queue).toString(), id,
                    next?.let { call("queueId", it) as Long }, position, wants)
            }
            override fun holdOutput(originalId: Long): Boolean {
                val host = owner.get() ?: return false
                val item = call("hostCurrent", host) ?: return false
                if (call("queueId", item) != originalId) return false
                val media = call("payload", item) ?: return false
                val content = (call("itemStore", media) as? String)?.toLongOrNull() ?: return false
                return (content > 0 && audio.held?.track == content && !audio.failed).also { if (it) audio.confirm() }
            }
            override fun audiblePositionMs() = audio.audibleMs
            override fun select(queueId: Long): Boolean {
                if (queueId != originalId && audio.held?.track != originalContentId) return false
                audio.restoringOriginal = queueId == originalId
                if (queueId != originalId) audio.prepareNextHop()
                return owner.get()?.let { call("hostSelect", it, queueId) == true } ?: false
            }
            override fun seek(positionMs: Long) {
                owner.get()?.let { host ->
                    call("hostSeek", host, positionMs) // Preserves native pending seek during preparation.
                    val id = originalId ?: return@let
                    val index = call("hostQueueIndex", host, id) as Int
                    // Native canSeek can be false during an asynchronous queue transition. Explicit
                    // window+position masking keeps this seek even before the new timeline arrives.
                    if (index >= 0) call("engineSeek", engine, index, positionMs)
                }
            }
            override fun play() { owner.get()?.let { call("hostPlay", it) } }
            override fun nextReady() = audio.next
            override fun refilled() = audio.held?.track == originalContentId && audio.fresh && !audio.failed
            override fun clearTemporaryCache(originalId: Long): Boolean {
                val host = owner.get() ?: return false
                val item = call("hostCurrent", host) ?: return false
                if (call("engineState", engine) != 1 || call("queueId", item) != originalId || audio.failed || audio.held?.track != originalContentId) return false
                val media = call("payload", item) ?: return false
                if (call("downloaded", media) != false) return false
                call("hostRemoveCache", host, media)
                return true
            }
            override fun finishOutput(result: DrmRoundTripRecovery.Stage) {
                selectorConstraints.remove(selector); unmute(audio)
                BufferedDrmRecoveryStatus.stage = result; BufferedDrmRecoveryStatus.lastResult = result
                val host = owner.get()
                val paused = host == null || (!field("hostWants").getBoolean(host) && !field("hostPending").getBoolean(host))
                if (result == DrmRoundTripRecovery.Stage.COMPLETE) audio.finish(pause = paused)
                else audio.abort()
            }
        }, SystemClock::elapsedRealtime)
        private val tick = object : Runnable {
            override fun run() {
                try {
                    drainUserAction()
                    val internal = field("engineInternal").get(engine)
                    val fade = internal?.let { field("fadeControl").get(it) }
                    if (fade != null && call("fadeEnabled", fade) == true) { audio.abort(); controller.disable() }
                    else if (!enabled()) controller.disable()
                    else if (audio.failed || audio.held == null) controller.fault(false, false)
                    else controller.tick()
                } catch (error: Exception) {
                    controller.disable()
                    ModernXposedRuntime.log("Buffered recovery polling stopped: ${error.javaClass.simpleName}")
                }
                BufferedDrmRecoveryStatus.stage = controller.stage
                if (controller.active) handler.postDelayed(this, 100)
                else owner.get()?.let { if (workers[it] === this@Worker) workers.remove(it) }
            }
        }
        fun begin(): Boolean {
            val internal = field("engineInternal").get(engine) ?: return false
            val fade = field("fadeControl").get(internal) ?: return false
            if (call("fadeEnabled", fade) == true) return false
            val renderers = (field("engineRenderers").get(engine) as Array<*>).filterNotNull().filter { sinkField(it) != null }
            audio.permittedRenderers = renderers.map { call("rendererIndex", it) as Int }.toSet()
            renderers.forEach { mutedRenderers[it] = audio }
            selectorConstraints[selector] = audio
            if (!controller.start(true, false)) { selectorConstraints.remove(selector); unmute(audio); return false }
            BufferedDrmRecoveryStatus.stage = controller.stage
            handler.postDelayed(tick, 100)
            return true
        }
    }
    companion object {
        private val USER_ACTIONS = setOf("hostPause", "hostStop", "hostRelease", "hostSeek", "hostSelect", "hostIndex", "hostChangeBy",
            "hostResetQueue", "hostRemoveItem", "hostMoveItem", "hostAddItems", "hostPrepare", "hostPrepare2", "hostPrepare4")
        private val METHODS = USER_ACTIONS + setOf("codecRender", "codecFormat", "nativeConfigure", "hostQueueIndex", "engineSeek", "unexpected", "render", "initializeSink", "fatal", "hostError", "hostPlay", "hostVolume", "hostCurrent", "hostQueue", "hostNext", "hostAt", "hostRemoveCache",
            "queueId", "payload", "downloaded", "queueUuid", "engineLoop", "engineError", "engineState", "engineWants", "engineRelease", "enginePlayWhen", "enginePlayWhen2", "rendererState", "rendererIndex", "rendererType", "fadeEnabled", "chooseRenderer", "capType", "capSupport", "groupFormat", "itemType", "stream", "asset", "assetStore", "itemStore",
            "sinkConfigure", "sinkDisableTunneling", "sinkEnableTunnelingV21", "sinkFlush", "sinkGetCurrentPositionUs", "sinkGetPlaybackParameters", "sinkHandleBuffer", "sinkHandleDiscontinuity", "sinkHasPendingData", "sinkIsEnded", "sinkPause", "sinkPlay", "sinkPlayToEndOfStream", "sinkReset", "sinkSetAudioAttributes", "sinkSetAudioSessionId", "sinkSetAuxEffectInfo", "sinkSetListener", "sinkSetPlaybackParameters", "sinkSetVolume", "sinkSupportsOutput")
        private val FIELDS = setOf("codecSink", "codecInputFormat", "codecOutputInfo", "codecEosWorkaround", "codecLastInput", "nativePendingConfig", "selector", "groupLength", "fadeControl", "soundCheck", "gain", "rate", "channels", "bits", "encoderDelay", "encoderPadding", "mime", "nativeInput", "pendingTs", "inputFormat", "sink", "offset", "hostPlayer", "hostWants", "hostPending", "engineInternal", "engineRenderers", "internalEnabled", "internalWants")
    }
}
