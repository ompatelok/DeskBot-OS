package com.deskbot.os.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "water_reminder_events")
data class WaterReminderEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val isAcknowledged: Boolean = false,
    val acknowledgedTimestamp: Long = 0L
)

@Dao
interface WaterReminderDao {
    @Insert
    suspend fun insertEvent(event: WaterReminderEvent): Long

    @Query("UPDATE water_reminder_events SET isAcknowledged = 1, acknowledgedTimestamp = :ackTime WHERE id = (SELECT id FROM water_reminder_events WHERE isAcknowledged = 0 ORDER BY timestamp DESC LIMIT 1)")
    suspend fun acknowledgeLatest(ackTime: Long = System.currentTimeMillis()): Int

    @Query("UPDATE water_reminder_events SET isAcknowledged = 1, acknowledgedTimestamp = :ackTime WHERE id = :id")
    suspend fun acknowledgeById(id: Long, ackTime: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM water_reminder_events WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getEventsBetween(startTime: Long, endTime: Long): List<WaterReminderEvent>

    @Query("SELECT * FROM water_reminder_events WHERE isAcknowledged = 1 ORDER BY acknowledgedTimestamp DESC")
    suspend fun getAllAcknowledged(): List<WaterReminderEvent>

    @Query("SELECT COUNT(*) FROM water_reminder_events WHERE isAcknowledged = 1 AND (acknowledgedTimestamp >= :startOfDay OR (acknowledgedTimestamp == 0 AND timestamp >= :startOfDay))")
    suspend fun getCountAcknowledgedSince(startOfDay: Long): Int

    @Query("SELECT * FROM water_reminder_events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<WaterReminderEvent>>
}
