package app.kairo.android.ui.components

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairo.android.ui.theme.Kairo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState { Idle, Armed, Listening, Thinking, Speaking, Error }

/**
 * The Kairo orb: open ring + amber dot (the same geometry as the icon).
 * - Idle: breathes slowly.         - Armed: the dot pulses softly (waiting for the wake phrase).
 * - Listening: the ring deforms with the microphone level and the dot grows.
 * - Thinking: the gap rotates.     - Speaking: concentric waves to the rhythm of the voice.
 * It is static when the system's "Remove animations" is on.
 */
@Composable
fun KairoOrb(
    state: OrbState,
    level: Float,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val context = LocalContext.current
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val ringColor = Kairo.colors.orbRing
    val dotColor = if (state == OrbState.Error) Kairo.scheme.error else Kairo.colors.accentFill

    val smoothLevel by animateFloatAsState(
        targetValue = if (reduceMotion) 0f else level.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "level",
    )
    val active by animateFloatAsState(
        targetValue = when (state) { OrbState.Listening, OrbState.Speaking -> 1f; OrbState.Thinking -> 0.6f; else -> 0f },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "active",
    )

    val t = rememberInfiniteTransition(label = "orb")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ripple")

    val description = when (state) {
        OrbState.Idle -> stringResource(R.string.orb_idle)
        OrbState.Armed -> stringResource(R.string.orb_armed, stringResource(R.string.wake_phrase))
        OrbState.Listening -> stringResource(R.string.orb_listening)
        OrbState.Thinking -> stringResource(R.string.orb_thinking)
        OrbState.Speaking -> stringResource(R.string.orb_speaking)
        OrbState.Error -> stringResource(R.string.orb_error)
    }

    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val minDim = this.size.minDimension
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val baseR = minDim * 0.34f
        val stroke = minDim * 0.075f
        val still = reduceMotion

        val breathScale = if (still) 1f else 1f + (breath - 0.5f) * 0.03f * (1f - active)
        val r = baseR * breathScale

        // Waves while speaking
        if (!still && state == OrbState.Speaking) {
            for (i in 0 until 3) {
                val p = (ripple + i / 3f) % 1f
                drawCircle(
                    color = ringColor.copy(alpha = (1f - p) * 0.18f * (0.4f + smoothLevel)),
                    radius = r + p * minDim * 0.16f,
                    center = center,
                    style = Stroke(width = stroke * 0.35f),
                )
            }
        }

        val rotation = if (!still && state == OrbState.Thinking) spin else 0f
        val gapCenter = -40f // degrees on screen (top right), as in the icon
        val gapHalf = if (state == OrbState.Thinking && !still) 30f + 10f * sin(phase) else 30f

        rotate(rotation, center) {
            val wobble = if (still) 0f else (smoothLevel * 0.085f * active)
            drawOpenRing(center, r, stroke, ringColor, gapCenter + gapHalf, 360f - 2 * gapHalf, wobble, phase)

            // Amber dot in the gap
            val a = Math.toRadians(gapCenter.toDouble())
            val dotR = stroke * 0.85f * (1f + smoothLevel * 0.55f * active) * (if (state == OrbState.Armed && !still) 0.92f + 0.12f * breath else 1f)
            val dotPos = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            if (state == OrbState.Listening || state == OrbState.Armed) {
                drawCircle(dotColor.copy(alpha = 0.16f + 0.2f * smoothLevel), radius = dotR * (2.1f + smoothLevel), center = dotPos)
            }
            drawCircle(dotColor, radius = dotR, center = dotPos)
        }
    }
}

private fun DrawScope.drawOpenRing(
    center: Offset,
    radius: Float,
    stroke: Float,
    color: Color,
    startDeg: Float,
    sweepDeg: Float,
    wobble: Float,
    phase: Float,
) {
    if (wobble <= 0.0005f) {
        drawArc(
            color = color,
            startAngle = startDeg,
            sweepAngle = sweepDeg,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        return
    }
    val path = Path()
    val steps = 140
    for (i in 0..steps) {
        val deg = startDeg + sweepDeg * i / steps
        val th = Math.toRadians(deg.toDouble())
        val k = 1f + wobble * (sin(3 * th + phase).toFloat() * 0.6f + sin(5 * th - phase * 1.3).toFloat() * 0.4f)
        val x = center.x + radius * k * cos(th).toFloat()
        val y = center.y + radius * k * sin(th).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}
