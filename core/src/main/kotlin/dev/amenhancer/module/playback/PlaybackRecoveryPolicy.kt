package dev.amenhancer.module.playback

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class PlaybackFailure { NETWORK, HTTP_TRANSIENT, AUTHORIZATION, DRM_OR_RENDERER, OTHER }
enum class PlaybackRecoveryAction { OBSERVED, RETRY_REQUESTED, READY }
data class PlaybackRecoveryReport(val failure: PlaybackFailure, val action: PlaybackRecoveryAction, val httpStatus: Int?)
object PlaybackRecoveryStatus {
    @Volatile var available: Boolean? = null
    @Volatile var latest: PlaybackRecoveryReport? = null
    @Volatile var nativeState: Int? = null
}

/** Never infer an error from a buffering spinner or force playback while paused. */
object PlaybackFailurePolicy {
    fun classify(type: Int, cause: Throwable?, httpStatus: Int?): PlaybackFailure {
        if (type == 1) return PlaybackFailure.DRM_OR_RENDERER
        if (type != 0) return PlaybackFailure.OTHER
        val chain = generateSequence(cause) { it.cause }.take(8).toList()
        if (httpStatus == 401 || httpStatus == 403 || chain.any {
            it.javaClass.simpleName in setOf("PersistentKeyLoaderException", "ErrorConditionException", "NetworkConnectionDeniedException", "DrmException")
        }) return PlaybackFailure.AUTHORIZATION
        if (httpStatus != null) return if (httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599)
            PlaybackFailure.HTTP_TRANSIENT else PlaybackFailure.OTHER
        return if (chain.any { it is SocketTimeoutException || it is SocketException || it is UnknownHostException || it is EOFException })
            PlaybackFailure.NETWORK else PlaybackFailure.OTHER
    }
    fun recoverable(failure: PlaybackFailure) = failure == PlaybackFailure.NETWORK || failure == PlaybackFailure.HTTP_TRANSIENT
}

/** One automatic recovery per queue item; explicit play/seek attempts are rate limited too. */
class PlaybackRetryBudget(private val now: () -> Long, private val cooldownMs: Long = 10_000) {
    private val autoAttempted = LinkedHashSet<String>()
    private var lastAttempt: Long? = null
    @Synchronized fun claim(itemKey: String, automatic: Boolean): Boolean {
        val time = now()
        if (automatic && itemKey in autoAttempted) return false
        if (lastAttempt?.let { time < it || time - it < cooldownMs } == true) return false
        lastAttempt = time
        if (automatic) {
            autoAttempted += itemKey
            while (autoAttempted.size > 32) autoAttempted.remove(autoAttempted.first())
        }
        return true
    }
}
