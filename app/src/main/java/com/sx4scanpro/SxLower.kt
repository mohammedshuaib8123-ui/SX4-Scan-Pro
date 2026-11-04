package com.sx4scanpro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun SectionHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, eyebrow: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, contentDescription = null, tint = Sx.MutedForeground, modifier = Modifier.size(18.dp))
            Text(title, color = Sx.Foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(eyebrow, color = Sx.MutedForeground, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HealthItem(label: String, detail: String, ok: Boolean?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(
            if (ok == true) Icons.Outlined.Check else Icons.Outlined.Remove, contentDescription = null,
            tint = if (ok == true) Sx.Primary else Sx.MutedForeground, modifier = Modifier.size(14.dp).padding(top = 1.dp)
        )
        Column {
            Text(label, color = Sx.Foreground, fontSize = 10.sp)
            Text(detail, color = Sx.MutedForeground, fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Left half of the lower grid: three health checks and the fault-code row in the prototype's warning style. */
@Composable
internal fun SxHealth(connected: Boolean, rpmSignal: Boolean, faultCount: Int?, onOpenDiagnostics: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.drawBehind { drawLine(Sx.Border, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }.padding(end = 20.dp)) {
        SectionHeading(Icons.Outlined.VerifiedUser, "Vehicle health", "ECU MONITOR")
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            HealthItem("Engine", if (!connected) "Not connected" else if (rpmSignal) "Reporting RPM" else "No RPM signal", if (connected) rpmSignal else null)
            HealthItem("ECU", if (connected) "Responding" else "Not connected", if (connected) true else null)
            HealthItem("OBD link", if (connected) "Connected" else "Offline", connected)
        }
        val has = (faultCount ?: 0) > 0
        val tint = if (has) Sx.Warning else Sx.MutedForeground
        val shape = RoundedCornerShape(4.dp)
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(shape).background(Sx.Warning.copy(alpha = if (has) 0.05f else 0f))
                .border(1.dp, if (has) Sx.Warning.copy(alpha = 0.25f) else Sx.Border, shape)
                .clickable { onOpenDiagnostics() }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(23.dp).border(1.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
                Text(faultCount?.toString() ?: "?", color = tint, fontSize = 11.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when { faultCount == null -> "Fault codes not scanned"; faultCount == 0 -> "No fault codes in last scan"; faultCount == 1 -> "Fault code detected"; else -> "Fault codes detected" },
                    color = tint, fontSize = 11.sp
                )
                Text(if (faultCount == null) "Open diagnostics to scan" else "Review diagnostic report", color = Sx.MutedForeground, fontSize = 8.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
    }
}

/** The record graphic of the prototype's album art. */
@Composable
private fun Record(modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Sx.Stage).border(1.dp, Sx.Border, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp)) {
            val c = center
            drawCircle(Sx.Background, size.minDimension / 2f)
            var r = size.minDimension / 2f - 2.dp.toPx()
            while (r > 7.dp.toPx()) { drawCircle(Sx.Muted, r, c, style = Stroke(1.dp.toPx())); r -= 3.dp.toPx() }
            drawCircle(Sx.MutedForeground, size.minDimension / 2f, c, style = Stroke(1.dp.toPx()))
            drawCircle(Sx.Primary, 5.dp.toPx(), c, style = Stroke(4.dp.toPx()))
        }
    }
}

private fun uptimeText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * Right half of the lower grid, laid out like the prototype's player but showing the real Open Headunit
 * session. The round button asks Open Headunit to bring the projection to the front (when it is installed).
 */
@Composable
internal fun SxHeadUnit(headUnit: HeadUnitSession, receiverInstalled: Boolean, onOpen: () -> Unit, onShowProjection: () -> Unit, modifier: Modifier = Modifier) {
    val title = when (headUnit.phase) {
        SessionPhase.PROJECTING -> "Android Auto projecting"
        SessionPhase.CONNECTED -> "Phone connected"
        SessionPhase.CONNECTING -> "Connecting…"
        SessionPhase.FAILED -> "Connection failed"
        SessionPhase.DISCONNECTED -> "Disconnected"
        SessionPhase.UNKNOWN -> if (receiverInstalled) "No session reported" else "Receiver not installed"
    }
    Column(modifier) {
        SectionHeading(Icons.Outlined.DirectionsCar, "Head unit", "ANDROID AUTO")
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Record()
            Column(Modifier.weight(1f).clickable { onOpen() }) {
                Text(title, color = Sx.Foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(headUnit.transport.uppercase(), headUnit.reason).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Open for connection controls" },
                    color = Sx.MutedForeground, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    if (headUnit.phase == SessionPhase.PROJECTING) "On screen" else "Not projecting",
                    color = Sx.Primary, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (receiverInstalled) Sx.Primary else Sx.Muted)
                    .clickable(enabled = receiverInstalled) { onShowProjection() }.semantics { contentDescription = "Show projection" },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = if (receiverInstalled) Sx.Background else Sx.MutedForeground, modifier = Modifier.size(22.dp)) }
        }
        if (headUnit.phase == SessionPhase.PROJECTING || headUnit.phase == SessionPhase.CONNECTED) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(uptimeText(headUnit.uptimeMs), color = Sx.MutedForeground, fontSize = 9.sp)
                Box(Modifier.weight(1f).height(2.dp).background(Sx.Primary))
                Text("SESSION", color = Sx.MutedForeground, fontSize = 9.sp)
            }
        }
    }
}

/** Footer line of the prototype with the adapter state, replay button and, here, the real connection controls. */
@Composable
internal fun SxFooter(
    connected: Boolean, statusText: String, wallOn: Boolean,
    onReplayWelcome: () -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit, onToggleWall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (connected) Sx.Primary else Sx.MutedForeground))
        Text("ELM327 · Bluetooth", color = Sx.MutedForeground, fontSize = 9.sp)
        Text(statusText, color = Sx.MutedForeground, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(
            Icons.Outlined.Replay, contentDescription = "Replay welcome animation", tint = Sx.MutedForeground,
            modifier = Modifier.size(22.dp).clickable { onReplayWelcome() }.padding(4.dp)
        )
        Text(if (wallOn) "MAIN VIEW" else "GAUGE WALL", color = Sx.Foreground, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onToggleWall() }.padding(6.dp))
        Text(
            if (connected) "DISCONNECT" else "CONNECT ADAPTER", color = Sx.Primary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { if (connected) onDisconnect() else onConnect() }.padding(6.dp)
        )
        Text("SX4 1.3 DDiS", color = Sx.MutedForeground, fontSize = 9.sp)
    }
}
