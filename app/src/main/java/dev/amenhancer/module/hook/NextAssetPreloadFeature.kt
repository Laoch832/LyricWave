package dev.amenhancer.module.hook
import dev.amenhancer.module.ModuleConstants
/** Hooks remain dormant while disabled, so the experiment can be switched without restarting. */
internal class NextAssetPreloadFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_NEXT_ASSET_PRELOAD
    override fun install(context: HookContext): FeatureInstallResult = context.target.nextAssetPreload.install().toFeatureInstallResult()
}
