package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

internal class BufferedDrmRecoveryFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_BUFFERED_DRM_RECOVERY
    // Install capability hooks once; the default-off setting is read live on the native runtime path.
    override fun install(context: HookContext) = context.target.bufferedPlayback.install().toFeatureInstallResult()
}
