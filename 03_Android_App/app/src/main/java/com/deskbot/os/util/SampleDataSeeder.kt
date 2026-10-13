package com.deskbot.os.util

import android.content.Context
import android.util.Log
import com.deskbot.os.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object SampleDataSeeder {

    private const val TAG = "SampleDataSeeder"
    private const val PREFS_KEY = "deskbot_demo_seed_v2"

    suspend fun seedOneMonthHistory(context: Context, database: DeskBotDatabase, force: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("deskbot_seed_prefs", Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(PREFS_KEY, false)) {
            Log.d(TAG, "Sample data already seeded, skipping.")
            return@withContext
        }

        try {
            val summaryDao = database.dailySummaryDao()
            val postureDao = database.postureDao()
            val waterDao = database.waterReminderDao()
            val appBreakDao = database.appLimitBreakEventDao()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val now = System.currentTimeMillis()

            // 1. Seed 30 Days of Daily Summaries
            for (daysAgo in 30 downTo 1) {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, -daysAgo)
                    set(Calendar.HOUR_OF_DAY, 21)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }
                val dateStr = dateFormat.format(cal.time)

                // Realistic fluctuating ergonomic metrics
                val postureScore = 84 + (daysAgo % 9) + Random.nextInt(-2, 3)
                val focusScore = 82 + ((daysAgo * 3) % 11) + Random.nextInt(-1, 3)
                val slouchCount = 3 + (daysAgo % 7)
                val waterCompliance = if (daysAgo % 5 == 0) 80 else if (daysAgo % 3 == 0) 90 else 100
                val goodMinutes = 195 + (daysAgo % 8) * 15 + Random.nextInt(0, 20)

                val suggestions = when {
                    postureScore >= 90 -> "• Exceptional cervical posture recorded.\n• Ergonomic monitor distance maintained.\n• Hydration streak preserved."
                    postureScore >= 85 -> "• Stable posture throughout core work blocks.\n• Minor slouch detected during afternoon intervals.\n• Water intake target achieved."
                    else -> "• Frequent slouching observed after 15:00.\n• Consider elevating desk monitor 2 inches.\n• Perform thoracic spine extensions."
                }

                val summary = DailySummaryEntity(
                    dateStr = dateStr,
                    avgPostureScore = postureScore.coerceIn(75, 98),
                    avgFocusScore = focusScore.coerceIn(75, 99),
                    slouchCount = slouchCount.coerceIn(1, 14),
                    waterCompliancePercent = waterCompliance.coerceIn(60, 100),
                    goodPostureMinutes = goodMinutes,
                    suggestions = suggestions,
                    computedTimestamp = cal.timeInMillis
                )
                summaryDao.insertSummary(summary)
            }

            // 2. Seed 60 Realistic Posture Telemetry Snapshots for Today / Recent
            for (i in 0 until 50) {
                val sampleOffset = (50 - i) * 45_000L // every 45 seconds leading up to now
                val sampleTime = now - sampleOffset
                val isSlouch = (i == 12 || i == 13 || i == 28 || i == 41)
                val state = if (isSlouch) "Slouching" else if (i % 4 == 0) "Optimal Alignment" else "Good Posture"
                val score = if (isSlouch) 65 + Random.nextInt(0, 8) else 92 + Random.nextInt(0, 9)

                postureDao.insertLog(
                    PostureEntity(
                        timestamp = sampleTime,
                        state = state,
                        focusScore = score.coerceIn(60, 100)
                    )
                )
            }

            // 3. Seed Water Reminders across the last 7 Days for a healthy streak
            for (daysAgo in 6 downTo 0) {
                val dayCal = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, -daysAgo)
                }

                // 4 reminders per day (morning, noon, afternoon, evening)
                val hours = listOf(10, 12, 15, 17)
                for (h in hours) {
                    dayCal.set(Calendar.HOUR_OF_DAY, h)
                    dayCal.set(Calendar.MINUTE, 15)
                    val reminderTime = dayCal.timeInMillis

                    // Past days 100% ack, today's afternoon acknowledged via hardware touch
                    val isAck = daysAgo > 0 || h <= 15
                    waterDao.insertEvent(
                        WaterReminderEvent(
                            timestamp = reminderTime,
                            isAcknowledged = isAck,
                            acknowledgedTimestamp = if (isAck) reminderTime + 45_000L else 0L
                        )
                    )
                }
            }

            // 4. Seed App Limit Break Events (2 realistic triggers)
            appBreakDao.insertEvent(
                AppLimitBreakEvent(
                    appName = "Instagram",
                    packageName = "com.instagram.android",
                    timestamp = now - TimeUnit.HOURS.toMillis(4),
                    thresholdMinutes = 30,
                    usageMinutesAtTrigger = 31
                )
            )
            appBreakDao.insertEvent(
                AppLimitBreakEvent(
                    appName = "YouTube",
                    packageName = "com.google.android.youtube",
                    timestamp = now - TimeUnit.DAYS.toMillis(2) - TimeUnit.HOURS.toMillis(3),
                    thresholdMinutes = 60,
                    usageMinutesAtTrigger = 64
                )
            )

            prefs.edit().putBoolean(PREFS_KEY, true).apply()
            Log.i(TAG, "Successfully seeded 30 days of realistic history, telemetry, water events, and app breaks!")
        } catch (e: Exception) {
            Log.e(TAG, "Error seeding demo data", e)
        }
    }
}
