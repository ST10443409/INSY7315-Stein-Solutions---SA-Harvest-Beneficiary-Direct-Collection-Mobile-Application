package com.example.client.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_payloads")
data class SyncPayload(
    @PrimaryKey
    val id: String,
    val data: String,
    val isSynced: Boolean = false
)
