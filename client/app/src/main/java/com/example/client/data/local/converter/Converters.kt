package com.example.client.data.local.converter

import androidx.room.TypeConverter
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.Tone

class Converters {
    @TypeConverter
    fun fromSyncStatus(value: SyncStatus?): String? {
        return value?.name
    }

    @TypeConverter
    fun toSyncStatus(value: String?): SyncStatus? {
        return value?.let { SyncStatus.valueOf(it) }
    }

    @TypeConverter
    fun fromTone(value: Tone?): String? {
        return value?.name
    }

    @TypeConverter
    fun toTone(value: String?): Tone? {
        return value?.let { Tone.valueOf(it) }
    }

    @TypeConverter
    fun fromBooleanList(value: List<Boolean>?): String? {
        return value?.joinToString(separator = ",") { it.toString() }
    }

    @TypeConverter
    fun toBooleanList(value: String?): List<Boolean>? {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(",").map { it.toBoolean() }
    }

    @TypeConverter
    fun fromDecisionOutcome(value: DecisionOutcome?): String? {
        return value?.name
    }

    @TypeConverter
    fun toDecisionOutcome(value: String?): DecisionOutcome? {
        return value?.let { DecisionOutcome.valueOf(it) }
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.joinToString(separator = "|||")
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split("|||")
    }
}
