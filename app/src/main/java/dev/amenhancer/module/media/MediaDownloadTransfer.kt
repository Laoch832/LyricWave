package dev.amenhancer.module.media

import java.util.concurrent.atomic.AtomicBoolean

internal enum class MediaDownloadPhase {
    READY, AUTHENTICATING, FETCHING_STREAMS, FETCHING_DECRYPTION_KEYS,
    DOWNLOADING, MUXING, TAGGING, PUBLISHING, COMPLETED, CANCELLED, FAILED
}

internal data class MediaDownloadSnapshot(
    val phase: MediaDownloadPhase = MediaDownloadPhase.READY,
    val title: String = "",
    val codec: String = "",
    val bytes: Long = 0,
    val total: Long = -1,
    val errorCode: String = "",
    val outputName: String = "",
)

/** The service and activity both run in LyricWave's private app process. */
internal object MediaDownloadTransfer {
    @Volatile var snapshot = MediaDownloadSnapshot()
    val running = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
}
