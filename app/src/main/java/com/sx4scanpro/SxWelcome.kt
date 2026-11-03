package com.sx4scanpro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Welcome screen of the red-and-black cockpit: full-bleed car, title, rule, kicker lines and a
 * progress bar that runs for [durationMs]. Shown only after the ECU has answered (or from a preview).
 */
@Composable
internal fun SxWelcome(durationMs: Long, onEnter: () -> Unit, modifier: Modifier = Modifier) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(durationMs) { clock.animateTo(1f, tween(durationMs.toInt().coerceAtLeast(1), easing = LinearEasing)) }
    val t = clock.value
    val reveal = (t * 8f).coerceIn(0f, 1f)
    Box(modifier.fillMaxSize().background(Sx.Background)) {
        Image(
            painterResource(R.drawable.sx4_redline), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f * reveal; val s = 1.08f - 0.08f * t; scaleX = s; scaleY = s }
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Sx.Background.copy(alpha = 0.35f), Sx.Background.copy(alpha = 0.96f)))))
        Column(
            Modifier.fillMaxSize().padding(48.dp).graphicsLayer { alpha = reveal },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text("SUZUKI", color = Sx.MutedForeground, fontSize = 14.sp, letterSpacing = 8.sp, fontWeight = FontWeight.Bold)
            Text("SX4 ZDi", color = Sx.Foreground, fontSize = 84.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Box(Modifier.padding(vertical = 14.dp).width(120.dp).height(3.dp).background(Sx.Primary))
            Text("WELCOME ABOARD", color = Sx.Primary, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Text("SX4 AIRLINES 2874", color = Sx.Foreground, fontSize = 16.sp, letterSpacing = 6.sp, modifier = Modifier.padding(top = 6.dp))
            Text("PRECISION IN EVERY DRIVE", color = Sx.MutedForeground, fontSize = 12.sp, letterSpacing = 5.sp, modifier = Modifier.padding(top = 18.dp))
            Box(Modifier.padding(top = 28.dp).width(360.dp).height(3.dp).background(Sx.Muted, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(t).background(Sx.Primary, RoundedCornerShape(2.dp)))
            }
            Text(
                "ENTER COCKPIT", color = Sx.Foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                modifier = Modifier.padding(top = 28.dp).clip(RoundedCornerShape(24.dp))
                    .border(1.dp, Sx.Primary, RoundedCornerShape(24.dp)).clickable { onEnter() }
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }
    }
}
