package com.deskbot.os.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.deskbot.os.MainActivity
import com.deskbot.os.data.DeskBotDatabase
import com.deskbot.os.service.BreathingOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AppBudgetTrackingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AppBudgetTracker"
        const val WORK_NAME_PERIODIC = "AppBudgetTrackerPeriodic"
        const val WORK_NAME_ONE_TIME = "AppBudgetTrackerOneTime"
        private const val CHANNEL_ID = "BUDGET_OVERLAY_ALERTS"
        private const val NOTIF_ID_BASE = 5000
        private const val COOLDOWN_MS = 300_000L // 5 minutes

        fun scheduleTracking(context: Context) {
            val wm = WorkManager.getInstance(context)
            val periodicReq = PeriodicWorkRequestBuilder<AppBudgetTrackingWorker>(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicReq
            )

            val initialReq = OneTimeWorkRequestBuilder<AppBudgetTrackingWorker>()
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniqueWork(WORK_NAME_ONE_TIME, ExistingWorkPolicy.REPLACE, initialReq)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = DeskBotDatabase.getDatabase(applicationContext)
            val rulesDao = db.appBlockingRuleDao()
            val activeRules = rulesDao.getAllRules().filter { it.isEnabled }

            if (activeRules.isNotEmpty() && !BreathingOverlayService.isOverlayActive) {
                val usm = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val cal = Calendar.getInstance()
                    val endTime = cal.timeInMillis
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val midnightTime = cal.timeInMillis

                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    val todayDate = dateFormat.format(cal.time)

                    val preciseUsageMinutes = getStrictDailyForegroundMinutes(usm, midnightTime, endTime)
                    val activeForegroundPkg = getCurrentlyActiveForegroundPackage(usm, endTime)
                    Log.d(TAG, "Strict daily usage computed: ${preciseUsageMinutes.size} apps tracked. Active foreground: $activeForegroundPkg")

                    for (rawRule in activeRules) {
                        // Check midnight rollover: if lastActiveDate != todayDate, reset triggerCountToday to 0
                        val rule = if (rawRule.lastActiveDate != todayDate) {
                            rawRule.copy(triggerCountToday = 0, lastActiveDate = todayDate).also {
                                rulesDao.updateRule(it)
                            }
                        } else {
                            rawRule
                        }

                        val effectiveThreshold = rule.dailyBudgetMinutes + (rule.triggerCountToday * rule.extensionMinutes)
                        val totalMinutes = preciseUsageMinutes[rule.packageName] ?: 0
                        val elapsedSinceLastHijack = endTime - rule.lastHijackTimestamp

                        // Strict check:
                        // 1. Must be the ACTUALLY ACTIVE foreground app (never hijack when the user is on home screen or another app!)
                        // 2. Cumulative daily time >= extending effective threshold
                        // 3. Cooldown elapsed
                        // 4. Overlay not already showing
                        val isAppInForeground = (rule.packageName == activeForegroundPkg)
                        if (isAppInForeground && totalMinutes >= effectiveThreshold && elapsedSinceLastHijack > COOLDOWN_MS && !BreathingOverlayService.isOverlayActive) {
                            val newTriggerCount = rule.triggerCountToday + 1
                            val updatedRule = rule.copy(
                                triggerCountToday = newTriggerCount,
                                lastHijackTimestamp = endTime,
                                lastActiveDate = todayDate
                            )
                            rulesDao.updateRule(updatedRule)

                            // Log every trigger event into AppLimitBreakEvent entity for auditing
                            try {
                                val breakEvent = com.deskbot.os.data.AppLimitBreakEvent(
                                    packageName = rule.packageName,
                                    appName = rule.appName,
                                    timestamp = endTime,
                                    usageMinutesAtTrigger = totalMinutes,
                                    thresholdMinutes = effectiveThreshold
                                )
                                db.appLimitBreakEventDao().insertEvent(breakEvent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to persist AppLimitBreakEvent", e)
                            }

                            val nextThreshold = rule.dailyBudgetMinutes + (newTriggerCount * rule.extensionMinutes)
                            Log.i(TAG, "Triggering break for ${rule.appName}: $totalMinutes >= ${effectiveThreshold}m (triggerCountToday: $newTriggerCount, next threshold: ${nextThreshold}m)")

                            val reason = "${rule.appName.uppercase()} BUDGET REACHED (${totalMinutes}m / ${effectiveThreshold}m)"

                            if (Settings.canDrawOverlays(applicationContext)) {
                                try {
                                    val overlayIntent = Intent(applicationContext, BreathingOverlayService::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        putExtra("TRIGGER_REASON", reason)
                                        putExtra("PACKAGE_NAME", rule.packageName)
                                    }
                                    applicationContext.startService(overlayIntent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start BreathingOverlayService from worker", e)
                                    showFallbackNotification(rule.appName, totalMinutes, effectiveThreshold)
                                }
                            } else {
                                showFallbackNotification(rule.appName, totalMinutes, effectiveThreshold)
                            }
                            break
                        }
                    }
                }
            }

            scheduleNextRun()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in AppBudgetTrackingWorker", e)
            scheduleNextRun()
            Result.retry()
        }
    }

    private fun getCurrentlyActiveForegroundPackage(usm: UsageStatsManager, now: Long): String? {
        return try {
            val events = usm.queryEvents(now - 30_000L, now)
            val event = UsageEvents.Event()
            var lastResumedPkg: String? = null
            var lastResumedTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp >= lastResumedTime) {
                        lastResumedTime = event.timeStamp
                        lastResumedPkg = event.packageName
                    }
                }
            }
            if (lastResumedPkg != null) return lastResumedPkg

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 60_000L, now)
            stats.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) {
            Log.e(TAG, "Error querying current foreground app", e)
            null
        }
    }

    private fun getStrictDailyForegroundMinutes(usm: UsageStatsManager, startMidnight: Long, endNow: Long): Map<String, Int> {
        val usageMap = mutableMapOf<String, Long>()

        // 1. Authoritative base from queryAndAggregateUsageStats
        try {
            val aggregated = usm.queryAndAggregateUsageStats(startMidnight, endNow)
            for ((pkg, stats) in aggregated) {
                if (stats.totalTimeInForeground > 0L) {
                    usageMap[pkg] = stats.totalTimeInForeground
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying aggregated usage stats", e)
        }

        // 2. Supplement with exact event stream to capture active foreground delta
        try {
            val events = usm.queryEvents(startMidnight, endNow)
            val event = UsageEvents.Event()
            val resumeTimes = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                val t = event.timeStamp
                if (t < startMidnight || t > endNow) continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        resumeTimes[pkg] = t
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val resumeT = resumeTimes.remove(pkg)
                        if (resumeT != null) {
                            val duration = t - maxOf(resumeT, startMidnight)
                            if (duration > 0 && !usageMap.containsKey(pkg)) {
                                usageMap[pkg] = (usageMap[pkg] ?: 0L) + duration
                            }
                        }
                    }
                }
            }

            // Account for any app currently in foreground
            for ((pkg, resumeT) in resumeTimes) {
                val duration = endNow - maxOf(resumeT, startMidnight)
                if (duration > 0) {
                    usageMap[pkg] = maxOf(usageMap[pkg] ?: 0L, duration)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying UsageEvents for exact midnight boundary", e)
        }

        return usageMap.mapValues { (_, millis) -> (millis / 60000L).toInt() }
    }

    private fun showFallbackNotification(appName: String, usedMin: Int, capMin: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Budget Exceeded Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when daily application focus budget is exceeded"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            NOTIF_ID_BASE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.deskbot.os.R.drawable.ic_stat_deskbot)
            .setContentTitle("Focus Budget Exceeded: $appName")
            .setContentText("$appName has reached ${usedMin}m of your ${capMin}m daily budget. Time for a neural reset!")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$appName has reached ${usedMin}m of your ${capMin}m daily budget. Take a 60-second breathing break to restore focus."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID_BASE, notif)
    }

    private fun scheduleNextRun() {
        try {
            val nextReq = OneTimeWorkRequestBuilder<AppBudgetTrackingWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                nextReq
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error self-scheduling AppBudgetTrackingWorker", e)
        }
    }
}
