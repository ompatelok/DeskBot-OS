package com.deskbot.os.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskbot.os.data.AppLimitBreakEvent
import com.deskbot.os.data.DailySummaryEntity
import com.deskbot.os.data.PostureEntity
import com.deskbot.os.data.WaterReminderEvent

private val BgDark = Color(0xFF07090E)
private val SurfaceDark = Color(0xFF0F131D)
private val SurfaceEnd = Color(0xFF0A0C13)
private val BorderDark = Color(0xFF19202F)
private val TextPrimary = Color(0xFFEDEDEF)
private val TextMuted = Color(0xFF676E7E)
private val NeonGreen = Color(0xFFA3E635)
private val CoralRed = Color(0xFFF87171)
private val CyanAccent = Color(0xFF00F0FF)
private val AmberWarn = Color(0xFFFBBF24)

@Composable
fun DailySummaryReportScreen(
    summaries: List<DailySummaryEntity>,
    onBack: (() -> Unit)? = null
) {
    val latestSummary = summaries.firstOrNull()
    val pastSevenDays = remember(summaries) { summaries.take(7).reversed() }

    var expandedMetric by remember { mutableStateOf<String?>("POSTURE") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DAILY SUMMARY REPORT",
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = latestSummary?.let { "DATE: ${it.dateStr} // 21:00 SNAPSHOT" } ?: "WAITING FOR 21:00 LOCAL ROLLUP",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanAccent
                    )
                }

                if (onBack != null) {
                    TextButton(onClick = onBack) {
                        Text("BACK", color = CyanAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        // Suggestions Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(SurfaceDark, SurfaceEnd)))
                    .border(1.2.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "> RULE-BASED ERGONOMIC ADVICE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CyanAccent,
                            letterSpacing = 1.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF141C2B))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ON-DEVICE AI",
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                color = NeonGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val suggestions = latestSummary?.suggestions
                        ?: "• Maintain good cervical alignment tomorrow.\n• Acknowledge hydration breaks to stay alert.\n• Keep 20-20-20 eye rhythm during heavy screen sessions."

                    Text(
                        text = suggestions,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Section header
        item {
            Text(
                text = "INTERACTIVE 7-DAY METRIC BREAKDOWN (TAP TO EXPAND)",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted,
                letterSpacing = 0.8.sp
            )
        }

        // 1. Average Posture Score Card
        item {
            val currentValue = latestSummary?.avgPostureScore
            ExpandableMetricCard(
                metricKey = "POSTURE",
                title = "POSTURE SCORE",
                currentDisplay = if (currentValue != null) "$currentValue%" else "—",
                subtitle = if (currentValue == null) "AWAITING SESSIONS" else if (currentValue >= 75) "OPTIMAL ALIGNMENT" else "EXCESSIVE SLOUCHING",
                accentColor = if (currentValue == null) TextMuted else if (currentValue >= 75) NeonGreen else CoralRed,
                isExpanded = expandedMetric == "POSTURE",
                onToggle = { expandedMetric = if (expandedMetric == "POSTURE") null else "POSTURE" }
            ) {
                val trendPoints = remember(pastSevenDays, currentValue) {
                    if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.avgPostureScore.toFloat() }
                    else if (currentValue != null) listOf(currentValue.toFloat())
                    else emptyList()
                }
                MetricTrendChart(
                    data = trendPoints,
                    accentColor = if (currentValue != null && currentValue >= 75) NeonGreen else CoralRed,
                    unit = "%",
                    dates = pastSevenDays.map { it.dateStr.takeLast(5) }
                )
            }
        }

        // 2. Average Focus Score Card
        item {
            val currentValue = latestSummary?.avgFocusScore
            ExpandableMetricCard(
                metricKey = "FOCUS",
                title = "FOCUS SCORE",
                currentDisplay = if (currentValue != null) "$currentValue%" else "—",
                subtitle = if (currentValue == null) "AWAITING TELEMETRY" else if (currentValue >= 70) "SUSTAINED ATTENTION" else "FREQUENT DISTRACTIONS",
                accentColor = if (currentValue == null) TextMuted else if (currentValue >= 70) CyanAccent else AmberWarn,
                isExpanded = expandedMetric == "FOCUS",
                onToggle = { expandedMetric = if (expandedMetric == "FOCUS") null else "FOCUS" }
            ) {
                val trendPoints = remember(pastSevenDays, currentValue) {
                    if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.avgFocusScore.toFloat() }
                    else if (currentValue != null) listOf(currentValue.toFloat())
                    else emptyList()
                }
                MetricTrendChart(
                    data = trendPoints,
                    accentColor = CyanAccent,
                    unit = "%",
                    dates = pastSevenDays.map { it.dateStr.takeLast(5) }
                )
            }
        }

        // 3. Slouch Event Count Card
        item {
            val currentValue = latestSummary?.slouchCount ?: 0
            ExpandableMetricCard(
                metricKey = "SLOUCH",
                title = "SLOUCH ALERTS",
                currentDisplay = if (latestSummary != null) "$currentValue" else "0",
                subtitle = if (currentValue < 10) "WITHIN HEALTHY RANGE" else "HIGH POSTURE DEFICIT",
                accentColor = if (currentValue < 10) NeonGreen else CoralRed,
                isExpanded = expandedMetric == "SLOUCH",
                onToggle = { expandedMetric = if (expandedMetric == "SLOUCH") null else "SLOUCH" }
            ) {
                val trendPoints = remember(pastSevenDays) {
                    if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.slouchCount.toFloat() }
                    else if (latestSummary != null) listOf(currentValue.toFloat())
                    else emptyList()
                }
                MetricBarChart(
                    data = trendPoints,
                    accentColor = CoralRed,
                    dates = pastSevenDays.map { it.dateStr.takeLast(5) }
                )
            }
        }

        // 4. Water Reminder Compliance Card
        item {
            val currentValue = latestSummary?.waterCompliancePercent
            ExpandableMetricCard(
                metricKey = "WATER",
                title = "WATER COMPLIANCE",
                currentDisplay = if (currentValue != null) "$currentValue%" else "—",
                subtitle = if (currentValue == null) "NO SCHEDULED BREAKS" else if (currentValue >= 75) "TARGET MET // HYDRATED" else "MISSED REMINDERS",
                accentColor = if (currentValue == null) TextMuted else if (currentValue >= 75) NeonGreen else AmberWarn,
                isExpanded = expandedMetric == "WATER",
                onToggle = { expandedMetric = if (expandedMetric == "WATER") null else "WATER" }
            ) {
                val trendPoints = remember(pastSevenDays, currentValue) {
                    if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.waterCompliancePercent.toFloat() }
                    else if (currentValue != null) listOf(currentValue.toFloat())
                    else emptyList()
                }
                MetricTrendChart(
                    data = trendPoints,
                    accentColor = if (currentValue != null && currentValue >= 75) NeonGreen else AmberWarn,
                    unit = "%",
                    dates = pastSevenDays.map { it.dateStr.takeLast(5) }
                )
            }
        }

        // 5. Time Spent in Good Posture Card
        item {
            val minutes = latestSummary?.goodPostureMinutes ?: 0
            val hrs = minutes / 60
            val mins = minutes % 60
            val display = if (minutes > 0) "${hrs}h ${mins}m" else if (latestSummary != null) "0m" else "—"

            ExpandableMetricCard(
                metricKey = "DURATION",
                title = "TIME IN GOOD POSTURE",
                currentDisplay = display,
                subtitle = "TOTAL CUMULATIVE TIME",
                accentColor = NeonGreen,
                isExpanded = expandedMetric == "DURATION",
                onToggle = { expandedMetric = if (expandedMetric == "DURATION") null else "DURATION" }
            ) {
                val trendPoints = remember(pastSevenDays, minutes) {
                    if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.goodPostureMinutes.toFloat() }
                    else if (minutes > 0) listOf(minutes.toFloat())
                    else emptyList()
                }
                MetricBarChart(
                    data = trendPoints,
                    accentColor = NeonGreen,
                    dates = pastSevenDays.map { it.dateStr.takeLast(5) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DailySummaryEmbed(
    summaries: List<DailySummaryEntity>,
    historyLogs: List<PostureEntity> = emptyList(),
    waterReminders: List<WaterReminderEvent> = emptyList(),
    appLimitBreaks: List<AppLimitBreakEvent> = emptyList()
) {
    val latestSummary = summaries.firstOrNull()
    val pastSevenDays = remember(summaries) { summaries.take(7).reversed() }
    var expandedMetric by remember { mutableStateOf<String?>("POSTURE") }

    // Live calculations from real telemetry if no daily rollup summary exists yet
    val todayGoodCount = remember(historyLogs) {
        historyLogs.count { it.state.contains("Good", ignoreCase = true) || it.state.contains("Aligned", ignoreCase = true) }
    }
    val todaySlouchCount = remember(historyLogs) {
        historyLogs.count { it.state.contains("Slouch", ignoreCase = true) }
    }
    val livePostureScore = remember(historyLogs, todayGoodCount) {
        if (historyLogs.isNotEmpty()) ((todayGoodCount.toFloat() / historyLogs.size) * 100).toInt() else null
    }
    val liveFocusScore = remember(historyLogs) {
        if (historyLogs.isNotEmpty()) historyLogs.map { it.focusScore }.average().toInt() else null
    }
    val liveGoodMinutes = remember(todayGoodCount) {
        (todayGoodCount * 2) / 60
    }

    // Dynamic Ergonomic Advice incorporating real water compliance & limit breaks & real telemetry
    val dynamicAdvice = remember(summaries, historyLogs, waterReminders, appLimitBreaks) {
        val list = mutableListOf<String>()

        // 1. App Limit Breaks
        if (appLimitBreaks.isNotEmpty()) {
            val appCounts = appLimitBreaks.groupBy { it.appName }
            val multiBreakApp = appCounts.entries.find { it.value.size >= 2 }
            if (multiBreakApp != null) {
                list.add("• High app usage: ${multiBreakApp.key} exceeded limit ${multiBreakApp.value.size} times. Consider extending work intervals.")
            } else {
                val firstBreak = appCounts.entries.first()
                list.add("• App limit reached: ${firstBreak.key} triggered a focus break today.")
            }
        }

        // 2. Hydration Compliance
        val totalWater = waterReminders.size
        val ackWater = waterReminders.count { it.isAcknowledged }
        if (totalWater == 0) {
            list.add("• Hydration tracking active. Awaiting first scheduled intake interval.")
        } else {
            val hydrationCompliance = (ackWater * 100 / totalWater)
            if (hydrationCompliance < 60) {
                list.add("• Low hydration compliance ($hydrationCompliance%). Tap the DeskBot touch sensor to log hydration.")
            } else {
                list.add("• Hydration compliance steady ($hydrationCompliance%). Regular water intake boosts alertness.")
            }
        }

        // 3. Posture / Ergonomics
        val avgPosture = latestSummary?.avgPostureScore ?: livePostureScore
        if (avgPosture != null) {
            if (avgPosture >= 80) {
                list.add("• Good cervical alignment ($avgPosture%). Keep monitor at eye level.")
            } else {
                list.add("• Frequent slouching detected ($avgPosture%). Perform periodic neck & shoulder stretches.")
            }
        } else {
            list.add("• Posture tracking active. Start a session in front of DeskBot to record alignment.")
        }

        list.joinToString("\n")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Suggestions Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(SurfaceDark, SurfaceEnd)))
                .border(1.2.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "> RULE-BASED ERGONOMIC ADVICE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        letterSpacing = 1.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF141C2B))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ON-DEVICE AI",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NeonGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = dynamicAdvice,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary,
                    lineHeight = 18.sp
                )
            }
        }

        // Daily Water Reminders Sent vs Acknowledged Card
        val totalWater = waterReminders.size
        val ackWater = waterReminders.count { it.isAcknowledged }
        val hasWaterData = totalWater > 0
        val waterPercent = if (hasWaterData) (ackWater * 100 / totalWater) else 0
        val complianceDisplay = if (hasWaterData) "$waterPercent%" else "—"
        val subtitleDisplay = if (hasWaterData) {
            "$ackWater ACKNOWLEDGED / $totalWater REMINDERS SENT"
        } else {
            "NO DATA YET // 0 REMINDERS SENT"
        }
        val statusColor = if (!hasWaterData) TextMuted else if (waterPercent >= 60) NeonGreen else AmberWarn
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "HYDRATION // COMPLIANCE AUDIT",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = subtitleDisplay,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = statusColor
                        )
                    }
                    Text(
                        text = complianceDisplay,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = statusColor
                    )
                }

                // Progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF19202F))
                ) {
                    if (hasWaterData) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = (waterPercent / 100f).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (waterPercent >= 60) NeonGreen else AmberWarn)
                        )
                    }
                }

                if (waterReminders.isNotEmpty()) {
                    Text(
                        text = "RECENT HYDRATION LOGS",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        fontWeight = FontWeight.Bold
                    )
                    waterReminders.take(3).forEach { evt ->
                        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(evt.timestamp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "• Reminder sent at $timeStr", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            Text(
                                text = if (evt.isAcknowledged) "ACKNOWLEDGED" else "MISSED",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (evt.isAcknowledged) NeonGreen else CoralRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Daily App-Limit Breaks Triggered Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "APP-LIMIT BREAKS // FOCUS AUDIT",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${appLimitBreaks.size} TOTAL SCREEN TAKEOVER BREAKS",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (appLimitBreaks.isEmpty()) NeonGreen else CoralRed
                        )
                    }
                    Text(
                        text = "${appLimitBreaks.size}",
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = if (appLimitBreaks.isEmpty()) NeonGreen else CoralRed
                    )
                }

                if (appLimitBreaks.isEmpty()) {
                    Text(
                        text = "No focus limit breaks triggered today. App budgets intact!",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                } else {
                    Text(
                        text = "BREAK EVENTS LOGGED",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        fontWeight = FontWeight.Bold
                    )
                    appLimitBreaks.take(5).forEach { evt ->
                        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(evt.timestamp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "• ${evt.appName.uppercase()} (${evt.usageMinutesAtTrigger}m / ${evt.thresholdMinutes}m cap)",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                            Text(
                                text = timeStr,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AmberWarn,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "7-DAY METRIC AUDIT (TAP TO EXPAND)",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted,
            letterSpacing = 0.8.sp
        )

        // 1. Posture
        val postureVal = latestSummary?.avgPostureScore ?: livePostureScore
        ExpandableMetricCard(
            metricKey = "POSTURE",
            title = "POSTURE SCORE",
            currentDisplay = if (postureVal != null) "$postureVal%" else "—",
            subtitle = if (postureVal == null) "AWAITING SESSIONS" else if (postureVal >= 75) "OPTIMAL ALIGNMENT" else "EXCESSIVE SLOUCHING",
            accentColor = if (postureVal == null) TextMuted else if (postureVal >= 75) NeonGreen else CoralRed,
            isExpanded = expandedMetric == "POSTURE",
            onToggle = { expandedMetric = if (expandedMetric == "POSTURE") null else "POSTURE" }
        ) {
            val trendPoints = remember(pastSevenDays, postureVal) {
                if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.avgPostureScore.toFloat() }
                else if (postureVal != null) listOf(postureVal.toFloat())
                else emptyList()
            }
            MetricTrendChart(
                data = trendPoints,
                accentColor = if (postureVal != null && postureVal >= 75) NeonGreen else CoralRed,
                unit = "%",
                dates = pastSevenDays.map { it.dateStr.takeLast(5) }
            )
        }

        // 2. Focus
        val focusVal = latestSummary?.avgFocusScore ?: liveFocusScore
        ExpandableMetricCard(
            metricKey = "FOCUS",
            title = "FOCUS SCORE",
            currentDisplay = if (focusVal != null) "$focusVal%" else "—",
            subtitle = if (focusVal == null) "AWAITING TELEMETRY" else if (focusVal >= 70) "SUSTAINED ATTENTION" else "FREQUENT DISTRACTIONS",
            accentColor = if (focusVal == null) TextMuted else if (focusVal >= 70) CyanAccent else AmberWarn,
            isExpanded = expandedMetric == "FOCUS",
            onToggle = { expandedMetric = if (expandedMetric == "FOCUS") null else "FOCUS" }
        ) {
            val trendPoints = remember(pastSevenDays, focusVal) {
                if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.avgFocusScore.toFloat() }
                else if (focusVal != null) listOf(focusVal.toFloat())
                else emptyList()
            }
            MetricTrendChart(
                data = trendPoints,
                accentColor = CyanAccent,
                unit = "%",
                dates = pastSevenDays.map { it.dateStr.takeLast(5) }
            )
        }

        // 3. Slouch
        val slouchVal = latestSummary?.slouchCount ?: todaySlouchCount
        ExpandableMetricCard(
            metricKey = "SLOUCH",
            title = "SLOUCH ALERTS",
            currentDisplay = if (latestSummary != null || historyLogs.isNotEmpty()) "$slouchVal" else "0",
            subtitle = if (slouchVal < 10) "WITHIN HEALTHY RANGE" else "HIGH POSTURE DEFICIT",
            accentColor = if (slouchVal < 10) NeonGreen else CoralRed,
            isExpanded = expandedMetric == "SLOUCH",
            onToggle = { expandedMetric = if (expandedMetric == "SLOUCH") null else "SLOUCH" }
        ) {
            val trendPoints = remember(pastSevenDays, slouchVal) {
                if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.slouchCount.toFloat() }
                else if (latestSummary != null || historyLogs.isNotEmpty()) listOf(slouchVal.toFloat())
                else emptyList()
            }
            MetricBarChart(
                data = trendPoints,
                accentColor = CoralRed,
                dates = pastSevenDays.map { it.dateStr.takeLast(5) }
            )
        }

        // 4. Water
        val realWaterVal = if (waterReminders.isNotEmpty()) {
            (waterReminders.count { it.isAcknowledged } * 100 / waterReminders.size)
        } else {
            latestSummary?.waterCompliancePercent
        }
        ExpandableMetricCard(
            metricKey = "WATER",
            title = "WATER COMPLIANCE",
            currentDisplay = if (realWaterVal != null) "$realWaterVal%" else "—",
            subtitle = if (realWaterVal == null) "NO SCHEDULED BREAKS" else if (realWaterVal >= 75) "TARGET MET // HYDRATED" else "MISSED REMINDERS",
            accentColor = if (realWaterVal == null) TextMuted else if (realWaterVal >= 75) NeonGreen else AmberWarn,
            isExpanded = expandedMetric == "WATER",
            onToggle = { expandedMetric = if (expandedMetric == "WATER") null else "WATER" }
        ) {
            val trendPoints = remember(pastSevenDays, realWaterVal) {
                if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.waterCompliancePercent.toFloat() }
                else if (realWaterVal != null) listOf(realWaterVal.toFloat())
                else emptyList()
            }
            MetricTrendChart(
                data = trendPoints,
                accentColor = if (realWaterVal != null && realWaterVal >= 75) NeonGreen else AmberWarn,
                unit = "%",
                dates = pastSevenDays.map { it.dateStr.takeLast(5) }
            )
        }

        // 5. Good Posture Duration
        val minutes = latestSummary?.goodPostureMinutes ?: liveGoodMinutes
        val hrs = minutes / 60
        val mins = minutes % 60
        val display = if (minutes > 0) "${hrs}h ${mins}m" else if (latestSummary != null || historyLogs.isNotEmpty()) "0m" else "—"
        ExpandableMetricCard(
            metricKey = "DURATION",
            title = "TIME IN GOOD POSTURE",
            currentDisplay = display,
            subtitle = "TOTAL CUMULATIVE TIME",
            accentColor = NeonGreen,
            isExpanded = expandedMetric == "DURATION",
            onToggle = { expandedMetric = if (expandedMetric == "DURATION") null else "DURATION" }
        ) {
            val trendPoints = remember(pastSevenDays, minutes) {
                if (pastSevenDays.isNotEmpty()) pastSevenDays.map { it.goodPostureMinutes.toFloat() }
                else if (minutes > 0) listOf(minutes.toFloat())
                else emptyList()
            }
            MetricBarChart(
                data = trendPoints,
                accentColor = NeonGreen,
                dates = pastSevenDays.map { it.dateStr.takeLast(5) }
            )
        }
    }
}

@Composable
fun ExpandableMetricCard(
    metricKey: String,
    title: String,
    currentDisplay: String,
    subtitle: String,
    accentColor: Color,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, if (isExpanded) accentColor.copy(alpha = 0.6f) else BorderDark, RoundedCornerShape(14.dp))
            .clickable { onToggle() }
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = accentColor
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = currentDisplay,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = accentColor
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(tween(300)) + fadeIn(),
                exit = shrinkVertically(tween(300)) + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = BorderDark, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "7-DAY HISTORICAL TREND",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}

@Composable
fun MetricTrendChart(
    data: List<Float>,
    accentColor: Color,
    unit: String,
    dates: List<String>
) {
    if (data.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Color(0xFF090B0F))
                .border(1.dp, BorderDark, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "NO HISTORICAL TRENDS LOGGED YET",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted
            )
        }
        return
    }

    val maxVal = (data.maxOrNull() ?: 100f).coerceAtLeast(10f)
    val minVal = (data.minOrNull() ?: 0f).coerceAtLeast(0f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(Color(0xFF090B0F))
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Draw grid line
            drawLine(BorderDark, Offset(0f, h / 2), Offset(w, h / 2), 1.dp.toPx())

            if (data.size == 1) {
                val v = data[0]
                val normalizedY = if (maxVal > minVal) (v - minVal) / (maxVal - minVal) else 0.5f
                val y = h - (normalizedY * (h - 20f)) - 10f
                drawLine(
                    color = accentColor.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )
                drawCircle(color = accentColor, radius = 4.dp.toPx(), center = Offset(w / 2f, y))
                return@Canvas
            }

            val spacing = w / (data.size - 1)
            val points = data.mapIndexed { index, v ->
                val x = index * spacing
                val normalizedY = if (maxVal > minVal) (v - minVal) / (maxVal - minVal) else 0.5f
                val y = h - (normalizedY * (h - 20f)) - 10f
                Offset(x, y)
            }

            // Path
            val strokePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 0 until points.size - 1) {
                    val p0 = points[i]
                    val p1 = points[i + 1]
                    val cx = p0.x + (p1.x - p0.x) / 2f
                    cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                }
            }

            val fillPath = Path().apply {
                addPath(strokePath)
                lineTo(points.last().x, h)
                lineTo(points.first().x, h)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.25f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            drawPath(
                path = strokePath,
                color = accentColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            points.forEach { pt ->
                drawCircle(color = accentColor, radius = 3.dp.toPx(), center = pt)
            }
        }
    }
}

@Composable
fun MetricBarChart(
    data: List<Float>,
    accentColor: Color,
    dates: List<String>
) {
    if (data.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Color(0xFF090B0F))
                .border(1.dp, BorderDark, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "NO HISTORICAL AUDIT LOGS YET",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted
            )
        }
        return
    }

    val maxVal = (data.maxOrNull() ?: 20f).coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(Color(0xFF090B0F))
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val barWidth = (w / (data.size * 2f)).coerceIn(12.dp.toPx(), 28.dp.toPx())
            val slotWidth = w / data.size

            data.forEachIndexed { i, value ->
                val barHeight = ((value / maxVal) * (h - 15f)).coerceAtLeast(4.dp.toPx())
                val x = (i * slotWidth) + (slotWidth - barWidth) / 2f
                val y = h - barHeight

                drawRect(
                    color = accentColor.copy(alpha = 0.85f),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }
}
