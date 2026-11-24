package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object VaultFileCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vaultguard_media_aes_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12
    private const val CHUNK_SIZE = 64 * 1024
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'G'.code.toByte(), 'E'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())

    private val secureRandom = SecureRandom()

    fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(false)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        key: SecretKey = getOrCreateMasterKey(),
    ) {
        val dataOut = DataOutputStream(output)
        dataOut.write(MAGIC)

        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            val iv = ByteArray(IV_LENGTH).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val encrypted = cipher.doFinal(buffer, 0, read)
            dataOut.writeInt(encrypted.size)
            dataOut.write(iv)
            dataOut.write(encrypted)
        }
        dataOut.flush()
    }

    fun decryptToStream(
        input: InputStream,
        output: OutputStream,
        key: SecretKey = getOrCreateMasterKey(),
    ) {
        val dataIn = DataInputStream(input)
        val magicRead = ByteArray(MAGIC.size)
        dataIn.readFully(magicRead)
        require(magicRead.contentEquals(MAGIC)) { "Invalid encrypted vault file header." }

        val iv = ByteArray(IV_LENGTH)
        while (true) {
            val encryptedSize = try {
                dataIn.readInt()
            } catch (_: EOFException) {
                break
            }
            require(encryptedSize in 1..(CHUNK_SIZE + 1024)) { "Corrupt encrypted chunk size." }
            dataIn.readFully(iv)
            val cipherBytes = ByteArray(encryptedSize)
            dataIn.readFully(cipherBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(cipherBytes)
            output.write(plain)
        }
        output.flush()
    }

    fun encryptBytes(
        plainBytes: ByteArray,
        destFile: File,
        key: SecretKey = getOrCreateMasterKey(),
    ) {
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        FileOutputStream(tempFile).use { fos ->
            encryptStream(plainBytes.inputStream(), fos, key)
            fos.fd.sync()
        }
        if (destFile.exists()) {
            destFile.delete()
        }
        if (!tempFile.renameTo(destFile)) {
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()
        }
    }

    fun decryptToBytes(
        sourceFile: File,
        key: SecretKey = getOrCreateMasterKey(),
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        FileInputStream(sourceFile).use { fis ->
            decryptToStream(fis, baos, key)
        }
        return baos.toByteArray()
    }
}
