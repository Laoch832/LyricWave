package dev.amenhancer.module.media

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Apple Music account token belongs only to the module UID; never sent to LSPosed host. */
internal object MediaDownloadSecrets {
    private const val STORE = "lyricwave_media_credentials"
    private const val KEY = "encrypted_media_user_token"
    private const val ALIAS = "lyricwave_media_token_aes_v1"
    private const val GCM_TAG_BITS = 128

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, token: String) {
        val value = token.trim().also { require(it.length in 10..4096) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(KEY, Base64.encodeToString(encoded, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): String? = runCatching {
        val packed = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
