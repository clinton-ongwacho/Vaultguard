package com.example.data.cloud

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CloudSyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    suspend fun upsertSignedInUserProfile(): Result<Unit> {
        var docPath = "users/unauthenticated"
        return try {
            val user = auth.currentUser
                ?: throw IllegalStateException("User must be signed in with Google before registering profile.")
            val uid = user.uid
            require(uid.isNotBlank()) { "Authenticated UID is blank." }

            val docRef = db.collection("users").document(uid)
            docPath = docRef.path

            val displayName = (user.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: user.email?.substringBefore('@')?.takeIf { it.isNotEmpty() }
                ?: "VaultGuard Admin").take(120)
            val email = user.email?.trim()?.take(200)
            val photoUrl = user.photoUrl?.toString()?.take(500)

            val existing = docRef.get().await()
            if (existing.exists()) {
                val updateMap = mutableMapOf<String, Any>(
                    "displayName" to displayName,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                if (email != null) updateMap["email"] = email
                if (photoUrl != null) updateMap["photoUrl"] = photoUrl
                docRef.update(updateMap).await()
            } else {
                val createMap = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to displayName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                if (email != null) createMap["email"] = email
                if (photoUrl != null) createMap["photoUrl"] = photoUrl
                docRef.set(createMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    fun observeCloudLimitedApps(userId: String): Flow<List<CloudLimitedApp>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user changed; refusing cross-user read." }
        val path = "users/$uid/limited_apps"
        emitAll(
            db.collection("users").document(uid).collection("limited_apps")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        CloudLimitedApp::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun syncLimitedApp(
        packageName: String,
        appName: String,
        limited: Boolean,
        blockCount: Int,
    ): Result<Unit> {
        val ruleId = packageName.replace(Regex("[^a-zA-Z0-9_\\-.]"), "_").take(128)
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/limited_apps/$ruleId"
        return try {
            val uid = requireUserId()
            require(ruleId.isNotBlank()) { "Invalid ruleId" }
            val docRef = db.collection("users").document(uid)
                .collection("limited_apps").document(ruleId)
            docPath = docRef.path

            val safeName = appName.trim().ifBlank { packageName }.take(120)
            val safePkg = packageName.trim().take(200)
            val safeCount = blockCount.coerceAtLeast(0).toLong()

            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "appName" to safeName,
                        "limited" to limited,
                        "blockCount" to safeCount,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "packageName" to safePkg,
                        "appName" to safeName,
                        "limited" to limited,
                        "blockCount" to safeCount,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    fun observeLatestAppRelease(): Flow<AppRelease?> = flow {
        val uid = requireUserId()
        val path = "app_releases"
        emitAll(
            db.collection("app_releases")
                .orderBy("versionCode", Query.Direction.DESCENDING)
                .limit(1)
                .snapshots()
                .map { snap ->
                    snap.documents.firstOrNull()?.toObject(
                        AppRelease::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun fetchLatestAppRelease(): Result<AppRelease?> {
        val path = "app_releases"
        return try {
            requireUserId()
            val snap = db.collection("app_releases")
                .orderBy("versionCode", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .await()
            val release = snap.documents.firstOrNull()?.toObject(
                AppRelease::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            Result.success(release)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun publishAppRelease(
        packageName: String,
        versionCode: Long,
        versionName: String,
        releaseNotes: String,
        downloadUrl: String,
        signingSha256: String,
        apkSizeBytes: Long,
    ): Result<AppRelease> {
        val releaseId = "v_${versionCode}_${System.currentTimeMillis()}"
        var docPath = "app_releases/$releaseId"
        return try {
            val uid = requireUserId()
            val cleanPkg = packageName.trim().take(200)
            val cleanVersionName = versionName.trim().take(40)
            val cleanNotes = releaseNotes.trim().take(2000)
            val cleanUrl = downloadUrl.trim().take(1000)
            val cleanSha = signingSha256.trim().lowercase()

            require(cleanPkg.isNotEmpty()) { "Package name is required." }
            require(versionCode >= 1L) { "Version code must be >= 1." }
            require(cleanVersionName.isNotEmpty()) { "Version name is required." }
            require(com.example.ota.ApkSecurityVerifier.isValidOtaDownloadUrl(cleanUrl)) {
                "APK download URL must be a valid public HTTPS URL."
            }
            require(cleanSha.matches(Regex("^[0-9a-f]{64}$"))) {
                "Signing SHA-256 fingerprint must be 64 lowercase hex characters."
            }
            require(apkSizeBytes > 0L) { "APK size must be greater than zero." }

            val docRef = db.collection("app_releases").document(releaseId)
            docPath = docRef.path
            docRef.set(
                mapOf(
                    "publishedByUid" to uid,
                    "packageName" to cleanPkg,
                    "versionCode" to versionCode,
                    "versionName" to cleanVersionName,
                    "releaseNotes" to cleanNotes,
                    "downloadUrl" to cleanUrl,
                    "signingSha256" to cleanSha,
                    "apkSizeBytes" to apkSizeBytes,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()

            Result.success(
                AppRelease(
                    id = releaseId,
                    publishedByUid = uid,
                    packageName = cleanPkg,
                    versionCode = versionCode,
                    versionName = cleanVersionName,
                    releaseNotes = cleanNotes,
                    downloadUrl = cleanUrl,
                    signingSha256 = cleanSha,
                    apkSizeBytes = apkSizeBytes,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }
}
