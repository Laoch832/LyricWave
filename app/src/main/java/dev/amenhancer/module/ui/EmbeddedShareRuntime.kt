package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.MomentsLinkContract
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.share.MomentsSharePanel

/** Consume only our own injected chooser target. Other share sheets keep their normal behavior. */
internal object EmbeddedShareRuntime {
    private var installed = false
    private const val SYSTEM_SHARE = "dev.amenhancer.module.system_share"
    fun install(enabled: () -> Boolean) {
        if (installed) return
        dev.amenhancer.module.hook.AppleMusicHostFactory.installEmbeddedShareRouting(enabled) { activity, chooser -> showPanel(activity, chooser) }
        installed = true
    }
    @Suppress("DEPRECATION")
    internal fun showPanel(activity: Activity, chooser: Intent, resolver: ((String) -> dev.amenhancer.module.MomentsSongMetadata?)? = null): Boolean {
        if (activity.isFinishing || activity.isDestroyed || chooser.action != Intent.ACTION_CHOOSER || chooser.getBooleanExtra(SYSTEM_SHARE, false)) return false
        val relay = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)?.filterIsInstance<Intent>()?.firstOrNull {
            it.component?.packageName == ModuleConstants.MODULE_PACKAGE &&
                it.component?.className == "dev.amenhancer.module.share.MomentsShareActivity"
        } ?: return false
        if (MomentsLinkContract.extract(relay.getStringExtra(Intent.EXTRA_TEXT).orEmpty()) == null) return false
        return runCatching {
            val panel = MomentsSharePanel(activity, relay).also { if (resolver != null) it.metadataResolver = resolver }
            val dialog = AlertDialog.Builder(activity).setTitle(ModuleText.WECHAT_MOMENTS_CARD.text())
                .setView(panel.buildView(null, showHeader = false)).setNegativeButton(ModuleText.CLOSE.text(), null)
                .setNeutralButton(ModuleText.SHARE_OTHER.text()) { _, _ ->
                    val source = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    val original = Intent(chooser).putExtra(SYSTEM_SHARE, true)
                    if (source != null) original.putExtra(Intent.EXTRA_INTENT, Intent(source).apply {
                        panel.shareUrl?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    })
                    val others = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS).orEmpty().filterIsInstance<Intent>()
                        .filterNot { it.component?.packageName == ModuleConstants.MODULE_PACKAGE }
                    if (others.isEmpty()) original.removeExtra(Intent.EXTRA_INITIAL_INTENTS)
                    else original.putExtra(Intent.EXTRA_INITIAL_INTENTS, others.toTypedArray())
                    activity.startActivity(original)
                }.create()
            panel.onOutcome = { code, _ ->
                if (code == MomentsLinkContract.RESULT_OPENED) dialog.dismiss()
            }
            dialog.setOnDismissListener { panel.close() }
            dialog.show()
            dialog.window?.decorView?.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = Unit
                override fun onViewDetachedFromWindow(view: android.view.View) { panel.close() }
            })
            true
        }.onFailure { ModernXposedRuntime.log("Embedded share panel failed open", it) }.getOrDefault(false)
    }
}
