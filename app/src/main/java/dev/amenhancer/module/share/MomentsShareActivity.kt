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
        layout.isFocusableInTouchMode = true; layout.requestFocus()
        val content = ScrollView(this).apply { addView(layout) }
        if (url == null) status.text = ModuleText.WECHAT_LINK_REQUIRED.text()
        open.setOnClickListener {
            if (metadataLoading || pending || url == null) return@setOnClickListener
            val title = MomentsSongMetadata.title(titleInput.text.toString()) ?: return@setOnClickListener
            val artist = MomentsSongMetadata.clean(artistInput.text.toString())
            val launch = packageManager.getLaunchIntentForPackage(Contract.INTERMEDIARY_PACKAGE)
            if (launch == null) { status.text = ModuleText.WECHAT_MISSING.text(); return@setOnClickListener }
            val sharedUrl = Contract.withChinaStorefront(url, chinaStorefront) ?: return@setOnClickListener
            shareStarted = true; pending = true; open.isEnabled = false; regionSwitch.isEnabled = false
            try {
                startActivity(launch)
                val requestId = UUID.randomUUID().toString()
                val deadline = SystemClock.elapsedRealtime() + Contract.REQUEST_TIMEOUT_MS
                handler.postDelayed({ requestEditor(sharedUrl, title, artist, artworkUrl, requestId, deadline, status, open) }, 800)
            } catch (_: Exception) { pending = false; open.isEnabled = true; status.text = ModuleText.WECHAT_MISSING.text() }
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
    @Suppress("DEPRECATION")
    private fun requestEditor(url: String, title: String, artist: String?, artworkUrl: String?, requestId: String, deadline: Long, status: TextView, button: Button) {
        if (isDestroyed || isFinishing) return
        val request = Intent(Contract.OPEN).setPackage(Contract.INTERMEDIARY_PACKAGE)
            .putExtra(Contract.URL, url).putExtra(Contract.TITLE, title).putExtra(Contract.ARTIST, artist).putExtra(Contract.ARTWORK, artworkUrl).putExtra(Contract.REQUEST_ID, requestId)
        sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (isDestroyed || isFinishing) return
                if (resultCode == Contract.RESULT_OPENED) {
                    pending = false; button.isEnabled = true
                    status.text = ModuleText.WECHAT_EDITOR_OPENED.text()
                    return
                }
                val code = resultCode
                if (SystemClock.elapsedRealtime() < deadline &&
                    code in listOf(0, Contract.RESULT_NOT_READY, Contract.RESULT_PENDING)) {
                    status.text = ModuleText.WECHAT_SHARE_WAITING.text()
                    handler.postDelayed({ requestEditor(url, title, artist, artworkUrl, requestId, deadline, status, button) }, 500)
                } else {
                    pending = false; button.isEnabled = true
                    val message = when (code) {
                        0 -> ModuleText.WECHAT_BRIDGE_NOT_LOADED.text()
                        Contract.RESULT_NOT_READY -> ModuleText.WECHAT_BRIDGE_NO_ACTIVITY.text()
                        Contract.RESULT_PENDING -> ModuleText.WECHAT_PANEL_NOT_VISIBLE.text()
                        Contract.RESULT_UNSUPPORTED -> ModuleText.WECHAT_VERSION_UNSUPPORTED.text()
                        Contract.RESULT_SIGNATURE -> ModuleText.WECHAT_SIGNATURE_UNSUPPORTED.text()
                        Contract.RESULT_CONTRACT -> ModuleText.WECHAT_CONTRACT_UNAVAILABLE.text()
                        Contract.RESULT_LAUNCH -> ModuleText.WECHAT_PANEL_LAUNCH_FAILED.text()
                        Contract.RESULT_BUSY -> ModuleText.WECHAT_SHARE_BUSY.text()
                        else -> ModuleText.WECHAT_BRIDGE_UNAVAILABLE.text()
                    }
                    // Only fixed result codes and version metadata are returned, never song content.
                    val version = resultData?.takeIf { it.matches(Regex("[0-9A-Za-z./_-]{1,80}")) }
                    dev.amenhancer.module.feedback.FeedbackRuntime.record("share", codes = mapOf("result" to code))
                    status.text = "$message [N$code]" + (version?.let { " ($it)" } ?: "")
                    Toast.makeText(this@MomentsSharePanel, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }, handler, 0, null, null)
    }
    fun saveState(outState: Bundle) {
        outState.putBoolean(Contract.CHINA_STOREFRONT, chinaStorefront)
        outState.putString(Contract.ARTWORK, artworkUrl)
        outState.putString(Contract.TITLE, titleInput.text.toString())
        outState.putString(Contract.ARTIST, artistInput.text.toString())
    }
    fun close() { closed = true; metadataExecutor.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}
