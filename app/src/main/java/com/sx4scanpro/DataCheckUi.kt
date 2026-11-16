package com.sx4scanpro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ALERT_RED = Color(0xFFFF5252)
private val WARN_AMBER = Color(0xFFFFB84D)
private val OK_GREEN = Color(0xFF66E08A)

private fun levelColor(l: CheckLevel, muted: Color) = when (l) { CheckLevel.ALERT -> ALERT_RED; CheckLevel.WARN -> WARN_AMBER; CheckLevel.INFO -> muted }

private fun fmt(v: Double, d: Int) = "%.${d}f".format(java.util.Locale.US, v)

/** Live findings, what is being learned, and the trip tally. All numbers come from [DataCheckMonitor]. */
@Composable
internal fun DataCheckCard(
    findings: List<DataFinding>, tally: List<TallyRow>, baselines: List<BaselineRow>, summary: TripSummary,
    connected: Boolean, panel: Color, cyan: Color, muted: Color, onResetLearning: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DATA CHECK", color = cyan, style = MaterialTheme.typography.titleMedium)
            Text(
                "Watches every sensor the ECU gives us, compares them with each other and with what is normal for your car, and warns when something does not add up. " +
                    "It learns your car's normal values while the engine is warm and healthy; if a repair is done, reset the learning.",
                color = muted, fontSize = 13.sp
            )
            if (findings.isEmpty()) {
                Text(
                    if (connected) "No sensor or rail-pressure problems seen so far on this trip." else "Connect to the car to start checking.",
                    color = if (connected) OK_GREEN else muted, fontWeight = FontWeight.Bold
                )
            }
            findings.forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text((if (f.level == CheckLevel.ALERT) "ALERT  " else "WARNING  ") + f.title, color = levelColor(f.level, muted), fontWeight = FontWeight.Black)
                    Text(f.detail, color = Color.White, fontSize = 13.sp)
                }
            }
            if (summary.runSeconds > 0) {
                Text(
                    "This trip: ${summary.runSeconds / 60} min engine running, ${summary.pct(summary.idleSeconds)}% idling, " +
                        "${summary.pct(summary.heavyLoadSeconds)}% at heavy load, ${summary.pct(summary.highRpmSeconds)}% above 3000 rpm" +
                        (summary.maxCoolantC?.let { ", coolant peak ${fmt(it, 0)} °C" } ?: "") + ".",
                    color = muted, fontSize = 13.sp
                )
            }
            Text("NORMAL FOR YOUR CAR", color = cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            baselines.forEach { b ->
                val ready = b.have >= b.need
                Text(
                    b.label + ": " + if (ready && b.reference != null) fmt(b.reference, if (b.unit == "") 2 else 1) + (if (b.unit == "") "" else " " + b.unit) + " (learned)"
                    else "learning ${b.have}/${b.need} samples (needs a warm engine, steady running)",
                    color = if (ready) Color.White else muted, fontSize = 13.sp
                )
            }
            if (tally.isNotEmpty()) {
                Text("SENSOR TALLY THIS TRIP (min / average / max)", color = cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                tally.forEach { t ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        val d = if (t.unit == "MPa" || t.unit == "g/s" || t.unit == "V") 1 else 0
                        Text("${fmt(t.min, d)} / ${fmt(t.avg, d)} / ${fmt(t.max, d)} ${t.unit}", color = muted, fontSize = 13.sp)
                    }
                }
            }
            if (confirmReset) {
                Text("Forget what this car's normal values are? Do this only after a repair or if the car was faulty while learning.", color = WARN_AMBER, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onResetLearning(); confirmReset = false }) { Text("YES, RESET") }
                    OutlinedButton(onClick = { confirmReset = false }) { Text("CANCEL") }
                }
            } else OutlinedButton(onClick = { confirmReset = true }) { Text("RESET LEARNED NORMAL VALUES") }
        }
    }
}

/** Tank-to-tank economy. Fuel level and fuel rate are not available from this ECU, so the owner enters odometer and litres at each full fill. */
@Composable
internal fun FuelLogCard(
    fills: List<FillUp>, nowMs: Long, panel: Color, cyan: Color, muted: Color,
    onAdd: (Double?, Double?) -> String?, onRemoveLast: () -> Unit
) {
    var odo by remember { mutableStateOf("") }
    var litres by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val segments = remember(fills) { FuelLog.segments(fills) }
    val finding = remember(fills, nowMs / 60_000L) { FuelLog.trend(segments, nowMs) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FUEL ECONOMY LOG", color = cyan, style = MaterialTheme.typography.titleMedium)
            Text(
                "This ECU does not report fuel level or fuel rate, so the app cannot measure fuel use itself. Fill the tank to the click each time, then enter the odometer and the litres you added. " +
                    "The app compares tank against tank and warns when economy drops.",
                color = muted, fontSize = 13.sp
            )
            if (finding != null) {
                Text((if (finding.level == CheckLevel.ALERT) "ALERT  " else "WARNING  ") + finding.title, color = levelColor(finding.level, muted), fontWeight = FontWeight.Black)
                Text(finding.detail, color = Color.White, fontSize = 13.sp)
            }
            segments.takeLast(5).reversed().forEach { s ->
                Text("${s.fromOdo.toInt()} to ${s.toOdo.toInt()} km: ${fmt(s.kmPerL, 1)} km/L (${fmt(s.l100, 1)} L/100 km), ${fmt(s.litres, 1)} L", color = Color.White, fontSize = 13.sp)
            }
            if (segments.isEmpty()) Text(if (fills.isEmpty()) "No fill-ups entered yet." else "${fills.size} fill-up(s) entered. One more full tank gives the first economy figure.", color = muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = odo, onValueChange = { odo = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("Odometer km") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = litres, onValueChange = { litres = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("Litres added") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val err = onAdd(odo.toDoubleOrNull(), litres.toDoubleOrNull())
                    message = err ?: "Saved."
                    if (err == null) { odo = ""; litres = "" }
                }) { Text("ADD FULL-TANK FILL-UP") }
                if (fills.isNotEmpty()) OutlinedButton(onClick = { onRemoveLast(); message = "Removed the last entry." }) { Text("REMOVE LAST") }
            }
            if (message.isNotBlank()) Text(message, color = muted, fontSize = 13.sp)
        }
    }
}
