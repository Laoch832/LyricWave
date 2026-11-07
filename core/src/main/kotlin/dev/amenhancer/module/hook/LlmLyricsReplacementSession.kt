package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LlmLyricsConfig
import java.util.concurrent.Executor

/** Immutable copied input; no host pointer, ViewModel or credentials are written to disk here. */
data class LlmLyricsRequest(
    val appleMusicId: Long,
    val originalTtml: String,
    val config: LlmLyricsConfig,
    val title: String? = null,
    val artist: String? = null,
)

/** App-owned network/storage wiring consumed through the existing automatic lyrics capability. */
data class LlmLyricsRuntime(
    val configuration: () -> LlmLyricsConfig?,
    val translate: (LlmLyricsRequest, isCancelled: () -> Boolean) -> String?,
    val executor: Executor,
    val close: () -> Unit = {},
    /** Notification after the current host has accepted the generated TTML. */
    val onPrepared: (LlmLyricsRequest, String) -> Unit = { _, _ -> },
    val onRejected: (LlmLyricsRequest) -> Unit = {},
)

/**
 * Selected lyric translation for one playback visit. The host must confirm source selection
 * before either disk cache or network is touched. Native pointers are retained
 * only for the current visit; each subsequent visit repeats source selection.
 */
class LlmLyricsReplacementSession(
    private val runtime: LlmLyricsRuntime,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val isAllowed: (Long) -> Boolean,
    private val onReplacementPublished: (Long) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var activeId: Long? = null
    private var generation = 0L
    private var sourceSelected = false
    private var original: String? = null
    private var configuration: LlmLyricsConfig? = null
    private var attempted = false
    private var ready: Any? = null
    private var closed = false

    fun onSongChanged(appleMusicId: Long?) = synchronized(lock) {
        if (closed || activeId == appleMusicId) return@synchronized
        activeId = appleMusicId
        sourceSelected = false
        invalidateLocked()
    }

    /** Opens translation for the verified document selected by the source priority policy. */
    fun onSourceSelected(appleMusicId: Long) = synchronized(lock) {
        if (!closed && activeId == appleMusicId) sourceSelected = true
    }

    fun onNativeSelected(appleMusicId: Long) = onSourceSelected(appleMusicId)

    fun onCacheChanged(appleMusicId: Long) = synchronized(lock) {
        if (!closed && activeId == appleMusicId) invalidateLocked()
    }

    /** Compatibility entry for callers that have not moved to explicit native selection. */
    fun onSourcesMissed(appleMusicId: Long) {
        val wake = synchronized(lock) {
            if (closed || activeId != appleMusicId || sourceSelected) false
            else { sourceSelected = true; true }
        }
        if (wake) onReplacementPublished(appleMusicId)
    }

    /** Revokes a result when the caller selects a different, untranslatable source. */
    fun onHigherPriorityResult(appleMusicId: Long) = synchronized(lock) {
        if (activeId == appleMusicId && (sourceSelected || original != null || ready != null)) {
            sourceSelected = false
            invalidateLocked()
        }
    }

    /** Missing exact selected input revokes old work without inventing a new source outcome. */
    fun onNativeInputUnavailable(appleMusicId: Long) {
        synchronized(lock) {
            if (activeId == appleMusicId) {
                sourceSelected = false
                invalidateLocked()
            }
        }
    }

    /** The host supplies raw TTML associated with its verified original pointer. */
    fun replacementFor(
        appleMusicId: Long,
        originalTtml: String,
        title: String? = null,
        artist: String? = null,
    ): Any? {
        val config = runtime.configuration()
        val allowed = config?.isConfigured == true && isAllowed(appleMusicId)
        synchronized(lock) {
            if (closed || appleMusicId <= 0L || activeId != appleMusicId) return null
            if (!allowed) {
                if (original != null || ready != null) invalidateLocked()
                return null
            }
            if (original != originalTtml || configuration != config) {
                invalidateLocked()
                original = originalTtml
                configuration = config
            }
            if (!sourceSelected) return null
            ready?.let { pointer ->
                if (runCatching { isAlive(pointer) }.getOrDefault(false)) return pointer
                // Rebuild a released native wrapper from the independent raw cache.
                ready = null
                attempted = false
            }
            if (attempted) return null
            attempted = true
            val stamp = generation
            val request = LlmLyricsRequest(appleMusicId, originalTtml, config, title, artist)
            runCatching { runtime.executor.execute { prepare(request, stamp) } }
                .onFailure {
                    // A busy worker has not attempted translation. Let the next host
                    // selection retry, including when updated lyrics arrived mid-request.
                    if (matchesLocked(request, stamp)) attempted = false
                }
            return null
        }
    }

    private fun prepare(request: LlmLyricsRequest, stamp: Long) {
        try {
            if (!isCurrent(request, stamp)) return
            val ttml = runtime.translate(request) { !isCurrent(request, stamp) } ?: return
            if (!isCurrent(request, stamp)) return
            val pointer = parseTtml(ttml) ?: run { runtime.onRejected(request); return }
            if (!isCurrent(request, stamp)) return
            if (readAdamId(pointer) != request.appleMusicId && !bindAdamId(pointer, request.appleMusicId)) {
                runtime.onRejected(request); return
            }
            if (!verifyPtr(pointer) || readAdamId(pointer) != request.appleMusicId) { runtime.onRejected(request); return }
            if (!isCurrent(request, stamp)) return
            runCatching { runtime.onPrepared(request, ttml) }
            if (!isCurrent(request, stamp)) return
            synchronized(lock) {
                if (!matchesLocked(request, stamp)) return
                ready = pointer
            }
            if (isCurrent(request, stamp)) onReplacementPublished(request.appleMusicId)
        } catch (_: Exception) {
            if (runCatching { isCurrent(request, stamp) }.getOrDefault(false)) runCatching { runtime.onRejected(request) }
            // A native binding failure must not reach the shared worker thread.
        }
    }

    private fun isCurrent(request: LlmLyricsRequest, stamp: Long): Boolean =
        synchronized(lock) { matchesLocked(request, stamp) } &&
            isAllowed(request.appleMusicId) && runtime.configuration() == request.config

    private fun matchesLocked(request: LlmLyricsRequest, stamp: Long): Boolean =
        !closed && generation == stamp && sourceSelected && activeId == request.appleMusicId &&
            original == request.originalTtml && configuration == request.config

    private fun invalidateLocked() {
        generation++
        original = null
        configuration = null
        ready = null
        attempted = false
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            sourceSelected = false
            activeId = null
            invalidateLocked()
        }
        runtime.close()
    }
}
