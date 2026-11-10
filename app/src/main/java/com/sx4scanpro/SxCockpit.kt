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
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * The red-and-black cockpit home. Every number is a live ECU reading or an honest dash.
 * [alertSensors] holds the gauge keys ("coolant", "rpm", "boost") currently above an owner-set limit.
 */
@Composable
internal fun SxCockpit(
    connected: Boolean,
    ecuOnline: Boolean,
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
    speed: GaugeValue,
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
    receiverInstalled: Boolean,
    onShowProjection: () -> Unit,
    statusText: String,
    wallOn: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleWall: () -> Unit,
    arrive: Float = 1f,
    modifier: Modifier = Modifier
) {
    val rpmSignal = readingOf(rpm) != null && !isStale(rpm, nowMs)
    val hudDim = if (mode == DriveMode.HUD) 0.45f else 1f
    Column(modifier.graphicsLayer { alpha = arrive; translationY = 5.dp.toPx() * (1f - arrive) }.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR DRIVE, AT A GLANCE", color = Sx.MutedForeground, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Row {
                    Text("Cockpit", color = Sx.Foreground, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    Text(".", color = Sx.Primary, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                }
            }
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (connected) Sx.Primary else Sx.MutedForeground))
            Text(
                if (connected && ecuOnline) "SYSTEM ONLINE" else if (connected) "ECU NOT RESPONDING" else "SYSTEM STANDBY", color = Sx.MutedForeground,
                fontSize = 9.sp, modifier = Modifier.padding(start = 8.dp)
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clusterBand()) {
            SxInstrument(
                "ENGINE SPEED", rpm, DialScale.forLabel("Engine RPM"), "RPM", "Engine speed", nowMs, "rpm" in alertSensors,
                "Diesel · 1,248 cc", Modifier.weight(1f), shiftLights = true, hotFrom = hotRpm, ignite = arrive
            )
            SxVehicleStage(fuel, battery, speed, nowMs, Modifier.weight(1.4f).alpha(hudDim))
            if (mode == DriveMode.ECO) SxInstrument("FUEL ECONOMY", economy, DialScale(40f, 10f), "KM/L", "From ECU fuel rate and speed", nowMs, false, "Instant economy", Modifier.weight(1f), ignite = arrive)
            else SxInstrument("VGT BOOST", boost, boostScale, boostCaption, "Boost pressure", nowMs, "boost" in alertSensors, "Variable geometry turbo", Modifier.weight(1f), hotFrom = hotBoost, ignite = arrive)
        }
        Box(Modifier.alpha(hudDim)) { SxTelemetry(coolant, intake, egr, rail, tripKm, "coolant" in alertSensors, nowMs) }
        Row(Modifier.fillMaxWidth().alpha(hudDim), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SxHealth(connected, ecuOnline, rpmSignal, faultCount, onOpenDiagnostics, Modifier.weight(1f))
            SxHeadUnit(headUnit, receiverInstalled, onOpenHeadUnit, onShowProjection, Modifier.weight(1f))
        }
        SxFooter(connected, statusText, wallOn, onReplayWelcome, onConnect, onDisconnect, onToggleWall)
    }
}
