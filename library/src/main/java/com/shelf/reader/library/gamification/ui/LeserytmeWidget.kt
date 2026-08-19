package com.shelf.reader.library.gamification.ui

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class SparkParticle(
    val initialAngle: Float,
    val speed: Float,
    val maxRadius: Float,
    val size: Float,
    val color: Color
)

@Composable
fun LeserytmeWidget(
    viewModel: ReadingRhythmViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val animatedProgress by animateFloatAsState(
        targetValue = state.progressFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "ProgressSpring"
    )

    val burstAnimation = remember { Animatable(0f) }
    val sparks = remember { mutableStateListOf<SparkParticle>() }

    LaunchedEffect(Unit) {
        viewModel.engine.goalMetEvents.collectLatest {
            triggerGoalHaptic(context)
            sparks.clear()
            for (i in 0 until 24) {
                sparks.add(
                    SparkParticle(
                        initialAngle = Random.nextFloat() * 360f,
                        speed = Random.nextFloat() * 40f + 20f,
                        maxRadius = Random.nextFloat() * 50f + 30f,
                        size = Random.nextFloat() * 4f + 2f,
                        color = if (i % 2 == 0) Color(0xFFFFD700) else Color(0xFFFFE57F)
                    )
                )
            }
            burstAnimation.snapTo(0f)
            burstAnimation.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1200, easing = LinearEasing)
            )
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A2332).copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(76.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawTactileProgressArc(
                        progress = animatedProgress,
                        isGoalMet = state.isGoalMet,
                        trackColor = Color.Black.copy(alpha = 0.35f),
                        primaryGradient = listOf(
                            Color(0xFFF59E0B),
                            Color(0xFFFF8A00),
                            Color(0xFFD97706)
                        ),
                        goldGradient = listOf(
                            Color(0xFFFFE082),
                            Color(0xFFFFB300),
                            Color(0xFFFF8F00)
                        ),
                        sparks = sparks,
                        burstProgress = burstAnimation.value
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val activeMins = state.activeSeconds / 60
                    Text(
                        text = "$activeMins",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            fontFamily = FontFamily.SansSerif
                        ),
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "min",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.width(18.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Leserytme",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color(0xFFF59E0B)
                    )

                    if (state.currentStreak > 0) {
                        StreakBadge(streakDays = state.currentStreak)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = state.formattedRemainingText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (state.isGoalMet) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.5.sp
                    ),
                    color = if (state.isGoalMet) {
                        Color(0xFFFFB300)
                    } else {
                        Color(0xFFF8FAFC)
                    }
                )
            }
        }
    }
}

private fun DrawScope.drawTactileProgressArc(
    progress: Float,
    isGoalMet: Boolean,
    trackColor: Color,
    primaryGradient: List<Color>,
    goldGradient: List<Color>,
    sparks: List<SparkParticle>,
    burstProgress: Float
) {
    val strokeWidth = 8.dp.toPx()
    val diameter = size.minDimension - strokeWidth
    val arcTopLeft = Offset(strokeWidth / 2, strokeWidth / 2)
    val arcSize = Size(diameter, diameter)
    val startAngle = -90f
    val sweepAngle = progress * 360f

    drawArc(
        color = trackColor,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )

    if (progress > 0.005f) {
        val activeColors = if (isGoalMet) goldGradient else primaryGradient
        drawArc(
            brush = Brush.sweepGradient(
                colors = activeColors,
                center = center
            ),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        val headAngleRad = Math.toRadians((startAngle + sweepAngle).toDouble())
        val radius = diameter / 2f
        val cometCenterX = center.x + (radius * cos(headAngleRad)).toFloat()
        val cometCenterY = center.y + (radius * sin(headAngleRad)).toFloat()

        drawCircle(
            color = activeColors.last().copy(alpha = 0.35f),
            radius = strokeWidth * 1.3f,
            center = Offset(cometCenterX, cometCenterY)
        )
        drawCircle(
            color = Color.White,
            radius = strokeWidth * 0.38f,
            center = Offset(cometCenterX, cometCenterY)
        )
    }

    if (burstProgress in 0.01f..0.99f) {
        val alpha = (1f - burstProgress).coerceIn(0f, 1f)
        for (spark in sparks) {
            val sparkRad = Math.toRadians(spark.initialAngle.toDouble())
            val currentDist = (size.minDimension / 2f) + (spark.maxRadius * burstProgress)
            val sx = center.x + (currentDist * cos(sparkRad)).toFloat()
            val sy = center.y + (currentDist * sin(sparkRad)).toFloat()

            drawCircle(
                color = spark.color.copy(alpha = alpha),
                radius = spark.size * (1f - (burstProgress * 0.5f)),
                center = Offset(sx, sy)
            )
        }
    }
}

@Composable
private fun StreakBadge(streakDays: Int) {
    Box(
        modifier = Modifier
            .background(
                color = Color(0xFFFF8F00).copy(alpha = 0.14f),
                shape = RoundedCornerShape(100.dp)
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$streakDays dager \uD83D\uDD25",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            ),
            color = Color(0xFFFFB300)
        )
    }
}

private fun triggerGoalHaptic(context: Context) {
    val timings = longArrayOf(0, 45, 60, 45)
    val amplitudes = intArrayOf(0, 160, 0, 255)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        val vibrator = vibratorManager?.defaultVibrator
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        vibrator?.let { v -> v.vibrate(effect) }
    } else {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator?.let { v -> v.vibrate(effect) }
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(90L)
        }
    }
}
