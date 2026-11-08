package dev.amenhancer.module.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.i18n.ModuleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

internal suspend fun updateTransferCommand(context: Context, method: String, extras: Bundle? = null): Bundle = withContext(Dispatchers.IO) {
    context.contentResolver.call(Uri.parse("content://${BuildConfig.APPLICATION_ID}.update-control"), method, null, extras) ?: Bundle()
}

/** This dialog lives in Apple Music; only the bounded transfer/notification service runs in the module UID. */
internal fun EmbeddedSettingsHost.showEmbeddedDownloadPanel(activity: Activity, assetId: Long = -1, official: Boolean = false) {
    showEmbeddedMiuixDialog(activity, localizedText(ModuleText.UPDATE_DOWNLOADER)) { dismiss ->
        val scope = rememberCoroutineScope()
        val preferences = remember { activity.getSharedPreferences("lyricwave_download_choices", Context.MODE_PRIVATE) }
        var threads by remember { mutableIntStateOf(preferences.getInt("connections", 8)) }
        var snapshot by remember { mutableStateOf(Bundle()) }
        var error by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var needsInstallPermission by remember { mutableStateOf(false) }
        val phase = snapshot.getString("phase", "READY")
        val running = phase == "CHECKING" || phase == "DOWNLOADING"
        val total = snapshot.getLong("total")
        val bytes = snapshot.getLong("bytes")
        @Suppress("DEPRECATION")
        fun command(method: String, source: Boolean = official) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    val id = if (assetId > 0) assetId else snapshot.getLong("asset_id", -1)
                    val extras = Bundle().apply { putLong("asset_id", id); putInt("connections", threads); putBoolean("official", source) }
                    val result = updateTransferCommand(activity, method, extras)
                    needsInstallPermission = result.getBoolean("install_permission")
                    result.getParcelable<PendingIntent>("action")?.let { action ->
                        // Explicit button actions use the visible host's launch privilege.
                        // Do not grant this to background progress polling or service commands.
                        if ((method == "install" || method == "notifications") && android.os.Build.VERSION.SDK_INT >= 34) {
                            val options = android.app.ActivityOptions.makeBasic().apply {
                                setPendingIntentBackgroundActivityStartMode(if (android.os.Build.VERSION.SDK_INT >= 36)
                                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                                else android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                            }
                            action.send(activity, 0, null, null, null, null, options.toBundle())
                        } else action.send()
                    }
                    error = false
                } catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }
        LaunchedEffect(Unit) {
            runCatching { updateTransferCommand(activity, "status") }.onSuccess { existing ->
                snapshot = existing
                val current = existing.getString("phase", "READY")
                if (assetId > 0 && current !in listOf("CHECKING", "DOWNLOADING") &&
                    (current != "VERIFIED" || existing.getLong("asset_id", -1) != assetId)) command("begin")
            }.onFailure { error = true }
            while (isActive && !activity.isDestroyed) {
                delay(700)
                runCatching { updateTransferCommand(activity, "status") }.onSuccess { snapshot = it; error = false }.onFailure { error = true }
            }
        }
        Text(snapshot.getString("name").orEmpty().ifBlank { "LyricWave" })
        when (phase) {
            "CHECKING" -> Text(localizedText(ModuleText.UPDATE_CHECKING))
            "DOWNLOADING" -> {
                val fraction = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
                Text("${(fraction * 100).toInt()}%", fontSize = 32.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(6.dp).background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceVariant)) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary))
                }
                Text(localizedText(ModuleText.UPDATE_PROGRESS,
                    "%.1f".format(bytes / 1048576.0), "%.1f".format(total / 1048576.0), snapshot.getInt("connections", threads)))
            }
            "VERIFIED" -> Text(localizedText(ModuleText.UPDATE_READY_TO_INSTALL))
            "FAILED" -> if (snapshot.getString("reason") == "cancelled") Text(localizedText(ModuleText.UPDATE_CANCELLED))
                else Text(localizedText(ModuleText.UPDATE_DOWNLOAD_FAILED, snapshot.getString("reason", "network")))
            else -> Text(localizedText(ModuleText.UPDATE_CONNECTIONS))
        }
        if (error) EmbeddedSettingsNotice(localizedText(ModuleText.UPDATE_DOWNLOAD_FAILED, "service"))
        if (needsInstallPermission) EmbeddedSettingsNotice(localizedText(ModuleText.UPDATE_INSTALL_PERMISSION))
        if (!running && phase != "VERIFIED") {
            Text(localizedText(ModuleText.UPDATE_CONNECTIONS))
            androidx.compose.foundation.layout.FlowRow {
                listOf(1, 4, 8, 16, 32, 64, 128).forEach { choice ->
                    TextButton(if (choice == threads) "✓ $choice" else "$choice", onClick = {
                        threads = choice; preferences.edit().putInt("connections", choice).apply()
                    })
                }
            }
            TextButton(localizedText(ModuleText.UPDATE_START), enabled = !busy && (assetId > 0 || snapshot.getLong("asset_id") > 0), onClick = { command("begin") })
            if (phase == "FAILED") TextButton(localizedText(ModuleText.UPDATE_GITHUB_DOWNLOAD), enabled = !busy, onClick = { command("begin", true) })
        }
        if (running) TextButton(localizedText(ModuleText.UPDATE_CANCEL), enabled = !busy, onClick = { command("cancel") })
        if (phase == "VERIFIED") TextButton(localizedText(ModuleText.UPDATE_INSTALL), enabled = !busy, onClick = { command("install") })
        if (!snapshot.getBoolean("notifications") || (android.os.Build.VERSION.SDK_INT >= 36 && !snapshot.getBoolean("promoted"))) TextButton(localizedText(ModuleText.UPDATE_NOTIFICATION_SETTINGS), onClick = { command("notifications") })
        TextButton(localizedText(if (running) ModuleText.UPDATE_BACKGROUND else ModuleText.CLOSE), onClick = dismiss)
    }
}
