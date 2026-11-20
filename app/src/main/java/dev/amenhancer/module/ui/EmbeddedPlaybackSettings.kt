package dev.amenhancer.module.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.playback.BufferedDrmRecoveryStatus
import dev.amenhancer.module.playback.DrmRoundTripRecovery.Stage
import dev.amenhancer.module.playback.NextAssetPreloadState
import dev.amenhancer.module.playback.NextAssetPreloadStatus
import kotlinx.coroutines.delay

@Composable
internal fun EmbeddedSettingsHost.EmbeddedPlaybackSettings(
    settings: ModuleSettings,
    active: Boolean,
    onEdit: ((ModuleSettings) -> ModuleSettings) -> Unit,
) {
    var bufferedMs by remember { mutableStateOf(BufferedDrmRecoveryStatus.bufferedMs) }
    var stage by remember { mutableStateOf(BufferedDrmRecoveryStatus.stage) }
    var preloadState by remember { mutableStateOf(NextAssetPreloadStatus.state) }
    LaunchedEffect(active) {
        while (active) {
            bufferedMs = BufferedDrmRecoveryStatus.bufferedMs
            stage = BufferedDrmRecoveryStatus.stage
            preloadState = NextAssetPreloadStatus.state
            delay(500)
        }
    }
    val bufferedSupported = BufferedDrmRecoveryStatus.available
    val preloadSupported = preloadState != NextAssetPreloadState.UNSUPPORTED
    val recoveryMessage = when (stage) {
        Stage.NEXT -> ModuleText.BUFFERED_DRM_NEXT
        Stage.REFILL -> ModuleText.BUFFERED_DRM_REFILL
        Stage.COMPLETE -> ModuleText.BUFFERED_DRM_COMPLETE
        Stage.CANCELLED -> ModuleText.BUFFERED_DRM_CANCELLED
        Stage.FAILED -> ModuleText.BUFFERED_DRM_FAILED
        else -> ModuleText.BUFFERED_DRM_WAITING
    }
    val preloadMessage = when (preloadState) {
        NextAssetPreloadState.REQUESTING -> ModuleText.PRELOAD_REQUESTING
        NextAssetPreloadState.READY -> ModuleText.PRELOAD_READY
        NextAssetPreloadState.USED -> ModuleText.PRELOAD_USED
        NextAssetPreloadState.NATIVE -> ModuleText.PRELOAD_NATIVE
        NextAssetPreloadState.FALLBACK -> ModuleText.PRELOAD_FALLBACK
        NextAssetPreloadState.UNSUPPORTED -> ModuleText.PRELOAD_UNSUPPORTED
        NextAssetPreloadState.WAITING -> ModuleText.PRELOAD_WAITING
    }
    val locale = LocalConfiguration.current.locales[0]
    EmbeddedSettingsSection(localizedText(ModuleText.SETTINGS_RECOVERY_GROUP))
    EmbeddedSettingsGroup {
        EmbeddedSettingSwitch(localizedText(ModuleText.PLAYBACK_RECOVERY), localizedText(ModuleText.PLAYBACK_RECOVERY_SUMMARY),
            checked = settings.playbackStallRecoveryEnabled, icon = EmbeddedSvgIcon.RestoreDefault,
            onCheckedChange = { enabled -> onEdit { it.copy(playbackStallRecoveryEnabled = enabled) } })
        EmbeddedSettingSwitch(localizedText(ModuleText.BUFFERED_DRM_RECOVERY),
            summary = localizedText(if (bufferedSupported) ModuleText.BUFFERED_DRM_RECOVERY_SUMMARY else ModuleText.PRELOAD_UNSUPPORTED),
            checked = bufferedSupported && settings.experimentalBufferedDrmRecoveryEnabled,
            enabled = bufferedSupported, icon = EmbeddedSvgIcon.StartupPrewarm,
            onCheckedChange = { enabled -> onEdit { it.copy(experimentalBufferedDrmRecoveryEnabled = enabled) } })
        if (bufferedSupported && settings.experimentalBufferedDrmRecoveryEnabled) {
            EmbeddedSettingsNotice(localizedText(ModuleText.BUFFERED_DRM_STATE,
                String.format(locale, "%.1f", bufferedMs / 1000.0), localizedText(recoveryMessage)))
        }
    }
    EmbeddedSettingsSection(localizedText(ModuleText.SETTINGS_PRELOAD_GROUP))
    EmbeddedSettingsGroup {
        EmbeddedSettingSwitch(localizedText(ModuleText.NEXT_ASSET_PRELOAD),
            summary = localizedText(if (preloadSupported) ModuleText.NEXT_ASSET_PRELOAD_SUMMARY else ModuleText.PRELOAD_UNSUPPORTED),
            checked = preloadSupported && settings.experimentalNextAssetPreloadEnabled,
            enabled = preloadSupported, icon = EmbeddedSvgIcon.Music,
            onCheckedChange = { enabled -> onEdit { it.copy(experimentalNextAssetPreloadEnabled = enabled) } })
        if (preloadSupported && settings.experimentalNextAssetPreloadEnabled) {
            EmbeddedSettingsNotice(localizedText(ModuleText.NEXT_ASSET_PRELOAD_STATE, localizedText(preloadMessage)))
        }
    }
}
