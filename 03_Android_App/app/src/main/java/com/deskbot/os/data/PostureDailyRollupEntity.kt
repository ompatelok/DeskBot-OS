package com.deskbot.os.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "posture_daily_rollups")
data class PostureDailyRollupEntity(
    @PrimaryKey val dateStr: String,
    val minScore: Int,
    val maxScore: Int,
    val avgScore: Int,
    val eventCount: Int,
    val slouchCount: Int
)

@Dao
interface PostureRollupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRollup(rollup: PostureDailyRollupEntity)

    @Query("SELECT * FROM posture_daily_rollups ORDER BY dateStr DESC")
    fun getAllRollups(): Flow<List<PostureDailyRollupEntity>>

    @Query("SELECT * FROM posture_daily_rollups WHERE dateStr = :dateStr LIMIT 1")
    suspend fun getRollupForDate(dateStr: String): PostureDailyRollupEntity?
}
