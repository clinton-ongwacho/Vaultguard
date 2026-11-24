package com.example.data.entity

enum class VaultMediaCategory(val label: String) {
    PHOTO("Photos"),
    VIDEO("Videos"),
    AUDIO("Music & Audio"),
    DOCUMENT("Documents"),
    OTHER("Other Media");

    companion object {
        fun fromName(raw: String): VaultMediaCategory =
            entries.firstOrNull { it.name == raw } ?: OTHER

        fun detectCategory(mimeType: String, fileName: String): VaultMediaCategory {
            val mime = mimeType.lowercase()
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp") -> PHOTO
                mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp") -> VIDEO
                mime.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus") -> AUDIO
                mime == "application/pdf" ||
                    mime.startsWith("text/") ||
                    mime.contains("document") ||
                    mime.contains("spreadsheet") ||
                    mime.contains("presentation") ||
                    mime.contains("msword") ||
                    ext in setOf("pdf", "doc", "docx", "txt", "csv", "xls", "xlsx", "ppt", "pptx", "rtf", "md") -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

/**
 * Stored strictly inside VaultGuard's encrypted private index file (.vault_index.vgem),
 * NOT in the Room SQLite database.
 */
data class VaultMediaEntity(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val category: String,
    val encryptedFileName: String,
    val sizeBytes: Long,
    val originalRelativePath: String,
    val originalUriString: String,
    val removedFromGallery: Boolean,
    val addedAtMillis: Long,
) {
    val categoryEnum: VaultMediaCategory
        get() = VaultMediaCategory.fromName(category)
}
