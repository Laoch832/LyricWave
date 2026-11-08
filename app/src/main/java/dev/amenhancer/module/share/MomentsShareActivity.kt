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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.i18n.ModuleText

/** Opens NetEase Cloud Music's native share panel with the original Apple Music URL. */
class MomentsShareActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = Contract.extract(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.take(300)?.takeIf(String::isNotBlank) ?: "Apple Music"
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        layout.addView(TextView(this).apply { text = ModuleText.WECHAT_MOMENTS_CARD.text(); textSize = 22f })
        layout.addView(TextView(this).apply { text = title; textSize = 18f })
        layout.addView(TextView(this).apply { text = url.orEmpty(); autoLinkMask = Linkify.WEB_URLS; setTextIsSelectable(true) })
        val status = TextView(this).apply { text = ModuleText.WECHAT_SHARE_NOTICE.text(); textSize = 15f }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = ModuleText.COPY_SONG_LINK.text(); isEnabled = url != null
            setOnClickListener { url?.let { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Apple Music", it)) } }
        })
        val open = Button(this).apply { text = ModuleText.WECHAT_SHARE_OPEN.text(); isEnabled = url != null }
        layout.addView(open); setContentView(layout)
        if (url == null) status.text = ModuleText.WECHAT_LINK_REQUIRED.text()
        open.setOnClickListener {
            if (pending || url == null) return@setOnClickListener
            val launch = packageManager.getLaunchIntentForPackage(Contract.INTERMEDIARY_PACKAGE)
            if (launch == null) { status.text = ModuleText.WECHAT_MISSING.text(); return@setOnClickListener }
            pending = true; open.isEnabled = false
            try {
                startActivity(launch)
                val requestId = UUID.randomUUID().toString()
                val deadline = SystemClock.elapsedRealtime() + Contract.REQUEST_TIMEOUT_MS
                handler.postDelayed({ requestEditor(url, title, requestId, deadline, status, open) }, 800)
            } catch (_: Exception) { pending = false; open.isEnabled = true; status.text = ModuleText.WECHAT_MISSING.text() }
        }
    }
    @Suppress("DEPRECATION")
    private fun requestEditor(url: String, title: String, requestId: String, deadline: Long, status: TextView, button: Button) {
        if (isDestroyed || isFinishing) return
        val request = Intent(Contract.OPEN).setPackage(Contract.INTERMEDIARY_PACKAGE)
            .putExtra(Contract.URL, url).putExtra(Contract.TITLE, title).putExtra(Contract.REQUEST_ID, requestId)
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
                    handler.postDelayed({ requestEditor(url, title, requestId, deadline, status, button) }, 500)
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
                    status.text = "$message [N$code]" + (version?.let { " ($it)" } ?: "")
                    Toast.makeText(this@MomentsShareActivity, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }, handler, 0, null, null)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
