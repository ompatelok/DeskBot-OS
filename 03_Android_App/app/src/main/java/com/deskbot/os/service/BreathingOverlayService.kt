package com.deskbot.os.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.deskbot.os.data.DeskBotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val OverlayBgDark = Color(0xFF07090E)
private val OverlaySurfaceDark = Color(0xFF0F131D)
private val OverlaySurfaceEnd = Color(0xFF0A0C13)
private val OverlayBorderDark = Color(0xFF19202F)
private val OverlayCyanAccent = Color(0xFF00F0FF)
private val OverlayNeonGreen = Color(0xFFA3E635)
private val OverlayTextMuted = Color(0xFF676E7E)

class BreathingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "BreathingOverlayService"
        @Volatile
        var isOverlayActive: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var activePackageName: String? = null
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val triggerReason = intent?.getStringExtra("TRIGGER_REASON") ?: "POSTURE & USAGE RESET"
        activePackageName = intent?.getStringExtra("PACKAGE_NAME")
        showOverlay(triggerReason)
        return START_NOT_STICKY
    }

    private fun showOverlay(reason: String) {
        if (composeView != null) return
        isOverlayActive = true

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BreathingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BreathingOverlayService)
            setContent {
                OverlayBreathingContent(
                    reason = reason,
                    onDismiss = { stopSelf() }
                )
            }
        }

        composeView = view
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isOverlayActive = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        composeView = null

        val pkg = activePackageName
        if (!pkg.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = DeskBotDatabase.getDatabase(applicationContext)
                    db.appBlockingRuleDao().updateLastHijackTimestamp(pkg, System.currentTimeMillis())
                    Log.i(TAG, "Refreshed 5-minute cooldown timestamp on dismissal for $pkg")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating cooldown timestamp for $pkg", e)
                }
            }
        }
    }
}

@Composable
fun OverlayBreathingContent(reason: String, onDismiss: () -> Unit) {
    var phase by remember { mutableStateOf("INHALE") }
    var secondsLeft by remember { mutableStateOf(30) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            val cycle = (30 - secondsLeft) % 12
            phase = when {
                cycle < 4 -> "INHALE"
                cycle < 8 -> "HOLD"
                else -> "EXHALE"
            }
            delay(1000L)
            secondsLeft--
        }
        onDismiss()
    }

    val breathScale = remember { Animatable(0.28f) }
    LaunchedEffect(phase) {
        when (phase) {
            "INHALE" -> breathScale.animateTo(1.0f, tween(4000, easing = FastOutSlowInEasing))
            "HOLD" -> breathScale.snapTo(1.0f)
            "EXHALE" -> breathScale.animateTo(0.28f, tween(4000, easing = FastOutSlowInEasing))
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
            .fillMaxSize()
            .background(OverlayBgDark.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(OverlaySurfaceDark, OverlaySurfaceEnd)))
                .border(1.5.dp, OverlayCyanAccent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DESKBOT // USAGE BREAKER",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = OverlayCyanAccent,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "PULSE RESET // ACTIVE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = OverlayTextMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF141A26))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${secondsLeft}s",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = OverlayNeonGreen
                    )
                }
            }

            Text(
                text = reason.uppercase(),
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier.size(190.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val maxRadius = size.minDimension / 2.3f

                    val progressSweep = (secondsLeft.toFloat() / 30f) * 360f
                    drawArc(
                        color = OverlayBorderDark,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx()),
                        size = Size(size.width - 16f, size.height - 16f),
                        topLeft = Offset(8f, 8f)
                    )
                    drawArc(
                        color = OverlayCyanAccent,
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
                        color = OverlayCyanAccent.copy(alpha = if (phase == "HOLD") 0.28f else 0.14f),
                        radius = currentRadius * 1.25f,
                        center = center
                    )

                    drawCircle(
                        color = OverlayCyanAccent,
                        radius = currentRadius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    val rotRad = reticleRotation * (Math.PI / 180f)
                    for (i in 0 until 12) {
                        val angle = (i * 30f) * (Math.PI / 180f) + rotRad
                        val dotX = center.x + currentRadius * cos(angle).toFloat()
                        val dotY = center.y + currentRadius * sin(angle).toFloat()
                        drawCircle(
                            color = if (i % 3 == 0) OverlayNeonGreen else OverlayCyanAccent,
                            radius = if (i % 3 == 0) 3.5f else 2f,
                            center = Offset(dotX, dotY)
                        )
                    }

                    drawCircle(
                        color = OverlayCyanAccent.copy(alpha = 0.35f),
                        radius = currentRadius * 0.45f,
                        center = center
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        color = OverlayTextMuted
                    )
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF192030)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OverlayCyanAccent.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "RESUME",
                    color = OverlayCyanAccent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
