package com.deskbot.os.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "app_blocking_rules")
data class AppBlockingRule(
    @PrimaryKey val packageName: String,
    val appName: String,
    val dailyBudgetMinutes: Int = 60,
    val extensionMinutes: Int = 20,
    val triggerCountToday: Int = 0,
    val lastActiveDate: String = "",
    val lastHijackTimestamp: Long = 0L,
    val isEnabled: Boolean = true
)

@Dao
interface AppBlockingRuleDao {
    @Query("SELECT * FROM app_blocking_rules")
    fun getAllRulesFlow(): Flow<List<AppBlockingRule>>

    @Query("SELECT * FROM app_blocking_rules")
    suspend fun getAllRules(): List<AppBlockingRule>

    @Query("SELECT * FROM app_blocking_rules WHERE packageName = :pkg LIMIT 1")
    suspend fun getRuleForPackage(pkg: String): AppBlockingRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: AppBlockingRule)

    @Update
    suspend fun updateRule(rule: AppBlockingRule)

    @Delete
    suspend fun deleteRule(rule: AppBlockingRule)

    @Query("UPDATE app_blocking_rules SET lastHijackTimestamp = :timestamp WHERE packageName = :pkg")
    suspend fun updateLastHijackTimestamp(pkg: String, timestamp: Long)
}
