package dev.amenhancer.module.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.i18n.ModuleText
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Module-owned foreground transfer, controlled from the host dialog without opening a module Activity. */
class UpdateDownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancellation = UpdateDownloadCancellation()
    private var running = false
    internal var checkRelease: () -> AppUpdateRelease = { AppUpdateClient().check(BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID) }
    internal var fetchFile: (UpdateAsset, UpdateBuild, File, UpdateDownloadCancellation, TransferRequest, (UpdateDownloadProgress) -> Unit) -> File =
        { asset, build, directory, cancel, request, progress -> AppUpdateDownloader(parallelism = request.connections).download(asset, build, directory, cancel, request.official, progress) }
    internal var verifyFile: (File, UpdateBuild) -> Unit = { file, build -> UpdateApkVerifier.verify(this, file, build) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ticket = intent?.getStringExtra("ticket").orEmpty()
        if (intent?.action == "cancel") {
            if (ticket.isNotBlank() && ticket == UpdateTransfer.controlToken) {
                cancellation.cancel(); UpdateTransfer.state = DownloadState(DownloadPhase.FAILED, reason = "cancelled")
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            return START_NOT_STICKY
        }
        val request = UpdateTransfer.take(ticket)
        if (request == null) { if (!running) stopSelf(startId); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true; UpdateTransfer.controlToken = ticket; UpdateTransfer.assetId = request.id
        UpdateTransfer.file = null; UpdateTransfer.build = null
        UpdateTransfer.state = DownloadState(DownloadPhase.CHECKING)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, ModuleText.UPDATE_DOWNLOADER.text(), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification())
        } catch (error: Exception) {
            UpdateTransfer.state = DownloadState(DownloadPhase.FAILED, reason = "foreground_service")
            dev.amenhancer.module.feedback.FeedbackRuntime.record("download", error)
            stopSelf(); return START_NOT_STICKY
        }
        worker.execute {
            try {
                val release = checkRelease()
                val build = release.build ?: throw AppUpdateException("metadata")
                if (build.versionCode <= BuildConfig.VERSION_CODE) throw AppUpdateException("already_current")
                val asset = release.apks.singleOrNull { it.id == request.id && it.name == build.apk } ?: throw AppUpdateException("asset")
                val directory = File(cacheDir, "updates").apply { mkdirs() }
                directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                val updated = AtomicLong(-1000)
                val file = fetchFile(asset, build, directory, cancellation, request) { p ->
                    val now = android.os.SystemClock.elapsedRealtime(); val previous = updated.get()
                    if ((now - previous >= 300 || p.bytes == p.total) && updated.compareAndSet(previous, now)) {
                        UpdateTransfer.state = DownloadState(DownloadPhase.DOWNLOADING, asset.name, p.bytes, p.total, p.connections)
                        postProgress(manager)
                    }
                }
                try { if (cancellation.cancelled) throw java.util.concurrent.CancellationException(); verifyFile(file, build); if (cancellation.cancelled) throw java.util.concurrent.CancellationException() } catch (error: Exception) { file.delete(); throw error }
                UpdateTransfer.file = file; UpdateTransfer.build = build
                UpdateTransfer.state = DownloadState(DownloadPhase.VERIFIED, asset.name, asset.size, asset.size, file = file)
                UpdateTransfer.persist(this)
            } catch (_: java.util.concurrent.CancellationException) {
                UpdateTransfer.state = DownloadState(DownloadPhase.FAILED, reason = "cancelled")
            } catch (error: Exception) {
                if (!cancellation.cancelled) dev.amenhancer.module.feedback.FeedbackRuntime.record("download", error)
                UpdateTransfer.state = DownloadState(DownloadPhase.FAILED, reason = if (cancellation.cancelled) "cancelled" else (error as? AppUpdateException)?.reason ?: "network")
            } finally {
                if (!cancellation.cancelled) postProgress(manager)
                stopForeground(if (cancellation.cancelled) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
                UpdateTransfer.controlToken = ""
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun postProgress(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            manager.notify(NOTIFICATION, notification())
    }
    private fun notification(): Notification {
        val state = UpdateTransfer.state
        val active = state.phase == DownloadPhase.DOWNLOADING || state.phase == DownloadPhase.CHECKING
        val percent = if (state.total > 0) (100 * state.bytes / state.total).toInt().coerceIn(0, 100) else 0
        val message = when (state.phase) {
            DownloadPhase.VERIFIED -> ModuleText.UPDATE_READY_TO_INSTALL.text()
            DownloadPhase.FAILED -> ModuleText.UPDATE_DOWNLOAD_FAILED.text(state.reason)
            DownloadPhase.DOWNLOADING -> "$percent% · %.1f / %.1f MB · %d".format(state.bytes / 1048576.0, state.total / 1048576.0, state.connections)
            else -> ModuleText.UPDATE_CHECKING.text()
        }
        val launch = packageManager.getLaunchIntentForPackage("com.apple.android.music")
            ?.putExtra("lyricwave_show_download", true)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(if (active) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (active) ModuleText.UPDATE_DOWNLOADER.text() else "LyricWave")
            .setContentText(message).setCategory(Notification.CATEGORY_PROGRESS).setOngoing(active).setOnlyAlertOnce(true)
        launch?.let { builder.setContentIntent(PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
        if (active) {
            builder.setProgress(100, percent, state.total == 0L)
            val cancel = PendingIntent.getService(this, 2, Intent(this, UpdateDownloadService::class.java).setAction("cancel")
                .putExtra("ticket", UpdateTransfer.controlToken), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, ModuleText.UPDATE_CANCEL.text(), cancel).build())
            if (Build.VERSION.SDK_INT >= 36) {
                // The first Android 16 framework lacks the later Builder setter.
                // Use the framework extra so both versions can request promotion.
                builder.addExtras(android.os.Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) }).setShortCriticalText("$percent%")
                    .setStyle(Notification.ProgressStyle().setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
                        .setProgress(percent).setProgressIndeterminate(state.total == 0L))
            }
        } else builder.setAutoCancel(true)
        return builder.build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancellation.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { cancellation.cancel(); worker.shutdownNow(); super.onDestroy() }
    companion object { const val CHANNEL = "lyricwave_downloads"; const val NOTIFICATION = 7100 }
}
