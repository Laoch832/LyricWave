package dev.amenhancer.module.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.amenhancer.module.ModuleConstants
import java.io.File
import java.security.MessageDigest

internal object UpdateApkVerifier {
    fun verify(context: Context, file: File, build: UpdateBuild, hash: Boolean = true) {
        if (hash) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
            } }
            if (digest.digest().joinToString("") { "%02x".format(it) } != build.sha256) throw AppUpdateException("checksum")
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            ?: throw AppUpdateException("apk")
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signature = signatures?.singleOrNull() ?: throw AppUpdateException("signature")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        if (info.packageName != ModuleConstants.MODULE_PACKAGE || code != build.versionCode || info.versionName != build.versionName ||
            fingerprint != AppUpdateClient.CERTIFICATE_SHA256) throw AppUpdateException("signature")
    }
}
