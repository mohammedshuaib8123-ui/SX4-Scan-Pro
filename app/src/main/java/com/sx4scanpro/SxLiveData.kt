package com.sx4scanpro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun freshnessColor(f: Freshness): Color = when (f) {
    Freshness.LIVE -> Sx.Primary
    Freshness.STALE -> Sx.Warning
    else -> Sx.MutedForeground
}

/** Live Data in the Diagnostics page: grouped, with an explicit freshness state per reading. Presentation only; reuses the runtime gauges. */
@Composable
internal fun SxLiveDataCard(gauges: List<GaugeValue>, supported: Set<Int>, connected: Boolean, nowMs: Long, registryState: (Int) -> ParamState?, modifier: Modifier = Modifier) {
    val rows = LiveDataModel.rows(gauges.map { LiveReading(it.label, it.value, it.unit, it.available, it.capturedAtMs, it.staleAfterMs) }, supported, connected, nowMs, registryState)
    val groups = LiveDataModel.grouped(rows)
    var filter by rememberSaveable { mutableStateOf(-1) }
    val selected = LiveCategory.values().getOrNull(filter)?.takeIf { c -> groups.any { it.first == c } }
    Column(modifier.fillMaxWidth().sxPanel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("LIVE DATA", color = Sx.Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(LiveDataModel.summary(rows), color = Sx.Foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("Only readings the ECU actually returned are shown. A reading that stops updating becomes STALE, then drops to --. Nothing is replaced by zero.", color = Sx.MutedForeground, fontSize = 12.sp)
        if (groups.isEmpty()) { Text("No live parameters yet.", color = Sx.MutedForeground); return@Column }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("ALL", selected == null) { filter = -1 }
            groups.forEach { (c, _) -> Chip(c.title.uppercase(), selected == c) { filter = c.ordinal } }
        }
        val keys = rows.filter { it.key && it.freshness == Freshness.LIVE }
        if (selected == null && keys.isNotEmpty()) {
            Text("KEY READINGS", color = Sx.MutedForeground, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.take(4).forEach { r ->
                    Column(Modifier.weight(1f).background(Sx.Accent, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text(r.label, color = Sx.MutedForeground, fontSize = 11.sp, maxLines = 1)
                        Text(r.value + " " + r.unit, color = Sx.Foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 640.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.filter { selected == null || it.first == selected }.forEach { (c, list) ->
                    Text(c.title.uppercase(), color = Sx.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    list.chunked(columns).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { Box(Modifier.weight(1f)) { ReadingRow(it) } }
                            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) Sx.Foreground else Sx.MutedForeground, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable { onClick() }.background(if (on) Sx.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun ReadingRow(r: LiveRow) {
    val shown = r.freshness == Freshness.LIVE || r.freshness == Freshness.STALE
    val c = freshnessColor(r.freshness)
    Column(Modifier.fillMaxWidth().background(Sx.Secondary, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(r.label, color = Sx.Foreground, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(r.freshness.label, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Text(if (shown) r.value + (if (r.unit.isNotBlank()) " " + r.unit else "") else "--",
            color = if (r.freshness == Freshness.LIVE) Sx.Foreground else Sx.MutedForeground,
            fontSize = if (r.key) 22.sp else 18.sp, fontWeight = FontWeight.Bold)
        Text(r.note, color = if (r.freshness == Freshness.STALE) Sx.Warning else Sx.MutedForeground, fontSize = 11.sp)
        Text(r.source, color = Sx.MutedForeground, fontSize = 10.sp)
    }
}
