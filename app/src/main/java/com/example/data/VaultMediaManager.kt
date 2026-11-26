package com.example.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.data.entity.VaultMediaCategory
import com.example.data.entity.VaultMediaEntity
import com.example.security.VaultFileCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class VaultImportResult(
    val importedItems: List<VaultMediaEntity>,
    val removedCount: Int,
    val pendingDeleteIntentSender: IntentSender?,
    val pendingDeleteItemIds: List<String>,
)

class VaultMediaManager(context: Context) {
    private val appContext = context.applicationContext
    private val indexMutex = Mutex()
    private val _vaultMediaFlow = MutableStateFlow<List<VaultMediaEntity>>(loadIndexFromDisk())
    val vaultMediaFlow: StateFlow<List<VaultMediaEntity>> = _vaultMediaFlow.asStateFlow()

    private fun vaultDir(): File {
        val dir = File(appContext.filesDir, "vault_media")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            runCatching { noMedia.createNewFile() }
        }
        return dir
    }

    private fun streamTempDir(): File {
        val dir = File(appContext.filesDir, "vault_stream")
        if (!dir.exists()) dir.mkdirs()
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
        return dir
    }

    private fun docPreviewCacheDir(): File {
        val dir = File(appContext.cacheDir, "unlocked_doc_preview")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun indexFile(): File = File(vaultDir(), ".vault_index.vgem")

    fun clearUnlockedTempPreviews() {
        runCatching {
            streamTempDir().listFiles()?.forEach { f ->
                if (f.name != ".nomedia") f.delete()
            }
        }
        runCatching {
            docPreviewCacheDir().listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    suspend fun copyUrisToPrivateVault(uris: List<Uri>): VaultImportResult = withContext(Dispatchers.IO) {
        val dir = vaultDir()
        val resolver = appContext.contentResolver
        val imported = mutableListOf<VaultMediaEntity>()
        var removedCount = 0
        val pendingDeleteMediaUris = mutableListOf<Uri>()
        val pendingItemIds = mutableListOf<String>()

        for (uri in uris) {
            val resolvedName = queryDisplayName(uri) ?: "vault_item_${System.currentTimeMillis()}"
            val mime = resolver.getType(uri) ?: guessMimeFromName(resolvedName)
            val category = VaultMediaCategory.detectCategory(mime, resolvedName)
            val deleteCandidateMediaUri = resolveMediaStoreUri(uri)
            val originalRelPath = queryRelativePath(uri, deleteCandidateMediaUri, category)
            val itemId = UUID.randomUUID().toString()
            val encryptedFileName = "$itemId.vgem"
            val destFile = File(dir, encryptedFileName)

            val plainSize = queryFileSize(uri)
            val encryptedOk = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        VaultFileCrypto.encryptStream(input, output)
                        output.fd.sync()
                    }
                    true
                } ?: false
            }.getOrDefault(false) && destFile.exists() && destFile.length() > 0L

            if (!encryptedOk) {
                runCatching { destFile.delete() }
                continue
            }

            var removedDirectly = false

            // Attempt 1: DocumentsContract deletion (works for Downloads / ExternalStorage providers)
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                val docDeleted = runCatching {
                    DocumentsContract.deleteDocument(resolver, uri)
                }.getOrDefault(false)
                if (docDeleted) {
                    removedDirectly = true
                }
            }

            // Attempt 2: Direct ContentResolver delete
            if (!removedDirectly) {
                try {
                    val rows = resolver.delete(deleteCandidateMediaUri ?: uri, null, null)
                    if (rows > 0) {
                        removedDirectly = true
                    }
                } catch (sec: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sec is RecoverableSecurityException) {
                        val pendingTarget = deleteCandidateMediaUri ?: uri
                        if (pendingTarget !in pendingDeleteMediaUris) {
                            pendingDeleteMediaUris += pendingTarget
                        }
                    } else if (deleteCandidateMediaUri != null && deleteCandidateMediaUri !in pendingDeleteMediaUris) {
                        pendingDeleteMediaUris += deleteCandidateMediaUri
                    }
                } catch (_: Exception) {
                    if (deleteCandidateMediaUri != null && deleteCandidateMediaUri !in pendingDeleteMediaUris) {
                        pendingDeleteMediaUris += deleteCandidateMediaUri
                    }
                }
            }

            if (!removedDirectly && deleteCandidateMediaUri != null && deleteCandidateMediaUri !in pendingDeleteMediaUris) {
                pendingDeleteMediaUris += deleteCandidateMediaUri
            }

            if (removedDirectly) {
                removedCount += 1
            } else if (deleteCandidateMediaUri != null) {
                pendingItemIds += itemId
            }

            val entity = VaultMediaEntity(
                id = itemId,
                displayName = resolvedName,
                mimeType = mime,
                category = category.name,
                encryptedFileName = encryptedFileName,
                sizeBytes = plainSize.takeIf { it > 0L } ?: destFile.length(),
                originalRelativePath = originalRelPath,
                originalUriString = uri.toString(),
                removedFromGallery = removedDirectly,
                addedAtMillis = System.currentTimeMillis(),
            )
            imported += entity
        }

        if (imported.isNotEmpty()) {
            indexMutex.withLock {
                val current = loadIndexFromDisk()
                val updated = imported.reversed() + current
                saveIndexToDisk(updated)
                _vaultMediaFlow.value = updated
            }
        }

        val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pendingDeleteMediaUris.isNotEmpty()) {
            runCatching {
                MediaStore.createDeleteRequest(resolver, pendingDeleteMediaUris.distinct()).intentSender
            }.getOrNull()
        } else {
            null
        }

        VaultImportResult(
            importedItems = imported,
            removedCount = removedCount,
            pendingDeleteIntentSender = intentSender,
            pendingDeleteItemIds = pendingItemIds,
        )
    }

    suspend fun markItemsRemovedFromGallery(ids: List<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        withContext(Dispatchers.IO) {
            indexMutex.withLock {
                val updated = _vaultMediaFlow.value.map { item ->
                    if (item.id in idSet) item.copy(removedFromGallery = true) else item
                }
                saveIndexToDisk(updated)
                _vaultMediaFlow.value = updated
            }
        }
    }

    private fun resolveVaultFile(encryptedFileName: String): File? {
        if (!encryptedFileName.matches(Regex("^[a-zA-Z0-9._-]+\\.vgem$"))) return null
        val root = vaultDir()
        val candidate = File(root, encryptedFileName)
        return runCatching {
            val rootPrefix = root.canonicalPath + File.separator
            if (candidate.canonicalPath.startsWith(rootPrefix)) candidate else null
        }.getOrNull()
    }

    suspend fun decryptPhotoBytes(item: VaultMediaEntity): ByteArray? = withContext(Dispatchers.IO) {
        val encFile = resolveVaultFile(item.encryptedFileName) ?: return@withContext null
        if (!encFile.exists()) return@withContext null
        runCatching { VaultFileCrypto.decryptToBytes(encFile) }.getOrNull()
    }

    suspend fun decryptToInAppStreamFile(item: VaultMediaEntity): File? = withContext(Dispatchers.IO) {
        val encFile = resolveVaultFile(item.encryptedFileName) ?: return@withContext null
        if (!encFile.exists()) return@withContext null
        val safeId = item.id.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "media" }
        val ext = item.displayName.substringAfterLast('.', "").takeIf {
            it.matches(Regex("^[a-zA-Z0-9]{1,8}$"))
        }
        val outName = if (ext != null) "$safeId.$ext" else safeId
        val tempRoot = streamTempDir()
        val outFile = File(tempRoot, outName)
        val confined = runCatching {
            outFile.canonicalPath.startsWith(tempRoot.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!confined) return@withContext null
        val ok = runCatching {
            FileInputStream(encFile).use { fis ->
                FileOutputStream(outFile).use { fos ->
                    VaultFileCrypto.decryptToStream(fis, fos)
                }
            }
        }.isSuccess && outFile.exists()
        if (ok) outFile else null
    }

    suspend fun decryptToDocPreviewFile(item: VaultMediaEntity): File? = withContext(Dispatchers.IO) {
        val encFile = resolveVaultFile(item.encryptedFileName) ?: return@withContext null
        if (!encFile.exists()) return@withContext null
        val safeId = item.id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(12).ifBlank { "doc" }
        val safeName = item.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trimStart('.').ifBlank { "document" }
        val previewRoot = docPreviewCacheDir()
        val outFile = File(previewRoot, "${safeId}_$safeName")
        val confined = runCatching {
            outFile.canonicalPath.startsWith(previewRoot.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!confined) return@withContext null
        val ok = runCatching {
            FileInputStream(encFile).use { fis ->
                FileOutputStream(outFile).use { fos ->
                    VaultFileCrypto.decryptToStream(fis, fos)
                }
            }
        }.isSuccess && outFile.exists()
        if (ok) outFile else null
    }

    /**
     * Deleting/removing an item from Vault ALWAYS restores the decrypted file back to its
     * original phone gallery / storage location so media never disappears on deletion.
     */
    suspend fun deleteFromVaultReturningToGallery(item: VaultMediaEntity): Result<String> = withContext(Dispatchers.IO) {
        val encFile = resolveVaultFile(item.encryptedFileName)
        if (encFile == null || !encFile.exists()) {
            indexMutex.withLock {
                val updated = _vaultMediaFlow.value.filterNot { it.id == item.id }
                saveIndexToDisk(updated)
                _vaultMediaFlow.value = updated
            }
            return@withContext Result.failure(IllegalStateException("Encrypted file missing from private storage."))
        }

        val resolver = appContext.contentResolver
        return@withContext runCatching {
            val (collectionUri, defaultRelDir) = collectionAndDefaultDirFor(item.categoryEnum)
            val targetRelPath = normalizeRelativePathForCategory(
                raw = item.originalRelativePath,
                category = item.categoryEnum,
                fallback = defaultRelDir,
            )

            val outUri = insertIntoMediaStoreWithFallback(
                collectionUri = collectionUri,
                displayName = item.displayName,
                mimeType = item.mimeType,
                preferredRelativePath = targetRelPath,
                fallbackRelativePath = defaultRelDir,
            )

            resolver.openOutputStream(outUri)?.use { out ->
                FileInputStream(encFile).use { fis ->
                    VaultFileCrypto.decryptToStream(fis, out)
                }
            } ?: throw IllegalStateException("Unable to write file back to device gallery.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(outUri, doneValues, null, null)
            }

            encFile.delete()
            indexMutex.withLock {
                val updated = _vaultMediaFlow.value.filterNot { it.id == item.id }
                saveIndexToDisk(updated)
                _vaultMediaFlow.value = updated
            }
            "Removed from Vault and returned \"${item.displayName}\" to $targetRelPath"
        }
    }

    private fun insertIntoMediaStoreWithFallback(
        collectionUri: Uri,
        displayName: String,
        mimeType: String,
        preferredRelativePath: String,
        fallbackRelativePath: String,
    ): Uri {
        val resolver = appContext.contentResolver
        fun buildValues(relPath: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return runCatching {
            resolver.insert(collectionUri, buildValues(preferredRelativePath))
        }.getOrNull()
            ?: resolver.insert(collectionUri, buildValues(fallbackRelativePath))
            ?: throw IllegalStateException("Unable to restore item to phone gallery.")
    }

    private fun collectionAndDefaultDirFor(category: VaultMediaCategory): Pair<Uri, String> {
        return when (category) {
            VaultMediaCategory.PHOTO -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                uri to Environment.DIRECTORY_PICTURES
            }
            VaultMediaCategory.VIDEO -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                uri to Environment.DIRECTORY_MOVIES
            }
            VaultMediaCategory.AUDIO -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                uri to Environment.DIRECTORY_MUSIC
            }
            VaultMediaCategory.DOCUMENT -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }
                uri to Environment.DIRECTORY_DOCUMENTS
            }
            VaultMediaCategory.OTHER -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }
                uri to Environment.DIRECTORY_DOWNLOADS
            }
        }
    }

    private fun normalizeRelativePathForCategory(
        raw: String,
        category: VaultMediaCategory,
        fallback: String,
    ): String {
        val cleaned = raw.trim().trim('/')
        if (cleaned.isEmpty()) return fallback
        val top = cleaned.substringBefore('/')
        val allowedRoots = when (category) {
            VaultMediaCategory.PHOTO -> setOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES)
            VaultMediaCategory.VIDEO -> setOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_PICTURES)
            VaultMediaCategory.AUDIO -> setOf(Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_ALARMS, Environment.DIRECTORY_NOTIFICATIONS, Environment.DIRECTORY_PODCASTS, Environment.DIRECTORY_RINGTONES)
            VaultMediaCategory.DOCUMENT -> setOf(Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_DOWNLOADS)
            VaultMediaCategory.OTHER -> setOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS)
        }
        return if (top in allowedRoots) cleaned else fallback
    }

    private fun queryRelativePath(
        uri: Uri,
        mediaStoreUri: Uri?,
        category: VaultMediaCategory,
    ): String {
        val target = mediaStoreUri ?: uri
        val resolver = appContext.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fromRel = runCatching {
                resolver.query(
                    target,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        val rel = if (relIdx >= 0) cursor.getString(relIdx) else null
                        if (!rel.isNullOrBlank()) {
                            rel.trim().trim('/')
                        } else {
                            val bIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                            val bucket = if (bIdx >= 0) cursor.getString(bIdx) else null
                            if (!bucket.isNullOrBlank()) "${defaultRootFor(category)}/$bucket" else null
                        }
                    } else null
                }
            }.getOrNull()
            if (!fromRel.isNullOrBlank()) return fromRel
        }

        // Document ID heuristic e.g. "primary:DCIM/Camera/IMG_1234.jpg"
        if (DocumentsContract.isDocumentUri(appContext, uri)) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            if (docId.contains(':')) {
                val pathPart = docId.substringAfter(':').trim('/')
                if (pathPart.contains('/')) {
                    return pathPart.substringBeforeLast('/')
                }
            }
        }
        return defaultRootFor(category)
    }

    private fun defaultRootFor(category: VaultMediaCategory): String = when (category) {
        VaultMediaCategory.PHOTO -> Environment.DIRECTORY_PICTURES
        VaultMediaCategory.VIDEO -> Environment.DIRECTORY_MOVIES
        VaultMediaCategory.AUDIO -> Environment.DIRECTORY_MUSIC
        VaultMediaCategory.DOCUMENT -> Environment.DIRECTORY_DOCUMENTS
        VaultMediaCategory.OTHER -> Environment.DIRECTORY_DOWNLOADS
    }

    private fun resolveMediaStoreUri(uri: Uri): Uri? {
        val str = uri.toString()
        if (str.startsWith("content://media/external/") || str.startsWith("content://media/external_primary/")) {
            return uri
        }
        if (DocumentsContract.isDocumentUri(appContext, uri) && uri.authority == "com.android.providers.media.documents") {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            val parts = docId.split(':', limit = 2)
            if (parts.size == 2) {
                val numericId = parts[1].toLongOrNull() ?: return null
                val baseUri = when (parts[0]) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    "document" -> MediaStore.Files.getContentUri("external")
                    else -> MediaStore.Files.getContentUri("external")
                }
                return ContentUris.withAppendedId(baseUri, numericId)
            }
        }
        if (uri.authority == "media" && uri.pathSegments.any { it.startsWith("picker") }) {
            val lastId = uri.lastPathSegment?.toLongOrNull()
            if (lastId != null) {
                val mime = appContext.contentResolver.getType(uri).orEmpty()
                val base = when {
                    mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                return ContentUris.withAppendedId(base, lastId)
            }
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun queryFileSize(uri: Uri): Long {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun loadIndexFromDisk(): List<VaultMediaEntity> {
        val file = indexFile()
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            val jsonStr = String(VaultFileCrypto.decryptToBytes(file), Charsets.UTF_8)
            val array = JSONArray(jsonStr)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val encFileName = obj.getString("encryptedFileName")
                    val encFile = resolveVaultFile(encFileName)
                    // Only expose items whose encrypted payload exists inside private vault storage
                    if (encFile != null && encFile.exists()) {
                        add(
                            VaultMediaEntity(
                                id = obj.getString("id"),
                                displayName = obj.getString("displayName"),
                                mimeType = obj.getString("mimeType"),
                                category = obj.getString("category"),
                                encryptedFileName = encFileName,
                                sizeBytes = obj.optLong("sizeBytes", 0L),
                                originalRelativePath = obj.optString("originalRelativePath", ""),
                                originalUriString = obj.optString("originalUriString", ""),
                                removedFromGallery = obj.optBoolean("removedFromGallery", false),
                                addedAtMillis = obj.optLong("addedAtMillis", 0L),
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveIndexToDisk(items: List<VaultMediaEntity>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("displayName", item.displayName)
                put("mimeType", item.mimeType)
                put("category", item.category)
                put("encryptedFileName", item.encryptedFileName)
                put("sizeBytes", item.sizeBytes)
                put("originalRelativePath", item.originalRelativePath)
                put("originalUriString", item.originalUriString)
                put("removedFromGallery", item.removedFromGallery)
                put("addedAtMillis", item.addedAtMillis)
            }
            array.put(obj)
        }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        VaultFileCrypto.encryptBytes(bytes, indexFile())
    }
}
