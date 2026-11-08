package dev.amenhancer.module.playback

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class PlaybackFailure { NETWORK, HTTP_TRANSIENT, AUTHORIZATION, DRM, RENDERER, UNEXPECTED, REMOTE, OTHER }
enum class PlaybackRecoveryAction { OBSERVED, RETRY_REQUESTED, READY }
data class PlaybackRecoveryReport(val failure: PlaybackFailure, val action: PlaybackRecoveryAction, val httpStatus: Int?, val diagnostic: String? = null,
    val nativeErrorCode: Int? = null, val componentErrorCode: Int? = null)
object PlaybackRecoveryStatus {
    @Volatile var rendererRefreshAvailable: Boolean? = null
    @Volatile var available: Boolean? = null
    @Volatile var latest: PlaybackRecoveryReport? = null
    @Volatile var nativeState: Int? = null
    @Volatile var currentFaultActive: Boolean? = null
    @Volatile var observedAtElapsedMs: Long? = null
}

/** Never infer an error from a buffering spinner or force playback while paused. */
object PlaybackFailurePolicy {
    fun classify(type: Int, cause: Throwable?, httpStatus: Int?, componentErrorCode: Int? = null): PlaybackFailure {
        val chain = generateSequence(cause) { it.cause }.take(8).toList()
        val names = chain.map { it.javaClass.simpleName }.toSet()
        // Native DrmException constants: item revoked, subscription key missing, subscription expired.
        if (componentErrorCode in setOf(-42584, -42585, -42587) || httpStatus == 401 || httpStatus == 403 || names.any {
            it in setOf("PersistentKeyLoaderException", "ErrorConditionException", "NetworkConnectionDeniedException",
                "DeniedByServerException", "NotProvisionedException", "KeysExpiredException")
        }) return PlaybackFailure.AUTHORIZATION
        if (names.any { it.contains("Drm", ignoreCase = true) || it in setOf("ResourceBusyException", "DrmException") })
            return PlaybackFailure.DRM
        if (type == 1) return PlaybackFailure.RENDERER
        if (type == 2) return PlaybackFailure.UNEXPECTED
        if (type == 3) return PlaybackFailure.REMOTE
        if (type != 0) return PlaybackFailure.OTHER
        if (httpStatus != null) return if (httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599)
            PlaybackFailure.HTTP_TRANSIENT else PlaybackFailure.OTHER
        return if (chain.any { it is SocketTimeoutException || it is SocketException || it is UnknownHostException || it is EOFException })
            PlaybackFailure.NETWORK else PlaybackFailure.OTHER
    }
    /** Class identities only: never persist exception messages, URLs, credentials or license bodies. */
    fun diagnostic(type: Int, cause: Throwable?): String = "T$type:" +
        generateSequence(cause) { it.cause }.take(8).map { it.javaClass.simpleName }
            .filter { it.matches(Regex("[A-Za-z0-9_$]{1,100}")) }.distinct().joinToString(" > ").ifEmpty { "unknown" }

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
