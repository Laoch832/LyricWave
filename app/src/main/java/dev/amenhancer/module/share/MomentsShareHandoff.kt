package dev.amenhancer.module.share

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.update.UpdateControlProvider
import java.security.MessageDigest

/** Explicit Activity IPC works even when the host cannot resolve our provider. */
internal object MomentsShareHandoff {
    const val ACTION = "dev.amenhancer.module.action.CONFIRMED_MOMENTS_SHARE"
    const val CALLBACK = "handoff_callback"
    const val REJECTION = "handoff_rejection"
    const val CANCEL = "handoff_cancel"
    const val DEADLINE = "handoff_deadline"
    const val READY = 100
    const val CANCELLED = 101
    const val REJECTED = 102
    const val REQUEST_CODE = 0x4c57

    fun intent(url: String, title: String, artist: String?, artwork: String?, id: String, deadline: Long, callback: ResultReceiver) =
        Intent(ACTION).setClassName(BuildConfig.APPLICATION_ID, MomentsShareHandoffActivity::class.java.name)
            .putExtra(Intent.EXTRA_TEXT, url).putExtra(Contract.TITLE, title).putExtra(Contract.ARTIST, artist)
            .putExtra(Contract.ARTWORK, artwork).putExtra(Contract.REQUEST_ID, id)
            .putExtra(DEADLINE, deadline).putExtra(CALLBACK, callback)

    /** getCallingPackage is supplied by Android's Activity result relationship, never by intent extras. */
    @Suppress("DEPRECATION")
    fun trustedOrigin(activity: Activity): Boolean = runCatching {
        val resultCaller = activity.callingPackage
        if (resultCaller != null && resultCaller != "com.apple.android.music") return false
        val initial = if (resultCaller == null && Build.VERSION.SDK_INT >= 35) Api35.initialCaller(activity) else null
        if (resultCaller == null && initial?.packageName != "com.apple.android.music") return false
        val info = activity.packageManager.getPackageInfo("com.apple.android.music",
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val officialSigner = signers?.singleOrNull()?.let { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } == UpdateControlProvider.APPLE_CERT
        } == true
        ShareCallerIdentity.accepts(resultCaller, initial, info.applicationInfo?.uid ?: -1, officialSigner)
    }.getOrDefault(false)

    @android.annotation.TargetApi(35)
    private object Api35 {
        fun initialCaller(activity: Activity): ShareInitialCaller? = activity.initialCaller?.let {
            ShareInitialCaller(it.getPackage(), it.uid)
        }
    }
}
