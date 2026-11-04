package com.sx4scanpro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Premium SX4 / ZDi identity plate for the welcome screen.
 *
 * The smoked-glass surface keeps the metallic wordmark legible over both bright and dark
 * vehicle photography. This component is presentation-only; it does not affect ECU state.
 */
@Composable
fun SX4WelcomeBadge(modifier: Modifier = Modifier) {
    val crimsonGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFFF2A32), Color(0xFFD60010), Color(0xFF8A000A)),
        start = Offset(0f, 0f),
        end = Offset(0f, 120f)
    )

    val chromeGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFFFFFFF), Color(0xFFE2E8F0), Color(0xFF94A3B8)),
        start = Offset(0f, 0f),
        end = Offset(0f, 120f)
    )

    val redlineRule = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            Color(0xFFE11D2A),
            Color(0xFFFFA2A2),
            Color(0xFFE11D2A),
            Color.Transparent
        )
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        val compact = maxWidth < 360.dp
        val titleSize = if (compact) 36.sp else 46.sp
        val innerPadding = if (compact) 20.dp else 32.dp
        val kickerSize = if (compact) 9.sp else 11.sp
        val kickerSpacing = if (compact) 2.sp else 4.sp
        val captionSize = if (compact) 9.sp else 11.sp
        val captionSpacing = if (compact) 1.sp else 3.sp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 28.dp,
                    shape = RoundedCornerShape(22.dp),
                    ambientColor = Color.Black,
                    spotColor = Color(0xFFE11D2A)
                )
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xE00A0A0E))
                .border(1.dp, Color(0x40E11D2A), RoundedCornerShape(22.dp))
                .padding(horizontal = innerPadding, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "PRECISION IN EVERY DRIVE",
                style = TextStyle(
                    fontSize = kickerSize,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = kickerSpacing,
                    color = Color(0xFFFF4D4D)
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
            ) {
                Text(
                    text = "SX4",
                    style = TextStyle(
                        fontSize = titleSize,
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        letterSpacing = 2.sp,
                        brush = crimsonGradient
                    )
                )
                Text(
                    text = "ZDi",
                    style = TextStyle(
                        fontSize = titleSize,
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        letterSpacing = 2.sp,
                        brush = chromeGradient
                    )
                )
            }

            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(if (compact) 180.dp else 220.dp)
                    .height(2.dp)
                    .background(redlineRule)
            )

            Text(
                text = "WELCOME ABOARD  •  DDiS 1.3 TURBO",
                style = TextStyle(
                    fontSize = captionSize,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = captionSpacing,
                    color = Color(0xFFCBD5E1)
                )
            )
        }
    }
}
