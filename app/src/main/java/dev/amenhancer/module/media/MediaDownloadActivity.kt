package dev.amenhancer.module.media

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** User-initiated form lives in the module UID, so account secrets never enter the host. */
class MediaDownloadActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tokenInput: EditText
    private lateinit var choose: Spinner
    private lateinit var identityInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val formats = listOf(
        "AAC · 约 256 kbps" to "aac",
        "HE-AAC · 约 64 kbps" to "aac-he",
        "AAC Web · 约 256 kbps" to "aac-web",
        "ALAC · 自动最高无损" to "alac",
        "ALAC Lossless · ≤48 kHz" to "alac-lossless",
        "ALAC Hi-Res · >48 kHz" to "alac-hires",
        "Dolby Atmos · E-AC-3 JOC" to "atmos",
        "Dolby Digital · AC-3" to "ac3",
        "MV MP4 · H.264 720p" to "video:h264:720",
        "MV MP4 · H.264 1080p" to "video:h264:1080",
        "MV MP4 · H.265 1080p" to "video:h265:1080",
        "MV MP4 · H.265 2160p（需要高等级 DRM）" to "video:h265:2160",
    )
    private val mediaId get() = intent.getLongExtra(MediaDownloadService.EXTRA_MEDIA_ID, -1L)
    private val mediaTitle get() = intent.getStringExtra(MediaDownloadService.EXTRA_TITLE).orEmpty()
    private val mediaArtist get() = intent.getStringExtra(MediaDownloadService.EXTRA_ARTIST).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (mediaId <= 0) { finish(); return }
        title = "澜音 · 下载歌曲"
        val dp = resources.displayMetrics.density
        fun px(n: Int) = (n * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(24))
        }
        val scroller = ScrollView(this).apply { addView(root) }
        setContentView(scroller)

        fun label(value: String, size: Float = 15f): TextView = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, px(8), 0, px(8))
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label(mediaTitle.ifBlank { "Apple Music · " + mediaId }, 21f)
        if (mediaArtist.isNotBlank()) label(mediaArtist, 14f)
        label("选择下载音质／MV 视频画质（以资源及授权为准）")
        choose = Spinner(this)
        choose.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, formats.map { it.first }
        )
        root.addView(choose, LinearLayout.LayoutParams(-1, px(52)))
        label("可选：粘贴 Apple Music 歌曲/MV 链接或资源 ID。下载 MV 时应提供 MV 的 ID，而非歌曲的 ID。")
        identityInput = EditText(this).apply {
            hint = "https://music.apple.com/... 或媒体 ID"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        root.addView(identityInput, LinearLayout.LayoutParams(-1, px(52)))
        val prefs = getSharedPreferences("lyricwave_media_options", MODE_PRIVATE)
        val last = prefs.getString("codec", "aac")
        choose.setSelection(formats.indexOfFirst { it.second == last }.coerceAtLeast(0))

        label("首次配置：输入你本人 Apple Music 网页端 media-user-token。凭据在澜音自身进程内使用，并经 Android Keystore 加密保存。")
        tokenInput = EditText(this).apply {
            hint = if (MediaDownloadSecrets.load(this@MediaDownloadActivity) != null)
                "已经保存凭据；留空表示继续使用" else "media-user-token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(tokenInput, LinearLayout.LayoutParams(-1, px(52)))
        label("此功能尝试访问已授权的媒体流。AAC、无损和 Atmos 的可用性不同，不能保证全部歌曲或地区支持。")

        val download = Button(this).apply { text = "开始下载" }
        root.addView(download, LinearLayout.LayoutParams(-1, px(54)))
        download.setOnClickListener {
            if (MediaDownloadTransfer.running.get()) {
                toast("已有下载任务正在运行")
                return@setOnClickListener
            }
            try {
                val typed = tokenInput.text.toString().trim()
                if (typed.isNotEmpty()) MediaDownloadSecrets.save(this, typed)
                if (MediaDownloadSecrets.load(this).isNullOrEmpty()) {
                    toast("请先配置媒体访问凭据")
                    return@setOnClickListener
                }
                val selection = formats[choose.selectedItemPosition].second
                val sections = selection.split(":")
                val kind = if (sections[0] == "video") "video" else "song"
                val codec = if (kind == "video") sections[1] else selection
                val height = if (kind == "video") sections[2].toInt() else 0
                val requestedId = parseRequestedId(identityInput.text.toString()) ?: mediaId
                prefs.edit().putString("codec", selection).apply()
                val service = Intent(this, MediaDownloadService::class.java)
                    .setAction(MediaDownloadService.ACTION_START)
                    .putExtra(MediaDownloadService.EXTRA_MEDIA_ID, requestedId)
                    .putExtra(MediaDownloadService.EXTRA_TITLE, mediaTitle)
                    .putExtra(MediaDownloadService.EXTRA_ARTIST, mediaArtist)
                    .putExtra(MediaDownloadService.EXTRA_CODEC, codec)
                    .putExtra(MediaDownloadService.EXTRA_KIND, kind)
                    .putExtra(MediaDownloadService.EXTRA_HEIGHT, height)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                tokenInput.text.clear()
            } catch (_: Exception) {
                toast("启动失败，请检查下载权限和设备支持情况")
            }
        }
        val cancel = Button(this).apply { text = "取消当前下载" }
        root.addView(cancel, LinearLayout.LayoutParams(-1, px(50)))
        cancel.setOnClickListener {
            startService(Intent(this, MediaDownloadService::class.java)
                .setAction(MediaDownloadService.ACTION_CANCEL))
        }
        val clear = Button(this).apply { text = "清除保存的登录凭据" }
        root.addView(clear, LinearLayout.LayoutParams(-1, px(48)))
        clear.setOnClickListener {
            if (MediaDownloadTransfer.running.get()) toast("请先结束当前任务")
            else {
                MediaDownloadSecrets.clear(this)
                tokenInput.text.clear()
                tokenInput.hint = "media-user-token"
                toast("已清除")
            }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 1000
        root.addView(progress, LinearLayout.LayoutParams(-1, px(16)))
        status = label("尚未开始")
    }

    private fun parseRequestedId(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        return runCatching {
            val uri = android.net.Uri.parse(value)
            require(uri.scheme == "https" && uri.host == "music.apple.com")
            (uri.getQueryParameter("i") ?: uri.lastPathSegment)
                ?.toLongOrNull()?.takeIf { it > 0 }
        }.getOrNull() ?: throw IllegalArgumentException("invalid Apple Music URL")
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private val refresh = object : Runnable {
        override fun run() {
            if (!polling) return
            val s = MediaDownloadTransfer.snapshot
            val percent = if (s.total > 0) (1000 * s.bytes / s.total).toInt().coerceIn(0, 1000) else 0
            progress.isIndeterminate = MediaDownloadTransfer.running.get() && s.total <= 0
            progress.progress = percent
            status.text = when (s.phase) {
                MediaDownloadPhase.COMPLETED -> "已保存到 Android 媒体库：" + s.outputName
                MediaDownloadPhase.FAILED -> "下载失败：无法获取媒体或授权，或引擎不受当前设备支持"
                MediaDownloadPhase.CANCELLED -> "下载已取消"
                MediaDownloadPhase.READY -> "尚未开始"
                else -> s.phase.name + " · " +
                    (s.bytes / 1024 / 1024) + " MB" +
                    (if (s.total > 0) " / " + (s.total / 1024 / 1024) + " MB" else "")
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        polling = true
        handler.post(refresh)
    }
    override fun onPause() {
        polling = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
