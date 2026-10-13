package com.deskbot.os.work

import android.content.Context
import androidx.work.*
import com.deskbot.os.data.DeskBotDatabase
import java.util.concurrent.TimeUnit

class PurgeDiscoveredWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val db = DeskBotDatabase.getDatabase(applicationContext)
            val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            db.filterDao().purgeOldDiscoveredConversations(sevenDaysAgo)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "PurgeDiscoveredWorker"

        fun enqueuePeriodicCleanup(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<PurgeDiscoveredWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
