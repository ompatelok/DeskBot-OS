package com.deskbot.os.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.deskbot.os.MqttManager
import org.json.JSONObject

class DeskBotNotificationListener : NotificationListenerService() {

    private var lastForwardedTimestamp = 0L
    private val debounceIntervalMs = 2500L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName ?: return
        val extras = sbn.notification.extras ?: return

        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        val now = System.currentTimeMillis()
        if (now - lastForwardedTimestamp < debounceIntervalMs) return

        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") {
            if (title.isNotBlank() && text.isNotBlank()) {
                Log.d("DeskBot_Notif", "WhatsApp Alert -> Title: $title | Text: $text")
                forwardAlert("WhatsApp", title, text)
                lastForwardedTimestamp = now
            }
        } else if (packageName == "com.instagram.android") {
            if (title.isNotBlank()) {
                Log.d("DeskBot_Notif", "Instagram Alert -> Title: $title | Text: $text")
                forwardAlert("Instagram", title, text)
                lastForwardedTimestamp = now
            }
        }
    }

    private fun forwardAlert(source: String, sender: String, message: String) {
        val payload = JSONObject().apply {
            put("source", source)
            put("sender", sender.take(18))
            put("msg", message.take(30))
        }.toString()

        MqttManager.getInstance(applicationContext).publishNotification(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}