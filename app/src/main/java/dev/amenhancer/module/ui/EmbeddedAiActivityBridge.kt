package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.playback.*

/** Keep the tested journal/cache controls and their attachment lifecycle inside the Miuix navigation. */
@Composable
internal fun EmbeddedSettingsHost.EmbeddedAiActivityPage(activity: Activity, settings: ModuleSettings) {
    key(settings) {
        AndroidView(modifier = Modifier.fillMaxWidth(), factory = {
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                renderEmbeddedAiLyricsActivityPage(activity, this, settings)
            }
        })
    }
}

@Composable
internal fun EmbeddedSettingsHost.EmbeddedAiLyricsSummary(activity: Activity, settings: ModuleSettings, onOpen: () -> Unit) {
    key(settings) {
        AndroidView(modifier = Modifier.fillMaxWidth(), factory = {
            LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addAiLyricsSummary(activity, this, onOpen) }
        })
    }
}

@Composable
internal fun EmbeddedSettingsHost.EmbeddedPlaybackDiagnostic(activity: Activity) {
    val report = PlaybackRecoveryStatus.latest
    val fault = when (report?.failure) {
        PlaybackFailure.NETWORK -> ModuleText.PLAYBACK_NETWORK
        PlaybackFailure.HTTP_TRANSIENT -> ModuleText.PLAYBACK_HTTP
        PlaybackFailure.AUTHORIZATION -> ModuleText.PLAYBACK_AUTH
        PlaybackFailure.DRM -> ModuleText.PLAYBACK_DRM_ONLY
        PlaybackFailure.RENDERER -> ModuleText.PLAYBACK_RENDERER
        PlaybackFailure.UNEXPECTED -> ModuleText.PLAYBACK_UNEXPECTED
        PlaybackFailure.REMOTE -> ModuleText.PLAYBACK_REMOTE
        PlaybackFailure.OTHER -> ModuleText.PLAYBACK_OTHER
        null -> ModuleText.PLAYBACK_NO_FAULT
    }
    val action = when (report?.action) {
        PlaybackRecoveryAction.OBSERVED -> ModuleText.PLAYBACK_OBSERVED
        PlaybackRecoveryAction.RETRY_REQUESTED -> ModuleText.PLAYBACK_RETRY
        PlaybackRecoveryAction.READY -> ModuleText.PLAYBACK_READY
        null -> ModuleText.PLAYBACK_NO_FAULT
    }
    val text = if (PlaybackRecoveryStatus.available == false) localizedText(ModuleText.PLAYBACK_RECOVERY_UNAVAILABLE)
        else localizedText(ModuleText.PLAYBACK_RECOVERY_STATUS, PlaybackRecoveryStatus.nativeState ?: "—",
            localizedText(fault), localizedText(action), report?.httpStatus ?: "—") + (report?.diagnostic?.let { "\n$it" } ?: "")
    SelectionContainer(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { EmbeddedSettingsNotice(text) }
}

// Native controls used only by the journal panel; the rest of the settings use Miuix preferences.
internal fun EmbeddedSettingsHost.embeddedCard(activity: Activity, title: String?, content: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        bindEmbeddedTheme(activity) { colors ->
            background = EmbeddedG3Drawable().apply {
                setColor(if (colors.isDark) 0xFF242428.toInt() else 0xFFF5F5F7.toInt())
                cornerRadius = dp(activity, 16).toFloat()
            }
        }
        title?.let { addView(TextView(activity).apply { text = it; textSize = 18f; setEmbeddedTextColor(activity) { it.onSurface } }) }
        content()
    }

internal fun EmbeddedSettingsHost.embeddedActionButton(activity: Activity, label: String, onClick: () -> Unit): Button =
    Button(embeddedThemedContext(activity)).apply {
        text = label; isAllCaps = false
        bindEmbeddedTheme(activity) { colors ->
            backgroundTintList = ColorStateList.valueOf(colors.primary); setTextColor(colors.onPrimary)
        }
        setOnClickListener { onClick() }
    }

internal fun EmbeddedSettingsHost.embeddedNavigationRow(activity: Activity, title: String, summary: String, onClick: () -> Unit): View =
    LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10))
        addView(TextView(activity).apply { text = title; textSize = 16f; setEmbeddedTextColor(activity) { it.onSurface } })
        addView(TextView(activity).apply { text = summary; textSize = 13f; setEmbeddedTextColor(activity) { it.onSurfaceVariant } })
        isClickable = true; isFocusable = true; bindEmbeddedClickFeedback(activity)
        setOnClickListener { onClick() }
    }

internal fun EmbeddedSettingsHost.embeddedDialogBuilder(activity: Activity): AlertDialog.Builder =
    object : AlertDialog.Builder(embeddedThemedContext(activity)) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.setOwnerActivity(activity)
            aiActivityDialogs += dialog
            dialog.setOnDismissListener { aiActivityDialogs.remove(dialog) }
        }
    }
