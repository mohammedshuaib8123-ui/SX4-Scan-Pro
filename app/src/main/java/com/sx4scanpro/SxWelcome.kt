package com.sx4scanpro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun ease(p: Float): Float { val q = 1f - p.coerceIn(0f, 1f); return 1f - q * q * q }

/**
 * Welcome screen following the prototype: SUZUKI / SX4 ZDi top line, the car reveal, a shade, then the
 * kicker, "SX4" slamming in red beside "ZDi", a growing rule, WELCOME ABOARD, and a 1 dp progress line with
 * "ZDI EDITION / COCKPIT" and Enter cockpit. All timings are fractions of [durationMs]. It is shown only
 * after the ECU has answered (or from the replay / preview buttons).
 */
@Composable
internal fun SxWelcome(durationMs: Long, onEnter: () -> Unit, modifier: Modifier = Modifier) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(durationMs) { clock.animateTo(1f, tween(durationMs.toInt().coerceAtLeast(1), easing = LinearEasing)) }
    val t = clock.value
    val carAlpha = when { t < 0.25f -> ease(t / 0.25f); t < 0.86f -> 1f; else -> 1f - 0.3f * ((t - 0.86f) / 0.14f) }
    val carScale = 1.055f - 0.055f * ease(t)
    val identity = ease((t - 0.14f) / 0.24f)
    val word1 = ease((t - 0.12f) / 0.32f)
    val word2 = ease((t - 0.18f) / 0.32f)
    val rule = ease(t / 0.42f)
    BoxWithConstraints(modifier.fillMaxSize().background(Sx.Background)) {
        val h = maxHeight
        Image(
            painterResource(R.drawable.sx4_redline), contentDescription = null, contentScale = ContentScale.Fit,
            alignment = BiasAlignment(0f, -0.3f),
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = carAlpha; scaleX = carScale; scaleY = carScale }
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Sx.Background, 0.22f to Sx.Background.copy(alpha = 0f), 0.5f to Sx.Background.copy(alpha = 0f),
                    0.76f to Sx.Background.copy(alpha = 0.85f), 1f to Sx.Background
                )
            )
        )
        Text("SUZUKI / SX4 ZDi", color = Sx.MutedForeground, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 28.dp))
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.17f + 24.dp).graphicsLayer { alpha = identity; translationY = 12f * (1f - identity) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("PRECISION IN EVERY DRIVE", color = Sx.MutedForeground, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "SX4", color = Sx.Primary, fontSize = 46.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, letterSpacing = 2.sp,
                    modifier = Modifier.graphicsLayer { alpha = word1; translationX = -40f * (1f - word1); scaleX = 1f + 0.25f * (1f - word1) }
                )
                Text(
                    "ZDi", color = Sx.Foreground, fontSize = 46.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, letterSpacing = 2.sp,
                    modifier = Modifier.graphicsLayer { alpha = word2; translationX = -40f * (1f - word2); scaleX = 1f + 0.25f * (1f - word2) }
                )
            }
            Box(Modifier.padding(vertical = 20.dp).width(64.dp).height(2.dp).graphicsLayer { scaleX = rule }.background(Sx.Primary))
            Text("WELCOME ABOARD", color = Sx.MutedForeground, fontSize = 10.sp, letterSpacing = 1.sp)
            Text("SX4 AIRLINES 2874", color = Sx.MutedForeground, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Sx.Border)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(t).background(Sx.Primary))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        append("ZDI EDITION")
                        withStyle(SpanStyle(color = Sx.Primary)) { append("  /  ") }
                        append("COCKPIT")
                    },
                    color = Sx.MutedForeground, fontSize = 9.sp, modifier = Modifier.weight(1f)
                )
                Row(Modifier.clickable { onEnter() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enter cockpit", color = Sx.Foreground, fontSize = 10.sp)
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Sx.Foreground, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
