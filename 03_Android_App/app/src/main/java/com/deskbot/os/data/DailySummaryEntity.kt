package com.deskbot.os.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    @PrimaryKey val dateStr: String,
    val avgPostureScore: Int,
    val avgFocusScore: Int,
    val slouchCount: Int,
    val waterCompliancePercent: Int,
    val goodPostureMinutes: Int,
    val suggestions: String,
    val computedTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface DailySummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: DailySummaryEntity)

    @Query("SELECT * FROM daily_summaries ORDER BY dateStr DESC")
    fun getAllSummaries(): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries ORDER BY dateStr DESC LIMIT :limit")
    fun getRecentSummaries(limit: Int): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries WHERE dateStr = :dateStr LIMIT 1")
    suspend fun getSummaryForDate(dateStr: String): DailySummaryEntity?
}
