package com.sx4scanpro

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val TN = TextStyle(fontFeatureSettings = "tnum")

internal fun readingOf(g: GaugeValue): Double? = if (g.available) g.value.toDoubleOrNull() else null
internal fun isStale(g: GaugeValue, nowMs: Long): Boolean = readingOf(g) != null && nowMs - g.capturedAtMs > g.staleAfterMs

/** The cluster band: one continuous panel with a soft red wash at the top, a tinted top rule and a plain bottom rule. */
internal fun Modifier.clusterBand(): Modifier = this
    .background(Brush.verticalGradient(0f to lerp(Sx.Card, Sx.Primary, 0.08f), 0.4f to Sx.Card, 1f to Sx.Card))
    .drawBehind {
        drawLine(lerp(Sx.Border, Sx.Primary, 0.22f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        drawLine(Sx.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }

/**
 * One instrument of the cluster, drawn like the prototype's SVG dial: a 240 degree arc from 150 to 390 degrees,
 * 41 ticks, a glowing active arc, the value and unit inside, scale labels at the bottom and a footer rule.
 * [hotFrom] (0..1) turns ticks amber from that point up; it is set only from a limit the owner chose.
 */
@Composable
internal fun SxInstrument(
    title: String,
    gauge: GaugeValue,
    scale: DialScale,
    unit: String,
    caption: String,
    nowMs: Long,
    alert: Boolean,
    footer: String,
    modifier: Modifier = Modifier,
    shiftLights: Boolean = false,
    hotFrom: Float? = null,
    ignite: Float = 1f
) {
    val number = readingOf(gauge)
    val stale = isStale(gauge, nowMs)
    val animated by animateFloatAsState(scale.fraction(number), spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "sx-instrument")
    val fraction = animated * ignite
    val description = if (number == null) "$title: no signal" else "$title: ${gauge.value} $unit" + if (stale) ", stale" else ""
    Column(
        modifier.alertPulse(alert, 10.dp).padding(start = 12.dp, end = 12.dp, top = 12.dp).semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Sx.MutedForeground, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null, tint = Sx.Primary, modifier = Modifier.size(15.dp))
        }
        if (shiftLights) SxShiftLights(if (stale) null else number, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        BoxWithConstraints(Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally).widthIn(max = 260.dp).fillMaxWidth().aspectRatio(300f / 245f)) {
            val h = maxHeight
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / 300f
                val c = Offset(150f * k, 122f * k)
                val r = 123f * k
                val tl = Offset(c.x - r, c.y - r)
                val box = Size(r * 2f, r * 2f)
                drawArc(Sx.Muted, 150f, 240f, false, tl, box, style = Stroke(4f * k, cap = StrokeCap.Butt))
                if (number != null && fraction > 0f) {
                    drawArc(Sx.Primary.copy(alpha = 0.22f), 150f, 240f * fraction, false, tl, box, style = Stroke(11f * k))
                    drawArc(Sx.Primary, 150f, 240f * fraction, false, tl, box, style = Stroke(4f * k, cap = StrokeCap.Butt))
                }
                for (i in 0..40) {
                    val a = Math.toRadians((150 + i * 6).toDouble())
                    val r1 = if (i % 5 == 0) 102f else 109f
                    val hot = hotFrom != null && i / 40f >= hotFrom
                    drawLine(
                        if (hot) Sx.Warning else Sx.MutedForeground,
                        Offset(c.x + (cos(a) * r1 * k).toFloat(), c.y + (sin(a) * r1 * k).toFloat()),
                        Offset(c.x + (cos(a) * 114f * k).toFloat(), c.y + (sin(a) * 114f * k).toFloat()),
                        strokeWidth = (if (hot) 1.5f else 1f) * k.coerceAtLeast(0.8f)
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = h * 0.31f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (number == null) "—" else gauge.value,
                    color = if (number != null && !stale) Sx.Foreground else Sx.MutedForeground,
                    style = TN, fontSize = 34.sp, fontWeight = FontWeight.Medium
                )
                Text(unit, color = Sx.MutedForeground, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (number == null) Sx.MutedForeground else if (stale) Sx.Warning else Sx.Primary))
                    Text(
                        if (number == null) "NO SIGNAL" else if (stale) "STALE" else caption,
                        color = if (stale) Sx.Warning else Sx.MutedForeground, fontSize = 8.sp
                    )
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = maxWidth * 0.08f, vertical = h * 0.03f), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0", color = Sx.MutedForeground, fontSize = 9.sp)
                Text(scale.scaleLabel(unit), color = Sx.MutedForeground, fontSize = 8.sp)
                Text(scale.tickLabel(scale.max), color = Sx.MutedForeground, fontSize = 9.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().drawBehind { drawLine(Sx.Border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(footer, color = Sx.MutedForeground, fontSize = 8.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.NorthEast, contentDescription = null, tint = Sx.MutedForeground, modifier = Modifier.size(14.dp))
        }
    }
}

private fun DialScale.scaleLabel(unit: String): String = if (caption.isNotBlank()) caption else unit

@Composable
private fun StageReading(icon: ImageVector?, value: String, unit: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Sx.MutedForeground, modifier = Modifier.size(17.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = Sx.Foreground, style = TN, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (unit.isNotBlank()) Text(unit, color = Sx.MutedForeground, fontSize = 10.sp, modifier = Modifier.padding(start = 2.dp, bottom = 1.dp))
            }
            Text(label, color = Sx.MutedForeground, fontSize = 8.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Middle column: SX4 heading, the car, and the fuel / range / battery row. Range stays a dash: tank size and consumption are unknown. */
@Composable
internal fun SxVehicleStage(fuel: GaugeValue, battery: GaugeValue, speed: GaugeValue, nowMs: Long, modifier: Modifier = Modifier) {
    Column(modifier.drawBehind {
        drawLine(Sx.Border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
        drawLine(Sx.Border, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
    }) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("SX4", color = Sx.Foreground, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Text("1.3 DDiS", color = Sx.MutedForeground, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
                }
                Text("SUZUKI · DIESEL", color = Sx.MutedForeground, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
            }
            Text("S4", color = Sx.MutedForeground, fontSize = 18.sp, fontStyle = FontStyle.Italic)
        }
        // Fixed height: the cockpit row no longer uses IntrinsicSize.Min (it crashed), so a weight(1f) here would collapse to 0 and hide the photo and digital speed.
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Image(
                painterResource(R.drawable.sx4_redline), contentDescription = "Suzuki SX4",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
            )
            SxDigitalSpeed(
                SpeedReadout.from(speed.available, speed.value, speed.capturedAtMs, nowMs),
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().drawBehind { drawLine(Sx.Border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val f = readingOf(fuel)
            val b = readingOf(battery)
            StageReading(Icons.Outlined.LocalGasStation, f?.let { fuel.value } ?: "—", if (f != null) "%" else "", if (f == null) "Fuel level · no signal" else if (isStale(fuel, nowMs)) "Fuel level · stale" else "Fuel level")
            StageReading(null, "—", "", "Estimated range")
            StageReading(Icons.Outlined.BatteryChargingFull, b?.let { battery.value } ?: "—", if (b != null) " V" else "", if (b == null) "Battery · no signal" else if (isStale(battery, nowMs)) "Battery · stale" else "Battery")
        }
    }
}

@Composable
private fun Metric(icon: ImageVector, label: String, note: String, value: String?, unit: String, first: Boolean, last: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.drawBehind { if (!last) drawLine(Sx.Border, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(start = if (first) 0.dp else 16.dp, end = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, contentDescription = null, tint = Sx.Primary, modifier = Modifier.size(17.dp))
            Text(label, color = Sx.MutedForeground, fontSize = 11.sp)
        }
        Row(Modifier.padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(value ?: "—", color = if (value != null) Sx.Foreground else Sx.MutedForeground, style = TN, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Text(unit, color = Sx.MutedForeground, fontSize = 13.sp, modifier = Modifier.padding(start = 7.dp, bottom = 3.dp))
        }
        Text(note, color = Sx.MutedForeground, fontSize = 8.sp)
    }
}

private fun valueOf(g: GaugeValue, nowMs: Long): Pair<String?, String> = when {
    readingOf(g) == null -> null to "NO SIGNAL"
    isStale(g, nowMs) -> g.value to "STALE"
    else -> g.value to g.unit
}

/** Flat five-cell telemetry row with hairline dividers, like the prototype. */
@Composable
internal fun SxTelemetry(
    coolant: GaugeValue, intake: GaugeValue, egr: GaugeValue, rail: GaugeValue, tripKm: Double?, coolantAlert: Boolean, nowMs: Long,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).drawBehind { drawLine(Sx.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }.padding(vertical = 11.dp)
    ) {
        val c = valueOf(coolant, nowMs); val a = valueOf(intake, nowMs); val e = valueOf(egr, nowMs); val r = valueOf(rail, nowMs)
        Metric(Icons.Outlined.Thermostat, "Coolant", "Engine coolant", c.first, c.second, true, false, Modifier.weight(1f).fillMaxHeight().alertPulse(coolantAlert, 8.dp))
        Metric(Icons.Outlined.Air, "Intake air", "Air temperature", a.first, a.second, false, false, Modifier.weight(1f).fillMaxHeight())
        Metric(Icons.Outlined.Autorenew, "EGR commanded", "Commanded valve opening", e.first, e.second, false, false, Modifier.weight(1f).fillMaxHeight())
        Metric(Icons.Outlined.Speed, "Rail pressure", "Common rail", r.first, r.second, false, false, Modifier.weight(1f).fillMaxHeight())
        Metric(Icons.Outlined.Route, "Trip distance", "Since the adapter connected", tripKm?.let { String.format(java.util.Locale.US, "%.1f", it) }, if (tripKm == null) "NO SIGNAL" else "km", false, true, Modifier.weight(1f).fillMaxHeight())
    }
}
