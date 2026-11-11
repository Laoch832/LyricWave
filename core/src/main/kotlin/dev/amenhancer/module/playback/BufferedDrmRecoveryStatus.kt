package dev.amenhancer.module.playback

/** Aggregate live status only. No media identifiers, content, URLs or key material. */
object BufferedDrmRecoveryStatus {
    @Volatile var available = false
    @Volatile var bufferedMs = 0L
    @Volatile var stage = DrmRoundTripRecovery.Stage.IDLE
    @Volatile var lastResult: DrmRoundTripRecovery.Stage? = null
}
