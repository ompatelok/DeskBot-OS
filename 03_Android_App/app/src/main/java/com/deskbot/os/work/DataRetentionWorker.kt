package com.deskbot.os.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deskbot.os.data.DeskBotDatabase
import com.deskbot.os.data.PostureDailyRollupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DataRetentionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DataRetentionWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = DeskBotDatabase.getDatabase(applicationContext)
            val postureDao = db.postureDao()
            val rollupDao = db.postureRollupDao()

            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
            val oldLogs = postureDao.getLogsOlderThan(cutoff)

            if (oldLogs.isNotEmpty()) {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val groupedByDay = oldLogs.groupBy { dateFormat.format(Date(it.timestamp)) }

                for ((dateStr, dayLogs) in groupedByDay) {
                    val minScore = dayLogs.minOfOrNull { it.focusScore } ?: 0
                    val maxScore = dayLogs.maxOfOrNull { it.focusScore } ?: 100
                    val avgScore = (dayLogs.sumOf { it.focusScore } / dayLogs.size).coerceIn(0, 100)
                    val eventCount = dayLogs.size
                    val slouchCount = dayLogs.count { it.state.contains("Slouch", ignoreCase = true) }

                    val rollup = PostureDailyRollupEntity(
                        dateStr = dateStr,
                        minScore = minScore,
                        maxScore = maxScore,
                        avgScore = avgScore,
                        eventCount = eventCount,
                        slouchCount = slouchCount
                    )
                    rollupDao.upsertRollup(rollup)
                }

                postureDao.deleteLogsOlderThan(cutoff)
                Log.i(TAG, "Rolled up ${oldLogs.size} logs into ${groupedByDay.size} daily summaries and purged raw records older than 14 days.")
            } else {
                Log.d(TAG, "No logs older than 14 days found to roll up.")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error performing data retention rollup", e)
            Result.retry()
        }
    }
}
