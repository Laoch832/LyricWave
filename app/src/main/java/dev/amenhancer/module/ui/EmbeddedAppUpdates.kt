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
import dev.amenhancer.module.update.updateAvailable
import dev.amenhancer.module.update.upToDate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    val choices = remember { activity.getSharedPreferences("lyricwave_download_choices", android.content.Context.MODE_PRIVATE) }
    var connections by remember { mutableStateOf(choices.getInt("connections", 8)) }
    androidx.compose.runtime.LaunchedEffect(snapshot.completed, active) {
        if (!active) waiting = false
        if (waiting && !snapshot.checking && snapshot.completed > 0 && active && !activity.isFinishing && !activity.isDestroyed) {
            waiting = false
            if (!snapshot.failed) snapshot.release?.let {
                if (it.updateAvailable) showAppRelease(activity, it)
                else Toast.makeText(activity, updateStatus(it), Toast.LENGTH_SHORT).show()
            }
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
            Text("LyricWave", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(localizedText(ModuleText.UPDATE_INSTALLED, BuildConfig.VERSION_NAME))
            Text(localizedText(ModuleText.UPDATE_STABLE_CHANNEL), color = MiuixTheme.colorScheme.primary)
            TextButton(text = localizedText(if (snapshot.checking) ModuleText.UPDATE_CHECKING else ModuleText.UPDATE_CHECK),
                enabled = !snapshot.checking, onClick = {
                    if (active) { waiting = true; AppUpdateCoordinator.request(activity.applicationContext, true) }
                })
            if (snapshot.failed) EmbeddedSettingsNotice(localizedText(ModuleText.UPDATE_FAILED, snapshot.failureHttp?.toString() ?: "—"))
            snapshot.release?.let { known ->
                Text(updateStatus(known), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary)
                if (known.updateAvailable) {
                    if (known.summary.isNotBlank()) Text(known.summary)
                    known.changes.take(3).forEach { change -> Text("• ${change.title}") }
                    TextButton(localizedText(ModuleText.UPDATE_DETAILS), onClick = { showAppRelease(activity, known) })
                }
                EmbeddedSettingsNotice(localizedText(ModuleText.UPDATE_LAST_CHECK,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(known.checkedAt))))
            }
            TextButton(localizedText(ModuleText.UPDATE_CONNECTIONS_VALUE, connections), onClick = {
                showEmbeddedMiuixDialog(activity, localizedText(ModuleText.UPDATE_CONNECTIONS)) { dismiss ->
                    listOf(1, 4, 8, 16, 32, 64, 128).forEach { count ->
                        TextButton("$count", onClick = { connections = count; choices.edit().putInt("connections", count).apply(); dismiss() })
                    }
                }
            })
            TextButton(localizedText(ModuleText.UPDATE_RELEASES), onClick = { openUpdateUrl(activity, AppUpdateClient.RELEASES_URL) })
        }
    }
}

private fun EmbeddedSettingsHost.updateStatus(release: AppUpdateRelease): String = localizedText(when (release.comparison) {
    UpdateComparison.NEWER -> ModuleText.UPDATE_AVAILABLE
    UpdateComparison.CURRENT -> ModuleText.UPDATE_CURRENT
    UpdateComparison.AHEAD -> ModuleText.UPDATE_CURRENT
    UpdateComparison.UNKNOWN -> ModuleText.UPDATE_UNVERIFIED
}, release.tag)

private fun EmbeddedSettingsHost.showAppRelease(activity: Activity, release: AppUpdateRelease) {
    if (!release.updateAvailable) return
    showEmbeddedMiuixDialog(activity, localizedText(ModuleText.UPDATE_AVAILABLE, release.build?.versionName ?: release.tag)) { dismiss ->
        val locale = LocalConfiguration.current.locales[0]
        EmbeddedSettingsNotice(updateStatus(release))
        EmbeddedSettingsNotice(localizedText(ModuleText.UPDATE_INSTALLED, BuildConfig.VERSION_NAME))
        if (release.summary.isNotBlank()) Text(release.summary)
        release.changes.forEach { change ->
            Text(change.title, fontWeight = FontWeight.Bold)
            Text(change.description)
        }
        release.notices.forEach { EmbeddedSettingsNotice(it) }
        if (release.changes.isEmpty() && release.notes.isNotBlank()) Text(release.notes.take(2500))
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
    showEmbeddedDownloadPanel(activity, assetId, official)
}
