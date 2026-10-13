package com.deskbot.os

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import com.deskbot.os.data.*
import com.deskbot.os.service.BreathingOverlayService
import com.deskbot.os.service.DeskBotMqttService
import com.deskbot.os.service.StandaloneReminderScheduler
import com.deskbot.os.ui.DailySummaryReportScreen
import com.deskbot.os.ui.DailySummaryEmbed
import com.deskbot.os.ui.AnalyticsViewModel
import com.deskbot.os.ui.AnalyticsViewModelFactory
import com.deskbot.os.ui.PostureTrendsChart
import com.deskbot.os.work.AppBudgetTrackingWorker
import com.deskbot.os.work.DailySummaryWorker
import com.deskbot.os.work.DataRetentionWorker
import com.deskbot.os.work.PurgeDiscoveredWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

data class DeskTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val category: String = "FOCUS",
    val deadlineEpochMs: Long,
    val isCompleted: Boolean = false
)

data class AppUsageInfo(
    val appName: String,
    val packageName: String,
    val usageMillis: Long,
    val percentage: Float,
    var targetLimitMinutes: Int = 60
)

fun loadPersistedTasks(context: Context): List<DeskTask> {
    val prefs = context.getSharedPreferences("deskbot_tasks", Context.MODE_PRIVATE)
    val json = prefs.getString("tasks_json", null) ?: return listOf(
        DeskTask(title = "Review Deep Work Session", category = "FOCUS", deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 2),
        DeskTask(title = "Hydrate & Eye Ergonomics Reset", category = "WELLNESS", deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 8),
        DeskTask(title = "Submit DeskBot Hardware Sprint", category = "SPRINT", deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 26)
    )
    return try {
        val array = JSONArray(json)
        val list = mutableListOf<DeskTask>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                DeskTask(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    category = obj.getString("category"),
                    deadlineEpochMs = obj.getLong("deadline"),
                    isCompleted = obj.getBoolean("done")
                )
            )
        }
        if (list.isEmpty()) {
            listOf(
                DeskTask(title = "Review Deep Work Session", category = "FOCUS", deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 2),
                DeskTask(title = "Hydrate & Eye Ergonomics Reset", category = "WELLNESS", deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 8),
                DeskTask(title = "Submit DeskBot Hardware Sprint", category = "SPRINT", deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 26)
            )
        } else list
    } catch (_: Exception) {
        listOf(
            DeskTask(title = "Review Deep Work Session", category = "FOCUS", deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 2),
            DeskTask(title = "Hydrate & Eye Ergonomics Reset", category = "WELLNESS", deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 8)
        )
    }
}

fun savePersistedTasks(context: Context, tasks: List<DeskTask>) {
    try {
        val array = JSONArray()
        tasks.forEach { t ->
            val obj = JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("category", t.category)
                put("deadline", t.deadlineEpochMs)
                put("done", t.isCompleted)
            }
            array.put(obj)
        }
        context.getSharedPreferences("deskbot_tasks", Context.MODE_PRIVATE)
            .edit()
            .putString("tasks_json", array.toString())
            .apply()
    } catch (_: Exception) {}
}

class MainActivity : ComponentActivity() {
    private lateinit var mqttManager: MqttManager
    private val shouldShowDailySummary = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("SHOW_DAILY_SUMMARY", false)) {
            shouldShowDailySummary.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("SHOW_DAILY_SUMMARY", false) == true) {
            shouldShowDailySummary.value = true
        }

        mqttManager = MqttManager.getInstance(this).apply { connect() }
        PurgeDiscoveredWorker.enqueuePeriodicCleanup(applicationContext)

        // Schedule Daily Summary at 9:00 PM
        val now = Calendar.getInstance()
        val evening = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        if (now.after(evening)) evening.add(Calendar.DAY_OF_MONTH, 1)
        val delayToEvening = evening.timeInMillis - now.timeInMillis

        val dailyRequest = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayToEvening, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "DailySummary",
            ExistingPeriodicWorkPolicy.KEEP,
            dailyRequest
        )

        // Schedule 14-day Data Retention
        val retentionRequest = PeriodicWorkRequestBuilder<DataRetentionWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "DataRetention",
            ExistingPeriodicWorkPolicy.KEEP,
            retentionRequest
        )

        // Schedule Play Store-Compliant App Budget Tracker (~60s WorkManager chained + 15m backup)
        AppBudgetTrackingWorker.scheduleTracking(applicationContext)

        // Seed 1-month realistic history data for presentation demonstration
        val appDatabase = DeskBotDatabase.getDatabase(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            com.deskbot.os.util.SampleDataSeeder.seedOneMonthHistory(applicationContext, appDatabase, force = true)
            mqttManager.refreshHydrationStreak()
        }

        setContent {
            val context = LocalContext.current
            val connectionState by mqttManager.connectionState.collectAsState()
            val posture by mqttManager.posture.collectAsState()
            val focusScore by mqttManager.focusScore.collectAsState()
            val isDndActive by mqttManager.isDndActive.collectAsState()

            val database = remember { DeskBotDatabase.getDatabase(applicationContext) }
            val consentManager = remember { ConsentManager(applicationContext) }
            val settingsManager = remember { SettingsManager(applicationContext) }
            val isConsentAcknowledged by consentManager.isConsentGranted.collectAsState(initial = true)
            val reminderSettings by settingsManager.reminderSettings.collectAsState(initial = ReminderSettings())

            val analyticsViewModel: AnalyticsViewModel = viewModel(
                factory = AnalyticsViewModelFactory(database)
            )
            val historyLogs by analyticsViewModel.historyLogs.collectAsState()
            val dailySummaries by analyticsViewModel.dailySummaries.collectAsState()

            LaunchedEffect(connectionState, reminderSettings) {
                StandaloneReminderScheduler.scheduleReminders(
                    context,
                    reminderSettings.eyeIntervalMin,
                    reminderSettings.waterIntervalMin
                )
                if (connectionState == ConnectionState.CONNECTED) {
                    mqttManager.publishIntervals(
                        reminderSettings.eyeIntervalMin,
                        reminderSettings.waterIntervalMin,
                        reminderSettings.buzzerEnabled
                    )
                }
            }

            val postNotifLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) DeskBotMqttService.start(context)
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                        postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        DeskBotMqttService.start(context)
                    }
                } else {
                    DeskBotMqttService.start(context)
                }
            }

            MainAppContainer(
                mqttManager = mqttManager,
                database = database,
                consentManager = consentManager,
                settingsManager = settingsManager,
                isConsentAcknowledged = isConsentAcknowledged,
                connectionState = connectionState,
                posture = posture,
                focusScore = focusScore,
                isDndActive = isDndActive,
                historyLogs = historyLogs,
                dailySummaries = dailySummaries,
                reminderSettings = reminderSettings,
                shouldShowDailySummary = shouldShowDailySummary
            )
        }
    }
}

private val BgDark = Color(0xFF07090E)
private val SurfaceDark = Color(0xFF0F131D)
private val SurfaceGradientEnd = Color(0xFF0A0C13)
private val BorderDark = Color(0xFF19202F)
private val TextPrimary = Color(0xFFEDEDEF)
private val TextMuted = Color(0xFF676E7E)
private val NeonGreen = Color(0xFFA3E635)
private val CoralRed = Color(0xFFF87171)
private val CyanAccent = Color(0xFF00F0FF)
private val AmberWarn = Color(0xFFFBBF24)

enum class NavScreen(val label: String, val icon: ImageVector) {
    HUD("HUD", Icons.Default.Home),
    ANALYTICS("METRICS", Icons.Default.Info),
    DEVICE("DEVICE", Icons.Default.Share),
    SETTINGS("CONFIG", Icons.Default.Settings)
}

@Composable
fun MainAppContainer(
    mqttManager: MqttManager,
    database: DeskBotDatabase,
    consentManager: ConsentManager,
    settingsManager: SettingsManager,
    isConsentAcknowledged: Boolean,
    connectionState: ConnectionState,
    posture: String,
    focusScore: Int,
    isDndActive: Boolean,
    historyLogs: List<PostureEntity>,
    dailySummaries: List<DailySummaryEntity>,
    reminderSettings: ReminderSettings,
    shouldShowDailySummary: MutableState<Boolean>
) {
    var currentScreen by remember { mutableStateOf(NavScreen.HUD) }
    var analyticsInitialTab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val waterReminders by database.waterReminderDao().getAllEventsFlow().collectAsState(initial = emptyList())
    val appLimitBreaks by database.appLimitBreakEventDao().getAllEventsFlow().collectAsState(initial = emptyList())

    LaunchedEffect(shouldShowDailySummary.value) {
        if (shouldShowDailySummary.value) {
            currentScreen = NavScreen.ANALYTICS
            analyticsInitialTab = 1
            shouldShowDailySummary.value = false
        }
    }

    var showStartupDisclosure by remember(isConsentAcknowledged) {
        mutableStateOf(!isConsentAcknowledged)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0A0C14),
                tonalElevation = 8.dp
            ) {
                NavScreen.values().forEach { screen ->
                    val isSelected = currentScreen == screen
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentScreen = screen },
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.label,
                                tint = if (isSelected) CyanAccent else TextMuted
                            )
                        },
                        label = {
                            Text(
                                text = screen.label,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) CyanAccent else TextMuted
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF161D2B))
                    )
                }
            }
        },
        containerColor = BgDark
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                NavScreen.HUD -> DashboardScreen(
                    mqttManager = mqttManager,
                    database = database,
                    connectionState = connectionState,
                    posture = posture,
                    focusScore = focusScore,
                    historyLogs = historyLogs
                )
                NavScreen.ANALYTICS -> AnalyticsScreen(
                    historyLogs = historyLogs,
                    dailySummaries = dailySummaries,
                    waterReminders = waterReminders,
                    appLimitBreaks = appLimitBreaks,
                    initialTab = analyticsInitialTab
                )
                NavScreen.DEVICE -> DeviceHubScreen(
                    mqttManager = mqttManager,
                    connectionState = connectionState,
                    posture = posture,
                    focusScore = focusScore
                )
                NavScreen.SETTINGS -> UnifiedConfigScreen(
                    db = database,
                    consentManager = consentManager,
                    settingsManager = settingsManager,
                    reminderSettings = reminderSettings,
                    isDndActive = isDndActive,
                    onToggleDnd = { enabled -> mqttManager.publishDND(enabled) }
                )
            }

            if (showStartupDisclosure) {
                NotificationConsentDisclosureDialog(
                    onDismiss = { showStartupDisclosure = false },
                    onAccept = {
                        scope.launch {
                            consentManager.recordConsentGranted()
                            showStartupDisclosure = false
                            val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                            if (!hasAccess) {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    mqttManager: MqttManager,
    database: DeskBotDatabase,
    connectionState: ConnectionState,
    posture: String,
    focusScore: Int,
    historyLogs: List<PostureEntity>
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isLiveStreamActive = connectionState == ConnectionState.CONNECTED

    var sessionSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isLiveStreamActive) {
        if (isLiveStreamActive) {
            while (true) {
                delay(1000L)
                sessionSeconds++
            }
        } else {
            sessionSeconds = 0
        }
    }

    val hrs = sessionSeconds / 3600
    val mins = (sessionSeconds % 3600) / 60
    val secs = sessionSeconds % 60
    val sessionTimeFormatted = String.format("%02d:%02d:%02d", hrs, mins, secs)

    val hydrationStreak by mqttManager.hydrationStreak.collectAsState()
    val waterCountToday by mqttManager.waterCountToday.collectAsState()

    val tasks = remember {
        mutableStateListOf<DeskTask>().apply {
            addAll(loadPersistedTasks(context))
        }
    }

    val syncTasksToDeskBot: () -> Unit = {
        savePersistedTasks(context, tasks)
        coroutineScope.launch(Dispatchers.IO) {
            val jsonArray = JSONArray()
            tasks.forEach { t ->
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("category", t.category)
                    put("deadline", t.deadlineEpochMs)
                    put("done", t.isCompleted)
                }
                jsonArray.put(obj)
            }
            val syncPayload = JSONObject().apply {
                put("action", "sync_tasks")
                put("tasks", jsonArray)
            }.toString()
            mqttManager.publishTaskSync(syncPayload)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeaderBar(
            connectionState = connectionState,
            sessionTimeFormatted = sessionTimeFormatted,
            isLiveStreamActive = isLiveStreamActive,
            onReconnectClick = {
                mqttManager.connect()
                Toast.makeText(context, "Re-connecting AWS IoT Core...", Toast.LENGTH_SHORT).show()
            }
        )

        HeroTelemetryCard(
            posture = posture,
            focusScore = focusScore,
            isLiveStreamActive = isLiveStreamActive
        )

        HydrationStreakCard(
            streak = hydrationStreak,
            waterCountToday = waterCountToday
        )

        SynchronizedSquarePulseResetCard()

        TopThreeAppsWithLimitCard(context = context, database = database)

        DeskTasksManagerCard(
            tasks = tasks,
            onToggleTask = { task, newStatus ->
                val index = tasks.indexOfFirst { it.id == task.id }
                if (index != -1) {
                    tasks[index] = task.copy(isCompleted = newStatus)
                    syncTasksToDeskBot()
                }
            },
            onSaveTask = { task ->
                val index = tasks.indexOfFirst { it.id == task.id }
                if (index != -1) {
                    tasks[index] = task
                } else {
                    tasks.add(task)
                }
                syncTasksToDeskBot()
            },
            onDeleteTask = { task ->
                tasks.removeAll { it.id == task.id }
                syncTasksToDeskBot()
            }
        )

        CompactMetricsBar(historyLogs)

        NotificationBridgeCard(
            context = context,
            onSendTestNotification = {
                val testPayload = "{\"source\":\"WhatsApp\",\"sender\":\"DeskBot\",\"msg\":\"Take a gentle stretch!\"}"
                mqttManager.publishNotification(testPayload)
                Toast.makeText(context, "Test Alert dispatched to OLED", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun HeaderBar(
    connectionState: ConnectionState,
    sessionTimeFormatted: String,
    isLiveStreamActive: Boolean,
    onReconnectClick: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> NeonGreen
            ConnectionState.RECONNECTING -> AmberWarn
            ConnectionState.STANDALONE -> CoralRed
        },
        animationSpec = tween(500),
        label = "statusColor"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "DESKBOT OS",
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary,
                letterSpacing = 1.5.sp
            )
            Text(
                text = if (isLiveStreamActive) "SESSION: $sessionTimeFormatted // CV ONLINE" else "STANDALONE // LOCAL ACTIVE",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0E1015))
                .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .clickable { onReconnectClick() }
                .padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = connectionState.name,
                    color = statusColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun HeroTelemetryCard(posture: String, focusScore: Int, isLiveStreamActive: Boolean) {
    val isSlouching = posture.contains("Slouch", ignoreCase = true) || posture.contains("Distraction", ignoreCase = true)
    val isDrowsy = posture.contains("Drowsy", ignoreCase = true) || posture.contains("Sleep", ignoreCase = true)

    val accentColor = when {
        !isLiveStreamActive -> CyanAccent
        isSlouching -> CoralRed
        isDrowsy -> AmberWarn
        else -> NeonGreen
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(SurfaceDark, SurfaceGradientEnd)))
            .border(
                1.2.dp,
                if (isLiveStreamActive && !isSlouching) CyanAccent.copy(alpha = glowAlpha * 0.7f) else BorderDark,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLiveStreamActive) "> LIVE TELEMETRY" else "> STANDALONE MODE",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = if (isLiveStreamActive) "WSS SIGV4" else "LOCAL WELLNESS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isLiveStreamActive) posture.uppercase() else "AUTONOMOUS MONITOR",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (!isLiveStreamActive) "EYE & HYDRATION TIMERS ACTIVE"
                        else if (isSlouching) "POSTURE CORRECTION REQ"
                        else if (isDrowsy) "DROWSINESS DETECTED // BREAK ADVISE"
                        else "ALIGNED // OPTIMAL",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSlouching || isDrowsy) accentColor else TextMuted
                    )
                }

                Text(
                    text = if (isLiveStreamActive) "$focusScore%" else "OK",
                    fontSize = 32.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = accentColor,
                    letterSpacing = (-1).sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { (focusScore.toFloat() / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accentColor,
                trackColor = Color(0xFF1B202A)
            )
        }
    }
}

// Synchronized 4-4-4 Pulse Reset
@Composable
fun SynchronizedSquarePulseResetCard() {
    var isExercising by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("INHALE") }
    var secondsLeft by remember { mutableStateOf(60) }

    LaunchedEffect(isExercising) {
        if (isExercising) {
            secondsLeft = 60
            while (secondsLeft > 0 && isExercising) {
                val cycle = (60 - secondsLeft) % 12
                phase = when {
                    cycle < 4 -> "INHALE"
                    cycle < 8 -> "HOLD"
                    else -> "EXHALE"
                }
                delay(1000L)
                secondsLeft--
            }
            isExercising = false
            phase = "COMPLETE"
        }
    }

    val breathScale = remember { Animatable(0.28f) }
    LaunchedEffect(phase, isExercising) {
        if (!isExercising) {
            breathScale.snapTo(0.28f)
        } else {
            when (phase) {
                "INHALE" -> breathScale.animateTo(1.0f, tween(4000, easing = FastOutSlowInEasing))
                "HOLD" -> breathScale.snapTo(1.0f)
                "EXHALE" -> breathScale.animateTo(0.28f, tween(4000, easing = FastOutSlowInEasing))
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulseRingHarmonics")
    val jiggleOffset by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "jiggleOffset"
    )

    val reticleRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart),
        label = "reticleRotation"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(
                1.5.dp,
                if (isExercising) CyanAccent.copy(alpha = 0.6f) else BorderDark,
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PULSE RESET // 60s",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )
                    Text(
                        text = if (isExercising) "CYCLE: 4-4-4 SYNCHRONIZED" else "NEURAL RECALIBRATION",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF141A26))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isExercising) "${secondsLeft}s" else "READY",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isExercising) NeonGreen else TextPrimary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(190.dp)
                    .clickable { isExercising = !isExercising },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val maxRadius = size.minDimension / 2.3f

                    if (isExercising) {
                        val progressSweep = (secondsLeft.toFloat() / 60f) * 360f
                        drawArc(
                            color = BorderDark,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx()),
                            size = Size(size.width - 16f, size.height - 16f),
                            topLeft = Offset(8f, 8f)
                        )
                        drawArc(
                            color = CyanAccent,
                            startAngle = -90f,
                            sweepAngle = progressSweep,
                            useCenter = false,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                            size = Size(size.width - 16f, size.height - 16f),
                            topLeft = Offset(8f, 8f)
                        )

                        val holdOffset = if (phase == "HOLD") jiggleOffset else 0f
                        val currentRadius = ((maxRadius * breathScale.value) + holdOffset).coerceIn(25f, maxRadius)

                        drawCircle(
                            color = CyanAccent.copy(alpha = if (phase == "HOLD") 0.28f else 0.14f),
                            radius = currentRadius * 1.25f,
                            center = center
                        )

                        drawCircle(
                            color = CyanAccent,
                            radius = currentRadius,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        val rotRad = reticleRotation * (Math.PI / 180f)
                        for (i in 0 until 12) {
                            val angle = (i * (360f / 12f)) * (Math.PI / 180f) + rotRad
                            val dotX = center.x + currentRadius * cos(angle).toFloat()
                            val dotY = center.y + currentRadius * sin(angle).toFloat()
                            drawCircle(
                                color = if (i % 3 == 0) NeonGreen else CyanAccent,
                                radius = if (i % 3 == 0) 3.5f else 2f,
                                center = Offset(dotX, dotY)
                            )
                        }

                        drawCircle(
                            color = CyanAccent.copy(alpha = 0.35f),
                            radius = currentRadius * 0.45f,
                            center = center
                        )
                    } else {
                        drawCircle(
                            color = BorderDark,
                            radius = 65f,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawCircle(
                            color = CyanAccent.copy(alpha = 0.08f),
                            radius = 65f,
                            center = center
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isExercising) {
                        Text(
                            text = phase,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = when (phase) {
                                "INHALE" -> "Expand lungs"
                                "HOLD" -> "Hold stillness"
                                else -> "Release spine"
                            },
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start",
                            tint = CyanAccent,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "ACTIVATE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CyanAccent
                        )
                    }
                }
            }

            Button(
                onClick = { isExercising = !isExercising },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isExercising) Color(0xFF192030) else CyanAccent
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = if (isExercising) "HALT SESSION" else "START PULSE RESET",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isExercising) CyanAccent else Color.Black
                )
            }
        }
    }
}

@Composable
fun HydrationStreakCard(
    streak: Int,
    waterCountToday: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(SurfaceDark, SurfaceGradientEnd)))
            .border(1.2.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "💧 HYDRATION STREAK",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF141C2B))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "ESP8266 CAPACITIVE SYNC",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = NeonGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F1117))
                        .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                        .padding(vertical = 10.dp, horizontal = 12.dp)
                ) {
                    Column {
                        Text("CURRENT STREAK", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "$streak",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (streak > 0) NeonGreen else TextPrimary
                            )
                            Text("DAYS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F1117))
                        .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                        .padding(vertical = 10.dp, horizontal = 12.dp)
                ) {
                    Column {
                        Text("TODAY'S INTAKE", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "$waterCountToday",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CyanAccent
                            )
                            Text("CUPS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D121D))
                    .border(1.dp, CyanAccent.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp, horizontal = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("⚡", fontSize = 12.sp)
                    Text(
                        text = "Tap the capacitive touch sensor on DeskBot hardware to log water intake & build your streak.",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanAccent,
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}

// Room-Backed App Usage with WorkManager Compliant Budgeting
@Composable
fun TopThreeAppsWithLimitCard(context: Context, database: DeskBotDatabase) {
    var topApps by remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    var hasUsagePermission by remember { mutableStateOf(false) }
    var showUsageDisclosure by remember { mutableStateOf(false) }
    var appToEditLimit by remember { mutableStateOf<AppUsageInfo?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val ruleDao = remember { database.appBlockingRuleDao() }

    fun checkUsagePermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun queryTopApps() {
        withContext(Dispatchers.IO) {
            val granted = checkUsagePermission()
            withContext(Dispatchers.Main) { hasUsagePermission = granted }
            if (granted) {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return@withContext
                val cal = Calendar.getInstance()
                val endTime = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val startTime = cal.timeInMillis

                val usageMap = mutableMapOf<String, Long>()

                // 1. Authoritative base from queryAndAggregateUsageStats
                try {
                    val aggregated = usm.queryAndAggregateUsageStats(startTime, endTime)
                    for ((pkg, stats) in aggregated) {
                        if (stats.totalTimeInForeground > 0L) {
                            usageMap[pkg] = stats.totalTimeInForeground
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DeskBotOS", "Error querying queryAndAggregateUsageStats", e)
                }

                // 2. Supplement with events to account for currently active foreground app session
                try {
                    val events = usm.queryEvents(startTime, endTime)
                    val event = android.app.usage.UsageEvents.Event()
                    val resumeTimes = mutableMapOf<String, Long>()

                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        val pkg = event.packageName ?: continue
                        val t = event.timeStamp
                        if (t < startTime || t > endTime) continue

                        when (event.eventType) {
                            android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                                resumeTimes[pkg] = t
                            }
                            android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                            android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> {
                                val resumeT = resumeTimes.remove(pkg)
                                if (resumeT != null) {
                                    val duration = t - maxOf(resumeT, startTime)
                                    if (duration > 0 && !usageMap.containsKey(pkg)) {
                                        usageMap[pkg] = (usageMap[pkg] ?: 0L) + duration
                                    }
                                }
                            }
                        }
                    }

                    for ((pkg, resumeT) in resumeTimes) {
                        val duration = endTime - maxOf(resumeT, startTime)
                        if (duration > 0) {
                            usageMap[pkg] = maxOf(usageMap[pkg] ?: 0L, duration)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DeskBotOS", "Error querying UsageEvents in MainActivity", e)
                }

                val pm = context.packageManager
                val ignoredPackages = setOf(
                    "com.google.android.googlequicksearchbox",
                    "com.android.systemui",
                    "android",
                    "com.android.settings",
                    "com.google.android.apps.nexuslauncher",
                    "com.google.android.inputmethod.latin"
                )

                val topAggregated = usageMap.entries
                    .filter { (pkg, duration) ->
                        duration > 5_000L &&
                        pkg != context.packageName &&
                        !ignoredPackages.contains(pkg) &&
                        !pkg.contains("launcher", ignoreCase = true) &&
                        !pkg.contains("systemui", ignoreCase = true) &&
                        !pkg.contains("inputmethod", ignoreCase = true)
                    }
                    .sortedByDescending { it.value }
                    .take(3)

                val totalTopMillis = topAggregated.sumOf { it.value }.coerceAtLeast(1L)
                val formattedList = topAggregated.map { (pkg, duration) ->
                    val cleanName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) {
                        pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                    }
                    val existingRule = ruleDao.getRuleForPackage(pkg)
                    val limitVal = existingRule?.dailyBudgetMinutes ?: 60

                    AppUsageInfo(
                        appName = cleanName,
                        packageName = pkg,
                        usageMillis = duration,
                        percentage = (duration.toFloat() / totalTopMillis.toFloat()).coerceIn(0.1f, 1.0f),
                        targetLimitMinutes = limitVal
                    )
                }

                withContext(Dispatchers.Main) {
                    topApps = formattedList
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { queryTopApps() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Live continuous dynamic polling every 8s while permission is granted
    LaunchedEffect(hasUsagePermission) {
        while (true) {
            queryTopApps()
            delay(8000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ACTIVE SCREEN TIME // TODAY",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent
                )
                Text(
                    text = "TAP TO SET LIMIT",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            }

            if (!hasUsagePermission) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Permission required to mirror daily application time",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { showUsageDisclosure = true },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("GRANT ACCESS", color = Color.Black, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (topApps.isEmpty()) {
                Text(
                    text = "Compiling device application telemetry...",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    topApps.forEachIndexed { index, app ->
                        val hours = app.usageMillis / (1000 * 60 * 60)
                        val mins = (app.usageMillis / (1000 * 60)) % 60
                        val totalMinutes = (hours * 60) + mins
                        val isOverLimit = totalMinutes >= app.targetLimitMinutes

                        val rankColor = when {
                            isOverLimit -> CoralRed
                            index == 0 -> CyanAccent
                            index == 1 -> NeonGreen
                            else -> AmberWarn
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { appToEditLimit = app }
                                .padding(vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(rankColor.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = rankColor
                                        )
                                    }
                                    Text(
                                        text = app.appName,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = if (hours > 0) "${hours}h ${mins}m / ${app.targetLimitMinutes}m" else "${mins}m / ${app.targetLimitMinutes}m",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = rankColor
                                )
                            }

                            LinearProgressIndicator(
                                progress = { (totalMinutes.toFloat() / app.targetLimitMinutes.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = rankColor,
                                trackColor = Color(0xFF141924)
                            )
                        }
                }

                if (!Settings.canDrawOverlays(context)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1B150A))
                            .border(1.dp, AmberWarn.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clickable {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("DISPLAY OVERLAY PERMISSION REQUIRED", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AmberWarn)
                            Text("Needed for 60s breathing break screen takeover", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                        }
                        Text("ENABLE >", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AmberWarn)
                    }
                }
            }
        }
    }
}

    appToEditLimit?.let { targetApp ->
        var limitInput by remember(targetApp) { mutableStateOf(targetApp.targetLimitMinutes.toString()) }

        Dialog(
            onDismissRequest = { appToEditLimit = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0F131D)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "SET TIME LIMIT // ${targetApp.appName.uppercase()}",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Daily Budget (Minutes)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyanAccent) },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { appToEditLimit = null }) {
                            Text("CANCEL", color = TextMuted, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val userConfigured = limitInput.toIntOrNull() ?: targetApp.targetLimitMinutes
                                targetApp.targetLimitMinutes = userConfigured
                                topApps = topApps.map { if (it.packageName == targetApp.packageName) it.copy(targetLimitMinutes = userConfigured) else it }

                                scope.launch(Dispatchers.IO) {
                                    ruleDao.upsertRule(
                                        AppBlockingRule(
                                            packageName = targetApp.packageName,
                                            appName = targetApp.appName,
                                            dailyBudgetMinutes = userConfigured
                                        )
                                    )
                                    queryTopApps()
                                }

                                appToEditLimit = null
                                Toast.makeText(context, "Saved: ${userConfigured}m budget", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("SAVE", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showUsageDisclosure) {
        UsageAccessConsentDisclosureDialog(
            onDismiss = { showUsageDisclosure = false },
            onAccept = {
                showUsageDisclosure = false
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        )
    }
}

@Composable
fun DeskTasksManagerCard(
    tasks: List<DeskTask>,
    onToggleTask: (DeskTask, Boolean) -> Unit,
    onSaveTask: (DeskTask) -> Unit,
    onDeleteTask: (DeskTask) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<DeskTask?>(null) }
    val now = System.currentTimeMillis()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DESK REMINDERS & TO-DO TASKS",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "DEADLINE COLOR-SHIFT TELEMETRY",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }

                Button(
                    onClick = {
                        taskToEdit = DeskTask(
                            title = "",
                            category = "FOCUS",
                            deadlineEpochMs = System.currentTimeMillis() + 3600_000L * 4
                        )
                        showEditDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B202A)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent)
                ) {
                    Text("+ ADD", color = CyanAccent, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEach { task ->
                    val diffHours = (task.deadlineEpochMs - now) / (1000 * 60 * 60)
                    val (statusColor, deadlineLabel) = when {
                        task.isCompleted -> Pair(TextMuted, "COMPLETED")
                        diffHours < 0 -> Pair(CoralRed, "OVERDUE")
                        diffHours < 2 -> Pair(CoralRed, "< 2 HOURS")
                        diffHours < 12 -> Pair(AmberWarn, "DUE IN ${diffHours}H")
                        else -> Pair(NeonGreen, "DUE IN ${diffHours / 24}D ${diffHours % 24}H")
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0C0F16))
                            .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable {
                                taskToEdit = task
                                showEditDialog = true
                            }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = task.isCompleted,
                                    onCheckedChange = { checked ->
                                        onToggleTask(task, checked)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = CyanAccent,
                                        checkmarkColor = Color.Black,
                                        uncheckedColor = BorderDark
                                    )
                                )
                                Column {
                                    Text(
                                        text = task.title,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (task.isCompleted) TextMuted else TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = task.category,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextMuted
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = deadlineLabel,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                                Text(
                                    text = "TAP TO EDIT",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog && taskToEdit != null) {
        val current = taskToEdit!!
        var titleInput by remember { mutableStateOf(current.title) }
        var categoryInput by remember { mutableStateOf(current.category) }
        var hoursAhead by remember { mutableStateOf("4") }

        Dialog(
            onDismissRequest = { showEditDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .imePadding()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF141926)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "EDIT DESKBOT TASK",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )

                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Task Description", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyanAccent) },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = BorderDark,
                            cursorColor = CyanAccent
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text("Category Tag (e.g. FOCUS, SPRINT)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyanAccent) },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = BorderDark,
                            cursorColor = CyanAccent
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = hoursAhead,
                        onValueChange = { hoursAhead = it.filter { char -> char.isDigit() } },
                        label = { Text("Deadline in Hours (e.g. 2, 8, 24)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyanAccent) },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = BorderDark,
                            cursorColor = CyanAccent
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            onDeleteTask(current)
                            showEditDialog = false
                        }) {
                            Text("DELETE", color = CoralRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (titleInput.isNotBlank()) {
                                    val addedHours = hoursAhead.toLongOrNull() ?: 4L
                                    val updatedTask = current.copy(
                                        title = titleInput.trim(),
                                        category = categoryInput.trim().uppercase(),
                                        deadlineEpochMs = System.currentTimeMillis() + (addedHours * 3600_000L)
                                    )
                                    onSaveTask(updatedTask)
                                }
                                showEditDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("SAVE & SYNC", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactMetricsBar(logs: List<PostureEntity>) {
    var slouchCount = 0
    var goodCount = 0
    for (entry in logs) {
        if (entry.state.contains("Slouch", ignoreCase = true)) slouchCount++
        if (entry.state.contains("Good", ignoreCase = true) || entry.state.contains("Aligned", ignoreCase = true)) goodCount++
    }
    val accuracy = if (logs.isNotEmpty()) ((goodCount.toFloat() / logs.size) * 100).toInt() else 0
    val accuracyDisplay = if (logs.isNotEmpty()) "$accuracy%" else "—"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0F1117))
                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                .padding(vertical = 8.dp, horizontal = 8.dp)
        ) {
            Column {
                Text("SLOUCH ALERTS", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                Spacer(modifier = Modifier.height(2.dp))
                Text("$slouchCount", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = CoralRed)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0F1117))
                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                .padding(vertical = 8.dp, horizontal = 8.dp)
        ) {
            Column {
                Text("ACCURACY", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                Spacer(modifier = Modifier.height(2.dp))
                Text(accuracyDisplay, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (logs.isNotEmpty()) NeonGreen else TextMuted)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0F1117))
                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                .padding(vertical = 8.dp, horizontal = 8.dp)
        ) {
            Column {
                Text("SAMPLES", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                Spacer(modifier = Modifier.height(2.dp))
                Text("${logs.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = CyanAccent)
            }
        }
    }
}

@Composable
fun NotificationBridgeCard(
    context: Context,
    onSendTestNotification: () -> Unit
) {
    val isPermissionGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NOTIFICATION BRIDGE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isPermissionGranted) "STATUS: ACTIVE // FORWARDING" else "PERMISSION REQUIRED",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isPermissionGranted) NeonGreen else CoralRed
                    )
                }

                if (!isPermissionGranted) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "ENABLE",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (BuildConfig.DEBUG) {
                Button(
                    onClick = onSendTestNotification,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B202A)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "> SEND TEST NOTIFICATION TO OLED",
                        color = CyanAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AnalyticsScreen(
    historyLogs: List<PostureEntity>,
    dailySummaries: List<DailySummaryEntity> = emptyList(),
    waterReminders: List<WaterReminderEvent> = emptyList(),
    appLimitBreaks: List<AppLimitBreakEvent> = emptyList(),
    initialTab: Int = 0
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "METRICS // TELEMETRY & AUDIT",
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CyanAccent.copy(alpha = 0.15f))
                    .border(1.dp, CyanAccent.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "UNIFIED REPORT",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent
                )
            }
        }

        // 1. Time-Series Telemetry & Posture Trends
        PostureTrendsChart(logs = historyLogs)
        CompactMetricsBar(historyLogs)

        // 2. Merged 7-Day Rollup & Daily Summary Report with Water & App Breaks Audit
        DailySummaryEmbed(
            summaries = dailySummaries,
            historyLogs = historyLogs,
            waterReminders = waterReminders,
            appLimitBreaks = appLimitBreaks
        )

        // 3. Live Telemetry Event Log
        Text(
            text = "SYSTEM EVENT LOG",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        if (historyLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F1117))
                    .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Awaiting live telemetry stream...", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                historyLogs.take(8).forEach { log ->
                    val isSlouch = log.state.contains("Slouch", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F1117))
                            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    log.state,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSlouch) CoralRed else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(timeFormatter.format(Date(log.timestamp)), fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                            }
                            Text("${log.focusScore}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (isSlouch) CoralRed else CyanAccent)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun DeviceHubScreen(
    mqttManager: MqttManager,
    connectionState: ConnectionState,
    posture: String,
    focusScore: Int
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val context = LocalContext.current
    var isHighContrast by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DEVICE HUB // HARDWARE",
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "NODE: ESP8266 + SH1106 OLED",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF141A26))
                    .border(1.dp, if (isConnected) NeonGreen.copy(alpha = 0.5f) else CoralRed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isConnected) "ONLINE" else "STANDALONE",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isConnected) NeonGreen else CoralRed
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF04060A))
                .border(1.5.dp, if (isConnected) CyanAccent.copy(alpha = 0.6f) else BorderDark, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SH1106 OLED MIRROR // 128x64", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = CyanAccent)
                    Text("I2C 0x3C", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(1.dp, Color(0xFF162032), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isConnected) "[ ${posture.uppercase()} ]" else "[ STANDALONE LOCAL ]",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isHighContrast) Color.White else Color(0xFF888888)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isConnected) "FOCUS: $focusScore% // SIGV4" else "AUTONOMOUS WELLNESS MONITORS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isConnected) NeonGreen else TextMuted
                        )
                    }
                }
            }
        }

        Text(
            text = "HARDWARE ACTIONS",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    isHighContrast = !isHighContrast
                    mqttManager.publishDeviceCmd(if (isHighContrast) "contrast_high" else "contrast_low")
                    Toast.makeText(context, "OLED Contrast toggled", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.6f))
            ) {
                Text("TOGGLE CONTRAST", color = CyanAccent, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    mqttManager.forceReconnect()
                    Toast.makeText(context, "Reconnecting MQTT transport...", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarn.copy(alpha = 0.6f))
            ) {
                Text("FORCE RECONNECT", color = AmberWarn, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            text = "DIAGNOSTIC TELEMETRY",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("HARDWARE CONTROLLER", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                    Text("ESP8266 80MHz", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("OLED PERIPHERAL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                    Text("SH1106 1.3\" (I2C)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CLOUD BROKER", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                    Text("AWS IoT Core (ATS)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = CyanAccent)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AUTH CREDENTIALS", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                    Text("Cognito SigV4 TLS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = NeonGreen)
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun UnifiedConfigScreen(
    db: DeskBotDatabase,
    consentManager: ConsentManager,
    settingsManager: SettingsManager,
    reminderSettings: ReminderSettings,
    isDndActive: Boolean,
    onToggleDnd: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filterDao = db.filterDao()

    var showPickerModal by remember { mutableStateOf(false) }
    var showDisclosureDialog by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }

    val selectedChats by filterDao.getSelectedConversations("com.whatsapp").collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "CONFIGURATION // SETTINGS",
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "WELLNESS SCHEDULE & INTERVALS",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("20-20-20 Eye Break", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            Text("${reminderSettings.eyeIntervalMin} min", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = NeonGreen)
                        }
                        Slider(
                            value = reminderSettings.eyeIntervalMin.toFloat(),
                            onValueChange = { scope.launch { settingsManager.updateEyeInterval(it.toInt()) } },
                            valueRange = 10f..60f,
                            steps = 9,
                            colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
                        )
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Hydration Reminder", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            Text("${reminderSettings.waterIntervalMin} min", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = NeonGreen)
                        }
                        Slider(
                            value = reminderSettings.waterIntervalMin.toFloat(),
                            onValueChange = { scope.launch { settingsManager.updateWaterInterval(it.toInt()) } },
                            valueRange = 15f..120f,
                            steps = 6,
                            colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
                        )

                        Button(
                            onClick = {
                                StandaloneReminderScheduler.triggerImmediateReminder(context)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D2329)),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
                        ) {
                            Text("⚡ TEST HYDRATION REMINDER NOW", color = CyanAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Buzzer Chime Alert", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            Text("Sound chime on display alerts", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                        }
                        Switch(
                            checked = reminderSettings.buzzerEnabled,
                            onCheckedChange = { scope.launch { settingsManager.setBuzzerEnabled(it) } },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = CyanAccent
                            )
                        )
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DO NOT DISTURB (DND)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Mute physical buzzer on robot", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                    }
                    Switch(
                        checked = isDndActive,
                        onCheckedChange = onToggleDnd,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = CyanAccent
                        )
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("WHATSAPP ALLOWLIST", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { showPickerModal = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B202A)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent)
                ) {
                    Text("+ ADD FILTER", color = CyanAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (selectedChats.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F1117))
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .padding(14.dp)
                ) {
                    Text("No active filters. Tap '+ ADD FILTER' to specify allowed contacts.", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            items(selectedChats, key = { it.id }) { chat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(chat.conversationTitle, color = NeonGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("RULE: ${chat.matchType.uppercase()}", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = { scope.launch { filterDao.deleteSelectedConversation(chat) } }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CoralRed)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showPolicyDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Text("PRIVACY POLICY & DISCLOSURE", color = Color(0xFFB0B7C3), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showPickerModal) {
        WhatsAppAllowlistModal(
            onDismiss = { showPickerModal = false },
            onSaveRule = { pattern, isRegex ->
                scope.launch {
                    filterDao.upsertSelectedConversation(
                        SelectedConversation(
                            appPackage = "com.whatsapp",
                            conversationTitle = pattern,
                            matchType = if (isRegex) "regex" else "exact",
                            isEnabled = true
                        )
                    )
                    showPickerModal = false
                }
            }
        )
    }

    if (showDisclosureDialog) {
        NotificationConsentDisclosureDialog(
            onDismiss = { showDisclosureDialog = false },
            onAccept = {
                scope.launch {
                    consentManager.recordConsentGranted()
                    showDisclosureDialog = false
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
        )
    }

    if (showPolicyDialog) {
        PrivacyPolicyDialog(onDismiss = { showPolicyDialog = false })
    }
}

@Composable
fun WhatsAppAllowlistModal(
    onDismiss: () -> Unit,
    onSaveRule: (pattern: String, isRegex: Boolean) -> Unit
) {
    var customInput by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F1219)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "ADD WHATSAPP SENDER FILTER",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Enter sender name or regex pattern to allow mirroring to desk OLED. No contact permissions required.",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text(if (isRegex) "Regex Pattern (e.g. .*Family.*)" else "Exact Contact/Chat Name", fontSize = 11.sp) },
                    textStyle = TextStyle(fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isRegex,
                        onCheckedChange = { isRegex = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = CyanAccent,
                            checkmarkColor = Color.Black,
                            uncheckedColor = BorderDark
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Treat as regular expression", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (customInput.isNotBlank()) {
                                onSaveRule(customInput.trim(), isRegex)
                            }
                        },
                        enabled = customInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("SAVE FILTER", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}


@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF08090C)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PRIVACY POLICY // DESKBOT OS", fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = CyanAccent)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Effective Date: September 2026\n\n" +
                                "1. OVERVIEW\n" +
                                "DeskBot OS mirrors posture metrics and approved WhatsApp notifications to an ESP8266 + SH1106 OLED desk hardware terminal.\n\n" +
                                "2. DATA PROCESSING\n" +
                                "• Notification Content: Only messages from WhatsApp chats you select in the allowlist are forwarded. All other notifications are dropped instantly in memory.\n" +
                                "• Zero Remote Storage: Messages and posture logs are signed using AWS SigV4 and relayed ephemerally. No remote profiling or central database is maintained.\n" +
                                "• Local Persistence: Whitelist filters, app usage limits, and session stats are stored exclusively in your device's local Room database.\n\n" +
                                "3. USER CONTROLS\n" +
                                "Permissions can be revoked at any time via Android System Settings.",
                        color = Color(0xFFB0B7C3),
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B202A)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
                ) {
                    Text("CLOSE", color = CyanAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun NotificationConsentDisclosureDialog(
    onDismiss: () -> Unit,
    onAccept: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF08090C)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(40.dp))
                Text("Data Access Disclosure", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(
                    text = "DeskBot OS requires Notification Access strictly to forward messages from approved WhatsApp chats to your desk OLED.\n\n" +
                            "• Granular Whitelist: Only chats you explicitly allow are forwarded.\n" +
                            "• Zero Profiling: Notifications are never stored remotely or analyzed.\n" +
                            "• Instant Control: Revoke permissions anytime in settings.",
                    color = Color(0xFFB0B7C3),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("DECLINE", color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onAccept,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("AGREE & CONTINUE", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun UsageAccessConsentDisclosureDialog(
    onDismiss: () -> Unit,
    onAccept: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF08090C)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(40.dp))
                Text("Usage Access Disclosure", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(
                    text = "DeskBot OS requires Usage Access (PACKAGE_USAGE_STATS) to track screen time for your configured daily app budgets.\n\n" +
                            "• On-Device Evaluation: App usage statistics are queried locally and never transmitted externally.\n" +
                            "• Budget Enforcement: Triggers the Breathing Break overlay when you exceed your set daily focus limit.\n" +
                            "• Zero Profiling: Browsing activity, app content, and private data are never accessed or logged.\n" +
                            "• Instant Control: You can revoke Usage Access at any time in Android Settings.",
                    color = Color(0xFFB0B7C3),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("DECLINE", color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onAccept,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("AGREE & GRANT", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

