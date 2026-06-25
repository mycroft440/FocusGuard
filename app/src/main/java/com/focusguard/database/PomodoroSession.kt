package com.focusguard.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "pomodoro_sessions")
data class PomodoroSession(
    @PrimaryKey val id: Int = 1,
    val endTime: Long,
    val durationMillis: Long,
    val isActive: Boolean,
    val isBreak: Boolean = false,
    val lastTickTime: Long = System.currentTimeMillis(),
    val isBlockingEnabled: Boolean = true
)

@Dao
interface PomodoroSessionDao {
    @Query("SELECT * FROM pomodoro_sessions WHERE id = 1")
    fun getPomodoroSession(): Flow<PomodoroSession?>

    @Query("SELECT * FROM pomodoro_sessions WHERE id = 1")
    suspend fun getPomodoroSessionSync(): PomodoroSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(session: PomodoroSession)

    @Update
    suspend fun update(session: PomodoroSession)

    @Query("DELETE FROM pomodoro_sessions WHERE id = 1")
    suspend fun deleteSession()
}

