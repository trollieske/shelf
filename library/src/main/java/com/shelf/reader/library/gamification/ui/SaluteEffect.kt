package com.shelf.reader.library.gamification.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInBack
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class SaluteTier { GOLD, SILVER, BRONZE }

private object EaseOutExp {
    fun transform(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x == 1f) 1f else 1f - 2f.pow(-10f * x)
    }
}

private data class ConfettiParticle(
    val angleRad: Float,
    val speed: Float,
    val gravityFactor: Float,
    val size: Float,
    val color: Color,
    val shape: ConfettiShape,
    val initial3DZ: Float,
    val rotationSpeed: Float,
    val rotationAxisX: Float,
    val rotationAxisY: Float,
    val hueShift: Float = 0f,
    val lifeDelay: Float = 0f
)

private enum class ConfettiShape {
    SQUARE, CIRCLE, STAR, DIAMOND, RECTANGLE
}

private data class StardustParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val alpha: Float,
    val twinkleSpeed: Float,
    val color: Color
)

@Stable
class SaluteEffectState {
    internal val showEffect = Animatable(0f)
    internal val trophySpin = Animatable(0f)
    internal val trophyFloat = Animatable(0f)
    var isPlaying by mutableStateOf(false)
        internal set

    private val listeners = mutableListOf<() -> Unit>()

    fun addOnEndListener(l: () -> Unit) { listeners.add(l) }

    internal fun notifyEnded() {
        isPlaying = false
        listeners.forEach { it() }
    }
}

@Composable
fun rememberSaluteEffectState(): SaluteEffectState {
    return remember { SaluteEffectState() }
}

suspend fun SaluteEffectState.play(tier: SaluteTier = SaluteTier.GOLD, durationMs: Int = 5000) {
    if (isPlaying) return
    isPlaying = true
    trophySpin.snapTo(0f)
    trophyFloat.snapTo(0f)
    showEffect.snapTo(0f)
    showEffect.animateTo(1f, animationSpec = tween(durationMillis = 320, easing = EaseOutCubic))
    val trophyJob = CoroutineScope(Dispatchers.Default).launch {
        val start = System.currentTimeMillis()
        while (isPlaying) {
            val timeMs = System.currentTimeMillis() - start
            val t = ((timeMs % 2400L) / 2400f)
            trophyFloat.snapTo(sin(t * PI.toFloat() * 2) * 0.5f + 0.5f)
            trophySpin.snapTo((t * 360f) % 360f)
            delay(16L)
        }
    }
    delay(durationMs.toLong())
    showEffect.animateTo(0f, animationSpec = tween(durationMillis = 600, easing = EaseInOutCubic))
    trophyJob.cancel()
    notifyEnded()
}

@Composable
fun SaluteEffectOverlay(
    state: SaluteEffectState,
    tier: SaluteTier = SaluteTier.GOLD,
    titleText: String? = null,
    subtitleText: String? = null,
    modifier: Modifier = Modifier
) {
    val progress = state.showEffect.value
    if (progress <= 0.001f) return

    val density = LocalDensity.current.density

    val palette = when (tier) {
        SaluteTier.GOLD -> SalutePalette(
            primary = Color(0xFFFFD700),
            secondary = Color(0xFFFFE57F),
            tertiary = Color(0xFFF59E0B),
            deep = Color(0xFFFF8F00),
            dark = Color(0xFFB45309),
            confettiColors = listOf(
                Color(0xFFFFD700), Color(0xFFFFE57F), Color(0xFFFF8F00),
                Color(0xFFF59E0B), Color(0xFFFFB300), Color(0xFFFFFFFF),
                Color(0xFFFF6B35), Color(0xFFEF4444), Color(0xFFA855F7)
            )
        )
        SaluteTier.SILVER -> SalutePalette(
            primary = Color(0xFFE5E7EB),
            secondary = Color(0xFFFFFFFF),
            tertiary = Color(0xFF9CA3AF),
            deep = Color(0xFF6B7280),
            dark = Color(0xFF374151),
            confettiColors = listOf(
                Color(0xFFFFFFFF), Color(0xFFE5E7EB), Color(0xFF9CA3AF),
                Color(0xFF60A5FA), Color(0xFF3B82F6), Color(0xFF06B6D4),
                Color(0xFFA78BFA), Color(0xFFF9FAFB), Color(0xFFD1D5DB)
            )
        )
        SaluteTier.BRONZE -> SalutePalette(
            primary = Color(0xFFCD7F32),
            secondary = Color(0xFFE8A15E),
            tertiary = Color(0xFFB87333),
            deep = Color(0xFF8B4513),
            dark = Color(0xFF654321),
            confettiColors = listOf(
                Color(0xFFCD7F32), Color(0xFFE8A15E), Color(0xFFB87333),
                Color(0xFFFF8C42), Color(0xFFD2691E), Color(0xFFF4A460),
                Color(0xFFFFD700), Color(0xFFFFEFD5), Color(0xFFDEB887)
            )
        )
    }

    val confetti = remember(tier) { generateConfetti(palette) }
    val stardust = remember { generateStardust(palette) }

    val textMeasurer = rememberTextMeasurer()

    val infinite = rememberInfiniteTransition(label = "salute_infinite")
    val shimmerPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer"
    )

    val title = titleText ?: when (tier) {
        SaluteTier.GOLD -> "MÅL OPPNÅDD!"
        SaluteTier.SILVER -> "FLOTT JOBB!"
        SaluteTier.BRONZE -> "BRA JOBB!"
    }
    val subtitle = subtitleText ?: when (tier) {
        SaluteTier.GOLD -> "Du er gullverdig!"
        SaluteTier.SILVER -> "Fortsett slik!"
        SaluteTier.BRONZE -> "Bra start!"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val alpha = progress.coerceIn(0f, 1f)

            drawVignetteBackground(alpha, w, h)
            drawShockwaves(progress, cx, cy, palette, shimmerPhase, alpha)
            drawStardustBurst(stardust, progress, shimmerPhase, alpha, cx, cy, h)
            drawConfetti3D(confetti, progress, palette, shimmerPhase, alpha, cx, cy, w, h)
            drawTrophyGlowHalo(progress, shimmerPhase, alpha, palette, cx, cy)
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val enter = EaseOutBack.transform(progress)
                    val floatSin = sin(state.trophyFloat.value * PI.toFloat() * 2)
                    translationY = (1f - enter) * 400f + floatSin * 8f
                    scaleX = 0.5f + enter * 0.5f
                    scaleY = scaleX
                    rotationX = 15f * sin((state.trophySpin.value * PI.toFloat() / 180f) * 2)
                    rotationY = state.trophySpin.value
                    rotationZ = (1f - enter) * -25f
                    cameraDistance = 10f * density
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                }
        ) {
            Canvas(modifier = Modifier.size(220.dp)) {
                draw3DTrophy(
                    palette = palette,
                    shimmerPhase = shimmerPhase,
                    overallAlpha = progress
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 110.dp)
                .graphicsLayer {
                    val enter = EaseOutBack.transform(
                        ((progress - 0.15f).coerceIn(0f, 1f)) / 0.85f
                    )
                    translationY = (1f - enter) * -80f
                    alpha = enter
                }
        ) {
            val titleLayout = remember(title, palette.primary.hashCode()) {
                textMeasurer.measure(
                    AnnotatedString(title),
                    style = TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 1.2.sp
                    )
                )
            }
            val subtitleLayout = remember(subtitle, palette.dark.hashCode()) {
                textMeasurer.measure(
                    AnnotatedString(subtitle),
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.3.sp
                    )
                )
            }

            val widthPx = titleLayout.size.width + 40f * density
            val heightPx = titleLayout.size.height + subtitleLayout.size.height + 28f * density
            val widthDp: Dp
            val heightDp: Dp
            with(LocalDensity.current) {
                widthDp = widthPx.toDp()
                heightDp = heightPx.toDp()
            }

            Canvas(
                modifier = Modifier
                    .size(widthDp, heightDp)
            ) {
                val shimmerColors = listOf(
                    palette.dark.copy(alpha = 0.95f * progress),
                    palette.primary.copy(alpha = 1f * progress),
                    palette.secondary.copy(alpha = 1f * progress),
                    palette.primary.copy(alpha = 1f * progress),
                    palette.dark.copy(alpha = 0.95f * progress)
                )
                val brushStops = arrayOf(
                    0f to shimmerColors[0],
                    (0.25f + shimmerPhase * 0.25f) to shimmerColors[1],
                    (0.5f + shimmerPhase * 0.25f) to shimmerColors[2],
                    (0.75f + shimmerPhase * 0.25f) to shimmerColors[3],
                    1f to shimmerColors[4]
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = title,
                    topLeft = Offset(
                        (size.width - titleLayout.size.width) / 2f,
                        0f
                    ),
                    style = TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 1.2.sp,
                        brush = Brush.linearGradient(
                            colorStops = brushStops,
                            start = Offset.Zero,
                            end = Offset(size.width, titleLayout.size.height.toFloat())
                        ),
                        // shadow disabled for this compose version
                    )
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = subtitle,
                    topLeft = Offset(
                        (size.width - subtitleLayout.size.width) / 2f,
                        titleLayout.size.height + 12f
                    ),
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.3.sp,
                        color = Color(0xFFF8FAFC).copy(alpha = 0.92f * progress),
                        // shadow disabled for this compose version
                    )
                )
            }
        }
    }
}

private data class SalutePalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val deep: Color,
    val dark: Color,
    val confettiColors: List<Color>
)

private fun generateConfetti(palette: SalutePalette): List<ConfettiParticle> {
    val rnd = Random(1337)
    val shapes = ConfettiShape.values()
    return List(180) {
        val angle = (it / 180f) * 2f * PI.toFloat() + rnd.nextFloat() * 0.15f - 0.075f
        ConfettiParticle(
            angleRad = angle,
            speed = 0.55f + rnd.nextFloat() * 0.45f,
            gravityFactor = 0.55f + rnd.nextFloat() * 0.55f,
            size = 5f + rnd.nextFloat() * 10f,
            color = palette.confettiColors.random(rnd),
            shape = shapes[rnd.nextInt(shapes.size)],
            initial3DZ = rnd.nextFloat() * 2f - 1f,
            rotationSpeed = rnd.nextFloat() * 720f - 360f,
            rotationAxisX = rnd.nextFloat() * 360f,
            rotationAxisY = rnd.nextFloat() * 360f,
            lifeDelay = rnd.nextFloat() * 0.08f
        )
    }
}

private fun generateStardust(palette: SalutePalette): List<StardustParticle> {
    val rnd = Random(420)
    val baseColors = listOf(palette.primary, palette.secondary, palette.tertiary, Color.White)
    return List(60) {
        val angle = rnd.nextFloat() * 2f * PI.toFloat()
        val dist = 12f + rnd.nextFloat() * 24f
        StardustParticle(
            x = cos(angle) * dist,
            y = sin(angle) * dist,
            vx = cos(angle) * (0.8f + rnd.nextFloat() * 1.4f),
            vy = sin(angle) * (0.8f + rnd.nextFloat() * 1.4f),
            size = 1.5f + rnd.nextFloat() * 3f,
            alpha = 0.6f + rnd.nextFloat() * 0.4f,
            twinkleSpeed = 1.5f + rnd.nextFloat() * 3f,
            color = baseColors.random(rnd)
        )
    }
}

private fun DrawScope.drawVignetteBackground(alpha: Float, w: Float, h: Float) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x00000000),
                Color(0xAA000000),
                Color(0xDD000000)
            ),
            center = Offset(w / 2f, h / 2f),
            radius = (w + h) / 2.8f
        ),
        size = size,
        alpha = alpha * 0.85f
    )
}

private fun DrawScope.drawShockwaves(
    progress: Float,
    cx: Float,
    cy: Float,
    palette: SalutePalette,
    shimmerPhase: Float,
    alpha: Float
) {
    val shockwaves = listOf(0f, 0.08f, 0.16f)
    shockwaves.forEachIndexed { _, delayFrac ->
        val localP = ((progress - delayFrac) / (1f - delayFrac)).coerceIn(0f, 1f)
        if (localP <= 0.001f) return@forEachIndexed
        val eased = EaseOutCubic.transform(localP)
        val radius = 40f + eased * (size.maxDimension * 0.9f)
        val thickness = 10f * (1f - eased) + 2f
        val a = (1f - eased) * alpha * 0.5f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.primary.copy(alpha = a),
                    palette.secondary.copy(alpha = a * 0.6f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = radius
            ),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = thickness),
            alpha = alpha
        )
    }
}

private fun atan2_approx(y: Float, x: Float): Float {
    return kotlin.math.atan2(y, x)
}

private fun DrawScope.drawStardustBurst(
    stardust: List<StardustParticle>,
    progress: Float,
    shimmerPhase: Float,
    alpha: Float,
    cx: Float,
    cy: Float,
    h: Float
) {
    stardust.forEach { p ->
        val localP = (progress - 0.05f).coerceIn(0f, 1f) / 0.95f
        if (localP <= 0f) return@forEach
        val t = localP
        val easedRadius = EaseOutExp.transform(t)
        val distPx = (size.minDimension * 0.08f) + easedRadius * (size.maxDimension * 0.65f)
        val baseAngle = atan2_approx(p.y, p.x)
        val wobble = sin(t * 24f + shimmerPhase * PI.toFloat() * 2 + baseAngle) * 6f * (1f - t)
        val angle = baseAngle + wobble * (PI.toFloat() / 180f)
        val x = cx + cos(angle) * distPx + p.x * (1f - t) * 4f
        val y = cy + sin(angle) * distPx * 0.75f + p.y * (1f - t) * 4f
        val twinkle = 0.6f + 0.4f * sin(shimmerPhase * PI.toFloat() * 2 * p.twinkleSpeed + baseAngle * 3f)
        val a = alpha * (1f - t) * p.alpha * twinkle
        if (a <= 0.01f) return@forEach
        val sz = p.size * (1f - t * 0.4f)
        drawStarSparkle(x, y, sz, p.color, a)
    }
}

private fun DrawScope.drawStarSparkle(x: Float, y: Float, size: Float, color: Color, alpha: Float) {
    val beamLen = size * 3.2f
    drawCircle(color.copy(alpha = alpha * 0.9f), radius = size, center = Offset(x, y))
    drawCircle(color.copy(alpha = alpha * 0.35f), radius = size * 2.2f, center = Offset(x, y))
    drawLine(
        color = color.copy(alpha = alpha * 0.7f),
        start = Offset(x - beamLen, y),
        end = Offset(x + beamLen, y),
        strokeWidth = (size * 0.35f).coerceAtLeast(0.5f),
        blendMode = BlendMode.Plus
    )
    drawLine(
        color = color.copy(alpha = alpha * 0.7f),
        start = Offset(x, y - beamLen),
        end = Offset(x, y + beamLen),
        strokeWidth = (size * 0.35f).coerceAtLeast(0.5f),
        blendMode = BlendMode.Plus
    )
    drawLine(
        color = color.copy(alpha = alpha * 0.45f),
        start = Offset(x - beamLen * 0.65f, y - beamLen * 0.65f),
        end = Offset(x + beamLen * 0.65f, y + beamLen * 0.65f),
        strokeWidth = (size * 0.22f).coerceAtLeast(0.4f),
        blendMode = BlendMode.Plus
    )
    drawLine(
        color = color.copy(alpha = alpha * 0.45f),
        start = Offset(x + beamLen * 0.65f, y - beamLen * 0.65f),
        end = Offset(x - beamLen * 0.65f, y + beamLen * 0.65f),
        strokeWidth = (size * 0.22f).coerceAtLeast(0.4f),
        blendMode = BlendMode.Plus
    )
}

private fun DrawScope.drawConfetti3D(
    confetti: List<ConfettiParticle>,
    progress: Float,
    palette: SalutePalette,
    shimmerPhase: Float,
    alpha: Float,
    cx: Float,
    cy: Float,
    w: Float,
    h: Float
) {
    confetti.forEach { p ->
        val localP = ((progress - p.lifeDelay) / (1f - p.lifeDelay)).coerceIn(0f, 1f)
        if (localP <= 0.001f) return@forEach
        val burstT = (localP * 1.45f).coerceIn(0f, 1f)
        val burstEase = EaseOutExp.transform(burstT)
        val fallT = (localP - 0.25f).coerceIn(0f, 1f)
        val fallEase = EaseInBack.transform(fallT)
        val maxTravel = size.minDimension * 0.48f
        val burstDist = maxTravel * burstEase
        val zDepth = p.initial3DZ * (1f - burstEase * 0.85f)
        val zScale = 1f + zDepth * 0.35f
        val xBase = cx + cos(p.angleRad) * burstDist * zScale
        val yBurst = cy + sin(p.angleRad) * burstDist * 0.72f * zScale
        val fallDist = (size.height * 0.95f + 600f) * fallEase
        val y = yBurst + fallDist * p.gravityFactor
        val x = xBase + sin(fallT * PI.toFloat() * 3f + p.angleRad * 4f) * 28f * fallT
        val rotZ = p.rotationSpeed * localP
        val rotX = p.rotationAxisX * localP
        val rotY = p.rotationAxisY * localP
        val perspXVal = ((rotY % 180f)) / 180f
        val perspFactor = abs(cos(perspXVal * PI.toFloat())) * 0.55f + 0.45f
        val sz = p.size * zScale * perspFactor * (1f - fallT * 0.15f)
        val fadeOut = if (localP > 0.82f) {
            1f - EaseInOutCubic.transform((localP - 0.82f) / 0.18f)
        } else 1f
        val life = (1f - EaseInBack.transform(((localP - 0.72f).coerceIn(0f, 1f)) / 0.28f)).coerceIn(0f, 1f)
        val a = alpha * fadeOut * life
        if (a <= 0.01f || y < -40f || y > h + 80f || x < -80f || x > w + 80f) return@forEach
        val shimmer = 0.75f + 0.25f * sin(
            shimmerPhase * PI.toFloat() * 2f +
                p.angleRad * 8f +
                localP * 16f
        )
        val col = p.color.copy(alpha = a * shimmer)
        drawConfettiShape(x, y, sz, p.shape, col, rotZ, rotX, rotY)
    }
}

private fun DrawScope.drawConfettiShape(
    x: Float, y: Float, size: Float,
    shape: ConfettiShape, color: Color,
    rotZ: Float, rotX: Float, rotY: Float
) {
    val perspX = abs(cos((rotY * PI.toFloat() / 180f)))
    val perspY = abs(cos((rotX * PI.toFloat() / 180f)))
    val w = size * perspX
    val hVal = size * perspY

    when (shape) {
        ConfettiShape.SQUARE, ConfettiShape.RECTANGLE -> {
            val rw = if (shape == ConfettiShape.RECTANGLE) w * 0.55f else w
            val rh = if (shape == ConfettiShape.RECTANGLE) hVal * 1.5f else hVal
            translate(left = x, top = y) {
                rotate(degrees = rotZ) {
                    val gradient = Brush.linearGradient(
                        colors = listOf(
                            color,
                            color.copy(alpha = (color.alpha * 0.55f).coerceIn(0f, 1f)),
                            color
                        ),
                        start = Offset(-rw / 2f, -rh / 2f),
                        end = Offset(rw / 2f, rh / 2f)
                    )
                    drawRect(
                        brush = gradient,
                        topLeft = Offset(-rw / 2f, -rh / 2f),
                        size = Size(rw, rh)
                    )
                }
            }
        }
        ConfettiShape.CIRCLE -> {
            val r = (w + hVal) / 4f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = (color.alpha * 1.1f).coerceAtMost(1f)),
                        color.copy(alpha = (color.alpha * 0.6f).coerceIn(0f, 1f))
                    ),
                    center = Offset(x, y),
                    radius = r
                ),
                radius = r,
                center = Offset(x, y)
            )
        }
        ConfettiShape.STAR -> {
            translate(left = x, top = y) {
                rotate(degrees = rotZ) {
                    drawStar5Point(color, (w + hVal) / 2f)
                }
            }
        }
        ConfettiShape.DIAMOND -> {
            translate(left = x, top = y) {
                rotate(degrees = rotZ + 45f) {
                    val dw = w.coerceAtLeast(0.5f)
                    val dh = hVal.coerceAtLeast(0.5f)
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                color,
                                color.copy(alpha = (color.alpha * 0.7f).coerceIn(0f, 1f)),
                                color
                            ),
                            start = Offset(-dw / 2f, -dh / 2f),
                            end = Offset(dw / 2f, dh / 2f)
                        ),
                        topLeft = Offset(-dw / 2f, -dh / 2f),
                        size = Size(dw, dh)
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawStar5Point(color: Color, radius: Float) {
    val path = Path()
    val cx = 0f
    val cy = 0f
    val outer = radius.coerceAtLeast(1f)
    val inner = outer * 0.45f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val angle = (i * Math.PI / 5.0 - PI / 2.0).toFloat()
        val px = cx + r * cos(angle)
        val py = cy + r * sin(angle)
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path = path, color = color)
}

private fun DrawScope.drawTrophyGlowHalo(
    progress: Float,
    shimmerPhase: Float,
    alpha: Float,
    palette: SalutePalette,
    cx: Float,
    cy: Float
) {
    val t = (progress - 0.1f).coerceIn(0f, 1f) / 0.9f
    if (t <= 0.001f) return
    val enter = EaseOutCubic.transform(t)
    val pulse = 0.85f + 0.15f * sin(shimmerPhase * PI.toFloat() * 2f)
    val baseR = 140f * enter * pulse

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.primary.copy(alpha = 0.38f * alpha * enter),
                palette.secondary.copy(alpha = 0.18f * alpha * enter),
                Color.Transparent
            ),
            center = Offset(cx, cy),
            radius = baseR * 2.4f
        ),
        center = Offset(cx, cy),
        radius = baseR * 2.4f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.secondary.copy(alpha = 0.28f * alpha * enter),
                Color.Transparent
            ),
            center = Offset(cx, cy),
            radius = baseR * 1.25f
        ),
        center = Offset(cx, cy),
        radius = baseR * 1.25f,
        blendMode = BlendMode.Plus
    )
}

private fun DrawScope.draw3DTrophy(
    palette: SalutePalette,
    shimmerPhase: Float,
    overallAlpha: Float
) {
    val a = overallAlpha.coerceIn(0f, 1f)
    if (a <= 0.001f) return

    val cx = size.width / 2f
    val cy = size.height / 2f

    val shimmer = Brush.linearGradient(
        colorStops = arrayOf(
            0f to palette.dark,
            (0.25f + shimmerPhase * 0.4f) to palette.secondary,
            (0.55f + shimmerPhase * 0.25f) to palette.primary,
            (0.8f + shimmerPhase * 0.2f) to palette.tertiary,
            1f to palette.deep
        ),
        start = Offset(cx - 100f, cy - 100f),
        end = Offset(cx + 100f, cy + 100f)
    )

    val baseW = 110f
    val baseH = 18f
    val baseY = cy + 70f
    drawOval(
        color = Color.Black.copy(alpha = 0.35f * a),
        topLeft = Offset(cx - baseW / 2f - 2f, baseY + baseH - 3f),
        size = Size(baseW + 4f, 8f)
    )
    drawRect(
        brush = shimmer,
        topLeft = Offset(cx - baseW / 2f, baseY),
        size = Size(baseW, baseH),
        alpha = a
    )
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(palette.tertiary, palette.dark),
            start = Offset(cx - baseW / 2f, baseY),
            end = Offset(cx + baseW / 2f, baseY)
        ),
        topLeft = Offset(cx - baseW / 2f, baseY),
        size = Size(baseW, 3f),
        alpha = a
    )

    val stemW = 28f
    val stemH = 44f
    val stemX = cx - stemW / 2f
    val stemY = baseY - stemH
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(palette.dark, palette.primary, palette.deep),
            start = Offset(stemX, 0f),
            end = Offset(stemX + stemW, 0f)
        ),
        topLeft = Offset(stemX, stemY),
        size = Size(stemW, stemH),
        alpha = a
    )

    val cupR = 58f
    val cupTopY = stemY - 4f
    val cupBottomY = cupTopY - 68f
    val cupCenterY = (cupTopY + cupBottomY) / 2f

    val cupPath = Path()
    cupPath.moveTo(cx - cupR, cupBottomY)
    cupPath.cubicTo(
        cx - cupR - 8f, cupBottomY + 22f,
        cx - cupR - 4f, cupTopY - 12f,
        cx - cupR + 18f, cupTopY
    )
    cupPath.lineTo(cx + cupR - 18f, cupTopY)
    cupPath.cubicTo(
        cx + cupR + 4f, cupTopY - 12f,
        cx + cupR + 8f, cupBottomY + 22f,
        cx + cupR, cupBottomY
    )
    cupPath.close()
    drawPath(path = cupPath, brush = shimmer, alpha = a)

    drawOval(
        brush = Brush.linearGradient(
            colors = listOf(palette.deep, palette.secondary, palette.deep),
            start = Offset(cx - cupR, cupBottomY),
            end = Offset(cx + cupR, cupBottomY)
        ),
        topLeft = Offset(cx - cupR, cupBottomY - 6f),
        size = Size(cupR * 2f, 12f),
        alpha = a
    )

    val handleOut = 16f
    val handleW = 10f
    val handleTop = cupBottomY + 18f
    val handleBottom = cupBottomY + 46f
    val handlePathL = Path()
    handlePathL.moveTo(cx - cupR + 4f, handleTop)
    handlePathL.cubicTo(
        cx - cupR - handleOut - 2f, handleTop - 4f,
        cx - cupR - handleOut - handleW, (handleTop + handleBottom) / 2f,
        cx - cupR + 4f, handleBottom
    )
    handlePathL.lineTo(cx - cupR + 12f, handleBottom - 6f)
    handlePathL.cubicTo(
        cx - cupR - handleOut + 2f, (handleTop + handleBottom) / 2f,
        cx - cupR - handleOut + 6f, handleTop + 2f,
        cx - cupR + 12f, handleTop + 6f
    )
    handlePathL.close()
    drawPath(path = handlePathL, brush = shimmer, alpha = a)

    val handlePathR = Path()
    handlePathR.moveTo(cx + cupR - 4f, handleTop)
    handlePathR.cubicTo(
        cx + cupR + handleOut + 2f, handleTop - 4f,
        cx + cupR + handleOut + handleW, (handleTop + handleBottom) / 2f,
        cx + cupR - 4f, handleBottom
    )
    handlePathR.lineTo(cx + cupR - 12f, handleBottom - 6f)
    handlePathR.cubicTo(
        cx + cupR + handleOut - 2f, (handleTop + handleBottom) / 2f,
        cx + cupR + handleOut - 6f, handleTop + 2f,
        cx + cupR - 12f, handleTop + 6f
    )
    handlePathR.close()
    drawPath(path = handlePathR, brush = shimmer, alpha = a)

    val emblemR = 16f
    val emblemY = cupCenterY + 4f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.9f * a),
                palette.secondary.copy(alpha = 0.5f * a),
                Color.Transparent
            ),
            center = Offset(cx, emblemY),
            radius = emblemR + 12f
        ),
        center = Offset(cx, emblemY),
        radius = emblemR + 12f,
        alpha = a
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(palette.secondary, palette.primary, palette.deep),
            center = Offset(cx, emblemY),
            radius = emblemR
        ),
        center = Offset(cx, emblemY),
        radius = emblemR,
        alpha = a
    )
    drawCircle(
        color = palette.dark.copy(alpha = 0.5f * a),
        center = Offset(cx, emblemY),
        radius = emblemR,
        style = Stroke(width = 1.2f)
    )

    translate(left = cx, top = emblemY) {
        rotate(degrees = -10f) {
            drawStar5PointForTrophy(Color.White.copy(alpha = a), 9f)
        }
    }

    val highlightGrad = Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            Color.White.copy(alpha = 0.35f * a),
            Color.Transparent
        ),
        start = Offset(cx - cupR, cupBottomY),
        end = Offset(cx - cupR * 0.35f, cupTopY)
    )
    val highlightClip = Path().apply {
        addPath(cupPath)
    }
    drawPath(path = cupPath, brush = highlightGrad, alpha = a)
}

private fun DrawScope.drawStar5PointForTrophy(color: Color, radius: Float) {
    val outer = radius.coerceAtLeast(0.5f)
    val inner = outer * 0.45f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val angle = (i * PI / 5.0 - PI / 2.0).toFloat()
        val px = r * cos(angle)
        val py = r * sin(angle)
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path = path, color = color)
}
