package dev.amenhancer.module.hook

import dev.amenhancer.module.playback.NextAssetPreloadState as State

/** Confined to the native playback Looper. Opaque assets never leave the process or enter logs. */
internal class NextAssetWarmup(
    private val now: () -> Long,
    private val request: (Any, Long, (Any?) -> Unit) -> (() -> Unit),
    private val state: (State) -> Unit,
) {
    data class Candidate(val current: Long, val next: Long, val item: Any)
    private var candidate: Candidate? = null
    private var attempted: Pair<Long, Long>? = null
    private var generation = 0
    private var cancel: (() -> Unit)? = null
    private var asset: Any? = null
    private var expires = 0L
    private var started = 0L
    private var pending = false

    fun update(next: Candidate?, remainingMs: Long, enabled: Boolean, playing: Boolean) {
        if (!enabled || !playing || next == null || next.current == next.next) { clear(); attempted = null; state(State.WAITING); return }
        if (candidate?.let { it.current != next.current || it.next != next.next || it.item !== next.item } != false) {
            clear(); candidate = next
        }
        expire()
        if (remainingMs !in 1..60_000 || attempted == (next.current to next.next)) return
        attempted = next.current to next.next
        started = now(); pending = true
        val ticket = ++generation
        state(State.REQUESTING)
        try {
            val stop = request(next.item, next.next) { value ->
                expire()
                if (ticket == generation && pending) {
                    pending = false
                    asset = value; expires = now() + 90_000
                    state(if (value == null) State.FALLBACK else State.READY)
                }
            }
            if (ticket == generation) cancel = stop else stop()
        } catch (_: Exception) { clearPayload(); state(State.FALLBACK) }
    }

    /** One-shot native handover. A pending native request simply joins the native manager instead. */
    fun take(item: Any, currentQueueId: Long, nextQueueId: Long?, validate: (Any) -> Boolean): Any? {
        expire()
        val selected = candidate ?: return null
        if (selected.item !== item || currentQueueId != selected.current && currentQueueId != selected.next ||
            currentQueueId == selected.current && nextQueueId != selected.next) return null
        if (pending) {
            // Relinquish cancellation before the real consumer joins the shared native request.
            generation++; pending = false; cancel = null; candidate = null
            state(State.USED); return null
        }
        val result = asset ?: return null
        if (now() !in started..expires || !runCatching { validate(result) }.getOrDefault(false)) { clear(); state(State.FALLBACK); return null }
        asset = null; cancel = null; candidate = null; generation++
        state(State.USED)
        return result
    }
    /** Native lookahead already fetched this occurrence: do not issue a redundant warmup later. */
    fun nativeRequested(currentQueueId: Long, nextQueueId: Long, item: Any) {
        val pair = currentQueueId to nextQueueId
        if (attempted == pair) return
        clear(); candidate = Candidate(currentQueueId, nextQueueId, item); attempted = pair
        state(State.NATIVE)
    }
    /** Driven by the source Looper even while the application Looper is busy. */
    fun expire() {
        if (pending && now() - started !in 0..10_000) { clearPayload(); state(State.FALLBACK) }
        if (asset != null && now() !in started..expires) { clearPayload(); state(State.WAITING) }
    }
    private fun clearPayload() {
        generation++; pending = false; asset = null
        val stop = cancel; cancel = null
        runCatching { stop?.invoke() }
    }
    fun clear() { clearPayload(); candidate = null; state(State.WAITING) }
}
