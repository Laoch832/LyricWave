package dev.amenhancer.module.hook

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LabeledIntent
import dev.amenhancer.module.MomentsLinkContract
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.i18n.ModuleText

/** Verified 6.5.2 share URL and lyrics paths both call Android Intent.createChooser. */
internal class AppleMusicMomentsShareTarget(private val enabled: () -> Boolean) : WechatMomentsTarget {
    private val scope = HookRegistrationScope()
    override fun install(): TargetCapabilityInstall {
        if (scope.isActive) return TargetCapabilityInstall.Active("Moments share-sheet destination already installed")
        return try {
            check(ModernXposedRuntime.hookAllMethods(Intent::class.java, "createChooser", object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !enabled()) return
                    val chooser = param.result as? Intent ?: return
                    runCatching { MomentsShareIntents.addDestination(chooser) }.getOrNull()?.let { param.result = it }
                }
            }, scope).isNotEmpty())
            scope.activate()
            TargetCapabilityInstall.Active("Moments destination added to native Android share sheets; no automatic posting")
        } catch (error: Throwable) {
            scope.close()
            TargetCapabilityInstall.Degraded("Moments share destination unavailable: ${error.javaClass.simpleName}")
        }
    }
}

internal object MomentsShareIntents {
    private const val MARKER = "dev.amenhancer.module.moments_added"
    const val RELAY_CLASS = "dev.amenhancer.module.share.MomentsShareActivity"
    @Suppress("DEPRECATION")
    fun addDestination(chooser: Intent): Intent? {
        if (chooser.action != Intent.ACTION_CHOOSER || chooser.getBooleanExtra(MARKER, false)) return null
        val source = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
        if (source.action != Intent.ACTION_SEND) return null
        val text = source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (MomentsLinkContract.extract(text) == null) return null
        val relay = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(ModuleConstants.MODULE_PACKAGE, RELAY_CLASS)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, MomentsLinkContract.extract(text))
            putExtra(Intent.EXTRA_SUBJECT, source.getStringExtra(Intent.EXTRA_SUBJECT)?.take(300))
        }
        val labeled = LabeledIntent(relay, ModuleConstants.MODULE_PACKAGE, ModuleText.WECHAT_MOMENTS_CARD.text(), 0)
        val previous = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS).orEmpty().filterIsInstance<Intent>()
        return Intent(chooser).apply {
            putExtra(MARKER, true)
            // Android supports at most two initial targets. Preserve the first existing target.
            putExtra(Intent.EXTRA_INITIAL_INTENTS, (listOf(labeled) + previous.take(1)).toTypedArray())
        }
    }
}
