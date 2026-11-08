package dev.amenhancer.module.hook
import dev.amenhancer.module.ModuleConstants
internal class PlaybackStallRecoveryFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_PLAYBACK_STALL_RECOVERY
    override fun install(context: HookContext): FeatureInstallResult =
        if (context.config.settings().playbackStallRecoveryEnabled) context.target.playbackStall.install().toFeatureInstallResult()
        else FeatureInstallResult.disabled()
}
