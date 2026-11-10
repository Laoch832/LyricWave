package dev.amenhancer.module.playback

/** Process-local, content-free state for the experimental settings page. */
enum class NextAssetPreloadState { WAITING, REQUESTING, READY, USED, NATIVE, FALLBACK, UNSUPPORTED }
object NextAssetPreloadStatus {
    @Volatile var state = NextAssetPreloadState.WAITING
}
