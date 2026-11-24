package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "access_events")
data class AccessEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val appName: String,
    val eventType: String,
    val detail: String = "",
    val photoPath: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
)
