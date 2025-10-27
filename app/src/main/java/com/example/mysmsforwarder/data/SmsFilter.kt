package com.example.mysmsforwarder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_filters")
data class SmsFilter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val senderNumber: String,
    val senderName: String,
    val forwardToNumber: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "forwarding_history")
data class ForwardingHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filterId: Long,
    val filterName: String,
    val originalSender: String,
    val forwardedTo: String,
    val messagePreview: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true
)

@Entity(tableName = "app_logs")
data class AppLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val level: String, // DEBUG, INFO, WARNING, ERROR
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)