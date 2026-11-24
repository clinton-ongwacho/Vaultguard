package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "security_questions")
data class SecurityQuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val question: String,
    val answerHash: String,
    val encryptedAnswer: String,
    val createdAtMillis: Long,
)
