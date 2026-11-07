package dev.amenhancer.host.wechat

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import androidx.core.content.ContextCompat
import android.os.Looper
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.hook.ModernMethodHook
import dev.amenhancer.module.hook.ModernXposedRuntime
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Runs only in WeChat's main process. Uses the native link editor; never accesses chat or account data. */
object WeChatShareBridge {
    private val installed = AtomicBoolean(false)
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        ModernXposedRuntime.hookMethod(Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java),
            object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val app = param.args.firstOrNull() as? Application ?: return
                    if (app.packageName == Contract.WECHAT_PACKAGE) register(app)
                }
            })
    }
    private val registered = AtomicBoolean(false)
    private fun register(app: Application) {
        if (!registered.compareAndSet(false, true)) return
        var foreground = WeakReference<Activity>(null)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { foreground = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (foreground.get() === activity) foreground.clear() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, request: Intent) {
                if (request.action != Contract.OPEN) return
                val url = Contract.validate(request.getStringExtra(Contract.URL))
                if (url == null) { resultCode = Contract.RESULT_INVALID; return }
                val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                if (activity == null) { resultCode = Contract.RESULT_NOT_READY; return }
                resultCode = try {
                    activity.startActivity(MomentsLinkEditor.intent(url, request.getStringExtra(Contract.TITLE)))
                    Contract.RESULT_OPENED
                } catch (_: Exception) { Contract.RESULT_UNSUPPORTED }
            }
        }
        // Permission is owned and held by the signed module app. Other apps cannot invoke this bridge.
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Contract.OPEN),
            Contract.PERMISSION, Handler(Looper.getMainLooper()), ContextCompat.RECEIVER_EXPORTED)
    }
}

internal object MomentsLinkEditor {
    fun intent(url: String, title: String?): Intent {
        require(Contract.validate(url) != null)
        return Intent().setClassName(Contract.WECHAT_PACKAGE, "com.tencent.mm.plugin.sns.ui.SnsUploadUI")
            .putExtra("Ksnsupload_type", 1) // Native link-card editor, not image or text posting.
            .putExtra("Ksnsupload_link", url)
            .putExtra("Ksnsupload_title", title?.take(300)?.takeIf(String::isNotBlank) ?: "Apple Music")
            .putExtra("ksnsupload_link_desc", "Apple Music")
            .putExtra("Kdescription", "")
    }
}
