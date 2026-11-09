package dev.amenhancer.module.share

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.text.util.Linkify
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors
import android.text.Editable
import android.text.TextWatcher
import android.widget.ScrollView
import dev.amenhancer.module.MomentsSongMetadata
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.i18n.ModuleText

/** Opens NetEase Cloud Music's native share panel with the original Apple Music URL. */
class MomentsShareActivity : Activity() {
    internal var metadataResolver: (String) -> MomentsSongMetadata? = AppleSongMetadataLookup()::lookup
    private lateinit var panel: MomentsSharePanel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = MomentsSharePanel(this, intent).also { it.metadataResolver = metadataResolver }
        setContentView(panel.buildView(savedInstanceState))
    }
    override fun onSaveInstanceState(outState: Bundle) { panel.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() { panel.close(); super.onDestroy() }
}

internal class MomentsSharePanel(private val owner: Activity, private val intent: Intent) : android.content.ContextWrapper(owner) {
    private var closed = false
    private val isDestroyed get() = closed || owner.isDestroyed
    private val isFinishing get() = owner.isFinishing
    private val handler = Handler(Looper.getMainLooper())
    private var chinaStorefront = false
    private var attempt: ShareAttempt? = null
    private var pending = false
    private var shareStarted = false
    private var artworkUrl: String? = null
    private var metadataLoading = false
    private val metadataExecutor = Executors.newSingleThreadExecutor { Thread(it, "LyricWave-share-metadata").apply { isDaemon = true } }
    internal var metadataResolver: (String) -> MomentsSongMetadata? = AppleSongMetadataLookup()::lookup
    private lateinit var titleInput: EditText
    private lateinit var artistInput: EditText
    fun buildView(savedInstanceState: Bundle?): android.view.View {
        val originalUrl = Contract.extract(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
        chinaStorefront = savedInstanceState?.getBoolean(Contract.CHINA_STOREFRONT)
            ?: intent.getBooleanExtra(Contract.CHINA_STOREFRONT, false)
        val url = originalUrl
        artworkUrl = MomentsSongMetadata.artwork(savedInstanceState?.getString(Contract.ARTWORK))
        val initialTitle = savedInstanceState?.getString(Contract.TITLE)
            ?: MomentsSongMetadata.title(intent.getStringExtra(Contract.TITLE))
            ?: MomentsSongMetadata.title(intent.getStringExtra(Intent.EXTRA_SUBJECT)).orEmpty()
        val initialArtist = savedInstanceState?.getString(Contract.ARTIST)
            ?: MomentsSongMetadata.clean(intent.getStringExtra(Contract.ARTIST)).orEmpty()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        layout.addView(TextView(this).apply { text = ModuleText.WECHAT_MOMENTS_CARD.text(); textSize = 22f })
        titleInput = EditText(this).apply { hint = ModuleText.SHARE_SONG_TITLE.text(); setSingleLine(true); setText(initialTitle) }
        artistInput = EditText(this).apply { hint = ModuleText.SHARE_SONG_ARTIST.text(); setSingleLine(true); setText(initialArtist) }
        layout.addView(titleInput); layout.addView(artistInput)
        val linkPreview = TextView(this).apply { text = Contract.withChinaStorefront(url, chinaStorefront).orEmpty(); autoLinkMask = Linkify.WEB_URLS; setTextIsSelectable(true) }
        val regionSwitch = android.widget.Switch(this).apply {
            text = ModuleText.SHARE_CHINA_STOREFRONT_ONCE.text(); isChecked = chinaStorefront
            setOnCheckedChangeListener { _, checked ->
                chinaStorefront = checked
                linkPreview.text = Contract.withChinaStorefront(url, checked).orEmpty()
            }
        }
        layout.addView(regionSwitch); layout.addView(linkPreview)
        val status = TextView(this).apply { text = ModuleText.WECHAT_SHARE_NOTICE.text(); textSize = 15f }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = ModuleText.COPY_SONG_LINK.text(); isEnabled = url != null
            setOnClickListener { Contract.withChinaStorefront(url, chinaStorefront)?.let { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Apple Music", it)) } }
        })
        val open = Button(this).apply { text = ModuleText.WECHAT_SHARE_OPEN.text(); isEnabled = url != null && MomentsSongMetadata.title(initialTitle) != null }
        titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                open.isEnabled = !metadataLoading && !pending && url != null && MomentsSongMetadata.title(s?.toString()) != null
            }
        })
        layout.addView(open)
        val stop = Button(this).apply { text = ModuleText.SHARE_STOP_WAITING.text(); visibility = android.view.View.GONE }
        layout.addView(stop)
        layout.isFocusableInTouchMode = true; layout.requestFocus()
        val content = ScrollView(this).apply { addView(layout) }
        if (url == null) status.text = ModuleText.WECHAT_LINK_REQUIRED.text()
        open.setOnClickListener {
            if (metadataLoading || pending || url == null) return@setOnClickListener
            val title = MomentsSongMetadata.title(titleInput.text.toString()) ?: return@setOnClickListener
            val artist = MomentsSongMetadata.clean(artistInput.text.toString())
            val sharedUrl = Contract.withChinaStorefront(url, chinaStorefront) ?: return@setOnClickListener
            shareStarted = true; pending = true; open.isEnabled = false; regionSwitch.isEnabled = false
            val current = ShareAttempt(sharedUrl, title, artist, artworkUrl, status, open, regionSwitch, stop)
            attempt = current
            current.start()
        }
        if (url != null && MomentsSongMetadata.songId(url) != null && (initialTitle.isBlank() || initialArtist.isBlank() || artworkUrl == null)) {
            metadataLoading = true; open.isEnabled = false
            status.text = ModuleText.SHARE_METADATA_LOADING.text()
            metadataExecutor.execute {
                val metadata = runCatching { metadataResolver(originalUrl!!) }.getOrNull()
                handler.post {
                    if (isDestroyed || isFinishing || shareStarted) return@post
                    metadataLoading = false
                    if (metadata != null) {
                        artworkUrl = MomentsSongMetadata.artwork(metadata.artworkUrl)
                        if (initialTitle.isBlank() && titleInput.text.toString() == initialTitle) titleInput.setText(metadata.title)
                        if (initialArtist.isBlank() && artistInput.text.toString() == initialArtist) artistInput.setText(metadata.artist.orEmpty())
                    }
                    open.isEnabled = !pending && MomentsSongMetadata.title(titleInput.text.toString()) != null
                    if (!pending) status.text = if (MomentsSongMetadata.title(titleInput.text.toString()) == null)
                        ModuleText.SHARE_METADATA_MISSING.text() else ModuleText.WECHAT_SHARE_NOTICE.text() +
                            (if (artworkUrl == null) "\n" + ModuleText.SHARE_ARTWORK_MISSING.text() else "")
                }
            }
        }
        return content
    }
    val shareUrl: String? get() = Contract.withChinaStorefront(Contract.extract(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()), chinaStorefront)
    /** A local deadline is independent of ordered-broadcast delivery or remote main-thread health. */
    private inner class ShareAttempt(
        val url: String, val title: String, val artist: String?, val artwork: String?,
        val status: TextView, val button: Button, val region: android.widget.Switch, val stop: Button,
    ) {
        val id = UUID.randomUUID().toString()
        private val deadline = SystemClock.elapsedRealtime() + Contract.REQUEST_TIMEOUT_MS
        private var lastCode = 0
        private var detail: String? = null
        private val valid get() = attempt === this && !isDestroyed && !isFinishing
        private val timeout = Runnable { if (valid) fail(lastCode) }
        private val poll = Runnable { if (valid) send() }
        private val tick = object : Runnable {
            override fun run() {
                if (!valid) return
                val seconds = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
                status.text = ModuleText.SHARE_WAITING_SECONDS.text(seconds)
                handler.postDelayed(this, 1000)
            }
        }
        fun start() {
            stop.visibility = android.view.View.VISIBLE
            stop.setOnClickListener { if (valid) finish(ModuleText.SHARE_WAIT_STOPPED.text()) }
            tick.run(); handler.postDelayed(timeout, Contract.REQUEST_TIMEOUT_MS)
            if (owner is MomentsShareActivity) {
                try {
                    val launch = packageManager.getLaunchIntentForPackage(Contract.INTERMEDIARY_PACKAGE)
                    if (launch == null) fail(Contract.RESULT_MISSING)
                    else { startActivity(launch); handler.postDelayed(poll, 800) }
                } catch (_: Exception) { fail(Contract.RESULT_LAUNCH) }
            } else metadataExecutor.execute {
                try {
                    val result = owner.contentResolver.call(android.net.Uri.parse("content://${dev.amenhancer.module.BuildConfig.APPLICATION_ID}.update-control"),
                        "share_prepare", null, null) ?: throw IllegalStateException("Missing relay")
                    handler.post {
                        if (!valid) return@post
                        @Suppress("DEPRECATION")
                        val launch = result.getParcelable<android.app.PendingIntent>("action")
                        if (launch == null) fail(result.getInt("code", Contract.RESULT_RELAY))
                        else try {
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                val options = android.app.ActivityOptions.makeBasic().apply {
                                    setPendingIntentBackgroundActivityStartMode(if (android.os.Build.VERSION.SDK_INT >= 36)
                                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                                    else android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                                }
                                launch.send(owner, 0, null, null, null, null, options.toBundle())
                            } else launch.send()
                            handler.postDelayed(poll, 800)
                        } catch (_: Exception) { fail(Contract.RESULT_LAUNCH) }
                    }
                } catch (_: Exception) { handler.post { if (valid) fail(Contract.RESULT_RELAY) } }
            }
        }
        fun dispose() { handler.removeCallbacks(timeout); handler.removeCallbacks(poll); handler.removeCallbacks(tick) }
        private fun finish(message: String) {
            if (!valid) return
            dispose(); attempt = null; pending = false
            button.isEnabled = true; region.isEnabled = true; stop.visibility = android.view.View.GONE
            status.text = message
        }
        private fun send() {
            val callback = object : android.os.ResultReceiver(handler) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    if (!valid) return
                    lastCode = code; detail = data?.getString(MomentsShareRelay.DETAIL)
                    when {
                        code == Contract.RESULT_OPENED -> finish(ModuleText.WECHAT_EDITOR_OPENED.text())
                        code in listOf(0, Contract.RESULT_NOT_READY, Contract.RESULT_PENDING) -> {
                            handler.removeCallbacks(poll)
                            if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed(poll, 500) else fail(code)
                        }
                        else -> fail(code)
                    }
                }
            }
            val input = Bundle().apply {
                putString(Contract.URL, url); putString(Contract.TITLE, title); putString(Contract.ARTIST, artist)
                putString(Contract.ARTWORK, artwork); putString(Contract.REQUEST_ID, id)
                putParcelable(MomentsShareRelay.CALLBACK, callback)
            }
            if (owner is MomentsShareActivity) MomentsShareRelay.dispatch(this@MomentsSharePanel, input)
            else metadataExecutor.execute {
                try {
                    val accepted = owner.contentResolver.call(android.net.Uri.parse("content://${dev.amenhancer.module.BuildConfig.APPLICATION_ID}.update-control"),
                        "share_request", null, input)?.getBoolean("accepted") == true
                    if (!accepted) handler.post { if (valid) fail(Contract.RESULT_RELAY) }
                } catch (_: Exception) { handler.post { if (valid) fail(Contract.RESULT_RELAY) } }
            }
        }
        private fun fail(code: Int) {
            if (!valid) return
            val message = when (code) {
                0 -> ModuleText.WECHAT_BRIDGE_NOT_LOADED
                Contract.RESULT_NOT_READY -> ModuleText.WECHAT_BRIDGE_NO_ACTIVITY
                Contract.RESULT_PENDING -> ModuleText.WECHAT_PANEL_NOT_VISIBLE
                Contract.RESULT_UNSUPPORTED -> ModuleText.WECHAT_VERSION_UNSUPPORTED
                Contract.RESULT_SIGNATURE -> ModuleText.WECHAT_SIGNATURE_UNSUPPORTED
                Contract.RESULT_CONTRACT -> ModuleText.WECHAT_CONTRACT_UNAVAILABLE
                Contract.RESULT_LAUNCH -> ModuleText.WECHAT_PANEL_LAUNCH_FAILED
                Contract.RESULT_BUSY -> ModuleText.WECHAT_SHARE_BUSY
                Contract.RESULT_RELAY -> ModuleText.SHARE_RELAY_UNAVAILABLE
                Contract.RESULT_MISSING -> ModuleText.WECHAT_MISSING
                else -> ModuleText.WECHAT_BRIDGE_UNAVAILABLE
            }.text()
            val safeDetail = detail?.takeIf { it.matches(Regex("[0-9A-Za-z./_-]{1,80}")) }
            val text = "$message [N$code]" + (safeDetail?.let { " ($it)" } ?: "")
            dev.amenhancer.module.feedback.FeedbackRuntime.record("share", codes = mapOf("result" to code))
            finish(text); Toast.makeText(this@MomentsSharePanel, text, Toast.LENGTH_LONG).show()
        }
    }
    fun saveState(outState: Bundle) {
        outState.putBoolean(Contract.CHINA_STOREFRONT, chinaStorefront)
        outState.putString(Contract.ARTWORK, artworkUrl)
        outState.putString(Contract.TITLE, titleInput.text.toString())
        outState.putString(Contract.ARTIST, artistInput.text.toString())
    }
    fun close() { attempt?.dispose(); attempt = null; closed = true; metadataExecutor.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}
