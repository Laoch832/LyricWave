package dev.amenhancer.module.update

/** Wall-clock persistence with clock rollback recovery; no timers or per-track polling. */
object UpdateCheckSchedule {
    const val SUCCESS_INTERVAL = 6 * 60 * 60 * 1000L
    const val FAILURE_INTERVAL = 15 * 60 * 1000L
    fun due(now: Long, attempted: Long, succeeded: Long): Boolean {
        if (attempted <= 0 || now < attempted) return true
        val interval = if (succeeded >= attempted) SUCCESS_INTERVAL else FAILURE_INTERVAL
        return now - attempted >= interval
    }
}
