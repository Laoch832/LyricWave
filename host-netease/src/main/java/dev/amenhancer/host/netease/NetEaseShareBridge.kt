package dev.amenhancer.host.netease

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.hook.ModernMethodHook
import dev.amenhancer.module.hook.ModernXposedRuntime
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Installed only in NetEase Cloud Music's main process. Never attaches to or hooks WeChat. */
object NetEaseShareBridge {
    private val installed = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            ModernXposedRuntime.hookMethod(Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java),
                object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val app = param.args.firstOrNull() as? Application ?: return
                        if (app.packageName != Contract.INTERMEDIARY_PACKAGE) return
                        runCatching { register(app) }.onFailure {
                            ModernXposedRuntime.log("NetEase share receiver unavailable: ${it.javaClass.simpleName}")
                        }
                    }
                })
        }.onFailure { ModernXposedRuntime.log("NetEase sharing bootstrap unavailable: ${it.javaClass.simpleName}") }
    }

    @Suppress("DEPRECATION")
    private fun verifiedProfile(app: Application): NetEaseShareProfile? {
        val info = app.packageManager.getPackageInfo(app.packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val profile = NetEaseShareProfiles.find(info.versionName.orEmpty(), code) ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        if (signatures.isNullOrEmpty() || signatures.any { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } != NetEaseShareProfiles.SIGNER_SHA256
        }) return null
        return profile
    }

    private fun register(app: Application) {
        if (!registered.compareAndSet(false, true)) return
        var foreground = WeakReference<Activity>(null)
        var contract: NetEaseShareContract? = null
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
                resultCode = runCatching {
                    val url = Contract.validate(request.getStringExtra(Contract.URL)) ?: return@runCatching Contract.RESULT_INVALID
                    val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                        ?: return@runCatching Contract.RESULT_NOT_READY
                    val profile = verifiedProfile(app) ?: return@runCatching Contract.RESULT_UNSUPPORTED
                    val resolved = contract ?: NetEaseShareContract.resolve(app.classLoader, profile).also { contract = it }
                    resolved.open(activity, url, request.getStringExtra(Contract.TITLE))
                    Contract.RESULT_OPENED
                }.getOrDefault(Contract.RESULT_UNSUPPORTED)
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Contract.OPEN),
            Contract.PERMISSION, Handler(Looper.getMainLooper()), ContextCompat.RECEIVER_EXPORTED)
    }
}
