package dev.amenhancer.module.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.update.AppUpdateClient
import dev.amenhancer.module.update.AppUpdateException
import dev.amenhancer.module.update.AppUpdateRelease
import dev.amenhancer.module.update.UpdateComparison
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

private object UpdateMemoryCache { @Volatile var latest: AppUpdateRelease? = null }

/** A user-initiated check. Closing or covering the page cancels UI publication, never playback. */
@Composable
internal fun EmbeddedSettingsHost.EmbeddedAppUpdates(activity: Activity, active: Boolean) {
    var release by remember { mutableStateOf(UpdateMemoryCache.latest) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val currentActive by rememberUpdatedState(active)
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    DisposableEffect(active) {
        if (!active) { job?.cancel(); checking = false }
        onDispose { job?.cancel() }
    }
    EmbeddedSettingsGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localizedText(ModuleText.UPDATE_INSTALLED, BuildConfig.VERSION_NAME))
            TextButton(text = localizedText(if (checking) ModuleText.UPDATE_CHECKING else ModuleText.UPDATE_CHECK),
                enabled = !checking,
                onClick = {
                    if (currentActive && !checking) {
                        checking = true; failed = null
                        job = scope.launch {
                            val result = try {
                                withContext(Dispatchers.IO) {
                                    runCatching { AppUpdateClient().check(BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID) }
                                }
                            } catch (_: CancellationException) { return@launch }
                            if (!currentActive || activity.isFinishing || activity.isDestroyed) return@launch
                            checking = false
                            result.onSuccess {
                                UpdateMemoryCache.latest = it; release = it
                                showAppRelease(activity, it)
                            }.onFailure { error ->
                                failed = localizedText(ModuleText.UPDATE_FAILED,
                                    (error as? AppUpdateException)?.httpStatus?.toString() ?: "—")
                            }
                        }
                    }
                })
            failed?.let { EmbeddedSettingsNotice(it) }
            release?.let { known ->
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
                onClick = { openUpdateUrl(activity, asset.downloadUrl) })
            TextButton(localizedText(ModuleText.UPDATE_GITHUB_DOWNLOAD), onClick = { openUpdateUrl(activity, asset.upstreamUrl) })
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
