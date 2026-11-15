package dev.amenhancer.module.share

/** Evidence supplied by Android, never by referrer strings or intent extras. */
internal data class ShareInitialCaller(val packageName: String?, val uid: Int)

internal object ShareCallerIdentity {
    private const val APPLE = "com.apple.android.music"
    fun accepts(resultCaller: String?, initialCaller: ShareInitialCaller?, installedUid: Int, officialSigner: Boolean): Boolean {
        if (!officialSigner) return false
        if (resultCaller != null) return resultCaller == APPLE
        return initialCaller?.packageName == APPLE && installedUid >= 0 && initialCaller.uid == installedUid
    }
}
