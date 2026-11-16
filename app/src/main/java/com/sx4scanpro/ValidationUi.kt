package com.sx4scanpro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Owner-driven real-car validation: tag the operating condition in the session log and record each check's result. */
@Composable
internal fun ValidationCard(
    records: List<ValidationRecord>, phase: ValidationPhase?, panel: Color, cyan: Color, muted: Color,
    onPhase: (ValidationPhase) -> Unit, onResult: (String, ValidationStatus) -> Unit
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("REAL-CAR VALIDATION", color = cyan, style = MaterialTheme.typography.titleMedium)
            Text(
                "Do these on the car with someone else driving or while parked. Tap a condition before each test so the exported session says what the car was doing, then record what you saw. " +
                    "These are your own manual entries, timestamped when you tap; the app never marks a check and a PASS here is not independent verification.",
                color = muted, fontSize = 13.sp
            )
            Text("Current condition: " + (phase?.label ?: "none tagged"), color = Color.White, fontWeight = FontWeight.Bold)
            ValidationPhase.values().toList().chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { p -> OutlinedButton(onClick = { onPhase(p) }, modifier = Modifier.weight(1f)) { Text(p.label, fontSize = 11.sp, maxLines = 1) } }
                }
            }
            ValidationPlan.items.groupBy { it.group }.forEach { (group, list) ->
                Text(group, color = cyan, fontWeight = FontWeight.Bold)
                list.forEach { item ->
                    val st = ValidationPlan.statusOf(records, item.id)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(item.how, color = muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onResult(item.id, ValidationStatus.PASSED) }) { Text("PASSED", fontSize = 11.sp) }
                            OutlinedButton(onClick = { onResult(item.id, ValidationStatus.FAILED) }) { Text("FAILED", fontSize = 11.sp) }
                            OutlinedButton(onClick = { onResult(item.id, ValidationStatus.UNTESTED) }) { Text("RESET", fontSize = 11.sp) }
                            Text(st.text + (records.lastOrNull { it.id == item.id }?.takeIf { it.status != ValidationStatus.UNTESTED }?.let { " · " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.atMs)) } ?: ""), color = when (st) { ValidationStatus.PASSED -> Color(0xFF66E08A); ValidationStatus.FAILED -> Color(0xFFFF5252); else -> muted }, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
