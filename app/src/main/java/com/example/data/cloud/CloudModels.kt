package com.example.data.cloud

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val photoUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class CloudLimitedApp(
    @DocumentId val id: String = "",
    val userId: String = "",
    val packageName: String = "",
    val appName: String = "",
    val limited: Boolean = true,
    val blockCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class AppRelease(
    @DocumentId val id: String = "",
    val publishedByUid: String = "",
    val packageName: String = "",
    val versionCode: Long = 0L,
    val versionName: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val signingSha256: String = "",
    val apkSizeBytes: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)
