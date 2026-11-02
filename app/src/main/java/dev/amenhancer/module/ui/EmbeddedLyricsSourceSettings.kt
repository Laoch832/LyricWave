package dev.amenhancer.module.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.ModuleSettings
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun EmbeddedSettingsHost.EmbeddedSourceMirrorSetting(settings: ModuleSettings, onChanged: (String) -> Unit) {
    val currentOnChanged by rememberUpdatedState(onChanged)
    EmbeddedSettingsGroup {
        EmbeddedSettingLink(localizedText(ModuleText.CUSTOM_LYRICS_SOURCE_BASE_URL),
            settings.customLyricsSourceBaseUrl.ifBlank { localizedText(ModuleText.CUSTOM_LYRICS_SOURCE_BASE_URL_NOT_CONFIGURED) },
            EmbeddedSvgIcon.Document,
        ) {
            currentActivity()?.let { activity ->
                showEmbeddedMiuixDialog(activity, localizedText(ModuleText.CUSTOM_LYRICS_SOURCE_BASE_URL)) { dismiss ->
                    var input by remember { mutableStateOf(settings.customLyricsSourceBaseUrl) }
                    var invalid by remember { mutableStateOf(false) }
                    EmbeddedSettingsNotice(localizedText(ModuleText.CUSTOM_LYRICS_SOURCE_BASE_URL_SUMMARY))
                    TextField(value = input, onValueChange = { input = it; invalid = false }, singleLine = true,
                        label = localizedText(ModuleText.CUSTOM_LYRICS_SOURCE_BASE_URL_HINT),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    if (invalid) EmbeddedSettingsNotice(localizedText(ModuleText.CUSTOM_LYRICS_SOURCE_BASE_URL_INVALID))
                    EmbeddedDialogActions(localizedText(ModuleText.CANCEL), dismiss, localizedText(ModuleText.SAVE)) {
                        val normalized = ModuleSettings.normalizeCustomLyricsSourceBaseUrl(input)
                        if (input.isNotBlank() && normalized.isEmpty()) invalid = true
                        else { currentOnChanged(normalized); dismiss() }
                    }
                }
            }
        }
    }
}
