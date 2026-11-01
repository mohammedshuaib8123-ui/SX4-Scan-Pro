package com.sx4scanpro

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Animated instrument components for the cockpit. Everything shown comes from real ECU
 * readings: a signal that is missing says NO SIGNAL, an old one says STALE, and nothing is
 * ever drawn as a plausible-looking default.
 */

private object Fx {
    val Muted = Color(0xFF8FA6B9)
    val Track = Color(0xFF22313F)
    val TickMinor = Color(0xFF4A5E72)
    val Amber = Color(0xFFFFB84D)
    val Panel = Color(0xE6101B29)
    val Stroke = Color(0xFF263B50)
    const val STALE_AFTER_MS = 5_000L
    const val START_DEG = 135f
    const val SWEEP_DEG = 270f
}

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/** A 270 degree dial with a spring-driven sweep, tick labels and a smoothly counting value. */
@Composable
internal fun InstrumentDial(
    title: String,
    gauge: GaugeValue,
    scale: DialScale,
    accent: Color,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    val number: Double? = if (gauge.available) gauge.value.toDoubleOrNull() else null
    val stale = number != null && nowMs - gauge.capturedAtMs > Fx.STALE_AFTER_MS
    val live = number != null && !stale
    val fraction by animateFloatAsState(
        targetValue = scale.fraction(number),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "dial-sweep"
    )
    val shown by animateFloatAsState(
        targetValue = (number ?: 0.0).toFloat(),
        animationSpec = tween(600),
        label = "dial-count"
    )
    val measurer = rememberTextMeasurer()
    val statusText = if (number == null) "NO SIGNAL" else if (stale) "STALE" else "LIVE"
    val statusColor = if (number == null) Fx.Muted else if (stale) Fx.Amber else accent
    val description = if (number == null) "$title: no signal" else "$title: ${gauge.value} ${gauge.unit}" + if (stale) ", data is stale" else ""

    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(24.dp),
        color = Fx.Panel,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.40f))
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text(statusText, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                Canvas(Modifier.size(side)) {
                    drawDial(fraction, scale, accent, number != null, measurer)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (number != null) shown.roundToInt().toString() else "—",
                        color = if (live) Color.White else Fx.Muted,
                        style = TABULAR,
                        fontSize = (side.value * 0.20f).sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(gauge.unit, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (scale.caption.isNotEmpty()) {
                Text(
                    scale.caption,
                    color = Fx.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

private fun DrawScope.drawDial(
    fraction: Float,
    scale: DialScale,
    accent: Color,
    hasSignal: Boolean,
    measurer: TextMeasurer
) {
    val side = size.minDimension
    val stroke = side * 0.065f
    val labelPx = maxOf(side * 0.05f, 12.sp.toPx())
    val inset = stroke / 2f + labelPx * 1.9f
    val ringRadius = side / 2f - inset
    if (ringRadius <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val topLeft = Offset(center.x - ringRadius, center.y - ringRadius)
    val arcSize = Size(ringRadius * 2f, ringRadius * 2f)

    fun point(angleDeg: Float, radius: Float): Offset {
        val a = Math.toRadians(angleDeg.toDouble())
        return Offset(center.x + (cos(a) * radius).toFloat(), center.y + (sin(a) * radius).toFloat())
    }
    fun angleFor(value: Float): Float = Fx.START_DEG + Fx.SWEEP_DEG * (value / scale.max)

    drawArc(
        color = Fx.Track,
        startAngle = Fx.START_DEG,
        sweepAngle = Fx.SWEEP_DEG,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round)
    )

    // Minor then major ticks, just inside the ring.
    val majors = scale.majorTicks()
    val tickOuter = ringRadius - stroke * 0.9f
    majors.forEachIndexed { index, tick ->
        drawLine(accent, point(angleFor(tick), tickOuter), point(angleFor(tick), tickOuter - side * 0.035f), strokeWidth = 2.dp.toPx())
        if (index < majors.size - 1) {
            for (m in 1 until scale.minorPerMajor) {
                val v = tick + scale.majorStep * m / scale.minorPerMajor
                if (v <= scale.max) {
                    drawLine(Fx.TickMinor, point(angleFor(v), tickOuter), point(angleFor(v), tickOuter - side * 0.018f), strokeWidth = 1.dp.toPx())
                }
            }
        }
    }

    // Numbers just outside the ring.
    val labelStyle = TextStyle(color = Fx.Muted, fontSize = labelPx.toSp(), fontWeight = FontWeight.Medium)
    val labelRadius = ringRadius + stroke / 2f + labelPx * 1.05f
    majors.forEach { tick ->
        val layout = measurer.measure(text = scale.tickLabel(tick), style = labelStyle)
        val p = point(angleFor(tick), labelRadius)
        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
    }

    if (hasSignal && fraction > 0f) {
        val sweep = Fx.SWEEP_DEG * fraction
        drawArc(accent.copy(alpha = 0.18f), Fx.START_DEG, sweep, false, topLeft, arcSize, style = Stroke(stroke * 1.9f, cap = StrokeCap.Round))
        drawArc(accent, Fx.START_DEG, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val tip = point(Fx.START_DEG + sweep, ringRadius)
        drawCircle(accent, radius = stroke * 0.85f, center = tip)
        drawCircle(Color.White, radius = stroke * 0.45f, center = tip)
    }
}

/** A powertrain tile. Tap it to flip between the live value and a trend of the last readings. */
@Composable
internal fun MetricTile(
    title: String,
    gauge: GaugeValue,
    series: List<Float>,
    scale: DialScale,
    accent: Color,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    var showTrend by remember { mutableStateOf(false) }
    val number: Double? = if (gauge.available) gauge.value.toDoubleOrNull() else null
    val stale = number != null && nowMs - gauge.capturedAtMs > Fx.STALE_AFTER_MS
    val fraction by animateFloatAsState(
        targetValue = scale.fraction(number),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "tile-bar"
    )
    val state = if (number == null) "NO SIGNAL" else if (stale) "STALE" else gauge.unit
    val description = if (number == null) "${gauge.label}: no signal" else "${gauge.label}: ${gauge.value} ${gauge.unit}. Tap to show trend."

    Surface(
        onClick = { showTrend = !showTrend },
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0F1A27),
        border = BorderStroke(1.dp, if (showTrend) accent.copy(alpha = 0.7f) else Fx.Stroke)
    ) {
        AnimatedContent(targetState = showTrend, label = "tile-flip") { trend ->
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = Fx.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
                if (trend) {
                    if (series.size < 2) {
                        Text("Collecting readings…", color = Fx.Muted, fontSize = 13.sp)
                    } else {
                        Sparkline(series, accent, Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp))
                    }
                    Text("TREND • ${series.size} samples", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Column {
                        Text(
                            text = if (number != null) gauge.value else "—",
                            color = if (number != null && !stale) Color.White else Fx.Muted,
                            style = TABULAR,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(state, color = if (stale) Fx.Amber else Fx.Muted, fontSize = 13.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Fx.Track)) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent))
                    }
                }
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, accent: Color, modifier: Modifier = Modifier) {
    val points = remember(values) { GaugeHistory.trend(values) }
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val line = Path()
        points.forEachIndexed { i, p ->
            val x = p.x * w
            val y = (1f - p.y) * h
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), Color.Transparent)))
        drawPath(line, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = points.last()
        drawCircle(accent, radius = 4.dp.toPx(), center = Offset(last.x * w, (1f - last.y) * h))
    }
}

/** The four powertrain tiles, using real readings only. */
@Composable
internal fun PowertrainPanel(
    gauges: List<GaugeValue>,
    history: GaugeHistory,
    historyVersion: Int,
    nowMs: Long,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val tiles = listOf(
        "Turbo boost" to "BOOST",
        "Coolant temperature" to "COOLANT",
        "MAF airflow" to "MAF",
        "Calculated engine load" to "ENGINE LOAD"
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF101B29),
        border = BorderStroke(1.dp, Fx.Stroke)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("POWERTRAIN", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text("TAP FOR TREND", color = Fx.Muted, fontSize = 13.sp)
            }
            tiles.forEach { (label, short) ->
                val gauge = gauges.firstOrNull { it.label == label } ?: GaugeValue(label, "--", "", false)
                val series = remember(historyVersion, label) { history.series(label) }
                MetricTile(
                    title = short,
                    gauge = gauge,
                    series = series,
                    scale = DialScale.forLabel(label),
                    accent = accent,
                    nowMs = nowMs,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
}

/** Link indicator: steady when connected, pulsing while connecting, dim when offline. */
@Composable
internal fun LinkDot(connected: Boolean, connecting: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "link-dot")
    val pulse by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "link-pulse"
    )
    val color = if (connected) Color(0xFF26D7B1) else if (connecting) Fx.Amber else Color(0xFF8795A5)
    Box(
        modifier
            .size(10.dp)
            .graphicsLayer { alpha = if (connecting) pulse else 1f }
            .background(color, CircleShape)
    )
}
