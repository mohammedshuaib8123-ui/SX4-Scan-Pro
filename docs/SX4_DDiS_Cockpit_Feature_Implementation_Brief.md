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
