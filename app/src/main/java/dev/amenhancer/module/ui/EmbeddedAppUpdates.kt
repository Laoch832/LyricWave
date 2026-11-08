package dev.amenhancer.module.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.update.AppUpdateClient
import dev.amenhancer.module.update.AppUpdateRelease
import dev.amenhancer.module.update.UpdateComparison
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Foreground checks share one coordinator with the lifecycle hook. */
@Composable
internal fun EmbeddedSettingsHost.EmbeddedAppUpdates(activity: Activity, active: Boolean,
    settings: dev.amenhancer.module.model.ModuleSettings,
    onEdit: ((dev.amenhancer.module.model.ModuleSettings) -> dev.amenhancer.module.model.ModuleSettings) -> Unit,
) {
    val snapshot by AppUpdateCoordinator.state.collectAsState()
    var waiting by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    androidx.compose.runtime.LaunchedEffect(snapshot.completed, active) {
        if (!active) waiting = false
        if (waiting && !snapshot.checking && snapshot.completed > 0 && active && !activity.isFinishing && !activity.isDestroyed) {
            waiting = false
            if (!snapshot.failed) snapshot.release?.let { showAppRelease(activity, it) }
        }
    }
    EmbeddedSettingsGroup {
        EmbeddedSettingSwitch(localizedText(ModuleText.UPDATE_AUTOMATIC), localizedText(ModuleText.UPDATE_AUTOMATIC_SUMMARY),
            checked = settings.automaticUpdateCheckEnabled,
            onCheckedChange = { enabled ->
                onEdit { it.copy(automaticUpdateCheckEnabled = enabled) }
                if (enabled) AppUpdateCoordinator.foreground(activity.applicationContext, true)
            })
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localizedText(ModuleText.UPDATE_INSTALLED, BuildConfig.VERSION_NAME))
            TextButton(text = localizedText(if (snapshot.checking) ModuleText.UPDATE_CHECKING else ModuleText.UPDATE_CHECK),
                enabled = !snapshot.checking, onClick = {
                    if (active) { waiting = true; AppUpdateCoordinator.request(activity.applicationContext, true) }
                })
            if (snapshot.failed) EmbeddedSettingsNotice(localizedText(ModuleText.UPDATE_FAILED, snapshot.failureHttp?.toString() ?: "—"))
            snapshot.release?.let { known ->
                EmbeddedSettingsNotice(updateStatus(known))
                EmbeddedSettingsNotice(localizedText(ModuleText.UPDATE_LAST_CHECK,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(known.checkedAt))))
                TextButton(localizedText(ModuleText.UPDATE_DETAILS), onClick = { showAppRelease(activity, known) })
            }
            TextButton(localizedText(ModuleText.UPDATE_RELEASES), onClick = { openUpdateUrl(activity, AppUpdateClient.RELEASES_URL) })
        }
    }
}

private fun EmbeddedSettingsHost.updateStatus(release: AppUpdateRelease): String = localizedText(when (release.comparison) {
    UpdateComparison.NEWER -> ModuleText.UPDATE_AVAILABLE
    UpdateComparison.CURRENT -> ModuleText.UPDATE_CURRENT
    UpdateComparison.AHEAD -> ModuleText.UPDATE_AHEAD
    UpdateComparison.UNKNOWN -> ModuleText.UPDATE_UNVERIFIED
}, release.tag)

private fun EmbeddedSettingsHost.showAppRelease(activity: Activity, release: AppUpdateRelease) {
    showEmbeddedMiuixDialog(activity, release.name) { dismiss ->
        val locale = LocalConfiguration.current.locales[0]
        EmbeddedSettingsNotice(updateStatus(release))
        if (release.prerelease) EmbeddedSettingsNotice(localizedText(ModuleText.UPDATE_PRERELEASE))
        if (release.notes.isNotBlank()) Text(release.notes.take(12_000)) // Markdown is displayed as text, never HTML.
        release.apks.sortedBy { it.name != release.build?.apk }.forEach { asset ->
            val size = String.format(locale, "%.1f", asset.size / (1024.0 * 1024.0))
            TextButton(localizedText(ModuleText.UPDATE_DOWNLOAD, asset.name, size),
                enabled = release.build?.apk == asset.name,
                onClick = { openInternalDownload(activity, asset.id, false) })
            TextButton(localizedText(ModuleText.UPDATE_GITHUB_DOWNLOAD), enabled = release.build?.apk == asset.name,
                onClick = { openInternalDownload(activity, asset.id, true) })
        }
        TextButton(localizedText(ModuleText.UPDATE_RELEASES), onClick = { openUpdateUrl(activity, release.htmlUrl) })
        EmbeddedDialogActions(localizedText(ModuleText.CLOSE), dismiss)
    }
}

private fun EmbeddedSettingsHost.openUpdateUrl(activity: Activity, url: String) {
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
        Toast.makeText(activity, localizedText(ModuleText.UPDATE_OPEN_FAILED), Toast.LENGTH_LONG).show()
    }
}

private fun EmbeddedSettingsHost.openInternalDownload(activity: Activity, assetId: Long, official: Boolean) {
    runCatching {
        activity.startActivity(Intent().setComponent(android.content.ComponentName(BuildConfig.APPLICATION_ID,
            "dev.amenhancer.module.update.UpdateDownloadActivity"))
            .putExtra("asset_id", assetId).putExtra("official", official))
    }.onFailure { Toast.makeText(activity, localizedText(ModuleText.UPDATE_OPEN_FAILED), Toast.LENGTH_LONG).show() }
}
