package dev.amenhancer.module.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** All Gamdl/Python work runs in LyricWave's UID, not in Apple Music's process. */
class MediaDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor { job ->
        Thread(job, "lyricwave-gamdl").apply { isDaemon = true }
    }
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            MediaDownloadTransfer.cancel()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (!MediaDownloadTransfer.running.compareAndSet(false, true)) return START_NOT_STICKY

        val id = intent.getLongExtra(EXTRA_MEDIA_ID, -1)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().take(160)
        val artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty().take(160)
        val codec = intent.getStringExtra(EXTRA_CODEC).orEmpty()
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "song"
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
        if (id <= 0 || (kind == "song" && codec !in CODECS) ||
            (kind == "video" && (codec !in VIDEO_CODECS || height !in setOf(720, 1080, 2160))) ||
            kind !in setOf("song", "video")) {
            MediaDownloadTransfer.running.set(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        MediaDownloadTransfer.cancelled.set(false)
        MediaDownloadTransfer.snapshot = MediaDownloadSnapshot(
            phase = MediaDownloadPhase.AUTHENTICATING,
            title = title, codec = codec,
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "澜音 · 媒体下载", NotificationManager.IMPORTANCE_LOW)
        )
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(
                NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ) else startForeground(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
            fail("foreground_service_unavailable")
            MediaDownloadTransfer.running.set(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        executor.execute {
            val folder = File(cacheDir, "gamdl/" + UUID.randomUUID()).apply { mkdirs() }
            val temporary = File(folder, if (kind == "video") "media.mp4" else "media.m4a")
            try {
                if (Build.VERSION.SDK_INT < 29) error("android_storage_unsupported")
                val token = MediaDownloadSecrets.load(this)
                    ?: error("authentication_required")
                if (!Python.isStarted()) synchronized(Python::class.java) {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                }
                val events = MediaEventCallback { stage, state, bytes, total ->
                    if (MediaDownloadTransfer.cancelled.get()) throw CancellationException()
                    val phase = when (stage) {
                        "fetching_tags" -> MediaDownloadPhase.AUTHENTICATING
                        "fetching_streams" -> MediaDownloadPhase.FETCHING_STREAMS
                        "fetching_decryption_keys" -> MediaDownloadPhase.FETCHING_DECRYPTION_KEYS
                        "downloading" -> MediaDownloadPhase.DOWNLOADING
                        "muxing" -> MediaDownloadPhase.MUXING
                        "tagging" -> MediaDownloadPhase.TAGGING
                        "publishing" -> MediaDownloadPhase.PUBLISHING
                        else -> MediaDownloadTransfer.snapshot.phase
                    }
                    if (state == "started" || state == "progress") {
                        val current = MediaDownloadTransfer.snapshot
                        MediaDownloadTransfer.snapshot = current.copy(
                            phase = phase,
                            bytes = if (phase == MediaDownloadPhase.DOWNLOADING) bytes else current.bytes,
                            total = if (phase == MediaDownloadPhase.DOWNLOADING) total else current.total,
                        )
                        notifyThrottled(manager)
                    }
                }
                if (kind == "video") {
                    Python.getInstance().getModule("lyricwave_gamdl").callAttr(
                        "download_video", id, codec, height, token, temporary.absolutePath, events
                    )
                } else {
                    Python.getInstance().getModule("lyricwave_gamdl").callAttr(
                        "download_song", id, codec, token, temporary.absolutePath, events
                    )
                }
                if (MediaDownloadTransfer.cancelled.get()) throw CancellationException()
                MediaDownloadTransfer.snapshot = MediaDownloadTransfer.snapshot.copy(
                    phase = MediaDownloadPhase.PUBLISHING
                )
                val saved = if (kind == "video") {
                    MediaStorePublisher.publishVideo(this, temporary, artist, title)
                } else {
                    MediaStorePublisher.publish(this, temporary, artist, title)
                }
                MediaDownloadTransfer.snapshot = MediaDownloadTransfer.snapshot.copy(
                    phase = MediaDownloadPhase.COMPLETED, outputName = saved,
                )
            } catch (_: CancellationException) {
                MediaDownloadTransfer.snapshot = MediaDownloadTransfer.snapshot.copy(
                    phase = MediaDownloadPhase.CANCELLED
                )
            } catch (_: Throwable) {
                if (MediaDownloadTransfer.cancelled.get()) {
                    MediaDownloadTransfer.snapshot = MediaDownloadTransfer.snapshot.copy(
                        phase = MediaDownloadPhase.CANCELLED
                    )
                } else {
                    // Never include Apple tokens, license challenges, or signed URLs in
                // user-visible messages, service notifications, or bug reports.
                    fail("gamdl_or_authorization_failed")
                }
            } finally {
                temporary.delete()
                folder.deleteRecursively()
                MediaDownloadTransfer.running.set(false)
                notifyThrottled(manager, force = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun fail(code: String) {
        MediaDownloadTransfer.snapshot = MediaDownloadTransfer.snapshot.copy(
            phase = MediaDownloadPhase.FAILED, errorCode = code
        )
    }

    private fun notifyThrottled(manager: NotificationManager, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < 700) return
        lastNotificationAt = now
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) manager.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val state = MediaDownloadTransfer.snapshot
        val label = if (state.title.isBlank()) "Apple Music" else state.title
        val message = when (state.phase) {
            MediaDownloadPhase.COMPLETED -> "下载已保存到 Music/LyricWave"
            MediaDownloadPhase.FAILED -> "下载失败"
            MediaDownloadPhase.CANCELLED -> "已取消"
            else -> "正在处理：" + state.phase.name.lowercase()
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("澜音 · " + label)
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(MediaDownloadTransfer.running.get())
            .build()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "lyricwave.media.START"
        const val ACTION_CANCEL = "lyricwave.media.CANCEL"
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_TITLE = "media_title"
        const val EXTRA_ARTIST = "media_artist"
        const val EXTRA_CODEC = "media_codec"
        const val EXTRA_KIND = "media_kind"
        const val EXTRA_HEIGHT = "media_height"
        const val CHANNEL = "lyricwave_media_download"
        const val NOTIFICATION_ID = 5127
        val VIDEO_CODECS = setOf("h264", "h265")
        val CODECS = setOf(
            "aac", "aac-he", "aac-web", "alac", "alac-lossless", "alac-hires", "atmos", "ac3"
        )
    }
}

/** Public Java-callable method used by Chaquopy without passing credentials. */
class MediaEventCallback(
    private val callback: (String, String, Long, Long) -> Unit,
) {
    fun onEvent(stage: String, state: String, bytes: Long, total: Long) {
        callback(stage, state, bytes, total)
    }
}
