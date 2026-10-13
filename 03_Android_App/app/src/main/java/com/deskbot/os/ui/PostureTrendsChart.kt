package com.deskbot.os.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskbot.os.data.PostureEntity
import java.text.SimpleDateFormat
import java.util.*

private val SurfaceDark = Color(0xFF11141B)
private val SurfaceDarkGradient = Color(0xFF0C0E14)
private val BorderDark = Color(0xFF1B202A)
private val TextPrimary = Color(0xFFEDEDEF)
private val TextMuted = Color(0xFF676E7E)
private val CyanAccent = Color(0xFF00F0FF)
private val NeonGreen = Color(0xFFA3E635)

@Composable
fun PostureTrendsChart(
    logs: List<PostureEntity>,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("TODAY") }
    val filters = listOf("TODAY", "WEEK", "MONTH")
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }

    // 100% real logs only - no fake fallback arrays
    val filteredLogs = remember(logs, selectedFilter) {
        val takeCount = when (selectedFilter) {
            "TODAY" -> 20
            "WEEK" -> 40
            else -> 60
        }
        if (logs.isNotEmpty()) logs.take(takeCount).reversed() else emptyList()
    }

    val chartData = remember(filteredLogs) {
        filteredLogs.map { it.focusScore.toFloat().coerceIn(0f, 100f) }
    }

    val meanScore = remember(chartData) {
        if (chartData.isNotEmpty()) chartData.average().toInt() else null
    }

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(selectedFilter, logs.size) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(SurfaceDark, SurfaceDarkGradient)))
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Column {
            // Adaptive Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FOCUS METRICS // TIME-SERIES",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (meanScore != null) "$meanScore% MEAN SCORE" else "AWAITING SESSIONS // --%",
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (meanScore != null) NeonGreen else TextMuted
                    )
                }

                // Dynamic-Width Filter Selector
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF08090C))
                        .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    filters.forEach { filter ->
                        val isSelected = filter == selectedFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) NeonGreen.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable {
                                    selectedFilter = filter
                                    selectedPointIndex = null
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = filter,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) NeonGreen else TextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Chart Canvas Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                    Text("50", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                    Text("0", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 24.dp, bottom = 18.dp)
                        .pointerInput(chartData) {
                            if (chartData.isNotEmpty()) {
                                detectTapGestures { offset ->
                                    val spacing = if (chartData.size > 1) size.width / (chartData.size - 1) else size.width
                                    val index = if (chartData.size > 1) {
                                        ((offset.x + (spacing / 2)) / spacing).toInt().coerceIn(0, chartData.size - 1)
                                    } else 0
                                    selectedPointIndex = if (selectedPointIndex == index) null else index
                                }
                            }
                        }
                ) {
                    val width = size.width
                    val height = size.height
                    val progress = animProgress.value

                    val gridColor = BorderDark.copy(alpha = 0.5f)
                    drawLine(gridColor, Offset(0f, 0f), Offset(width, 0f), 1.dp.toPx())
                    drawLine(gridColor, Offset(0f, height / 2f), Offset(width, height / 2f), 1.dp.toPx())
                    drawLine(gridColor, Offset(0f, height), Offset(width, height), 1.dp.toPx())

                    if (chartData.isEmpty()) return@Canvas

                    if (chartData.size == 1) {
                        val singleY = height - (chartData[0] / 100f * height * progress)
                        // Draw horizontal reference line
                        drawLine(
                            color = NeonGreen.copy(alpha = 0.4f),
                            start = Offset(0f, singleY),
                            end = Offset(width, singleY),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                        // Draw single prominent point
                        drawCircle(
                            color = CyanAccent,
                            radius = 5.dp.toPx(),
                            center = Offset(width / 2f, singleY)
                        )
                        return@Canvas
                    }

                    val spacing = width / (chartData.size - 1)
                    val points = chartData.mapIndexed { index, score ->
                        val x = index * spacing
                        val y = height - (score / 100f * height * progress)
                        Offset(x, y)
                    }

                    val strokePath = Path().apply { moveTo(points.first().x, points.first().y) }
                    val fillPath = Path().apply {
                        moveTo(points.first().x, height)
                        lineTo(points.first().x, points.first().y)
                    }

                    for (i in 0 until points.size - 1) {
                        val p0 = points[i]
                        val p1 = points[i + 1]
                        val cx = p0.x + (p1.x - p0.x) / 2f
                        strokePath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                        fillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    }

                    fillPath.lineTo(points.last().x, height)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(NeonGreen.copy(alpha = 0.22f), Color.Transparent),
                            startY = 0f,
                            endY = height
                        )
                    )

                    drawPath(
                        path = strokePath,
                        color = NeonGreen.copy(alpha = 0.35f),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    drawPath(
                        path = strokePath,
                        color = NeonGreen,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    points.forEachIndexed { index, pt ->
                        val isSelected = selectedPointIndex == index
                        drawCircle(
                            color = if (isSelected) CyanAccent else NeonGreen,
                            radius = if (isSelected) 4.5.dp.toPx() else 2.5.dp.toPx(),
                            center = pt
                        )
                    }
                }

                // Empty state overlay if no logs yet
                if (chartData.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 24.dp, bottom = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "AWAITING TELEMETRY // REAL DATA ONLY",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = CyanAccent.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Sit in front of DeskBot to record your live posture curve",
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        }
                    }
                }

                // Tooltip on point tap
                selectedPointIndex?.let { idx ->
                    if (idx < filteredLogs.size) {
                        val entry = filteredLogs[idx]
                        val logTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF090B0E))
                                .border(1.dp, CyanAccent, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "[$logTime] ${entry.state} // ${entry.focusScore}%",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = CyanAccent
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(if (chartData.isNotEmpty()) "SESSION START" else "OFFLINE", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                Text("REAL TELEMETRY", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                Text("LATEST", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
            }
        }
    }
}