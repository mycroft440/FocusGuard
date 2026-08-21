package com.focusguard.database

import androidx.room.TypeConverter
import com.focusguard.domain.model.BlockSessionType
import com.focusguard.domain.model.UsageLimitLockMode

class DatabaseConverters {
    @TypeConverter
    fun blockSessionTypeToStorage(value: BlockSessionType): String = value.name

    @TypeConverter
    fun blockSessionTypeFromStorage(value: String): BlockSessionType =
        BlockSessionType.valueOf(value.uppercase())

    @TypeConverter
    fun usageLimitLockModeToStorage(value: UsageLimitLockMode): String = value.name

    @TypeConverter
    fun usageLimitLockModeFromStorage(value: String): UsageLimitLockMode =
        UsageLimitLockMode.valueOf(value.uppercase())

    @TypeConverter
    fun recurringDaysToStorage(days: Set<Int>): String = days
        .filter { it in 1..7 }
        .distinct()
        .sorted()
        .joinToString(",")

    @TypeConverter
    fun recurringDaysFromStorage(value: String): Set<Int> = value
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filterTo(linkedSetOf()) { it in 1..7 }
}
