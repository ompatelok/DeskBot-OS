package com.deskbot.os.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deskbot.os.MainActivity
import com.deskbot.os.data.DailySummaryEntity
import com.deskbot.os.data.DeskBotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class DailySummaryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DailySummaryWorker"
        const val CHANNEL_ID = "DAILY_SUMMARY"
        const val NOTIFICATION_ID = 2001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = DeskBotDatabase.getDatabase(applicationContext)
            val postureDao = db.postureDao()
            val summaryDao = db.dailySummaryDao()
            val waterDao = db.waterReminderDao()

            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis

            val logs = postureDao.getLogsBetween(startOfDay, now)
            val waterEvents = waterDao.getEventsBetween(startOfDay, now)

            val slouchCount = logs.count { it.state.contains("Slouch", ignoreCase = true) }
            val goodCount = logs.count {
                it.state.contains("Good", ignoreCase = true) || it.state.contains("Aligned", ignoreCase = true)
            }

            val avgPostureScore = if (logs.isNotEmpty()) {
                ((goodCount.toFloat() / logs.size) * 100).toInt().coerceIn(0, 100)
            } else 100

            val avgFocusScore = if (logs.isNotEmpty()) {
                (logs.sumOf { it.focusScore } / logs.size).coerceIn(0, 100)
            } else 100

            // Estimate duration in good posture (each sample corresponds to ~2s cadence)
            val goodPostureMinutes = ((goodCount * 2L) / 60L).toInt()

            val totalWaterSent = waterEvents.size
            val totalWaterAck = waterEvents.count { it.isAcknowledged }
            val waterCompliancePercent = if (totalWaterSent > 0) {
                ((totalWaterAck.toFloat() / totalWaterSent) * 100).toInt().coerceIn(0, 100)
            } else 100

            // 2-3 short, plain-language, rule-based suggestions based on today's actual numbers
            val suggestionsList = mutableListOf<String>()

            if (avgPostureScore < 70 || slouchCount > 15) {
                suggestionsList.add("Elevate monitor by 2-3 inches to reduce cervical spine strain; slouch spikes were high today.")
            } else {
                suggestionsList.add("Solid cervical alignment maintained across work blocks. Keep current screen height.")
            }

            if (avgFocusScore < 75) {
                suggestionsList.add("Focus dipped below target. Schedule 5-minute movement resets between deep work intervals.")
            } else {
                suggestionsList.add("Strong focus stamina recorded today (${avgFocusScore}% average).")
            }

            if (totalWaterSent > 0 && waterCompliancePercent < 75) {
                suggestionsList.add("Hydration compliance was ${waterCompliancePercent}%. Keep water bottle on desk during morning hours.")
            } else if (totalWaterSent > 0) {
                suggestionsList.add("Great hydration discipline (${totalWaterAck}/$totalWaterSent reminders acknowledged).")
            }

            val suggestionText = suggestionsList.take(3).joinToString("\n• ", prefix = "• ")
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))

            val summary = DailySummaryEntity(
                dateStr = dateStr,
                avgPostureScore = avgPostureScore,
                avgFocusScore = avgFocusScore,
                slouchCount = slouchCount,
                waterCompliancePercent = waterCompliancePercent,
                goodPostureMinutes = goodPostureMinutes,
                suggestions = suggestionText,
                computedTimestamp = now
            )

            summaryDao.insertSummary(summary)
            Log.i(TAG, "Daily summary computed and saved for $dateStr: posture=$avgPostureScore, focus=$avgFocusScore, water=$waterCompliancePercent%")

            // Post notification
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "DeskBot Daily Summary",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Daily 9 PM ergonomic health and posture insights"
                }
                nm.createNotificationChannel(channel)
            }

            val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("SHOW_DAILY_SUMMARY", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                201,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val primarySuggestion = suggestionsList.firstOrNull() ?: "View your daily ergonomic posture report."
            val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(com.deskbot.os.R.drawable.ic_stat_deskbot)
                .setContentTitle("DeskBot Daily Report // $dateStr")
                .setContentText(primarySuggestion)
                .setStyle(NotificationCompat.BigTextStyle().bigText("Posture: $avgPostureScore% | Focus: $avgFocusScore% | Hydration: $waterCompliancePercent%\n\n$suggestionText"))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIFICATION_ID, notif)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating daily summary", e)
            Result.retry()
        }
    }
}
