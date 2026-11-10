package dev.amenhancer.module.share

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.MomentsSongMetadata
import dev.amenhancer.module.i18n.ModuleText

/** A single confirmed handoff, no second editor and no provider dependency. Never posts content. */
class MomentsShareHandoffActivity : Activity() {
    private var panel: MomentsSharePanel? = null
    private var callback: ResultReceiver? = null
    private var completed = false
    private var started = false
    private var resumed = false
    private var approved = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { complete(MomentsShareHandoff.CANCELLED) }
    private var deadline = 0L
    private var requestId = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        callback = intent.getParcelableExtra(MomentsShareHandoff.CALLBACK)
        deadline = intent.getLongExtra(MomentsShareHandoff.DEADLINE, 0)
        requestId = intent.getStringExtra(Contract.REQUEST_ID).orEmpty()
        if (callback == null || intent.action != MomentsShareHandoff.ACTION || !MomentsShareHandoff.trustedOrigin(this) ||
            Contract.extract(intent.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty()) == null ||
            MomentsSongMetadata.title(intent.getStringExtra(Contract.TITLE)) == null ||
            !requestId.matches(Regex("[A-Za-z0-9-]{1,100}")) ||
            deadline - SystemClock.elapsedRealtime() !in 1..Contract.REQUEST_TIMEOUT_MS) {
            complete(MomentsShareHandoff.REJECTED); return
        }
        panel = MomentsSharePanel(this, intent).also { controller ->
            // The host already confirmed these fields; do not repeat a network lookup.
            controller.metadataResolver = { null }
            controller.onOutcome = { code, detail -> complete(code, detail) }
            controller.buildView(null, showHeader = false)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32)
            addView(TextView(this@MomentsShareHandoffActivity).apply { text = ModuleText.SHARE_HANDOFF_PROGRESS.text(); textSize = 18f })
            addView(Button(this@MomentsShareHandoffActivity).apply {
                text = ModuleText.SHARE_STOP_WAITING.text(); setOnClickListener { complete(MomentsShareHandoff.CANCELLED) }
            })
        })
        handler.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        callback?.send(MomentsShareHandoff.READY, Bundle().apply {
            putParcelable(MomentsShareHandoff.CANCEL, object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == MomentsShareHandoff.READY && !completed) { approved = true; startIfApproved() }
                    else complete(MomentsShareHandoff.CANCELLED)
                }
            })
        })
    }
    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        startIfApproved()
    }
    private fun startIfApproved() {
        if (!completed && !started && resumed && approved) {
            started = true
            if (deadline <= SystemClock.elapsedRealtime()) complete(MomentsShareHandoff.CANCELLED)
            else panel?.startConfirmedShare(requestId, deadline)
        }
    }
    private fun complete(code: Int, detail: String? = null) {
        if (completed) return
        completed = true; handler.removeCallbacks(timeout); panel?.close()
        callback?.send(code, Bundle().apply { detail?.let { putString(MomentsShareRelay.DETAIL, it) } })
        // The capability reports the outcome; do not feed an unrelated success into native Activity results.
        setResult(RESULT_CANCELED)
        finish()
    }
    override fun onDestroy() {
        if (!completed) complete(MomentsShareHandoff.CANCELLED)
        panel?.close(); callback = null
        super.onDestroy()
    }
}
