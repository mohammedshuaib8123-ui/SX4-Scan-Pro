package com.sx4scanpro

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Eight shift LEDs: three green, three amber, two accent-red; all eight flash at the upshift point. */
@Composable
internal fun SxShiftLights(rpm: Double?, modifier: Modifier = Modifier) {
    val lit = ShiftLights.lit(rpm)
    val flash by rememberInfiniteTransition(label = "shift-flash").animateFloat(1f, 0.2f, infiniteRepeatable(tween(90), RepeatMode.Reverse), label = "shift-a")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ShiftLights.THRESHOLDS.forEachIndexed { i, _ ->
            val color = when { i < 3 -> Color(0xFF34D058); i < 6 -> Color(0xFFF5B83D); else -> Sx.Primary }
            val a = if (i < lit) (if (lit == ShiftLights.THRESHOLDS.size) flash else 1f) else 0.18f
            Box(Modifier.size(18.dp, 6.dp).alpha(a).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

/** One telemetry cell: label, live value and unit, or NO SIGNAL / STALE. */
@Composable
private fun SxStat(label: String, gauge: GaugeValue, nowMs: Long, modifier: Modifier = Modifier, unitOverride: String? = null) {
    val number = readingOf(gauge)
    val stale = isStale(gauge, nowMs)
    Column(modifier.sxPanel(12.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), color = Sx.MutedForeground, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (number == null) "—" else gauge.value,
                color = if (number != null && !stale) Sx.Foreground else Sx.MutedForeground,
                style = TN, fontSize = 26.sp, fontWeight = FontWeight.Black
            )
            Text(
                if (number == null) "NO SIGNAL" else if (stale) "STALE" else (unitOverride ?: gauge.unit),
                color = if (stale) Sx.Warning else Sx.MutedForeground, fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)
            )
        }
    }
}

/** Free-text variant for values that are not a GaugeValue (trip distance). */
@Composable
private fun SxStatText(label: String, value: String?, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.sxPanel(12.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), color = Sx.MutedForeground, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value ?: "—", color = if (value != null) Sx.Foreground else Sx.MutedForeground, style = TN, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(if (value == null) "NO SIGNAL" else unit, color = Sx.MutedForeground, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun StageChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Sx.Background.copy(alpha = 0.72f))
            .border(1.dp, Sx.Border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = Sx.MutedForeground, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Sx.Foreground, style = TN, fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}

/** Car stage: the SX4 image with the readings that really come from the ECU. Range stays "—": tank size and consumption are unknown. */
@Composable
private fun VehicleStage(fuel: GaugeValue, battery: GaugeValue, nowMs: Long, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.clip(shape).background(Sx.Stage).border(1.dp, Sx.Border, shape)) {
        Image(
            painterResource(R.drawable.sx4_redline), contentDescription = "Suzuki SX4",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Sx.Background.copy(alpha = 0.92f)))))
        Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text("SX4 1.3 DDiS", color = Sx.Foreground, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("SUZUKI · DIESEL", color = Sx.MutedForeground, fontSize = 11.sp, letterSpacing = 2.sp)
        }
        Text(
            "S4", color = Sx.Primary, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).border(1.dp, Sx.Primary, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
        )
        Row(Modifier.align(Alignment.BottomStart).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val f = readingOf(fuel)
            val b = readingOf(battery)
            StageChip("FUEL LEVEL", if (f == null) "— NO SIGNAL" else fuel.value + " %" + if (isStale(fuel, nowMs)) " STALE" else "")
            StageChip("BATTERY", if (b == null) "— NO SIGNAL" else battery.value + " V" + if (isStale(battery, nowMs)) " STALE" else "")
            StageChip("RANGE", "—")
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean?, detail: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val dot = when (ok) { true -> Sx.Foreground; false -> Sx.Primary; null -> Sx.MutedForeground }
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Text(label, color = Sx.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp).weight(1f))
        Text(detail, color = Sx.MutedForeground, fontSize = 13.sp)
    }
}

/**
 * The red-and-black cockpit home. Every number is a live ECU reading or an honest dash.
 * [alertSensors] holds the gauge keys ("coolant", "rpm", "boost") currently above an owner-set limit.
 */
@Composable
internal fun SxCockpit(
    connected: Boolean,
    rpm: GaugeValue,
    boost: GaugeValue,
    boostScale: DialScale,
    boostCaption: String,
    mode: DriveMode,
    economy: GaugeValue,
    rail: GaugeValue,
    coolant: GaugeValue,
    intake: GaugeValue,
    egr: GaugeValue,
    fuel: GaugeValue,
    battery: GaugeValue,
    tripKm: Double?,
    faultCount: Int?,
    headUnit: HeadUnitSession,
    alertSensors: Set<String>,
    hotRpm: Float?,
    hotBoost: Float?,
    nowMs: Long,
    onOpenDiagnostics: () -> Unit,
    onOpenHeadUnit: () -> Unit,
    onReplayWelcome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rpmSignal = readingOf(rpm) != null && !isStale(rpm, nowMs)
    val hudDim = if (mode == DriveMode.HUD) 0.45f else 1f
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR DRIVE, AT A GLANCE", color = Sx.MutedForeground, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("Cockpit.", color = Sx.Foreground, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (connected) Sx.Primary else Sx.MutedForeground))
            Text(
                if (connected) "SYSTEM ONLINE" else "SYSTEM STANDBY", color = if (connected) Sx.Primary else Sx.MutedForeground,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(start = 8.dp)
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clusterBand()) {
            SxInstrument(
                "ENGINE SPEED", rpm, DialScale.forLabel("Engine RPM"), "RPM", "Engine speed", nowMs, "rpm" in alertSensors,
                "Diesel · 1,248 cc", Modifier.weight(1f), shiftLights = true, hotFrom = hotRpm
            )
            SxVehicleStage(fuel, battery, nowMs, Modifier.weight(1.4f).alpha(hudDim))
            if (mode == DriveMode.ECO) SxInstrument("FUEL ECONOMY", economy, DialScale(40f, 10f), "KM/L", "From ECU fuel rate and speed", nowMs, false, "Instant economy", Modifier.weight(1f))
            else SxInstrument("VGT BOOST", boost, boostScale, boostCaption, "Boost pressure", nowMs, "boost" in alertSensors, "Variable geometry turbo", Modifier.weight(1f), hotFrom = hotBoost)
        }
        Box(Modifier.alpha(hudDim)) { SxTelemetry(coolant, intake, egr, rail, tripKm, "coolant" in alertSensors, nowMs) }
        Row(Modifier.fillMaxWidth().alpha(hudDim), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).sxPanel(16.dp).padding(16.dp)) {
                Text("VEHICLE HEALTH", color = Sx.MutedForeground, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                CheckRow("Engine", if (connected) rpmSignal else null, if (!connected) "Not connected" else if (rpmSignal) "Reporting" else "No RPM signal")
                CheckRow("ECU", if (connected) true else null, if (connected) "Answering" else "Not connected")
                CheckRow("OBD link", connected, if (connected) "Adapter linked" else "Offline")
                Box(Modifier.fillMaxWidth().height(1.dp).background(Sx.Border))
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenDiagnostics() }.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when { faultCount == null -> "Fault codes: not scanned yet"; faultCount == 0 -> "Fault codes: none in last scan"; else -> "Fault codes: $faultCount stored" },
                        color = if ((faultCount ?: 0) > 0) Sx.Primary else Sx.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                    )
                    Text("OPEN DIAGNOSTICS ›", color = Sx.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
            }
            Column(Modifier.weight(1f).sxPanel(16.dp).clickable { onOpenHeadUnit() }.padding(16.dp)) {
                Text("HEAD UNIT", color = Sx.MutedForeground, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    when (headUnit.phase) {
                        SessionPhase.PROJECTING -> "Android Auto projecting"
                        SessionPhase.CONNECTED -> "Phone connected"
                        SessionPhase.CONNECTING -> "Connecting…"
                        SessionPhase.FAILED -> "Connection failed"
                        SessionPhase.DISCONNECTED -> "Disconnected"
                        SessionPhase.UNKNOWN -> "No session reported"
                    },
                    color = if (headUnit.phase == SessionPhase.PROJECTING) Sx.Primary else Sx.Foreground, fontSize = 18.sp, fontWeight = FontWeight.Black
                )
                if (headUnit.reason.isNotBlank()) Text(headUnit.reason, color = Sx.MutedForeground, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("OPEN HEAD UNIT CONTROLS ›", color = Sx.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (connected) "ELM327 • adapter linked, ECU answering" else "ELM327 • waiting for adapter",
                color = Sx.MutedForeground, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            Text("REPLAY WELCOME", color = Sx.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.clickable { onReplayWelcome() }.padding(8.dp))
        }
    }
}
