package com.deskbot.os.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "app_limit_break_events")
data class AppLimitBreakEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val appName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val usageMinutesAtTrigger: Int,
    val thresholdMinutes: Int
)

@Dao
interface AppLimitBreakEventDao {
    @Insert
    suspend fun insertEvent(event: AppLimitBreakEvent): Long

    @Query("SELECT * FROM app_limit_break_events WHERE timestamp >= :startOfDay AND timestamp <= :endOfDay ORDER BY timestamp DESC")
    suspend fun getEventsForDay(startOfDay: Long, endOfDay: Long): List<AppLimitBreakEvent>

    @Query("SELECT * FROM app_limit_break_events WHERE timestamp >= :startOfDay AND timestamp <= :endOfDay ORDER BY timestamp DESC")
    fun getEventsForDayFlow(startOfDay: Long, endOfDay: Long): Flow<List<AppLimitBreakEvent>>

    @Query("SELECT * FROM app_limit_break_events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<AppLimitBreakEvent>>

    @Query("SELECT * FROM app_limit_break_events ORDER BY timestamp DESC")
    suspend fun getAllEvents(): List<AppLimitBreakEvent>
}
