package com.sx4scanpro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top bar of the red-and-black cockpit: Suzuki/SX4 brand, live OBD link state and a clock.
 * Power asks twice (tap, then tap again within three seconds) so it cannot be hit while driving.
 */
@Composable
internal fun SxTopBar(connected: Boolean, onPower: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    var powerArmed by remember { mutableStateOf(false) }
    LaunchedEffect(powerArmed) { if (powerArmed) { delay(3000); powerArmed = false } }
    val clock = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .drawBehind { drawLine(Sx.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Sx.Primary))
        Column(Modifier.padding(start = 12.dp)) {
            Text("SUZUKI", color = Sx.Foreground, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Text("SX4 • SCAN PRO", color = Sx.MutedForeground, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Box(Modifier.weight(1f))
        val link = if (connected) Sx.Primary else Sx.MutedForeground
        Icon(Icons.Outlined.Bluetooth, contentDescription = "Bluetooth adapter", tint = link, modifier = Modifier.size(20.dp))
        Text(
            if (connected) "OBD CONNECTED" else "OBD OFFLINE", color = link, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 8.dp)
        )
        Text(clock, color = Sx.Foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp))
        Icon(
            Icons.Outlined.PowerSettingsNew, contentDescription = if (powerArmed) "Tap again to exit" else "Exit",
            tint = if (powerArmed) Sx.Destructive else Sx.MutedForeground,
            modifier = Modifier.padding(start = 20.dp).size(36.dp).clip(CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (powerArmed) onPower() else powerArmed = true
                }
                .padding(6.dp)
        )
    }
}

/** 76 dp bottom navigation: active item gets a red top rule; the SX4 / ZDI signature sits at the right. */
@Composable
internal fun SxBottomNav(selectedTab: Int, onSelect: (Int) -> Unit, faultCount: Int, modifier: Modifier = Modifier) {
    val tabs = listOf<Triple<String, ImageVector, Int>>(
        Triple("Cockpit", Icons.Outlined.Speed, 0),
        Triple("Diagnostics", Icons.Outlined.Build, 1),
        Triple("Head Unit", Icons.Outlined.DirectionsCar, 2),
        Triple("Settings", Icons.Outlined.Settings, 3)
    )
    Row(
        modifier.fillMaxWidth().height(76.dp).background(Sx.Card)
            .drawBehind { drawLine(Sx.Border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { (label, icon, index) ->
            val active = selectedTab == index
            val tint by animateColorAsState(if (active) Sx.Primary else Sx.MutedForeground, tween(240), label = "nav-tint")
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) }
                    .drawBehind { if (active) drawLine(Sx.Primary, Offset(0f, 0f), Offset(size.width, 0f), 3.dp.toPx()) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                        if (index == 1 && faultCount > 0) {
                            Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape).background(Sx.Primary))
                        }
                    }
                    Text(
                        label.uppercase(), color = tint, fontSize = 11.sp, letterSpacing = 1.5.sp,
                        fontWeight = if (active) FontWeight.Black else FontWeight.Medium
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.End) {
            Text("SX4 / ZDI", color = Sx.Foreground, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("1.3 MULTIJET", color = Sx.MutedForeground, fontSize = 10.sp, letterSpacing = 2.sp)
        }
    }
}
