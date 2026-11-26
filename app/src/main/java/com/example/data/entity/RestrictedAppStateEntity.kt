package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "restricted_app_states")
data class RestrictedAppStateEntity(
    @PrimaryKey val packageName: String,
    val snapshotJson: String = "",
    val discoveredChannelsJson: String = "[]",
    val lastVerificationState: String = "UNRESTRICTED",
    val lastInspectedAtMillis: Long = 0L,
)
