package com.sx4scanpro

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin

/** Design space of the car drawing: 1000 x 380, nose on the left, ground line at y = 362. */
private const val CAR_W = 1000f
private const val CAR_H = 380f

/** A mid-engine supercar side profile as a wireframe, stroked with a gradient that slowly flows. */
@Composable
internal fun HoloCar(modifier: Modifier = Modifier, alpha: Float = 0.38f) {
    val phase by rememberInfiniteTransition(label = "holo-car").animateFloat(
        0f, 2f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "holo-flow"
    )
    val breathe by rememberInfiniteTransition(label = "holo-breathe").animateFloat(
        0.75f, 1f, infiniteRepeatable(tween(3200, easing = Holo.Ease), RepeatMode.Reverse), label = "holo-breathe-a"
    )
    Canvas(modifier) { drawHoloCar(phase, alpha * breathe) }
}

private fun DrawScope.drawHoloCar(phase: Float, alpha: Float) {
    val s = minOf(size.width / CAR_W, size.height / CAR_H)
    val dx = (size.width - CAR_W * s) / 2f
    val dy = (size.height - CAR_H * s) / 2f
    // Colours run cyan (front axle) to violet (rear quarter) and the whole ramp slides along the car.
    val brush = Brush.linearGradient(
        colors = listOf(Holo.Cyan, Holo.Violet),
        start = Offset(1000f * phase, 0f), end = Offset(1000f * phase + 1000f, 0f),
        tileMode = TileMode.Mirror
    )
    val line = 2.2.dp.toPx() / s
    val thin = 1.dp.toPx() / s
    withTransform({ translate(dx, dy); scale(s, s, Offset.Zero) }) {
        // Faint projection scan-lines under the car.
        var y = 110f
        while (y < 362f) {
            drawLine(Holo.Cyan.copy(alpha = 0.05f * alpha / 0.38f), Offset(30f, y), Offset(970f, y), strokeWidth = thin)
            y += 16f
        }
        // Ground line.
        drawLine(brush, Offset(0f, 362f), Offset(1000f, 362f), strokeWidth = thin * 1.5f, alpha = alpha)

        val body = Path().apply {
            moveTo(40f, 300f)
            cubicTo(35f, 262f, 70f, 232f, 170f, 212f)
            cubicTo(260f, 194f, 330f, 150f, 420f, 118f)
            cubicTo(470f, 100f, 560f, 92f, 650f, 98f)
            cubicTo(740f, 106f, 830f, 150f, 910f, 192f)
            cubicTo(950f, 212f, 975f, 244f, 975f, 290f)
            lineTo(975f, 302f)
            lineTo(40f, 302f)
            close()
        }
        drawPath(body, brush, alpha = alpha, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Glass house, door cut, side intake and a shoulder line.
        val glass = Path().apply {
            moveTo(440f, 128f); lineTo(640f, 106f); lineTo(792f, 170f); lineTo(450f, 172f); close()
        }
        drawPath(glass, brush, alpha = alpha * 0.9f, style = Stroke(thin * 1.4f, join = StrokeJoin.Round))
        drawLine(brush, Offset(540f, 176f), Offset(540f, 286f), strokeWidth = thin * 1.4f, alpha = alpha * 0.8f)
        val intake = Path().apply { moveTo(690f, 204f); lineTo(790f, 220f); lineTo(690f, 252f); close() }
        drawPath(intake, brush, alpha = alpha * 0.8f, style = Stroke(thin * 1.4f, join = StrokeJoin.Round))
        drawLine(brush, Offset(120f, 238f), Offset(930f, 236f), strokeWidth = thin, alpha = alpha * 0.6f)

        // Wheels: tyre, rim and five spokes.
        listOf(Offset(230f, 302f) to 62f, Offset(772f, 302f) to 68f).forEach { (c, r) ->
            drawCircle(brush, r, c, alpha = alpha, style = Stroke(line))
            drawCircle(brush, r * 0.62f, c, alpha = alpha * 0.8f, style = Stroke(thin * 1.4f))
            for (i in 0 until 5) {
                val a = Math.toRadians(i * 72.0 + phase * 40.0)
                drawLine(
                    brush, Offset(c.x + (cos(a) * r * 0.18f).toFloat(), c.y + (sin(a) * r * 0.18f).toFloat()),
                    Offset(c.x + (cos(a) * r * 0.62f).toFloat(), c.y + (sin(a) * r * 0.62f).toFloat()),
                    strokeWidth = thin * 1.4f, alpha = alpha * 0.8f
                )
            }
        }
    }
}
