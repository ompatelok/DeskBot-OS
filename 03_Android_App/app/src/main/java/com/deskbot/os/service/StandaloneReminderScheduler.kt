package com.deskbot.os.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

object StandaloneReminderScheduler {
    private const val TAG = "ReminderScheduler"

    fun scheduleReminders(context: Context, eyeIntervalMin: Int, waterIntervalMin: Int) {
        // App-side eye-break reminders removed: handled hardware-side by DeskBot robot via MQTT deskbot/settings/intervals
        cancelEyeReminder(context)
        scheduleWaterReminder(context, waterIntervalMin)
    }

    fun cancelEyeReminder(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val eyeIntent = Intent(context, DeskBotReminderReceiver::class.java).apply {
                action = "EYE_BREAK"
            }
            val pi = PendingIntent.getBroadcast(
                context, 101, eyeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
            Log.d(TAG, "Cancelled any existing app-side eye break alarms")
        } catch (_: Exception) {}
    }

    fun scheduleWaterReminder(context: Context, intervalMin: Int) {
        val safeMin = intervalMin.coerceAtLeast(1)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val waterIntent = Intent(context, DeskBotReminderReceiver::class.java).apply {
            action = DeskBotReminderReceiver.ACTION_WATER_BREAK
            putExtra("INTERVAL_MIN", safeMin)
        }
        val pi = PendingIntent.getBroadcast(
            context, 102, waterIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + (safeMin * 60000L)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.i(TAG, "Scheduled exact water break in ${safeMin}m")
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.i(TAG, "Scheduled idle-allowed water break in ${safeMin}m")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.i(TAG, "Scheduled exact water break in ${safeMin}m")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed setExactAndAllowWhileIdle for water reminder, falling back", e)
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    fun triggerImmediateReminder(context: Context) {
        val waterIntent = Intent(context, DeskBotReminderReceiver::class.java).apply {
            action = DeskBotReminderReceiver.ACTION_WATER_BREAK
            putExtra("INTERVAL_MIN", 40)
        }
        context.sendBroadcast(waterIntent)
        Log.i(TAG, "Dispatched immediate water reminder broadcast for live testing")
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val eyeIntent = Intent(context, DeskBotReminderReceiver::class.java).apply { action = "EYE_BREAK" }
        val waterIntent = Intent(context, DeskBotReminderReceiver::class.java).apply { action = DeskBotReminderReceiver.ACTION_WATER_BREAK }

        alarmManager.cancel(PendingIntent.getBroadcast(context, 101, eyeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        alarmManager.cancel(PendingIntent.getBroadcast(context, 102, waterIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}
