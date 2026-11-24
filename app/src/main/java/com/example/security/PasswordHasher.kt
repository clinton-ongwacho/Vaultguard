package com.example.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val ITERATIONS = 10_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2(password.toCharArray(), salt)
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        return "$saltB64:$hashB64"
    }

    fun verifyPassword(password: String, stored: String): Boolean {
        if (stored.isBlank() || !stored.contains(':')) return false
        val parts = stored.split(':', limit = 2)
        if (parts.size != 2) return false
        return try {
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val expectedHash = Base64.decode(parts[1], Base64.NO_WRAP)
            val actualHash = pbkdf2(password.toCharArray(), salt)
            MessageDigest.isEqual(expectedHash, actualHash)
        } catch (_: Exception) {
            false
        }
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Maps any admin wake word (e.g. "shadow") into T9 keypad digits (e.g. "742369")
     * so the user can dial *#*#742369#*#* in the Phone Dialer to unhide/open VaultGuard.
     */
    fun wakeWordToT9Digits(word: String): String {
        val sb = StringBuilder()
        for (ch in word.lowercase()) {
            when (ch) {
                'a', 'b', 'c' -> sb.append('2')
                'd', 'e', 'f' -> sb.append('3')
                'g', 'h', 'i' -> sb.append('4')
                'j', 'k', 'l' -> sb.append('5')
                'm', 'n', 'o' -> sb.append('6')
                'p', 'q', 'r', 's' -> sb.append('7')
                't', 'u', 'v' -> sb.append('8')
                'w', 'x', 'y', 'z' -> sb.append('9')
                in '0'..'9' -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalizeSecurityAnswer(answer: String): String =
        answer.trim().lowercase().replace(Regex("\\s+"), " ")

    fun hashSecurityAnswer(answer: String): String =
        hashPassword(normalizeSecurityAnswer(answer))

    fun verifySecurityAnswer(answer: String, storedHash: String): Boolean {
        val normalized = normalizeSecurityAnswer(answer)
        if (normalized.isEmpty()) return false
        return verifyPassword(normalized, storedHash)
    }

    /**
     * Counts how many stored security-question hashes match the supplied answers.
     * Requires at least 3 stored questions and at least 2 correct matches to qualify for reset.
     */
    fun qualifiesForPasswordReset(
        storedHashesByQuestionId: Map<Long, String>,
        providedAnswersByQuestionId: Map<Long, String>,
        minQuestionsRequired: Int = 3,
        minMatchesRequired: Int = 2,
    ): Boolean {
        if (storedHashesByQuestionId.size < minQuestionsRequired) return false
        var matches = 0
        for ((id, storedHash) in storedHashesByQuestionId) {
            val answer = providedAnswersByQuestionId[id].orEmpty()
            if (verifySecurityAnswer(answer, storedHash)) {
                matches++
            }
        }
        return matches >= minMatchesRequired
    }
}
