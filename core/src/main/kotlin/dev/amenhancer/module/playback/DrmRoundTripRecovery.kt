package dev.amenhancer.module.playback

/** One audio-buffer-backed native next/back transaction. All calls belong to the player's Looper. */
class DrmRoundTripRecovery(private val port: Port, private val now: () -> Long) {
    data class Snapshot(val queue: String, val current: Long, val next: Long?, val positionMs: Long, val wantsPlay: Boolean)
    enum class Stage { IDLE, NEXT, REFILL, COMPLETE, CANCELLED, FAILED }
    interface Port {
        fun snapshot(): Snapshot?
        /** Must preserve the already decoded output. False means this device/format cannot bridge. */
        fun holdOutput(originalId: Long): Boolean
        fun audiblePositionMs(): Long?
        fun select(queueId: Long): Boolean
        fun seek(positionMs: Long)
        fun play()
        fun nextReady(): Boolean
        /** True only on fresh decoded original-song PCM past the preserved tail, not native READY. */
        fun refilled(): Boolean
        /** Evict this song's verified temporary streaming cache; never downloads, accounts or DRM keys. */
        fun clearTemporaryCache(originalId: Long): Boolean
        fun finishOutput(result: Stage)
    }
    var stage = Stage.IDLE; private set
    var attempts = 0; private set
    private var original: Snapshot? = null
    private var since = 0L
    private var started = 0L
    private var lastStarted: Long? = null
    private var internal = false
    val active: Boolean get() = stage == Stage.NEXT || stage == Stage.REFILL

    fun start(confirmedDrm: Boolean, authorizationDenied: Boolean): Boolean = guarded(false) {
        val t = now()
        if (!confirmedDrm || authorizationDenied || active || lastStarted?.let { t < it || t - it < 120_000 } == true) return false
        val s = port.snapshot() ?: return false
        if (!s.wantsPlay || s.positionMs < 0 || s.next == null || s.next == s.current) return false
        if (!port.holdOutput(s.current)) return false
        original = s; started = t; lastStarted = t; attempts = 1
        return hop()
    }
    /** Called before explicit user pause/seek/queue replacement/release, never for our own native calls. */
    fun userAction(keepOriginal: Boolean = false) {
        if (!internal && active) {
            end(Stage.CANCELLED, restore = keepOriginal)
        }
    }
    fun userSeek(positionMs: Long) {
        if (!internal && active) end(Stage.CANCELLED, restore = true, seekMs = positionMs.coerceAtLeast(0))
    }
    fun disable() { if (active) end(Stage.CANCELLED, restore = true, resume = true) }
    fun tick(): Unit = guarded(Unit) {
        if (!active) return
        val t = now()
        val s = original ?: return end(Stage.FAILED)
        val live = port.snapshot()
        if (t < since || t < started || live == null || live.queue != s.queue || !live.wantsPlay) {
            end(Stage.CANCELLED); return
        }
        if (t - started >= 22_000) { end(Stage.FAILED); return }
        val expected = if (stage == Stage.NEXT) s.next else s.current
        if (live.current != expected) { end(Stage.CANCELLED); return }
        if (stage == Stage.NEXT && (port.nextReady() || t - since >= 1500)) {
            returnOriginal()
        } else if (stage == Stage.REFILL) {
            if (port.refilled()) end(Stage.COMPLETE)
            else if (t - since >= 8000) retryOrFail()
        }
    }
    /** A second confirmed DRM error advances Plan B immediately; unrelated errors terminate. */
    fun fault(confirmedDrm: Boolean, authorizationDenied: Boolean): Unit = guarded(Unit) {
        if (!active) return
        if (!confirmedDrm || authorizationDenied) {
            end(Stage.FAILED)
        } else if (stage == Stage.NEXT) returnOriginal()
        else retryOrFail()
    }
    private fun returnOriginal() {
        val s = original ?: return end(Stage.FAILED)
        val position = port.audiblePositionMs() ?: s.positionMs
        stage = Stage.REFILL; since = now()
        native {
            if (!port.select(s.current)) return@native end(Stage.FAILED)
            port.seek(position.coerceAtLeast(s.positionMs))
            port.play()
        }
    }
    private fun retryOrFail() {
        val s = original ?: return end(Stage.FAILED)
        if (attempts != 1) { end(Stage.FAILED); return }
        val cleared = native { port.clearTemporaryCache(s.current) }
        if (!cleared) { end(Stage.FAILED); return }
        attempts = 2; hop()
    }
    private fun hop(): Boolean {
        val s = original ?: return false
        stage = Stage.NEXT; since = now()
        native {
            if (!port.select(checkNotNull(s.next))) { end(Stage.FAILED); return@native }
            port.play() // An IDLE native player needs prepare, not only an index edit.
        }
        return active
    }
    private inline fun <T> native(block: () -> T): T {
        val previous = internal; internal = true
        try { return block() } finally { internal = previous }
    }
    private inline fun <T> guarded(default: T, block: () -> T): T = try { block() } catch (_: Exception) {
        if (original != null) end(Stage.FAILED)
        default
    }
    private fun restoreOriginal(s: Snapshot? = original, play: Boolean, seekMs: Long? = null) {
        if (s == null) return
        runCatching {
            val live = port.snapshot()
            if (live?.queue == s.queue && (live.current == s.next || live.current == s.current) && (live.wantsPlay || !play)) native {
                if (live.current == s.current || port.select(s.current)) {
                    port.seek(seekMs ?: (port.audiblePositionMs() ?: s.positionMs).coerceAtLeast(s.positionMs))
                    if (play) port.play()
                }
            }
        }
    }
    private fun end(result: Stage, restore: Boolean = result == Stage.FAILED, resume: Boolean = false, seekMs: Long? = null) {
        val s = original
        stage = result; original = null
        // An internal failure must not strand playback on the temporary next song. Never restore over
        // an explicit user action, a replacement queue, a new selection, or a changed play intention.
        // Queue audio-thread cleanup before any native prepare/seek messages. No untracked extra
        // autoplay after failure; disabling may hand the original song back to native playback.
        runCatching { port.finishOutput(result) }
        if (restore && s != null) restoreOriginal(s, play = resume && s.wantsPlay, seekMs = seekMs)
    }
}
