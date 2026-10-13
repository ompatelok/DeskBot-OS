package com.deskbot.os.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.deskbot.os.MainActivity
import com.deskbot.os.data.DeskBotDatabase
import com.deskbot.os.data.WaterReminderEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DeskBotReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DeskBotReminderReceiver"
        const val ACTION_WATER_BREAK = "WATER_BREAK"
        const val ACTION_ACKNOWLEDGE_WATER = "com.deskbot.os.ACTION_ACKNOWLEDGE_WATER"
        const val CHANNEL_ID = "WELLNESS_REMINDERS"
        const val NOTIF_ID_WATER = 1002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: ACTION_WATER_BREAK
        Log.i(TAG, "onReceive triggered with action: $action")
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED -> {
                        Log.i(TAG, "Boot completed: restoring water reminder schedule")
                        StandaloneReminderScheduler.scheduleWaterReminder(context, 40)
                    }
                    ACTION_ACKNOWLEDGE_WATER, "com.deskbot.os.ACTION_ACKNOWLEDGE_WATER" -> {
                        val db = DeskBotDatabase.getDatabase(context)
                        val updated = db.waterReminderDao().acknowledgeLatest(System.currentTimeMillis())
                        Log.i(TAG, "Water reminder acknowledged via notification action ($updated rows updated)")

                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(NOTIF_ID_WATER)

                        try {
                            com.deskbot.os.MqttManager.getInstance(context).refreshHydrationStreak()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error refreshing hydration streak after notification ack", e)
                        }

                        // Visual passive acknowledgment HUD notification
                        val ackNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(com.deskbot.os.R.drawable.ic_stat_deskbot)
                            .setContentTitle("Hydration Confirmed")
                            .setContentText("Great job staying hydrated! +1 to daily compliance.")
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setAutoCancel(true)
                            .build()
                        nm.notify(NOTIF_ID_WATER + 10, ackNotif)
                    }
                    ACTION_WATER_BREAK, "WATER_BREAK", "com.deskbot.os.WATER_REMINDER" -> {
                        val db = DeskBotDatabase.getDatabase(context)
                        val eventId = db.waterReminderDao().insertEvent(WaterReminderEvent(timestamp = System.currentTimeMillis()))
                        Log.i(TAG, "Logged water reminder event #$eventId")

                        // Notify hardware over MQTT so robot OLED displays water reminder prompt
                        try {
                            com.deskbot.os.MqttManager.getInstance(context).publishDeviceCmd("water_reminder")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to publish water_reminder cmd to hardware", e)
                        }

                        // Water reminders are now handled directly on DeskBot hardware node.
                        // Zero phone notification banner or screen interruption.
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(NOTIF_ID_WATER)
                        Log.i(TAG, "Water reminder triggered on hardware; phone notification suppressed as requested.")

                        val interval = intent.getIntExtra("INTERVAL_MIN", 40)
                        StandaloneReminderScheduler.scheduleWaterReminder(context, interval)
                    }
                    else -> {
                        Log.w(TAG, "Ignoring unrecognized reminder action: $action")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling reminder action $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showWaterReminderNotification(context: Context) {
        // Zero screen hijacking: BreathingOverlayService is NEVER called for water breaks.
        // A gentle, high-priority heads-up notification alerts the user without interrupting ongoing tasks.
        val title = "Hydration Reminder"
        val message = "Time for water! Tap the DeskBot capacitive sensor to log intake & build your streak."
        val notifId = NOTIF_ID_WATER

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeskBot Wellness Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Hydration and posture breaks"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ackIntent = Intent(context, DeskBotReminderReceiver::class.java).apply {
            action = ACTION_ACKNOWLEDGE_WATER
        }
        val ackPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.deskbot.os.R.drawable.ic_stat_deskbot)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Take a moment to hydrate! Drink a glass of water, then tap the capacitive touch sensor on DeskBot or tap 'LOG INTAKE' below."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .addAction(
                com.deskbot.os.R.drawable.ic_stat_deskbot,
                "LOG INTAKE",
                ackPendingIntent
            )
            .setAutoCancel(true)

        nm.notify(notifId, builder.build())
        Log.i(TAG, "Dispatched gentle hydration notification to notification center (zero screen hijack)")
    }
}
