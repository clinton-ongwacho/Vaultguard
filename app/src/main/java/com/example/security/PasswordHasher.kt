package com.example.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val LEGACY_ITERATIONS = 10_000
    private const val V2_PREFIX = "v2"
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        val chars = password.toCharArray()
        val hash = pbkdf2(chars, salt, ITERATIONS)
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        return "$V2_PREFIX$$ITERATIONS$$saltB64$$hashB64"
    }

    fun needsRehash(stored: String): Boolean = !stored.startsWith("$V2_PREFIX$")

    fun verifyPassword(password: String, stored: String): Boolean {
        if (stored.isBlank()) return false
        return if (stored.startsWith("$V2_PREFIX$")) {
            val parts = stored.split('$', limit = 4)
            if (parts.size != 4) return false
            val iterations = parts[1].toIntOrNull() ?: return false
            if (iterations !in LEGACY_ITERATIONS..1_000_000) return false
            verifyWithParams(password, parts[2], parts[3], iterations)
        } else if (stored.contains(':')) {
            val parts = stored.split(':', limit = 2)
            if (parts.size != 2) return false
            verifyWithParams(password, parts[0], parts[1], LEGACY_ITERATIONS)
        } else {
            false
        }
    }

    private fun verifyWithParams(
        password: String,
        saltB64: String,
        expectedHashB64: String,
        iterations: Int,
    ): Boolean {
        return try {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val expectedHash = Base64.decode(expectedHashB64, Base64.NO_WRAP)
            val chars = password.toCharArray()
            val actualHash = pbkdf2(chars, salt, iterations)
            MessageDigest.isEqual(expectedHash, actualHash)
        } catch (_: Exception) {
            false
        }
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            runCatching { spec.clearPassword() }
            password.fill('\u0000')
        }
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
