package com.deskbot.os

import android.content.Context
import android.util.Log
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.amazonaws.regions.Regions
import com.deskbot.os.data.DeskBotDatabase
import com.deskbot.os.data.PostureEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState {
    CONNECTED,
    STANDALONE,
    RECONNECTING
}

data class PendingMqttMessage(
    val topic: String,
    val payload: String,
    val qos: AWSIotMqttQos
)

class MqttManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private var managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val postureDao = DeskBotDatabase.getDatabase(appContext).postureDao()

    private val _connectionState = MutableStateFlow(ConnectionState.STANDALONE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _posture = MutableStateFlow("Good Posture")
    val posture: StateFlow<String> = _posture.asStateFlow()

    private val _focusScore = MutableStateFlow(100)
    val focusScore: StateFlow<Int> = _focusScore.asStateFlow()

    private val _isDndActive = MutableStateFlow(false)
    val isDndActive: StateFlow<Boolean> = _isDndActive.asStateFlow()

    private val _hydrationStreak = MutableStateFlow(0)
    val hydrationStreak: StateFlow<Int> = _hydrationStreak.asStateFlow()

    private val _waterCountToday = MutableStateFlow(0)
    val waterCountToday: StateFlow<Int> = _waterCountToday.asStateFlow()

    private var mqttClient: AWSIotMqttManager? = null

    // Unique session ID per runtime instance eliminates AWS IoT Core socket collisions completely
    private var currentClientId = "DeskBot_Android_" + UUID.randomUUID().toString().replace("-", "").take(12)

    @Volatile
    private var isBrokerConnected = false
    @Volatile
    private var isConnecting = false
    private val isSubscribed = AtomicBoolean(false)

    private val lastPacketTimestamp = AtomicLong(0L)
    private var watchdogJob: Job? = null
    private var subscriptionJob: Job? = null
    private var lastDbInsertTime = 0L
    private var lastReconnectAttemptTime = 0L

    // Bounded ~100 item in-memory queue to prevent silent message drops when disconnected
    private val offlineQueue = ConcurrentLinkedQueue<PendingMqttMessage>()
    private val maxQueueSize = 100

    @Volatile
    private var rawUnderlyingState: ConnectionState = ConnectionState.STANDALONE
    private var candidateState: ConnectionState? = null
    private var candidateHoldCycles: Int = 0
    private var lastLoggedRawState: ConnectionState? = null

    companion object {
        private const val TAG = "DeskBotMqtt"
        private const val IOT_ENDPOINT = "a21v06g95kktaa-ats.iot.us-east-1.amazonaws.com"
        private const val IDENTITY_POOL_ID = "us-east-1:48245b37-8f77-4002-b31d-a2c09e59ca08"
        private val REGION = Regions.US_EAST_1

        const val TOPIC_POSTURE_LIVE = "deskbot/posture/live"
        const val TOPIC_NOTIF_STREAM = "deskbot/notifications/stream"
        const val TOPIC_DND_SETTINGS = "deskbot/settings/dnd"
        const val TOPIC_INTERVAL_SETTINGS = "deskbot/settings/intervals"
        const val TOPIC_DEVICE_CMD = "deskbot/device/cmd"
        const val TOPIC_TASKS_SYNC = "deskbot/tasks/sync"
        const val TOPIC_WATER_ACK = "deskbot/device/water_ack"
        const val TOPIC_TOUCH_WATER_ACK = "deskbot/touch/water_ack"

        private const val PACKET_TIMEOUT_WINDOW_MS = 15000L
        private const val WATCHDOG_INTERVAL_MS = 2000L
        private const val REQUIRED_CONSECUTIVE_CYCLES = 3

        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager(context).also { INSTANCE = it }
            }
        }
    }

    @Synchronized
    fun connect() {
        if (isConnecting || isBrokerConnected) {
            Log.d(TAG, "Connection attempt skipped: isConnecting=$isConnecting, isBrokerConnected=$isBrokerConnected")
            return
        }
        isConnecting = true

        if (!managerScope.isActive) {
            managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        startPassiveWatchdogLoop()
        refreshHydrationStreak()

        managerScope.launch {
            try {
                val credentialsProvider = CognitoCachingCredentialsProvider(
                    appContext,
                    IDENTITY_POOL_ID,
                    REGION
                )

                if (mqttClient != null) {
                    try {
                        mqttClient?.disconnect()
                    } catch (_: Exception) {}
                    mqttClient = null
                }

                currentClientId = "DeskBot_Android_" + UUID.randomUUID().toString().replace("-", "").take(12)
                val client = AWSIotMqttManager(currentClientId, IOT_ENDPOINT).apply {
                    isAutoReconnect = true
                    keepAlive = 60
                }
                mqttClient = client

                client.connect(credentialsProvider, AWSIotMqttClientStatusCallback { status, throwable ->
                    managerScope.launch {
                        Log.i(TAG, "MQTT Status Update: $status | Error: ${throwable?.message}")
                        when (status) {
                            AWSIotMqttClientStatus.Connected -> {
                                isBrokerConnected = true
                                isConnecting = false
                                subscribeToTelemetry()
                                drainOfflineQueue()
                                evaluateWatchdogTick()
                            }
                            AWSIotMqttClientStatus.Reconnecting -> {
                                isBrokerConnected = false
                                isConnecting = true
                                isSubscribed.set(false)
                                subscriptionJob?.cancel()
                                evaluateWatchdogTick()
                            }
                            AWSIotMqttClientStatus.ConnectionLost -> {
                                isBrokerConnected = false
                                isConnecting = false
                                isSubscribed.set(false)
                                subscriptionJob?.cancel()
                                evaluateWatchdogTick()
                            }
                            else -> {
                                if (!isBrokerConnected) {
                                    isConnecting = false
                                    isSubscribed.set(false)
                                    subscriptionJob?.cancel()
                                    evaluateWatchdogTick()
                                }
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Connect error: ${e.message}", e)
                isBrokerConnected = false
                isConnecting = false
                isSubscribed.set(false)
                subscriptionJob?.cancel()
                evaluateWatchdogTick()
            }
        }
    }

    private fun subscribeToTelemetry() {
        if (!isBrokerConnected || isSubscribed.get()) return

        subscriptionJob?.cancel()
        subscriptionJob = managerScope.launch {
            delay(300L)
            if (!isBrokerConnected || isSubscribed.get()) return@launch

            val listener = { topic: String, data: ByteArray ->
                try {
                    val payload = String(data, Charsets.UTF_8)
                    val now = System.currentTimeMillis()
                    lastPacketTimestamp.set(now)

                    val json = JSONObject(payload)
                    val postureStr = when {
                        json.has("posture") -> json.optString("posture")
                        json.has("state") -> json.optString("state")
                        else -> "Good Posture"
                    }

                    val scoreVal = when {
                        json.has("focus_score") -> json.optInt("focus_score", 100)
                        json.has("focusScore") -> json.optInt("focusScore", 100)
                        json.has("score") -> json.optInt("score", 100)
                        else -> 100
                    }.coerceIn(0, 100)

                    _posture.value = postureStr
                    _focusScore.value = scoreVal

                    evaluateWatchdogTick()

                    if (now - lastDbInsertTime >= 2000L) {
                        lastDbInsertTime = now
                        managerScope.launch {
                            try {
                                postureDao.insertLog(
                                    PostureEntity(
                                        timestamp = now,
                                        state = postureStr,
                                        focusScore = scoreVal
                                    )
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Error inserting posture log", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing telemetry packet", e)
                }
            }

            val waterAckListener = { topic: String, data: ByteArray ->
                try {
                    val payload = String(data, Charsets.UTF_8)
                    Log.i(TAG, "Received hardware water ack event on $topic: $payload")
                    recordWaterIntake(fromHardware = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling water ack packet", e)
                }
            }

            try {
                // Subscribe strictly to TOPIC_POSTURE_LIVE to avoid duplicate delivery from catch-all
                mqttClient?.subscribeToTopic(TOPIC_POSTURE_LIVE, AWSIotMqttQos.QOS0) { t, d -> listener(t, d) }
                mqttClient?.subscribeToTopic(TOPIC_WATER_ACK, AWSIotMqttQos.QOS1) { t, d -> waterAckListener(t, d) }
                mqttClient?.subscribeToTopic(TOPIC_TOUCH_WATER_ACK, AWSIotMqttQos.QOS1) { t, d -> waterAckListener(t, d) }
                mqttClient?.subscribeToTopic("deskbot/action", AWSIotMqttQos.QOS1) { t, d -> waterAckListener(t, d) }
                isSubscribed.set(true)
                Log.i(TAG, "Subscribed successfully to $TOPIC_POSTURE_LIVE, $TOPIC_WATER_ACK, $TOPIC_TOUCH_WATER_ACK, deskbot/action")
            } catch (e: Exception) {
                isSubscribed.set(false)
                Log.w(TAG, "Subscription attempt deferred: ${e.message}")
            }
        }
    }

    private fun evaluateWatchdogTick() {
        // 1. Determine instantaneous raw candidate state directly from broker transport status
        val instantaneousRaw: ConnectionState = when {
            !isBrokerConnected && isConnecting -> ConnectionState.RECONNECTING
            !isBrokerConnected -> ConnectionState.STANDALONE
            else -> ConnectionState.CONNECTED
        }

        if (instantaneousRaw != lastLoggedRawState) {
            Log.d(TAG, "Raw connection state change: $lastLoggedRawState -> $instantaneousRaw (Exposed UI: ${_connectionState.value})")
            lastLoggedRawState = instantaneousRaw
        }
        rawUnderlyingState = instantaneousRaw

        // 2. Debounce / Hysteresis: require stable hold across check cycles before exposing to UI
        val currentlyExposed = _connectionState.value
        if (instantaneousRaw == currentlyExposed) {
            if (candidateState != null) {
                Log.d(TAG, "Candidate transition reset: raw state returned to current UI state ($currentlyExposed)")
                candidateState = null
                candidateHoldCycles = 0
            }
        } else {
            if (instantaneousRaw == candidateState) {
                candidateHoldCycles++
            } else {
                candidateState = instantaneousRaw
                candidateHoldCycles = 1
            }

            // Require 1 cycle for CONNECTED (instant lock-on ~2s), 2 cycles for STANDALONE/RECONNECTING to avoid flicker
            val requiredCycles = if (instantaneousRaw == ConnectionState.CONNECTED) 1 else 2
            Log.d(TAG, "Candidate state $instantaneousRaw holding cycle $candidateHoldCycles of $requiredCycles (Current UI: $currentlyExposed)")

            if (candidateHoldCycles >= requiredCycles) {
                Log.i(TAG, "Watchdog Debounce: Committing ConnectionState $currentlyExposed -> $instantaneousRaw (held steadily for $candidateHoldCycles cycles)")
                _connectionState.value = instantaneousRaw
                candidateState = null
                candidateHoldCycles = 0
            }
        }
    }

    private fun startPassiveWatchdogLoop() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = managerScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                evaluateWatchdogTick()

                // Auto-reconnect watchdog: If disconnected and not currently attempting to connect, auto-trigger connect()
                if (!isBrokerConnected && !isConnecting) {
                    val now = System.currentTimeMillis()
                    if (now - lastReconnectAttemptTime >= 8000L) {
                        lastReconnectAttemptTime = now
                        Log.i(TAG, "Watchdog: Standalone detected, attempting auto-reconnect to AWS IoT...")
                        connect()
                    }
                }
            }
        }
    }

    private fun enqueueOrPublish(topic: String, payload: String, qos: AWSIotMqttQos) {
        if (!isBrokerConnected) {
            while (offlineQueue.size >= maxQueueSize) {
                offlineQueue.poll()
            }
            offlineQueue.offer(PendingMqttMessage(topic, payload, qos))
            Log.d(TAG, "Offline: message enqueued for $topic (queue size: ${offlineQueue.size})")
            return
        }

        managerScope.launch {
            try {
                mqttClient?.publishString(payload, topic, qos)
                Log.d(TAG, "Published to $topic: $payload")
            } catch (e: Exception) {
                Log.e(TAG, "Publish error to $topic: ${e.message}", e)
                while (offlineQueue.size >= maxQueueSize) {
                    offlineQueue.poll()
                }
                offlineQueue.offer(PendingMqttMessage(topic, payload, qos))
            }
        }
    }

    private fun drainOfflineQueue() {
        if (offlineQueue.isEmpty()) return
        Log.i(TAG, "Draining ${offlineQueue.size} offline queued messages...")
        managerScope.launch {
            while (isBrokerConnected && offlineQueue.isNotEmpty()) {
                val item = offlineQueue.poll() ?: break
                try {
                    mqttClient?.publishString(item.payload, item.topic, item.qos)
                    Log.d(TAG, "Drained message to ${item.topic}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to drain message to ${item.topic}", e)
                    offlineQueue.offer(item)
                    break
                }
            }
        }
    }

    fun publishNotification(payload: String) {
        enqueueOrPublish(TOPIC_NOTIF_STREAM, payload, AWSIotMqttQos.QOS1)
    }

    fun publishDND(enabled: Boolean) {
        _isDndActive.value = enabled
        val payload = "{\"dnd\":$enabled}"
        enqueueOrPublish(TOPIC_DND_SETTINGS, payload, AWSIotMqttQos.QOS1)
    }

    fun publishDeviceCmd(command: String) {
        val payload = "{\"cmd\":\"$command\"}"
        enqueueOrPublish(TOPIC_DEVICE_CMD, payload, AWSIotMqttQos.QOS1)
    }

    fun publishTaskSync(taskPayload: String) {
        enqueueOrPublish(TOPIC_TASKS_SYNC, taskPayload, AWSIotMqttQos.QOS1)
    }

    fun publishIntervals(eyeIntervalMin: Int, waterIntervalMin: Int, buzzerEnabled: Boolean) {
        try {
            val payload = JSONObject().apply {
                put("eyeIntervalMin", eyeIntervalMin)
                put("waterIntervalMin", waterIntervalMin)
                put("buzzerEnabled", buzzerEnabled)
            }.toString()
            enqueueOrPublish(TOPIC_INTERVAL_SETTINGS, payload, AWSIotMqttQos.QOS1)
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing intervals payload", e)
        }
    }

    fun refreshHydrationStreak() {
        managerScope.launch {
            try {
                val db = DeskBotDatabase.getDatabase(appContext)
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val startOfDay = cal.timeInMillis

                val countToday = db.waterReminderDao().getCountAcknowledgedSince(startOfDay)
                _waterCountToday.value = countToday

                val allAck = db.waterReminderDao().getAllAcknowledged()
                val dayFormatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val distinctDays = allAck.map { 
                    val t = if (it.acknowledgedTimestamp > 0) it.acknowledgedTimestamp else it.timestamp
                    dayFormatter.format(java.util.Date(t)) 
                }.distinct().toSet()

                var streak = 0
                val checkCal = java.util.Calendar.getInstance()
                val todayStr = dayFormatter.format(checkCal.time)
                if (distinctDays.contains(todayStr)) {
                    streak++
                    checkCal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                } else {
                    checkCal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                }

                while (distinctDays.contains(dayFormatter.format(checkCal.time))) {
                    streak++
                    checkCal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                }

                _hydrationStreak.value = streak
                Log.d(TAG, "Refreshed Hydration Streak: $streak days, today: $countToday")
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating hydration streak", e)
            }
        }
    }

    fun recordWaterIntake(fromHardware: Boolean = true) {
        managerScope.launch {
            try {
                val db = DeskBotDatabase.getDatabase(appContext)
                val now = System.currentTimeMillis()
                val updated = db.waterReminderDao().acknowledgeLatest(now)
                if (updated == 0) {
                    db.waterReminderDao().insertEvent(
                        com.deskbot.os.data.WaterReminderEvent(
                            timestamp = now,
                            isAcknowledged = true,
                            acknowledgedTimestamp = now
                        )
                    )
                }
                refreshHydrationStreak()

                val source = if (fromHardware) "Hardware Sensor" else "Companion HUD"
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(com.deskbot.os.service.DeskBotReminderReceiver.NOTIF_ID_WATER)
                nm.cancel(com.deskbot.os.service.DeskBotReminderReceiver.NOTIF_ID_WATER + 10)

                // No phone notification when hardware handles it
                if (!fromHardware) {
                    val ackNotif = androidx.core.app.NotificationCompat.Builder(appContext, com.deskbot.os.service.DeskBotReminderReceiver.CHANNEL_ID)
                        .setSmallIcon(com.deskbot.os.R.drawable.ic_stat_deskbot)
                        .setContentTitle("Hydration Confirmed [Companion HUD]")
                        .setContentText("Intake logged! Streak: ${_hydrationStreak.value} days.")
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(com.deskbot.os.service.DeskBotReminderReceiver.NOTIF_ID_WATER + 10, ackNotif)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record water intake", e)
            }
        }
    }

    fun disconnect() {
        managerScope.launch {
            try {
                mqttClient?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error", e)
            }
            isBrokerConnected = false
            isConnecting = false
            isSubscribed.set(false)
            rawUnderlyingState = ConnectionState.STANDALONE
            candidateState = null
            candidateHoldCycles = 0
            _connectionState.value = ConnectionState.STANDALONE
        }
    }

    fun forceReconnect() {
        managerScope.launch {
            Log.i(TAG, "User requested Force Reconnect: resetting transport and reconnecting")
            try {
                mqttClient?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting during forceReconnect", e)
            }
            isBrokerConnected = false
            isConnecting = true
            isSubscribed.set(false)
            lastPacketTimestamp.set(0L)
            rawUnderlyingState = ConnectionState.RECONNECTING
            candidateState = null
            candidateHoldCycles = 0
            _connectionState.value = ConnectionState.RECONNECTING

            delay(600L)
            isConnecting = false
            connect()
        }
    }

    fun cleanup() {
        disconnect()
        watchdogJob?.cancel()
        managerScope.cancel()
        INSTANCE = null
    }
}
