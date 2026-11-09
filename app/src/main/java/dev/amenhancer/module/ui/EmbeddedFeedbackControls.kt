package dev.amenhancer.module.ui

import android.app.Activity
import android.os.Bundle
import androidx.compose.runtime.*
import dev.amenhancer.module.feedback.FeedbackRuntime
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.ModuleSettings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun EmbeddedSettingsHost.EmbeddedFeedbackControls(activity: Activity, settings: ModuleSettings,
    onEdit: ((ModuleSettings) -> ModuleSettings) -> Unit) {
    val state by FeedbackRuntime.status.collectAsState()
    val scope = rememberCoroutineScope()
    EmbeddedSettingsGroup {
        EmbeddedSettingSwitch(localizedText(ModuleText.FEEDBACK_AUTOMATIC), localizedText(ModuleText.FEEDBACK_PRIVACY),
            settings.automaticFeedbackEnabled, onCheckedChange = { enabled ->
                onEdit { it.copy(automaticFeedbackEnabled = enabled) }
                FeedbackRuntime.changed(enabled)
                scope.launch { runCatching { updateTransferCommand(activity, "feedback_config", Bundle().apply { putBoolean("enabled", enabled) }) } }
            })
        EmbeddedSettingsNotice(localizedText(ModuleText.FEEDBACK_QUEUED, state.count))
        if (state.outsideWindow > 0) EmbeddedSettingsNotice(localizedText(ModuleText.FEEDBACK_OUTSIDE_WINDOW, state.outsideWindow))
        if (state.lastSuccess > 0) EmbeddedSettingsNotice(localizedText(ModuleText.FEEDBACK_ACKNOWLEDGED,
            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(state.lastSuccess))))
        state.receipt?.let { receipt ->
            TextButton(localizedText(ModuleText.FEEDBACK_COPY_RECEIPT), onClick = {
                (activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("LyricWave feedback receipt", receipt.json()))
            })
        }
        if (state.failed) EmbeddedSettingsNotice(localizedText(ModuleText.FEEDBACK_FAILED))
        TextButton(localizedText(if (state.sending) ModuleText.FEEDBACK_SENDING else ModuleText.FEEDBACK_SEND), enabled = !state.sending && state.count > 0,
            onClick = { FeedbackRuntime.sendNow() })
        TextButton(localizedText(ModuleText.FEEDBACK_VIEW), onClick = {
            val rows = FeedbackRuntime.recent()
            showEmbeddedMiuixDialog(activity, localizedText(ModuleText.FEEDBACK_VIEW)) { dismiss ->
                TextButton(localizedText(ModuleText.FEEDBACK_COPY), enabled = rows.isNotEmpty(), onClick = {
                    (activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("LyricWave diagnostics", FeedbackRuntime.exportRecords(rows)))
                })
                rows.forEach { row ->
                    Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.optLong("at")))} · ${row.optString("category")}")
                    Text("${row.optString("hostVersion")} · ${row.optJSONObject("codes")}\n${row.optJSONArray("exceptions")}")
                }
                EmbeddedDialogActions(localizedText(ModuleText.CLOSE), dismiss)
            }
        })
        TextButton(localizedText(ModuleText.FEEDBACK_CLEAR), enabled = state.total > 0, onClick = { FeedbackRuntime.clear() })
    }
}
