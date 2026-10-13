package com.deskbot.os.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PostureDao {
    @Insert
    suspend fun insertLog(log: PostureEntity)

    @Query("SELECT * FROM posture_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<PostureEntity>>
    @Query("SELECT * FROM posture_logs WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getLogsBetween(startTime: Long, endTime: Long): List<PostureEntity>

    @Query("SELECT * FROM posture_logs WHERE timestamp < :cutoffTime ORDER BY timestamp ASC")
    suspend fun getLogsOlderThan(cutoffTime: Long): List<PostureEntity>

    @Query("DELETE FROM posture_logs WHERE timestamp < :cutoffTime")
    suspend fun deleteLogsOlderThan(cutoffTime: Long)
}
