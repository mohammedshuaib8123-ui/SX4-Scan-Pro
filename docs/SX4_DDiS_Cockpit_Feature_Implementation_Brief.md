# Appendix B — Additional original material pasted by the owner (verbatim)

package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TripComputerCard(
    distanceKm: Float,
    avgSpeedKmh: Float,
    instantKmPerLiter: Float,
    dieselPricePerLiter: Float = 90.0f, // Optional cost estimate
    modifier: Modifier = Modifier
) {
    val estimatedFuelUsedLiters = if (instantKmPerLiter > 0) distanceKm / instantKmPerLiter else 0f
    val tripCost = estimatedFuelUsedLiters * dieselPricePerLiter

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141416), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("TRIP COMPUTER", color = Color(0xFF9E9E9E), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TripMetric("DISTANCE", String.format("%.1f", distanceKm), "km")
            TripMetric("AVG SPEED", String.format("%.0f", avgSpeedKmh), "km/h")
            TripMetric("EFFICIENCY", String.format("%.1f", instantKmPerLiter), "km/L")
            TripMetric("EST. COST", String.format("₹%.0f", tripCost), "")
        }
    }
}

@Composable
private fun TripMetric(label: String, value: String, unit: String) {
    Column {
        Text(label, color = Color(0xFF616161), fontSize = 9.sp)
        Row {
            Text(value, color = Color.White, fontSize = 16.sp)
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(unit, color = Color(0xFF888888), fontSize = 11.sp)
            }
        }
    }
}


package com.suzuki.sx4.cockpit.obd

// Extension methods for ObdBluetoothManager
suspend fun ObdBluetoothManager.readDtcCodes(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    // Mode 03 request
    val response = sendRawCommand("03\r")
    parseDtcCodes(response)
}

suspend fun ObdBluetoothManager.clearDtcCodes(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    // Mode 04 request (Clears fault codes & turns off check engine light)
    val response = sendRawCommand("04\r")
    response.contains("44") || response.contains("OK")
}

private fun parseDtcCodes(hexResponse: String): List<String> {
    val dtcs = mutableListOf<String>()
    val tokens = hexResponse.split(" ").filter { it.isNotBlank() }
    
    // Parses pairs of hex bytes into standard OBD codes (e.g. P0400, P0234)
    var i = 0
    while (i < tokens.size - 1) {
        if (tokens[i] == "43") { i++; continue } // Skip header byte
        val byte1 = tokens.getOrNull(i)?.toIntOrNull(16) ?: break
        val byte2 = tokens.getOrNull(i + 1)?.toIntOrNull(16) ?: break
        if (byte1 == 0 && byte2 == 0) break // No more codes

        val prefix = when ((byte1 and 0xC0) shr 6) {
            0 -> "P" // Powertrain
            1 -> "C" // Chassis
            2 -> "B" // Body
            else -> "U" // Network
        }
        val codeNumber = String.format("%02X%02X", byte1 and 0x3F, byte2)
        dtcs.add("$prefix$codeNumber")
        i += 2
    }
    return dtcs
}

// Plain-English map for common 1.3 DDiS / Fiat Multijet faults
fun getDdiSCodeDescription(dtc: String): String = when (dtc) {
    "P0400" -> "EGR Valve Flow Malfunction (Clean EGR assembly)"
    "P0234" -> "Turbocharger VGT Overboost Condition"
    "P0100" -> "Mass Air Flow (MAF) Sensor Circuit"
    "P0115" -> "Engine Coolant Temperature Circuit"
    "P0087" -> "Fuel Rail / System Pressure Too Low"
    "P0670" -> "Glow Plug Control Module Circuit"
    else -> "Generic Fault Code ($dtc)"
}



package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class DragTimerManager {
    var startTimeMs: Long = 0L
    var isRunning by mutableStateOf(false)
    var elapsedTimeSec by mutableStateOf(0.0f)
    var recordTimeSec by mutableStateOf<Float?>(null)

    fun onSpeedUpdate(speedKmh: Int) {
        if (!isRunning && speedKmh > 1 && elapsedTimeSec == 0.0f) {
            // Auto-trigger on initial launch
            startTimeMs = System.currentTimeMillis()
            isRunning = true
        } else if (isRunning) {
            elapsedTimeSec = (System.currentTimeMillis() - startTimeMs) / 1000f
            if (speedKmh >= 100) {
                // Completed run
                isRunning = false
                recordTimeSec = elapsedTimeSec
            }
        }
    }

    fun reset() {
        isRunning = false
        elapsedTimeSec = 0.0f
    }
}

@Composable
fun PerformanceTimer(
    timerManager: DragTimerManager,
    currentSpeedKmh: Int,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(currentSpeedKmh) {
        timerManager.onSpeedUpdate(currentSpeedKmh)
    }

    Column(
        modifier = modifier
            .background(Color(0xFF141416), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("0 — 100 KM/H RUN", color = Color(0xFF9E9E9E), fontSize = 11.sp)
        
        Spacer(modifier = Modifier.height(6.dp))

        val displayTime = when {
            timerManager.recordTimeSec != null -> String.format("%.2f s", timerManager.recordTimeSec)
            timerManager.isRunning -> String.format("%.2f s", timerManager.elapsedTimeSec)
            else -> "READY"
        }

        Text(
            text = displayTime,
            color = if (timerManager.isRunning) Color(0xFFFFD600) else Color(0xFFFF2A2A),
            fontSize = 28.sp
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (timerManager.isRunning) "RUN IN PROGRESS ($currentSpeedKmh km/h)" else "LAUNCH TO START",
            color = Color(0xFF757575),
            fontSize = 10.sp
        )
    }
}




package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.data.DriveMode

data class CockpitColors(
    val primaryAccent: Color,
    val background: Color,
    val surface: Color,
    val textPrimary: Color
)

fun getThemeForMode(mode: DriveMode): CockpitColors = when (mode) {
    DriveMode.SPORT -> CockpitColors(
        primaryAccent = Color(0xFFFF2A2A), // Aggressive Redline
        background = Color(0xFF0A0A0C),
        surface = Color(0xFF141416),
        textPrimary = Color(0xFFFFFFFF)
    )
    DriveMode.ECO -> CockpitColors(
        primaryAccent = Color(0xFF00E676), // Efficiency Green
        background = Color(0xFF0C0E0C),
        surface = Color(0xFF121814),
        textPrimary = Color(0xFFE0E0E0)
    )
    DriveMode.NIGHT_HUD -> CockpitColors(
        primaryAccent = Color(0xFFE02020), // Low-glare muted red
        background = Color(0xFF000000),    // Pure OLED black
        surface = Color(0xFF050505),
        textPrimary = Color(0xFF9E9E9E)
    )
}

@Composable
fun DriveModeSelector(
    selectedMode: DriveMode,
    onModeSelected: (DriveMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xFF141416), RoundedCornerShape(24.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DriveMode.values().forEach { mode ->
            val isSelected = mode == selectedMode
            val accent = when(mode) {
                DriveMode.SPORT -> Color(0xFFFF2A2A)
                DriveMode.ECO -> Color(0xFF00E676)
                DriveMode.NIGHT_HUD -> Color(0xFF757575)
            }

            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) accent.copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(20.dp)
                    )
                    .then(
                        if (isSelected) Modifier.border(1.dp, accent, RoundedCornerShape(20.dp))
                        else Modifier
                    )
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.name.replace("_", " "),
                    color = if (isSelected) accent else Color(0xFF888888),
                    fontSize = 12.sp
                )
            }
        }
    }
}


How to Drop This Into Your Android Studio Project
Copy CockpitModels.kt, ShiftLightBar.kt, GForceMeter.kt, and TelemetryGraphDrawer.kt into your ui/components/ package.
Feed your existing telemetry ViewModel state into the components.
For the tablet layout, position ShiftLightBar directly atop the RPM dial, and dock GForceMeter in the lower right telemetry drawer.



Standard ELM327 Bluetooth Driver Skeleton (ObdBluetoothManager.kt)
Standard AT and Mode 01 PID commands for the 1.3 DDiS ECU:


package com.suzuki.sx4.cockpit.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class ObdBluetoothManager(private val socket: BluetoothSocket) {

    private val inputStream: InputStream = socket.inputStream
    private val outputStream: OutputStream = socket.outputStream

    suspend fun initializeElm327(): Boolean = withContext(Dispatchers.IO) {
        try {
            sendRawCommand("ATZ\r")     // Reset ELM327
            sendRawCommand("ATE0\r")    // Echo Off
            sendRawCommand("ATL0\r")    // Linefeeds Off
            sendRawCommand("ATSP0\r")   // Set Protocol to Auto
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readRpm(): Int = withContext(Dispatchers.IO) {
        val response = sendRawCommand("010C\r") // Mode 01, PID 0C (Engine RPM)
        parseRpm(response)
    }

    suspend fun readManifoldPressure(): Float = withContext(Dispatchers.IO) {
        val response = sendRawCommand("010B\r") // Mode 01, PID 0B (MAP in kPa)
        val kpa = parseSingleByteHex(response)
        // Convert Absolute kPa to Relative Bar (Boost = (MAP - 101.3) / 100)
        ((kpa - 101.3f) / 100f).coerceAtLeast(0.0f)
    }

    suspend fun readCoolantTemp(): Int = withContext(Dispatchers.IO) {
        val response = sendRawCommand("0105\r") // Mode 01, PID 05 (Coolant Temp)
        parseSingleByteHex(response) - 40        // Formula: A - 40
    }

    private fun sendRawCommand(cmd: String): String {
        outputStream.write(cmd.toByteArray())
        outputStream.flush()

        val buffer = ByteArray(256)
        val bytes = inputStream.read(buffer)
        return String(buffer, 0, bytes).replace(">", "").trim()
    }

    private fun parseRpm(hexResponse: String): Int {
        val tokens = hexResponse.split(" ").filter { it.isNotBlank() }
        if (tokens.size >= 4 && tokens[0] == "41" && tokens[1] == "0C") {
            val a = tokens[2].toIntOrNull(16) ?: return 0
            val b = tokens[3].toIntOrNull(16) ?: return 0
            return ((a * 256) + b) / 4
        }
        return 0
    }

    private fun parseSingleByteHex(hexResponse: String): Int {
        val tokens = hexResponse.split(" ").filter { it.isNotBlank() }
        if (tokens.size >= 3) {
            return tokens[2].toIntOrNull(16) ?: 0
        }
        return 0
    }
}

4. 60-Second Real-Time Telemetry Graph Drawer (TelemetryGraphDrawer.kt)
Plots smooth Bezier telemetry history when you tap any sensor tile:

package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.data.TelemetryPoint

@Composable
fun TelemetryGraphDrawer(
    metricTitle: String,
    currentValue: String,
    unit: String,
    history: List<TelemetryPoint>,
    minExpected: Float,
    maxExpected: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141416), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(metricTitle.uppercase(), color = Color(0xFF9E9E9E), fontSize = 12.sp)
            Text("$currentValue $unit", color = Color(0xFFFF2A2A), fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            if (history.size < 2) return@Canvas

            val width = size.width
            val height = size.height
            val path = Path()

            val range = (maxExpected - minExpected).coerceAtLeast(1f)

            history.forEachIndexed { index, point ->
                val x = (index.toFloat() / (history.size - 1)) * width
                val normalizedY = ((point.value - minExpected) / range).coerceIn(0f, 1f)
                val y = height - (normalizedY * height)

                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    val prevPoint = history[index - 1]
                    val prevX = ((index - 1).toFloat() / (history.size - 1)) * width
                    val prevNormY = ((prevPoint.value - minExpected) / range).coerceIn(0f, 1f)
                    val prevY = height - (prevNormY * height)

                    val controlX = (prevX + x) / 2f
                    path.cubicTo(controlX, prevY, controlX, y, x, y)
                }
            }

            drawPath(
                path = path,
                color = Color(0xFFFF2A2A),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("60s ago", color = Color(0xFF616161), fontSize = 10.sp)
            Text("Live", color = Color(0xFF00E676), fontSize = 10.sp)
        }
    }
}


3. G-Force Friction Meter (GForceMeter.kt)
Renders a live motorsport-grade friction circle using tablet accelerometer data:

package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun GForceMeter(
    lateralG: Float,       // X-axis (-1.5g to +1.5g)
    longitudinalG: Float,  // Y-axis (-1.5g to +1.5g)
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(140.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.width / 2f - 8.dp.toPx()

        // Background concentric G rings (0.5g, 1.0g, 1.5g)
        listOf(0.33f, 0.66f, 1.0f).forEach { scale ->
            drawCircle(
                color = Color(0xFF2C2C30),
                radius = maxRadius * scale,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Crosshairs
        drawLine(Color(0xFF2C2C30), Offset(center.x, 8.dp.toPx()), Offset(center.x, size.height - 8.dp.toPx()))
        drawLine(Color(0xFF2C2C30), Offset(8.dp.toPx(), center.y), Offset(size.width - 8.dp.toPx(), center.y))

        // Clamped G dot position
        val clampedLat = (lateralG / 1.5f).coerceIn(-1f, 1f)
        val clampedLong = (longitudinalG / 1.5f).coerceIn(-1f, 1f)

        val dotPosition = Offset(
            x = center.x + (clampedLat * maxRadius),
            y = center.y - (clampedLong * maxRadius)
        )

        // Live G-force indicator
        drawCircle(
            color = Color(0xFFFF2A2A).copy(alpha = 0.35f),
            radius = 12.dp.toPx(),
            center = dotPosition
        )
        drawCircle(
            color = Color(0xFFFF2A2A),
            radius = 5.dp.toPx(),
            center = dotPosition
        )
    }
}


. DDiS Sequential LED Shift Light (ShiftLightBar.kt)
Calibrated specifically for the 1.3 DDiS (Fiat Multijet diesel powerband: peak torque 1,750–2,800 RPM, redline warning at 3,800 RPM)


package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ShiftLightBar(
    rpm: Int,
    modifier: Modifier = Modifier
) {
    val totalLeds = 8
    
    // Thresholds for 1.3 DDiS diesel engine
    val greenThreshold = 2000
    val amberThreshold = 2800
    val redThreshold = 3600
    val flashThreshold = 4000

    val infiniteTransition = rememberInfiniteTransition(label = "FlashTransition")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(120, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Blink"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(16.dp)) {
        val spacing = 6.dp.toPx()
        val ledWidth = (size.width - (spacing * (totalLeds - 1))) / totalLeds
        val ledHeight = size.height
        val corner = CornerRadius(4.dp.toPx())

        for (i in 0 until totalLeds) {
            val stepRpm = greenThreshold + (i * ((flashThreshold - greenThreshold) / totalLeds))
            val isActive = rpm >= stepRpm

            val baseColor = when {
                i < 3 -> Color(0xFF00E676) // Green (Optimal Torque)
                i < 6 -> Color(0xFFFFD600) // Amber (Upper Powerband)
                else -> Color(0xFFFF1744)  // Red (Upshift Alert)
            }

            val finalColor = if (isActive) {
                if (rpm >= flashThreshold && i >= 6) {
                    baseColor.copy(alpha = flashAlpha)
                } else {
                    baseColor
                }
            } else {
                Color(0xFF222224) // Inactive LED dark housing
            }

            drawRoundRect(
                color = finalColor,
                topLeft = Offset(x = i * (ledWidth + spacing), y = 0f),
                size = Size(ledWidth, ledHeight),
                cornerRadius = corner
            )
        }
    }
}


Data Models & State (CockpitModels.kt

package com.suzuki.sx4.cockpit.data

enum class DriveMode {
    SPORT,     // High-contrast red/black, boost & shift priority
    ECO,       // Green/white efficiency focus, fuel economy
    NIGHT_HUD  // OLED blackout, glare reduction
}

data class TelemetryPoint(
    val timestampMs: Long = System.currentTimeMillis(),
    val value: Float
)

data class CockpitState(
    val driveMode: DriveMode = DriveMode.SPORT,
    val rpm: Int = 0,
    val speedKmh: Int = 0,
    val boostBar: Float = 0.0f,          // 0.0 to 1.6 Bar (SX4 VGT Turbo)
    val railPressureBar: Float = 300f,   // 250 to 1600 Bar (DDiS Common Rail)
    val coolantTempC: Int = 88,
    val intakeAirTempC: Int = 32,
    val egrPositionPercent: Int = 12,
    val batteryVoltage: Float = 14.2f,
    val fuelLevelPercent: Int = 65,
    val isObdConnected: Boolean = false,
    
    // Performance metrics
    val lateralG: Float = 0.0f,
    val longitudinalG: Float = 0.0f,
    val zeroToHundredSec: Float? = null,
    val isTimerActive: Boolean = false
)


Architecture Overview
┌─────────────────────────────────────────────────────────────┐
│                      Realme Pad 2 UI                        │
│   ┌───────────────────┐ ┌─────────────────┐ ┌─────────────┐ │
│   │ ShiftLightBar.kt  │ │ DriveModeSelector│ │ G-Meter /   │ │
│   └─────────▲─────────┘ └────────▲────────┘ │ 0-100 Timer │ │
│             │                    │          └──────▲──────┘ │
│   ┌─────────┴────────────────────┴─────────────────┴───────┐ │
│   │               CockpitViewModel.kt                     │ │
│   │    (StateFlow<CockpitState>, Rolling History Buffers) │ │
│   └──────────────────────────▲────────────────────────────┘ │
│                              │                              │
│   ┌──────────────────────────┴────────────────────────────┐ │
│   │          ObdBluetoothManager.kt (ELM327)              │ │
│   │   PIDs: 010C (RPM), 010B (MAP), 0105 (Coolant), etc.   │ │
│   └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘

---

# Additional material pasted by the owner — 3 November 2026

## 1. DDiSRightGauge.kt (Dynamic Boost in Sport / Live km/L in Eco / Stealth in HUD)

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.ui.theme.CockpitColors
import com.suzuki.sx4.cockpit.ui.theme.DriveMode

@Composable
fun DDiSRightGauge(
    mode: DriveMode,
    boostBar: Float,        // 0.0 to 1.6 bar
    peakBoostBar: Float,    // Peak memory needle
    fuelEconomyKmpl: Float, // 0.0 to 30.0 km/L (for Eco Mode)
    coolantTempC: Int,      // for HUD mode
    colors: CockpitColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(320.dp)
            .background(
                Brush.radialGradient(listOf(Color(0xFF3A3F4A), Color(0xFF111317), Color(0xFF525968))),
                CircleShape
            )
            .padding(6.dp)
            .background(Color(0xFF08090D), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.minDimension / 2) - 30.dp.toPx()

            // Outer gauge track
            drawArc(
                color = Color.White.copy(alpha = 0.06f),
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )

            val currentFraction = when (mode) {
                DriveMode.SPORT -> (boostBar / 1.6f).coerceIn(0f, 1f)
                DriveMode.ECO -> (fuelEconomyKmpl / 30f).coerceIn(0f, 1f)
                DriveMode.NIGHT_HUD -> (coolantTempC / 120f).coerceIn(0f, 1f)
            }

            // Ghost peak needle in Sport mode
            if (mode == DriveMode.SPORT) {
                val peakAngle = 140f + (peakBoostBar / 1.6f).coerceIn(0f, 1f) * 260f
                drawArc(
                    color = Color.White.copy(alpha = 0.4f),
                    startAngle = peakAngle - 1f,
                    sweepAngle = 2f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 12.dp.toPx())
                )
            }

            // Primary active arc
            drawArc(
                color = colors.primaryAccent,
                startAngle = 140f,
                sweepAngle = currentFraction * 260f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Center Readout
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (mode) {
                DriveMode.SPORT -> {
                    Text("VGT BOOST", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, letterSpacing = 1.5.sp)
                    Text(String.format("%.2f", boostBar), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("BAR", color = colors.primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("PEAK ${String.format("%.2f", peakBoostBar)}", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
                }
                DriveMode.ECO -> {
                    Text("FUEL ECONOMY", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, letterSpacing = 1.5.sp)
                    Text(String.format("%.1f", fuelEconomyKmpl), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("KM / L", color = colors.primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                DriveMode.NIGHT_HUD -> {
                    Text("COOLANT TEMP", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, letterSpacing = 1.5.sp)
                    Text("$coolantTempC°C", color = if (coolantTempC > 98) Color(0xFFFF1744) else Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("NOMINAL 88°C", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
                }
            }
        }
    }
}
```

## 2. DDiSTelemetryDock.kt (Sensors, Rail Pressure, Heat Soak & EGR)

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.ui.theme.CockpitColors

@Composable
fun DDiSTelemetryDock(
    coolantTemp: Int,
    intakeTemp: Int,
    egrDuty: Int,
    railPressure: Int,
    batteryVoltage: Float,
    colors: CockpitColors,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0x2211141A), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TelemetryCard(label = "COOLANT", value = "$coolantTemp°C", alert = coolantTemp > 98, accent = colors.primaryAccent)
        TelemetryCard(label = "IAT (INTERCOOLER)", value = "$intakeTemp°C", alert = intakeTemp > 50, accent = colors.primaryAccent)
        TelemetryCard(label = "EGR DUTY", value = "$egrDuty%", alert = false, accent = colors.secondaryAccent)
        TelemetryCard(label = "COMMON RAIL", value = "$railPressure BAR", alert = false, accent = colors.primaryAccent)
        TelemetryCard(label = "BATTERY", value = "${String.format("%.1f", batteryVoltage)}V", alert = batteryVoltage < 12.2f, accent = colors.primaryAccent)
    }
}

@Composable
private fun TelemetryCard(label: String, value: String, alert: Boolean, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            value,
            color = if (alert) Color(0xFFFF1744) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
```

## 3. BottomNavBar.kt (MFD Tabs: Cockpit, Telemetry, Performance, DTC)

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.ui.theme.CockpitColors

enum class MFDTab(val title: String) {
    COCKPIT("INSTRUMENTS"),
    TELEMETRY("60S GRAPHS"),
    PERFORMANCE("0-100 / G-METER"),
    DTC_SCANNER("OBD-II DTC")
}

@Composable
fun CockpitBottomNav(
    activeTab: MFDTab,
    onTabSelect: (MFDTab) -> Unit,
    colors: CockpitColors,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF07080A))
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MFDTab.values().forEach { tab ->
            val isSelected = tab == activeTab
            Column(
                modifier = Modifier
                    .clickable { onTabSelect(tab) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    tab.title,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp
                )
                if (isSelected) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(2.dp)
                            .background(colors.primaryAccent, RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}
```

## 4. SX4CockpitDashboard.kt (Realme Pad 2 1333×800 Zero-Scroll Assembly)

```kotlin
package com.suzuki.sx4.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.ui.components.*
import com.suzuki.sx4.cockpit.ui.theme.DriveMode
import com.suzuki.sx4.cockpit.ui.theme.getCockpitColors

@Composable
fun SX4CockpitScreen() {
    var mode by remember { mutableStateOf(DriveMode.SPORT) }
    var activeTab by remember { mutableStateOf(MFDTab.COCKPIT) }
    val colors = getCockpitColors(mode)

    // Demo / OBD telemetry states
    var rpm by remember { mutableStateOf(2450f) }
    var speedKmh by remember { mutableStateOf(78) }
    var gear by remember { mutableStateOf("4") }
    var boostBar by remember { mutableStateOf(1.24f) }
    var peakBoost by remember { mutableStateOf(1.48f) }
    var fuelEco by remember { mutableStateOf(18.6f) }
    var coolantTemp by remember { mutableStateOf(88) }
    var intakeTemp by remember { mutableStateOf(34) }
    var egrDuty by remember { mutableStateOf(14) }
    var railPressure by remember { mutableStateOf(1420) }
    var batteryVolts by remember { mutableStateOf(14.2f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 1. Redline Horizon Header (48dp)
        RedlineHorizonHeader(
            currentMode = mode,
            onModeSelect = { mode = it },
            colors = colors
        )

        // 2. Main Cluster Viewport (Zero-Scroll on Realme Pad 2)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            when (activeTab) {
                MFDTab.COCKPIT -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Tachometer with 8-LED Shift Arc
                            ChronographTachometer(rpm = rpm, colors = colors)

                            // Center: SX4 Vehicle HUD & Speed
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("GEAR $gear", color = colors.primaryAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("$speedKmh", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                                Text("KM / H", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, letterSpacing = 2.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("1.3 DDiS • MULTIJET", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, letterSpacing = 1.sp)
                            }

                            // Right: Turbo Boost / Fuel Economy / HUD Temp
                            DDiSRightGauge(
                                mode = mode,
                                boostBar = boostBar,
                                peakBoostBar = peakBoost,
                                fuelEconomyKmpl = fuelEco,
                                coolantTempC = coolantTemp,
                                colors = colors
                            )
                        }

                        // Bottom Glass Telemetry Dock
                        DDiSTelemetryDock(
                            coolantTemp = coolantTemp,
                            intakeTemp = intakeTemp,
                            egrDuty = egrDuty,
                            railPressure = railPressure,
                            batteryVoltage = batteryVolts,
                            colors = colors
                        )
                    }
                }
                MFDTab.TELEMETRY -> {
                    // Placeholder for 60s Sparklines (S3 Section 4)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("60-SECOND TELEMETRY SPARKLINES", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                    }
                }
                MFDTab.PERFORMANCE -> {
                    // Placeholder for 0-100 Drag Tree & G-Meter (S3 Section 4)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("0-100 KM/H LAUNCH TREE & G-METER", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                    }
                }
                MFDTab.DTC_SCANNER -> {
                    // OBD-II DTC Fault Log (S3 Section 4)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("NO STORED DTC FAULTS — ECU READY", color = Color(0xFF10B981), fontSize = 16.sp)
                    }
                }
            }
        }

        // 3. Bottom Navigation Bar (48dp)
        CockpitBottomNav(
            activeTab = activeTab,
            onTabSelect = { activeTab = it },
            colors = colors
        )
    }
}
```

## 5. ChronographGauge.kt (8-LED Shift Arc + Peak-Hold Boost)

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.ui.theme.CockpitColors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ChronographTachometer(
    rpm: Float,
    maxRpm: Float = 5000f,
    colors: CockpitColors,
    modifier: Modifier = Modifier
) {
    // Stage 0..8
    val stage = when {
        rpm < 1750f -> 0
        rpm < 2200f -> 1
        rpm < 2600f -> 2
        rpm < 3000f -> 3
        rpm < 3400f -> 4
        rpm < 3700f -> 5
        rpm < 3900f -> 6
        rpm < 4100f -> 7
        else -> 8
    }

    // Flashing strobe for redline (Stage 8 at 4100+ RPM)
    val infiniteTransition = rememberInfiniteTransition(label = "strobe")
    val strobeColor by infiniteTransition.animateColor(
        initialValue = Color(0xFFFF1744),
        targetValue = Color.White,
        animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
        label = "strobeColor"
    )

    Box(
        modifier = modifier
            .size(320.dp)
            // Chrome Bezel outer ring
            .background(
                Brush.radialGradient(listOf(Color(0xFF3A3F4A), Color(0xFF111317), Color(0xFF525968))),
                CircleShape
            )
            .padding(6.dp)
            .background(Color(0xFF08090D), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // SVG/Canvas Dial & 8-LED Shift Light Arc
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.minDimension / 2) - 30.dp.toPx()

            // 1. Draw 8 Sequential Shift LEDs across top arc
            for (i in 0 until 8) {
                val ledAngle = 220f + (i * 14f)
                val rad = Math.toRadians(ledAngle.toDouble())
                val ledX = center.x + (radius + 18.dp.toPx()) * cos(rad).toFloat()
                val ledY = center.y + (radius + 18.dp.toPx()) * sin(rad).toFloat()

                val ledColor = when {
                    i >= stage -> Color.White.copy(alpha = 0.08f)
                    i < 3 -> Color(0xFF10B981) // Cyan/Green (1,750-2,800 RPM)
                    i < 6 -> Color(0xFFF59E0B) // Amber (2,800-3,800 RPM)
                    else -> strobeColor        // Flashing Red/White (3,900-4,200 RPM)
                }

                drawCircle(color = ledColor, radius = 4.dp.toPx(), center = Offset(ledX, ledY))
            }

            // 2. Track & Sweep
            drawArc(
                color = Color.White.copy(alpha = 0.06f),
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )
            val sweep = (rpm / maxRpm).coerceIn(0f, 1f) * 260f
            drawArc(
                color = colors.primaryAccent,
                startAngle = 140f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Center Digital Display
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ENGINE SPEED", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, letterSpacing = 1.5.sp)
            Text("${rpm.toInt()}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("RPM", color = colors.primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(8.dp))
            // Recessed 7-Segment Box
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    if (stage == 8) "SHIFT NOW" else "STAGE $stage/8",
                    color = if (stage == 8) Color(0xFFFF1744) else Color(0xFF10B981),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
```

## 6. RedlineHorizonHeader.kt (Choice A Aero Pod + Micro Silhouette)

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.ui.theme.CockpitColors
import com.suzuki.sx4.cockpit.ui.theme.DriveMode

@Composable
fun RedlineHorizonHeader(
    currentMode: DriveMode,
    onModeSelect: (DriveMode) -> Unit,
    colors: CockpitColors,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Brand
            Column {
                Text("SUZUKI", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("SX4 ZDi", color = colors.primaryAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }

            // Center: Choice A Aero Pod (Minimalist Silhouette + Speed Trails)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(modifier = Modifier.size(width = 46.dp, height = 12.dp)) {
                    val path = Path().apply {
                        moveTo(2f, 12f)
                        quadraticTo(12f, 12f, 18f, 9f)
                        lineTo(26f, 3f)
                        quadraticTo(35f, 1f, 44f, 3f)
                        lineTo(54f, 10f)
                        lineTo(58f, 12f)
                    }
                    drawPath(path, color = colors.primaryAccent, style = Stroke(width = 2.dp.toPx()))
                    // Speed trails
                    drawLine(colors.primaryAccent.copy(alpha = 0.5f), Offset(0f, 12f), Offset(14f, 12f), strokeWidth = 1.dp.toPx())
                }
                Text("SX4 / DDiS 1.3 TURBO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("DRIVER INFORMATION SYSTEM", color = Color.White.copy(alpha = 0.4f), fontSize = 7.sp, letterSpacing = 1.sp)
            }

            // Right: Drive Mode Selector Pill
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DriveMode.values().forEach { mode ->
                    val selected = mode == currentMode
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) colors.primaryAccent else Color.Transparent,
                                RoundedCornerShape(50)
                            )
                            .clickable { onModeSelect(mode) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(mode.name, color = if (selected) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Horizontal Redline Horizon Light Bar
        Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            val gradient = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.35f to colors.horizonGlow,
                0.65f to colors.horizonGlow,
                1.0f to Color.Transparent
            )
            drawRect(brush = gradient)
        }
    }
}
```

## 7. CockpitTheme.kt (Sport, Eco & HUD Tokens + 0.3s Transition)

```kotlin
package com.suzuki.sx4.cockpit.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class DriveMode { SPORT, ECO, NIGHT_HUD }

data class CockpitColors(
    val background: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val glassSurface: Color,
    val horizonGlow: Color
)

@Composable
fun getCockpitColors(mode: DriveMode): CockpitColors {
    // 0.3-second smooth transition between modes
    val transitionSpec = tween<Color>(durationMillis = 300)

    val targetBg = when (mode) {
        DriveMode.SPORT -> Color(0xFF07080A)
        DriveMode.ECO -> Color(0xFF060B10)
        DriveMode.NIGHT_HUD -> Color(0xFF000000)
    }
    val targetPrimary = when (mode) {
        DriveMode.SPORT -> Color(0xFFE11D48) // Crimson
        DriveMode.ECO -> Color(0xFF06B6D4)   // Cyan
        DriveMode.NIGHT_HUD -> Color(0xFFFF1744) // Aviation Red
    }
    val targetSecondary = when (mode) {
        DriveMode.SPORT -> Color(0xFFFF2E5B)
        DriveMode.ECO -> Color(0xFFF59E0B)   // Amber
        DriveMode.NIGHT_HUD -> Color(0xFF7F1D1D)
    }

    return CockpitColors(
        background = animateColorAsState(targetBg, transitionSpec, label = "bg").value,
        primaryAccent = animateColorAsState(targetPrimary, transitionSpec, label = "pri").value,
        secondaryAccent = animateColorAsState(targetSecondary, transitionSpec, label = "sec").value,
        glassSurface = Color(0x1AFFFFFF),
        horizonGlow = animateColorAsState(targetPrimary, transitionSpec, label = "glow").value
    )
}
```
