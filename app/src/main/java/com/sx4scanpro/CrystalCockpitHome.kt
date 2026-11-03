package com.sx4scanpro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CrystalCyan = Color(0xFF55E9FF)
private val CrystalBlue = Color(0xFF398BFF)
private val CrystalAmber = Color(0xFFFFBC66)
private val CrystalMuted = Color(0xFF91A9BC)
private val CrystalTrack = Color(0xFF243748)

/**
 * A glass-dynamic performance home screen. Values are sourced from GaugeValue only;
 * unavailable and stale signals remain explicit instead of being replaced with demo data.
 */
@Composable
internal fun CrystalCockpitHome(
    rpm: GaugeValue,
    speed: GaugeValue,
    speedUnitLabel: String,
    boost: GaugeValue,
    coolant: GaugeValue,
    intake: GaugeValue,
    load: GaugeValue,
    maf: GaugeValue,
    egr: GaugeValue,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 720.dp && maxHeight >= 330.dp
        if (wide) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        Modifier.weight(0.22f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CrystalMetric("ENGINE RPM", rpm, 5000.0, nowMs, CrystalCyan, Modifier.weight(1f))
                        CrystalMetric("TURBO BOOST", boost, 200.0, nowMs, CrystalBlue, Modifier.weight(1f))
                    }
                    CrystalHero(speed, speedUnitLabel, nowMs, Modifier.weight(0.56f).fillMaxHeight())
                    Column(
                        Modifier.weight(0.22f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CrystalMetric("COOLANT", coolant, 120.0, nowMs, CrystalAmber, Modifier.weight(1f))
                        CrystalMetric("INTAKE AIR", intake, 100.0, nowMs, CrystalCyan, Modifier.weight(1f))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(76.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CrystalTelemetry("EGR COMMAND", egr, "%", nowMs, Modifier.weight(1f))
                    CrystalTelemetry("ENGINE LOAD", load, "%", nowMs, Modifier.weight(1f))
                    CrystalTelemetry("AIRFLOW", maf, "g/s", nowMs, Modifier.weight(1f))
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Color.Transparent),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CrystalHero(speed, speedUnitLabel, nowMs, Modifier.fillMaxWidth().height(250.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CrystalMetric("ENGINE RPM", rpm, 5000.0, nowMs, CrystalCyan, Modifier.weight(1f))
                    CrystalMetric("TURBO BOOST", boost, 200.0, nowMs, CrystalBlue, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CrystalMetric("COOLANT", coolant, 120.0, nowMs, CrystalAmber, Modifier.weight(1f))
                    CrystalMetric("INTAKE AIR", intake, 100.0, nowMs, CrystalCyan, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CrystalTelemetry("EGR", egr, "%", nowMs, Modifier.weight(1f))
                    CrystalTelemetry("LOAD", load, "%", nowMs, Modifier.weight(1f))
                    CrystalTelemetry("MAF", maf, "g/s", nowMs, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CrystalHero(
    speed: GaugeValue,
    speedUnitLabel: String,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    val value = liveValue(speed, nowMs)
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF142B3D), Color(0xE9091421), Color(0xFF101A2C))
                )
            )
            .drawBehind {
                val glow = Brush.radialGradient(
                    colors = listOf(CrystalCyan.copy(alpha = 0.20f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.52f, size.height * 0.56f),
                    radius = size.maxDimension * 0.68f
                )
                drawRect(glow)
                val ribbon = Path().apply {
                    moveTo(size.width * 0.02f, size.height * 0.76f)
                    cubicTo(size.width * 0.28f, size.height * 0.44f, size.width * 0.55f, size.height * 0.98f, size.width * 0.98f, size.height * 0.30f)
                }
                drawPath(ribbon, CrystalCyan.copy(alpha = 0.13f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                drawLine(
                    CrystalBlue.copy(alpha = 0.16f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.06f, size.height * 0.18f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.94f, size.height * 0.18f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .crystalPanel(30.dp, CrystalCyan)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("SX4  /  1.3 DDiS", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    Text("SPORT GT  •  CRYSTAL DRIVE", color = CrystalCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (value == null) "NO SIGNAL" else if (isStale(speed, nowMs)) "STALE DATA" else "LIVE TELEMETRY", color = if (value == null) CrystalMuted else if (isStale(speed, nowMs)) CrystalAmber else CrystalCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("OBD-II  /  MODE 01", color = CrystalMuted, fontSize = 9.sp)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("VEHICLE SPEED", color = CrystalMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(value ?: "—", color = Color.White, fontSize = (maxWidth.value * 0.19f).coerceIn(48f, 100f).sp, fontWeight = FontWeight.Black, letterSpacing = (-3).sp)
                    Text(speedUnitLabel.uppercase(), color = CrystalCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 13.dp))
                }
                Text("GEAR  —     •     DRIVE MODE  MANUAL", color = CrystalMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("PERFORMANCE INTERFACE", color = CrystalMuted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                Text("SIGNAL-LED  •  NO SIMULATED VALUES", color = CrystalCyan.copy(alpha = 0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
        }
    }
}

@Composable
private fun CrystalMetric(
    title: String,
    gauge: GaugeValue,
    range: Double,
    nowMs: Long,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val number = liveValue(gauge, nowMs)?.toDoubleOrNull()
    val fraction = ((number ?: 0.0) / range).toFloat().coerceIn(0f, 1f)
    Column(
        modifier.crystalPanel(22.dp, accent).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
            Text(signalLabel(gauge, nowMs), color = signalColor(gauge, nowMs, accent), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(liveValue(gauge, nowMs) ?: "—", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Black)
                Text(gauge.unit.uppercase(), color = CrystalMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
            }
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(CrystalTrack)) {
                Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent))))
            }
        }
    }
}

@Composable
private fun CrystalTelemetry(
    title: String,
    gauge: GaugeValue,
    unit: String,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.crystalPanel(16.dp, CrystalBlue).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = CrystalMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(liveValue(gauge, nowMs) ?: "—", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(unit, color = CrystalCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(signalColor(gauge, nowMs, CrystalCyan)))
    }
}

private fun liveValue(gauge: GaugeValue, nowMs: Long): String? =
    gauge.value.takeIf { gauge.available && it.isNotBlank() && it != "--" && !isStale(gauge, nowMs) }

private fun isStale(gauge: GaugeValue, nowMs: Long): Boolean =
    gauge.available && nowMs - gauge.capturedAtMs > 5_000L

private fun signalLabel(gauge: GaugeValue, nowMs: Long): String =
    when {
        !gauge.available || gauge.value == "--" -> "NO SIGNAL"
        isStale(gauge, nowMs) -> "STALE"
        else -> "LIVE"
    }

private fun signalColor(gauge: GaugeValue, nowMs: Long, accent: Color): Color =
    when {
        !gauge.available || gauge.value == "--" -> CrystalMuted
        isStale(gauge, nowMs) -> CrystalAmber
        else -> accent
    }
