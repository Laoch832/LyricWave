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
    private var activeCancellation: UpdateDownloadCancellation? = null
    @Volatile private var paused = false
    private var transferRateSampler = UpdateTransferRateSampler()
    @Volatile private var measuredRate = UpdateTransferRate()
    private var running = false
    @Synchronized
    internal fun recordTransferRate(bytes: Long, total: Long, now: Long) {
        measuredRate = transferRateSampler.sample(bytes, total, now)
    }
    internal var checkRelease: () -> AppUpdateRelease = { AppUpdateClient().check(BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID) }
    internal var fetchFile: (UpdateAsset, UpdateBuild, File, UpdateDownloadCancellation, TransferRequest, (UpdateDownloadProgress) -> Unit) -> File =
        { asset, build, directory, cancel, request, progress -> AppUpdateDownloader(parallelism = request.connections, resumable = request.resumable, resumeDirectory = File(cacheDir, "updates/resumable")).download(asset, build, directory, cancel, request.official, progress) }
    internal var verifyFile: (File, UpdateBuild) -> Unit = { file, build -> UpdateApkVerifier.verify(this, file, build) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ticket = intent?.getStringExtra("ticket").orEmpty()
        val action = intent?.action
        if (action == "cancel" || action == "pause") {
            if (running && ticket.isNotBlank() && ticket == UpdateTransfer.controlToken &&
                (action == "cancel" || (UpdateTransfer.pausable && UpdateTransfer.state.phase == DownloadPhase.DOWNLOADING))
            ) {
                val operation = activeCancellation
                if (operation != null && !operation.cancelled) {
                    paused = action == "pause"
                    if (paused) operation.pause() else operation.cancel()
                }
            }
            return START_NOT_STICKY
        }
        val request = UpdateTransfer.take(ticket)
        if (request == null) { if (!running) stopSelf(startId); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val cancellation = UpdateDownloadCancellation()
        activeCancellation = cancellation
        UpdateTransfer.pausable = request.resumable
        paused = false
        running = true
        if (request.id != UpdateTransfer.assetId) UpdateTransfer.clearPause(this)
        UpdateTransfer.controlToken = ticket; UpdateTransfer.assetId = request.id
        transferRateSampler = UpdateTransferRateSampler(); measuredRate = UpdateTransferRate()
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
            UpdateTransfer.controlToken = ""
            UpdateTransfer.pausable = false
            stopSelf(); return START_NOT_STICKY
        }
        worker.execute {
            var lastSource = request.official
            var completedBuild: UpdateBuild? = null
            val outcome = try {
                val release = checkRelease()
                val build = release.build ?: throw AppUpdateException("metadata")
                if (build.versionCode <= BuildConfig.VERSION_CODE) throw AppUpdateException("already_current")
                val asset = release.apks.singleOrNull { it.id == request.id && it.name == build.apk } ?: throw AppUpdateException("asset")
                val directory = File(cacheDir, "updates").apply { mkdirs() }
                val updated = AtomicLong(-1000)
                val progressLock = Any()
                var lastBytes = -1L; var reused = 0L
                val file = fetchFile(asset, build, directory, cancellation, request) { p ->
                    synchronized(progressLock) {
                    if (cancellation.cancelled) return@synchronized
                    if (p.bytes > 0 && p.bytes < lastBytes && p.official == lastSource) return@synchronized
                    lastBytes = p.bytes; lastSource = p.official; reused = p.resumedBytes
                    val now = android.os.SystemClock.elapsedRealtime(); val previous = updated.get()
                    recordTransferRate(p.bytes, p.total, now)
                    if ((now - previous >= 300 || p.bytes == p.total || p.bytes == 0L) && updated.compareAndSet(previous, now)) {
                        UpdateTransfer.state = DownloadState(DownloadPhase.DOWNLOADING, asset.name, p.bytes, p.total, p.connections, official = p.official, resumedBytes = p.resumedBytes)
                        postProgress(manager)
                    }
                    }
                }
                try {
                    if (!cancellation.cancelled) verifyFile(file, build)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
                if (cancellation.cancelled) {
                    if (!paused) file.delete()
                    throw java.util.concurrent.CancellationException()
                }
                completedBuild = build
                DownloadState(DownloadPhase.VERIFIED, asset.name, asset.size, asset.size, file = file, resumedBytes = reused)
            } catch (error: Exception) {
                if (!cancellation.cancelled && error !is java.util.concurrent.CancellationException)
                    dev.amenhancer.module.feedback.FeedbackRuntime.record("download", error)
                DownloadState(DownloadPhase.FAILED, reason = (error as? AppUpdateException)?.reason ?: "network")
            }
            android.os.Handler(mainLooper).post {
                if (activeCancellation !== cancellation) return@post
                val finalState = when {
                    paused -> {
                        UpdateTransfer.recordPause(this, request, lastSource)
                        UpdateTransfer.state.copy(phase = DownloadPhase.PAUSED, official = lastSource)
                    }
                    cancellation.cancelled -> {
                        UpdateTransfer.clearPause(this)
                        DownloadState(DownloadPhase.FAILED, reason = "cancelled")
                    }
                    else -> outcome
                }
                if (cancellation.cancelled) {
                    if (!paused) outcome.file?.delete()
                    UpdateTransfer.file = null
                    UpdateTransfer.build = null
                } else if (outcome.phase == DownloadPhase.VERIFIED) {
                    UpdateTransfer.clearPause(this)
                    UpdateTransfer.file = outcome.file
                    UpdateTransfer.build = completedBuild
                    UpdateTransfer.persist(this)
                }
                UpdateTransfer.state = finalState
                UpdateTransfer.controlToken = ""
                activeCancellation = null
                UpdateTransfer.pausable = false
                running = false
                if (!cancellation.cancelled || paused) postProgress(manager)
                stopForeground(if (cancellation.cancelled && !paused) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun postProgress(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            manager.notify(NOTIFICATION, notification())
    }
    internal fun notification(): Notification {
        val state = UpdateTransfer.state
        val active = state.phase == DownloadPhase.DOWNLOADING || state.phase == DownloadPhase.CHECKING
        val percent = if (state.total > 0) (100 * state.bytes / state.total).toInt().coerceIn(0, 100) else 0
        val message = when (state.phase) {
            DownloadPhase.VERIFIED -> ModuleText.UPDATE_READY_TO_INSTALL.text()
            DownloadPhase.PAUSED -> ModuleText.UPDATE_PAUSED.text()
            DownloadPhase.FAILED -> ModuleText.UPDATE_DOWNLOAD_FAILED.text(state.reason)
            DownloadPhase.DOWNLOADING -> "${percent}% · " + "%.1f / %.1f MB · %d".format(
                java.util.Locale.ROOT, state.bytes / 1048576.0, state.total / 1048576.0, state.connections)
            else -> ModuleText.UPDATE_CHECKING.text()
        }
        var displayMessage = if (state.phase == DownloadPhase.DOWNLOADING && state.official)
            ModuleText.UPDATE_OFFICIAL_SOURCE.text() + " · " + message else message
        if (state.resumedBytes > 0) displayMessage += " · " + ModuleText.UPDATE_RESUMED.text("%.1f".format(java.util.Locale.ROOT, state.resumedBytes / 1048576.0))
        val launch = packageManager.getLaunchIntentForPackage("com.apple.android.music")
            ?.putExtra("lyricwave_show_download", true)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(if (active) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (active) ModuleText.UPDATE_DOWNLOADER.text() else "LyricWave")
            .setContentText(displayMessage).setCategory(Notification.CATEGORY_PROGRESS).setOngoing(active).setOnlyAlertOnce(true)
        if (state.phase == DownloadPhase.VERIFIED) {
            val install = UpdateInstallActivity.pendingIntent(this, UpdateTransfer.assetId)
            builder.setContentIntent(install)
                .addAction(Notification.Action.Builder(null, ModuleText.UPDATE_INSTALL.text(), install).build())
        } else {
            launch?.let { builder.setContentIntent(PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
        }
        if (state.phase == DownloadPhase.DOWNLOADING) {
            val speed = measuredRate.bytesPerSecond
            val remaining = measuredRate.remainingSeconds
            if (speed != null && remaining != null) {
                builder.setSubText(ModuleText.UPDATE_TRANSFER_RATE.text(
                    "%.0f".format(java.util.Locale.ROOT, speed / 1024.0), remaining))
            }
        }
        if (active) {
            builder.setProgress(100, percent, state.total == 0L)
            val cancel = PendingIntent.getService(this, 2, Intent(this, UpdateDownloadService::class.java).setAction("cancel")
                .putExtra("ticket", UpdateTransfer.controlToken), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, ModuleText.UPDATE_CANCEL.text(), cancel).build())
            if (UpdateTransfer.pausable && state.phase == DownloadPhase.DOWNLOADING) {
                val pause = PendingIntent.getService(this, 5, Intent(this, UpdateDownloadService::class.java)
                    .setAction("pause").putExtra("ticket", UpdateTransfer.controlToken),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(Notification.Action.Builder(null, ModuleText.UPDATE_PAUSE.text(), pause).build())
            }
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
    override fun onTimeout(startId: Int, fgsType: Int) {
        endInterruptedTransfer("foreground_service")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun endInterruptedTransfer(reason: String) {
        val cancellation = activeCancellation ?: return
        cancellation.cancel()
        activeCancellation = null
        UpdateTransfer.controlToken = ""
        UpdateTransfer.pausable = false
        UpdateTransfer.state = DownloadState(DownloadPhase.FAILED, reason = reason)
    }

    override fun onDestroy() {
        endInterruptedTransfer("cancelled")
        worker.shutdownNow()
        super.onDestroy()
    }
    companion object { const val CHANNEL = "lyricwave_downloads"; const val NOTIFICATION = 7100 }
}
