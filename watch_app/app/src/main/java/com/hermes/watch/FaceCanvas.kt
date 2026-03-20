package com.agentpet.watch

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
//  Eye shape variants
// ─────────────────────────────────────────────────────────────────────────────

enum class EyeShape { ROUND, HAPPY, X }

// ─────────────────────────────────────────────────────────────────────────────
//  Per-eye target parameters (all in dp)
// ─────────────────────────────────────────────────────────────────────────────

data class EyeTarget(
    val width:  Float     = 36f,
    val height: Float     = 28f,
    val corner: Float     = 14f,
    val slope:  Float     = 0f,     // degrees, outer edge up = positive
    val pupilX: Float     = 0f,     // −1..1 normalised gaze
    val pupilY: Float     = 0f,
    val shape:  EyeShape  = EyeShape.ROUND
)

// ─────────────────────────────────────────────────────────────────────────────
//  Full-face target parameters
// ─────────────────────────────────────────────────────────────────────────────

data class FaceTarget(
    val left:    EyeTarget = EyeTarget(),
    val right:   EyeTarget = EyeTarget(),
    val spacing: Float     = 52f,   // dp between eye centres
    val yOffset: Float     = -10f,  // dp above canvas centre
    val color:   Color     = Color(0xFF00FF41),
    val stroke:  Float     = 3f     // dp
)

// ─────────────────────────────────────────────────────────────────────────────
//  Emotion string → FaceTarget
// ─────────────────────────────────────────────────────────────────────────────

fun emotionToFace(emotion: String): FaceTarget {
    val green  = Color(0xFF00FF41)
    val blue   = Color(0xFF88BBFF)
    val red    = Color(0xFFFF4444)
    val yellow = Color(0xFFFFFF00)
    val pink   = Color(0xFFFF88CC)
    val gray   = Color(0xFF777777)

    return when (emotion) {

        "^_^" -> FaceTarget(                          // HAPPY — arch eyes
            left  = EyeTarget(40f, 30f, 15f, shape = EyeShape.HAPPY),
            right = EyeTarget(40f, 30f, 15f, shape = EyeShape.HAPPY),
            color = green
        )
        "u_u" -> FaceTarget(                          // SAD — inward droop
            left  = EyeTarget(32f, 18f, 9f,  slope = -10f),
            right = EyeTarget(32f, 18f, 9f,  slope =  10f),
            yOffset = -4f, color = blue
        )
        ">_<" -> FaceTarget(                          // ANGRY — steep inward tilt
            left  = EyeTarget(34f, 14f, 6f,  slope =  14f),
            right = EyeTarget(34f, 14f, 6f,  slope = -14f),
            color = red, stroke = 3.5f
        )
        "O_O" -> FaceTarget(                          // SURPRISED — huge round
            left  = EyeTarget(32f, 36f, 16f),
            right = EyeTarget(32f, 36f, 16f),
            spacing = 56f, yOffset = -10f, color = green
        )
        "♥_♥" -> FaceTarget(                          // LOVE — wide soft
            left  = EyeTarget(34f, 28f, 17f),
            right = EyeTarget(34f, 28f, 17f),
            color = pink, stroke = 3f
        )
        "-_-" -> FaceTarget(                          // BORED — thin slits
            left  = EyeTarget(40f, 7f, 3f),
            right = EyeTarget(40f, 7f, 3f),
            color = gray
        )
        "0_?" -> FaceTarget(                          // CONFUSED — asymmetric
            left  = EyeTarget(26f, 26f, 13f),
            right = EyeTarget(28f, 16f, 8f,  slope = -8f),
            color = green
        )
        "x_X" -> FaceTarget(                          // DEAD
            left  = EyeTarget(28f, 28f, 0f,  shape = EyeShape.X),
            right = EyeTarget(28f, 28f, 0f,  shape = EyeShape.X),
            color = red, stroke = 4f
        )
        "o_O" -> FaceTarget(                          // THINKING L
            left  = EyeTarget(20f, 20f, 10f, pupilX = -0.3f),
            right = EyeTarget(34f, 34f, 17f),
            spacing = 54f, color = yellow
        )
        "O_o" -> FaceTarget(                          // THINKING R
            left  = EyeTarget(34f, 34f, 17f),
            right = EyeTarget(20f, 20f, 10f, pupilX =  0.3f),
            spacing = 54f, color = yellow
        )
        "0_<" -> FaceTarget(                          // WINK LEFT
            left  = EyeTarget(36f, 7f, 3f),
            right = EyeTarget(36f, 26f, 13f),
            color = green
        )
        ">_0" -> FaceTarget(                          // WINK RIGHT
            left  = EyeTarget(36f, 26f, 13f),
            right = EyeTarget(36f, 7f, 3f),
            color = green
        )
        "._." -> FaceTarget(                          // PENSIVE — looking down
            left  = EyeTarget(24f, 22f, 11f, pupilY = 0.45f),
            right = EyeTarget(24f, 22f, 11f, pupilY = 0.45f),
            spacing = 48f, color = blue
        )
        else  -> FaceTarget(                          // NEUTRAL 0_0
            left  = EyeTarget(36f, 28f, 14f),
            right = EyeTarget(36f, 28f, 14f),
            color = green
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  FaceCanvas — drop-in replacement for the emoji Text
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FaceCanvas(
    emotion: String,
    sensorGaze: Offset = Offset.Zero,
    modifier: Modifier = Modifier
) {
    val target = remember(emotion) { emotionToFace(emotion) }

    // ── Animated face parameters ─────────────────────────────────────────────
    val bounce   = spring<Float>(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
    val smooth   = tween<Float>(280)

    val lW  by animateFloatAsState(target.left.width,   bounce, label = "lW")
    val lH  by animateFloatAsState(target.left.height,  bounce, label = "lH")
    val lC  by animateFloatAsState(target.left.corner,  smooth, label = "lC")
    val lSl by animateFloatAsState(target.left.slope,   bounce, label = "lSl")
    val lPX by animateFloatAsState(target.left.pupilX,  bounce, label = "lPX")
    val lPY by animateFloatAsState(target.left.pupilY,  bounce, label = "lPY")

    val rW  by animateFloatAsState(target.right.width,  bounce, label = "rW")
    val rH  by animateFloatAsState(target.right.height, bounce, label = "rH")
    val rC  by animateFloatAsState(target.right.corner, smooth, label = "rC")
    val rSl by animateFloatAsState(target.right.slope,  bounce, label = "rSl")
    val rPX by animateFloatAsState(target.right.pupilX, bounce, label = "rPX")
    val rPY by animateFloatAsState(target.right.pupilY, bounce, label = "rPY")

    val spc by animateFloatAsState(target.spacing, bounce, label = "spc")
    val yOf by animateFloatAsState(target.yOffset, bounce, label = "yOf")
    val swi by animateFloatAsState(target.stroke,  smooth, label = "swi")
    val col by animateColorAsState(target.color, tween(400), label = "col")

    // ── Blink ─────────────────────────────────────────────────────────────────
    var blinkTarget by remember { mutableFloatStateOf(0f) }
    val blink by animateFloatAsState(blinkTarget, tween(90), label = "blink")

    LaunchedEffect(Unit) {
        val rng = java.util.Random()
        while (true) {
            delay(2200L + rng.nextInt(5500).toLong())
            if (emotion == "x_X" || emotion == "-_-") continue   // skip blink on these
            blinkTarget = 1f; delay(90); blinkTarget = 0f
            if (rng.nextInt(5) == 0) {                            // occasional double-blink
                delay(160); blinkTarget = 1f; delay(75); blinkTarget = 0f
            }
        }
    }

    // ── Idle gaze shift ───────────────────────────────────────────────────────
    var gazeX by remember { mutableFloatStateOf(0f) }
    var gazeY by remember { mutableFloatStateOf(0f) }
    val aGX by animateFloatAsState(gazeX, tween(700, easing = FastOutSlowInEasing), label = "gX")
    val aGY by animateFloatAsState(gazeY, tween(700, easing = FastOutSlowInEasing), label = "gY")
    val sensorGX by animateFloatAsState(sensorGaze.x, tween(220, easing = FastOutSlowInEasing), label = "sensorGX")
    val sensorGY by animateFloatAsState(sensorGaze.y, tween(220, easing = FastOutSlowInEasing), label = "sensorGY")

    LaunchedEffect(Unit) {
        val rng = java.util.Random()
        while (true) {
            delay(3000L + rng.nextInt(4000).toLong())
            gazeX = (rng.nextFloat() * 2f - 1f) * 0.42f
            gazeY = (rng.nextFloat() * 2f - 1f) * 0.32f
            delay(900L + rng.nextInt(1400).toLong())
            gazeX = 0f; gazeY = 0f
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────
    Canvas(modifier = modifier) {
        val d   = density                           // px per dp
        val cx  = size.width  / 2f
        val cy  = size.height / 2f
        val eyeY = cy + yOf * d
        val half = spc * d / 2f

        drawEye(
            cx = cx - half,         cy = eyeY,
            wPx = lW * d,           hPx = lH * d * (1f - blink),
            cPx = lC * d,           slope = lSl,
            pupilX = (lPX + aGX + sensorGX).coerceIn(-1f, 1f),
            pupilY = (lPY + aGY + sensorGY).coerceIn(-1f, 1f),
            swPx = swi * d,         color = col,
            shape = target.left.shape, isLeft = true
        )
        drawEye(
            cx = cx + half,         cy = eyeY,
            wPx = rW * d,           hPx = rH * d * (1f - blink),
            cPx = rC * d,           slope = rSl,
            pupilX = (rPX + aGX + sensorGX).coerceIn(-1f, 1f),
            pupilY = (rPY + aGY + sensorGY).coerceIn(-1f, 1f),
            swPx = swi * d,         color = col,
            shape = target.right.shape, isLeft = false
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Low-level eye drawing (DrawScope extension)
// ─────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawEye(
    cx: Float, cy: Float,
    wPx: Float, hPx: Float, cPx: Float,
    slope: Float,
    pupilX: Float, pupilY: Float,
    swPx: Float,
    color: Color,
    shape: EyeShape,
    isLeft: Boolean
) {
    if (hPx < 1f) return
    val hw = wPx / 2f
    val hh = hPx / 2f
    val cr = cPx.coerceAtMost(hh).coerceAtMost(hw)
    val deg = if (isLeft) slope else -slope

    rotate(degrees = deg, pivot = Offset(cx, cy)) {
        when (shape) {
            EyeShape.ROUND -> {
                // Outline
                drawRoundRect(
                    color        = color,
                    topLeft      = Offset(cx - hw, cy - hh),
                    size         = Size(wPx, hPx),
                    cornerRadius = CornerRadius(cr, cr),
                    style        = Stroke(swPx, cap = StrokeCap.Round)
                )
                // Pupil
                val pR  = hh * 0.40f
                val mox = (hw - pR).coerceAtLeast(0f)
                val moy = (hh - pR).coerceAtLeast(0f)
                drawCircle(
                    color  = color,
                    radius = pR,
                    center = Offset(cx + pupilX * mox, cy + pupilY * moy)
                )
            }

            EyeShape.HAPPY -> {
                // Show only the top half of the oval → arch / ^ shape
                clipRect(cx - hw - 1f, cy - hh, cx + hw + 1f, cy + 2f) {
                    drawRoundRect(
                        color        = color,
                        topLeft      = Offset(cx - hw, cy - hh),
                        size         = Size(wPx, hPx * 2f),
                        cornerRadius = CornerRadius(cr * 1.4f, cr * 1.4f),
                        style        = Stroke(swPx, cap = StrokeCap.Round)
                    )
                }
            }

            EyeShape.X -> {
                val s  = (wPx.coerceAtMost(hPx)) / 2f * 0.75f
                val sw = swPx * 1.6f
                drawLine(color, Offset(cx - s, cy - s), Offset(cx + s, cy + s), sw, StrokeCap.Round)
                drawLine(color, Offset(cx + s, cy - s), Offset(cx - s, cy + s), sw, StrokeCap.Round)
            }
        }
    }
}
