package com.sx4scanpro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Left glass control strip, 80 dp wide. Menu widens it to show labels. Power asks twice
 * (tap, then tap again within three seconds) so it cannot be hit by accident while driving.
 */
@Composable
internal fun SideDock(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    onPower: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var powerArmed by remember { mutableStateOf(false) }
    LaunchedEffect(powerArmed) { if (powerArmed) { delay(3000); powerArmed = false } }
    val width by animateDpAsState(if (expanded) 196.dp else 80.dp, tween(420, easing = Holo.Ease), label = "dock-width")
    val tabs = listOf(
        Triple("Cockpit", Icons.Outlined.Speed, 0),
        Triple("Diagnostics", Icons.Outlined.Build, 1),
        Triple("Head Unit", Icons.Outlined.DirectionsCar, 2),
        Triple("Settings", Icons.Outlined.Settings, 3)
    )
    Column(
        modifier.width(width).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.01f))))
            .padding(vertical = 14.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DockItem("Menu", Icons.Outlined.Menu, false, expanded, Holo.Cyan) { expanded = !expanded }
        Spacer(Modifier.height(6.dp))
        tabs.forEach { (label, icon, index) ->
            DockItem(label, icon, selectedTab == index, expanded, Holo.Cyan) { onSelect(index) }
        }
        Spacer(Modifier.weight(1f))
        DockItem(if (powerArmed) "Tap again" else "Power", Icons.Outlined.PowerSettingsNew, powerArmed, expanded, Holo.Crimson) {
            if (powerArmed) onPower() else powerArmed = true
        }
    }
}

@Composable
private fun DockItem(label: String, icon: ImageVector, active: Boolean, expanded: Boolean, accent: Color, onClick: () -> Unit) {
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(380, easing = Holo.Ease), label = "dock-glow")
    val tint by animateColorAsState(if (active) accent else Holo.Muted, tween(240), label = "dock-tint")
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            // Isolated neon aura behind the glyph.
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.42f * glow), Color.Transparent)))
            )
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
        if (expanded) {
            Text(
                label.uppercase(), color = if (active) Color.White else Holo.Muted, fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Black else FontWeight.Medium, letterSpacing = 1.sp, maxLines = 1,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
