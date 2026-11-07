package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

internal class LyricsHdrFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_LYRICS_HDR
    override fun install(context: HookContext): FeatureInstallResult =
        if (context.config.settings().lyricsHdrEnabled) context.target.lyricsHdr.install().toFeatureInstallResult()
        else FeatureInstallResult.disabled()
}

internal class WechatMomentsFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_WECHAT_MOMENTS
    override fun install(context: HookContext): FeatureInstallResult =
        if (context.config.settings().wechatMomentsShareEnabled) context.target.wechatMoments.install().toFeatureInstallResult()
        else FeatureInstallResult.disabled()
}
