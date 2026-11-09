package dev.amenhancer.module.update

import kotlin.math.ceil

data class UpdateTransferRate(val bytesPerSecond: Long? = null, val remainingSeconds: Long? = null)

/** Monotonic, recent transfer rate; retries and stalls must not keep an old ETA. */
class UpdateTransferRateSampler {
    private var anchorTime = -1L
    private var anchorBytes = 0L
    private var lastTime = -1L
    private var lastBytes = 0L
    private var lastMovement = -1L
    private var rate: Long? = null
    fun sample(bytes: Long, total: Long, now: Long): UpdateTransferRate {
        if (anchorTime < 0 || now < lastTime || bytes < lastBytes) {
            anchorTime = now; anchorBytes = bytes; lastMovement = now; rate = null
        }
        if (bytes > lastBytes) lastMovement = now
        val elapsed = now - anchorTime
        if (elapsed >= 1000 && bytes > anchorBytes) {
            rate = ((bytes - anchorBytes).toDouble() * 1000 / elapsed).toLong().takeIf { it > 0 }
            anchorTime = now; anchorBytes = bytes
        }
        if (now - lastMovement >= 4000) { rate = null; anchorTime = now; anchorBytes = bytes }
        lastTime = now; lastBytes = bytes
        return UpdateTransferRate(rate, rate?.takeIf { total > 0 && bytes in 0..total }?.let {
            ceil((total - bytes).toDouble() / it).toLong()
        })
    }
}
