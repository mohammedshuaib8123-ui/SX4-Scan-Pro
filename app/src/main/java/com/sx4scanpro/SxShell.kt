package com.sx4scanpro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top bar like the prototype: "POWERED BY / Suzuki" brand (first letter red), OBD link state, the drive-mode
 * pill and a clock. Power asks twice (tap, then tap again within three seconds) so it cannot be hit while driving.
 */
@Composable
internal fun SxTopBar(connected: Boolean, mode: DriveMode, onMode: (DriveMode) -> Unit, onPower: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    var powerArmed by remember { mutableStateOf(false) }
    LaunchedEffect(powerArmed) { if (powerArmed) { delay(3000); powerArmed = false } }
    val clock = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    Row(
        modifier.fillMaxWidth().height(52.dp)
            .drawBehind { drawLine(Sx.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.semantics { contentDescription = "Suzuki SX4 ZDi cockpit" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("POWERED BY", color = Sx.MutedForeground, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Sx.Primary)) { append("S") }
                    append("UZUKI")
                },
                color = Sx.Foreground, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, letterSpacing = 2.sp
            )
        }
        Box(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (connected) Sx.Primary else Sx.MutedForeground))
            Text(if (connected) "OBD connected" else "OBD offline", color = Sx.MutedForeground, fontSize = 11.sp)
            Icon(Icons.Outlined.Bluetooth, contentDescription = "Bluetooth adapter", tint = if (connected) Sx.Primary else Sx.MutedForeground, modifier = Modifier.size(15.dp))
        }
        Box(Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, Sx.Border, RoundedCornerShape(50)).padding(2.dp)) {
            DriveMode.values().forEach { m ->
                val on = m == mode
                Text(
                    m.name, color = if (on) Sx.Background else Sx.MutedForeground, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Sx.Primary else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onMode(m) }
                        .semantics { contentDescription = "Drive mode " + m.name }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
        Box(Modifier.weight(1f))
        Text(clock, color = Sx.Foreground, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Icon(
            Icons.Outlined.PowerSettingsNew, contentDescription = if (powerArmed) "Tap again to exit" else "Exit",
            tint = if (powerArmed) Sx.Destructive else Sx.MutedForeground,
            modifier = Modifier.padding(start = 15.dp).size(32.dp).clip(CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (powerArmed) onPower() else powerArmed = true
                }
                .padding(6.dp)
        )
    }
}

/**
 * Bottom navigation like the prototype: icon beside label, red top rule and red text on the active item,
 * a small amber dot on Diagnostics while fault codes are stored, and the "SX4 / ZDI" signature at the right.
 */
@Composable
internal fun SxBottomNav(selectedTab: Int, onSelect: (Int) -> Unit, faultCount: Int, modifier: Modifier = Modifier) {
    val tabs = listOf<Triple<String, ImageVector, Int>>(
        Triple("Cockpit", Icons.Outlined.Speed, 0),
        Triple("Diagnostics", Icons.AutoMirrored.Outlined.ShowChart, 1),
        Triple("Head unit", Icons.Outlined.DirectionsCar, 2),
        Triple("Settings", Icons.Outlined.Tune, 3)
    )
    Row(
        modifier.fillMaxWidth().height(64.dp).background(Sx.Background)
            .drawBehind { drawLine(Sx.Border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tabs.forEach { (label, icon, index) ->
                val active = selectedTab == index
                val tint by animateColorAsState(if (active) Sx.Primary else Sx.MutedForeground, tween(240), label = "nav-tint")
                Row(
                    Modifier.fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) }
                        .drawBehind { if (active) drawLine(Sx.Primary, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx()) }
                        .padding(horizontal = 12.dp)
                        .semantics { contentDescription = label },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(label, color = tint, fontSize = 11.sp)
                    if (index == 1 && faultCount > 0) Box(Modifier.padding(start = 2.dp).size(4.dp).clip(CircleShape).background(Sx.Warning))
                }
            }
        }
        Box(Modifier.weight(1f))
        Text(
            buildAnnotatedString {
                append("SX4 ")
                withStyle(SpanStyle(color = Sx.Primary)) { append("/") }
                append(" ZDI")
            },
            color = Sx.MutedForeground, fontSize = 10.sp, letterSpacing = 1.sp
        )
    }
}
