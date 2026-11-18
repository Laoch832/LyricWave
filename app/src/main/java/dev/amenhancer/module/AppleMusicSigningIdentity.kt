package dev.amenhancer.module

import android.content.pm.PackageInfo
import android.os.Build
import java.security.MessageDigest

/** Trust the Apple signing certificate or an Android-verified rotation from it. */
internal object AppleMusicSigningIdentity {
    const val PACKAGE = "com.apple.android.music"
    private const val APPLE_CERT = "88ba590ec2e1ea33c4458daf59489faee2cef297a9b4071e18cf82ef531100aa"

    @Suppress("DEPRECATION")
    fun isOfficial(info: PackageInfo): Boolean {
        if (info.packageName != PACKAGE) return false
        val certificates = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return false
            val current = signing.apkContentsSigners?.singleOrNull() ?: return false
            if (signing.hasMultipleSigners()) return false
            signing.signingCertificateHistory ?: arrayOf(current)
        } else {
            arrayOf(info.signatures?.singleOrNull() ?: return false)
        }
        return certificates.any { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
                .joinToString("") { "%02x".format(it) } == APPLE_CERT
        }
    }
}
