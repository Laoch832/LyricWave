package dev.amenhancer.module.update

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.amenhancer.module.BuildConfig
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Cross-process UI control. Only the module or the original signed Apple Music app may issue commands. */
class UpdateControlProvider : ContentProvider() {
    override fun onCreate(): Boolean { context?.let(UpdateTransfer::restore); return true }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = requireNotNull(context)
        // Keep the caller for authorization, but never use its Binder identity when acting
        // as the module (PendingIntent creator package, notification manager, broadcasts).
        val caller = Binder.getCallingUid()
        val identity = Binder.clearCallingIdentity()
        try {
            if (!trustedCaller(app, caller)) throw SecurityException("Untrusted update caller")
            return execute(app, method, extras)
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    private fun execute(app: Context, method: String, extras: Bundle?): Bundle {
        return when (method) {
            "share_prepare" -> {
                val launch = app.packageManager.getLaunchIntentForPackage(dev.amenhancer.module.MomentsLinkContract.INTERMEDIARY_PACKAGE)
                Bundle().apply {
                    if (launch == null) putInt("code", dev.amenhancer.module.MomentsLinkContract.RESULT_MISSING)
                    else putParcelable("action", PendingIntent.getActivity(app, 7101, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            "share_request" -> {
                dev.amenhancer.module.share.MomentsShareRelay.dispatch(app, requireNotNull(extras))
                Bundle().apply { putBoolean("accepted", true) }
            }
            "feedback_config" -> {
                val enabled = extras?.getBoolean("enabled", true) ?: true
                app.getSharedPreferences("lyricwave_feedback_config", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
                dev.amenhancer.module.feedback.FeedbackRuntime.changed(enabled)
                Bundle()
            }
            "status" -> UpdateTransfer.snapshot(app)
            "begin" -> {
                val id = extras?.getLong("asset_id", -1) ?: -1
                require(id > 0)
                val count = extras?.getInt("connections", 8)?.coerceIn(1, 128) ?: 8
                val ticket = UpdateTransfer.issue(id, count, extras?.getBoolean("official", false) == true)
                val action = Intent(app, UpdateDownloadService::class.java).setAction("begin").putExtra("ticket", ticket)
                Bundle().apply { putParcelable("action", PendingIntent.getForegroundService(app, ticket.hashCode(), action, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            }
            "cancel" -> Bundle().apply {
                val token = UpdateTransfer.controlToken
                putParcelable("action", PendingIntent.getService(app, 2, Intent(app, UpdateDownloadService::class.java)
                    .setAction("cancel").putExtra("ticket", token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            "install" -> {
                val file = UpdateTransfer.file ?: throw IllegalStateException("No verified update")
                val build = UpdateTransfer.build ?: throw IllegalStateException("No verified metadata")
                UpdateApkVerifier.verify(app, file, build)
                val action = if (!app.packageManager.canRequestPackageInstalls())
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                else {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("LyricWave update", uri) }
                }
                Bundle().apply { putBoolean("install_permission", !app.packageManager.canRequestPackageInstalls()); putParcelable("action", PendingIntent.getActivity(app, 3, action, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            }
            "notifications" -> Bundle().apply {
                putParcelable("action", PendingIntent.getActivity(app, 4,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            else -> throw IllegalArgumentException("Unknown command")
        }
    }
    @Suppress("DEPRECATION")
    private fun trustedCaller(app: Context, uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        val packages = app.packageManager.getPackagesForUid(uid).orEmpty()
        if ("com.apple.android.music" !in packages) return false
        val info = app.packageManager.getPackageInfo("com.apple.android.music",
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.any { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } == APPLE_CERT } == true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object { const val APPLE_CERT = "88ba590ec2e1ea33c4458daf59489faee2cef297a9b4071e18cf82ef531100aa" }
}

internal data class TransferRequest(val id: Long, val connections: Int, val official: Boolean, val created: Long = android.os.SystemClock.elapsedRealtime())
internal object UpdateTransfer {
    private val tickets = ConcurrentHashMap<String, TransferRequest>()
    @Volatile var state = DownloadState(DownloadPhase.READY)
    @Volatile var file: java.io.File? = null
    @Volatile var build: UpdateBuild? = null
    @Volatile var controlToken = ""
    @Volatile var assetId = -1L
    fun persist(context: Context) {
        val metadata = build ?: return
        val apk = file ?: return
        val row = org.json.JSONObject().put("id", assetId).put("file", apk.name).put("size", apk.length())
            .put("versionCode", metadata.versionCode).put("versionName", metadata.versionName)
            .put("apk", metadata.apk).put("sha256", metadata.sha256)
        context.getSharedPreferences("lyricwave_verified_download", Context.MODE_PRIVATE).edit().putString("complete", row.toString()).apply()
    }
    fun restore(context: Context) {
        if (build != null || state.phase == DownloadPhase.DOWNLOADING || state.phase == DownloadPhase.CHECKING) return
        runCatching {
            val saved = context.getSharedPreferences("lyricwave_verified_download", Context.MODE_PRIVATE).getString("complete", null) ?: return
            val row = org.json.JSONObject(saved)
            val name = row.getString("file")
            if (!name.matches(Regex("update-[0-9]+-[a-f0-9]{12}\\.apk"))) return
            val metadata = UpdateBuild(row.getLong("versionCode"), row.getString("versionName"), row.getString("apk"), row.getString("sha256"))
            val apk = java.io.File(context.cacheDir, "updates/$name")
            if (metadata.versionCode <= BuildConfig.VERSION_CODE || !apk.isFile || apk.length() != row.getLong("size")) return
            assetId = row.getLong("id"); build = metadata; file = apk
            state = DownloadState(DownloadPhase.VERIFIED, metadata.apk, apk.length(), apk.length(), file = apk)
        }
    }
    @Synchronized fun issue(id: Long, connections: Int, official: Boolean): String {
        val now = android.os.SystemClock.elapsedRealtime()
        tickets.entries.removeIf { now - it.value.created > 60_000 }
        check(tickets.size < 8) { "Too many requests" }
        return UUID.randomUUID().toString().also { tickets[it] = TransferRequest(id, connections, official) }
    }
    fun take(ticket: String): TransferRequest? = tickets.remove(ticket)?.takeIf { android.os.SystemClock.elapsedRealtime() - it.created <= 60_000 }
    fun snapshot(context: Context) = Bundle().apply {
        val value = state
        putLong("asset_id", assetId)
        putString("phase", value.phase.name); putString("name", value.name)
        putLong("bytes", value.bytes); putLong("total", value.total); putInt("connections", value.connections)
        putString("reason", value.reason)
        putInt("module_version_code", BuildConfig.VERSION_CODE)
        putString("module_version_name", BuildConfig.VERSION_NAME)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        putBoolean("notifications", manager.areNotificationsEnabled())
        putBoolean("promoted", Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications())
    }
}
