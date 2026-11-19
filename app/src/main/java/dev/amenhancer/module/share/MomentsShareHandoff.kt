package dev.amenhancer.module.share

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import dev.amenhancer.module.AppleMusicSigningIdentity
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.MomentsLinkContract as Contract

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

    /** Android supplies either the result relationship or the initial Activity caller. */
    @Suppress("DEPRECATION")
    fun trustedOrigin(activity: Activity): Boolean = runCatching {
        val resultCaller = activity.callingPackage
        if (resultCaller != null && resultCaller != AppleMusicSigningIdentity.PACKAGE) return false
        val initial = if (resultCaller == null && Build.VERSION.SDK_INT >= 35) Api35.initialCaller(activity) else null
        if (resultCaller == null && initial?.packageName != AppleMusicSigningIdentity.PACKAGE) return false
        val info = activity.packageManager.getPackageInfo(AppleMusicSigningIdentity.PACKAGE,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val officialSigner = AppleMusicSigningIdentity.isOfficial(info)
        ShareCallerIdentity.accepts(resultCaller, initial, info.applicationInfo?.uid ?: -1, officialSigner)
    }.getOrDefault(false)

    @android.annotation.TargetApi(35)
    private object Api35 {
        fun initialCaller(activity: Activity): ShareInitialCaller? = activity.initialCaller?.let {
            ShareInitialCaller(it.getPackage(), it.uid)
        }
    }
}
