package dev.amenhancer.module.update

import android.content.Context
import android.os.Build
import dev.amenhancer.module.BuildConfig

internal class ModuleControlException(val reason: String, cause: Throwable? = null) : RuntimeException(reason, cause)

internal data class InstalledModuleVersion(val code: Long, val name: String)

/** Loaded hooks can outlive an APK upgrade. Compare against the installed module, not stale hook constants. */
internal fun installedModuleVersion(context: Context): InstalledModuleVersion = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0)
    @Suppress("DEPRECATION")
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    require(code > 0)
    InstalledModuleVersion(code, info.versionName ?: BuildConfig.VERSION_NAME)
}.getOrElse {
    runCatching {
        val status = requireNotNull(context.contentResolver.call(android.net.Uri.parse("content://${BuildConfig.APPLICATION_ID}.update-control"), "status", null, null))
        val code = status.getInt("module_version_code").toLong(); require(code > 0)
        InstalledModuleVersion(code, status.getString("module_version_name") ?: BuildConfig.VERSION_NAME)
    }.getOrElse { InstalledModuleVersion(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME) }
}

internal fun compareInstalledVersion(release: AppUpdateRelease, code: Long): AppUpdateRelease {
    val build = release.build ?: return release
    if (release.comparison == UpdateComparison.UNKNOWN) return release
    return release.copy(comparison = when {
        code < build.versionCode -> UpdateComparison.NEWER
        code == build.versionCode -> UpdateComparison.CURRENT
        else -> UpdateComparison.AHEAD
    })
}

internal fun moduleControlFailure(error: Throwable): String = when (error) {
    is ModuleControlException -> error.reason
    is SecurityException -> "control_permission"
    is android.app.PendingIntent.CanceledException -> "control_expired"
    else -> "control_unavailable"
}

internal fun backupDownloadIntent(id: Long, connections: Int, official: Boolean): android.content.Intent {
    require(id > 0)
    return android.content.Intent().setClassName(BuildConfig.APPLICATION_ID, UpdateDownloadActivity::class.java.name)
        .putExtra("asset_id", id).putExtra("official", official)
        .putExtra("connections", connections.takeIf { it in listOf(1, 4, 8, 16, 32, 64, 128) } ?: 8)
}
