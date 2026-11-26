package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vaultguard_sq_aes_key"
    private const val CONFIG_KEY_ALIAS = "vaultguard_config_aes_key"
    private const val CONFIG_PREFIX = "vgc1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    fun isAndroidKeyStoreAvailable(): Boolean {
        return runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }.isSuccess
    }

    fun isEncryptedConfigSecret(stored: String): Boolean = stored.startsWith(CONFIG_PREFIX)

    private fun getOrCreateSecretKey(alias: String = KEY_ALIAS): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(alias, null) as? SecretKey
            if (existing != null) return@runCatching existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }.getOrNull()
    }

    fun encryptAnswer(plainText: String): String {
        val secretKey = getOrCreateSecretKey(KEY_ALIAS) ?: return ""
        return encryptRaw(plainText, secretKey)
    }

    fun decryptAnswer(payload: String): String {
        if (payload.isBlank() || !payload.contains(':')) return ""
        val secretKey = getOrCreateSecretKey(KEY_ALIAS) ?: return ""
        return decryptRaw(payload, secretKey)
    }

    fun encryptConfigSecret(plainText: String): String {
        if (plainText.isEmpty()) return ""
        if (plainText.startsWith(CONFIG_PREFIX)) return plainText
        val secretKey = getOrCreateSecretKey(CONFIG_KEY_ALIAS) ?: return plainText
        return encryptConfigSecretWithKey(plainText, secretKey)
    }

    fun decryptConfigSecret(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(CONFIG_PREFIX)) return stored
        val secretKey = getOrCreateSecretKey(CONFIG_KEY_ALIAS) ?: return ""
        return decryptConfigSecretWithKey(stored, secretKey)
    }

    fun encryptConfigSecretWithKey(plainText: String, secretKey: SecretKey): String {
        if (plainText.isEmpty()) return ""
        if (plainText.startsWith(CONFIG_PREFIX)) return plainText
        val raw = encryptRaw(plainText, secretKey)
        return if (raw.isNotEmpty()) "$CONFIG_PREFIX$raw" else plainText
    }

    fun decryptConfigSecretWithKey(stored: String, secretKey: SecretKey): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(CONFIG_PREFIX)) return stored
        val raw = stored.removePrefix(CONFIG_PREFIX)
        return decryptRaw(raw, secretKey)
    }

    private fun encryptRaw(plainText: String, secretKey: SecretKey): String {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "$ivB64:$dataB64"
        }.getOrDefault("")
    }

    private fun decryptRaw(payload: String, secretKey: SecretKey): String {
        if (payload.isBlank() || !payload.contains(':')) return ""
        return runCatching {
            val parts = payload.split(':', limit = 2)
            if (parts.size != 2) return@runCatching ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
