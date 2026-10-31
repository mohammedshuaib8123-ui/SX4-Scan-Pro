package com.sx4scanpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.Locale


@Composable
private fun GaugeCard(gauge: GaugeValue) {
    val number = gauge.value.toFloatOrNull()
    val range = when (gauge.label) {
        "Engine RPM" -> 5000f
        "Vehicle speed" -> 180f
        "Coolant temperature" -> 120f
        "Intake air temperature" -> 100f
        "Manifold pressure" -> 250f
        "Turbo boost" -> 200f
        "MAF airflow" -> 100f
        else -> 100f
    }
    val fraction = if (gauge.available && number != null) (number / range).coerceIn(0f, 1f) else 0f
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(gauge.label, style = MaterialTheme.typography.labelLarge, minLines = 2)
            Box(Modifier.fillMaxWidth().height(82.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 9.dp.toPx()
                    val arcSize = Size(size.width * 0.78f, size.height * 1.55f)
                    val topLeft = androidx.compose.ui.geometry.Offset((size.width - arcSize.width) / 2f, size.height * 0.22f)
                    drawArc(color = Color(0xFF344252), startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (gauge.available) drawArc(color = Color(0xFF35B887), startAngle = 180f, sweepAngle = 180f * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text(if (gauge.available) gauge.value else "—", style = MaterialTheme.typography.headlineSmall)
            }
            Text(if (gauge.available) gauge.unit else "Waiting for ECU", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class GaugeValue(val label: String, val value: String, val unit: String = "", val available: Boolean = true)

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    private var socket: BluetoothSocket? = null
    private var supportedPids: Set<Int> = emptySet()
    private val engineStartDetector = EngineStartDetector()
    private var speechReady = false
    private var textToSpeech: TextToSpeech? = null
    private var chime: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)

        textToSpeech = TextToSpeech(this) { result ->
            speechReady = result == TextToSpeech.SUCCESS
            if (speechReady) textToSpeech?.language = Locale.UK
        }
        chime = ToneGenerator(AudioManager.STREAM_MUSIC, 72)
        setContent { MaterialTheme { Dashboard() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Dashboard() {
        val bg = Color(0xFF080D14)
        val panel = Color(0xFF111B27)
        val cyan = Color(0xFF38DDF5)
        val muted = Color(0xFF8FA6B9)
        var status by remember { mutableStateOf("Not connected") }
        var adapterName by remember { mutableStateOf("Vgate iCar Pro • Bluetooth Classic") }
        var gauges by remember { mutableStateOf(listOf(
            GaugeValue("Engine RPM", "--", "rpm", false), GaugeValue("Vehicle speed", "--", "km/h", false),
            GaugeValue("Coolant temperature", "--", "°C", false), GaugeValue("Intake air temperature", "--", "°C", false),
            GaugeValue("Manifold pressure", "--", "kPa", false), GaugeValue("Turbo boost", "--", "kPa", false),
            GaugeValue("MAF airflow", "--", "g/s", false), GaugeValue("Calculated engine load", "--", "%", false),
            GaugeValue("Throttle position", "--", "%", false)
        )) }
        var selectedTab by remember { mutableIntStateOf(0) }
        var dtcStatus by remember { mutableStateOf("Connect the Vgate adapter to read engine ECU fault codes.") }
        var dtcCodes by remember { mutableStateOf<List<String>>(emptyList()) }
        var scanningCodes by remember { mutableStateOf(false) }
        var headUnitStatus by remember { mutableStateOf("Open Headunit runs as a separate receiver app.") }
        val scope = rememberCoroutineScope()
        val connected = status == "Connected"

        fun disconnect() {
            closeSocket()
            status = "Disconnected"
            gauges = gauges.map { it.copy(value = "--", available = false) }
        }
        fun connect() {
            scope.launch {
                disconnect()
                status = "Connecting…"
                try {
                    val result = withContext(Dispatchers.IO) { connectAndRead() }
                    adapterName = result.first
                    status = "Connected"
                    while (isActive && socket?.isConnected == true) {
                        gauges = withContext(Dispatchers.IO) { readDashboard() }
                        val rpmGauge = gauges.firstOrNull { it.label == "Engine RPM" }
                        if (engineStartDetector.update(rpmGauge?.value?.toIntOrNull(), rpmGauge?.available == true)) {
                            playEngineWelcome()
                        }
                        delay(1300)
                    }
                    if (status == "Connected") {
                        status = "Disconnected"
                        gauges = gauges.map { it.copy(value = "--", available = false) }
                    }
                } catch (e: SecurityException) {
                    closeSocket()
                    status = "Permission required"
                } catch (e: Exception) {
                    closeSocket()
                    status = "Connection failed"
                }
            }
        }
        fun scanFaultCodes() {
            if (!connected) {
                dtcStatus = "Not connected. Scan requires the vehicle and adapter."
                return
            }
            scope.launch {
                scanningCodes = true
                dtcStatus = "Reading engine ECU modes 03 / 07 / 0A…"
                try {
                    dtcCodes = withContext(Dispatchers.IO) { readTroubleCodes() }
                    dtcStatus = if (dtcCodes.isEmpty())
                        "No codes returned, or modes unsupported. This does not prove every module is fault-free."
                    else "Engine ECU results only. BCM, ABS, airbag and other modules were not scanned."
                } catch (e: Exception) {
                    dtcStatus = "Scan failed: " + (e.message ?: "communication error")
                } finally { scanningCodes = false }
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = cyan, secondary = Color(0xFFFFB84D), background = bg, surface = panel, onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = muted)) {
            Scaffold(containerColor = bg) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).background(Brush.verticalGradient(listOf(Color(0xFF05080E), Color(0xFF0B1421), Color(0xFF05080E))))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("S X 4   /   S C A N   P R O", color = cyan, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Text("DIGITAL COCKPIT", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(if (connected) "● ECU LINK ACTIVE" else "○ ECU OFFLINE", color = if (connected) cyan else muted)
                            Text("v0.3.4 • UI PREVIEW • BUILD 15", color = muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    NavigationBar(containerColor = Color(0xFF09111D), tonalElevation = 0.dp) {
                        listOf("Cockpit", "Diagnostics", "Head Unit", "Settings").forEachIndexed { i, label ->
                            NavigationBarItem(selected = selectedTab == i, onClick = { selectedTab = i },
                                icon = { Text(listOf("◉", "⌁", "▣", "⚙")[i], color = if (selectedTab == i) cyan else muted) }, label = { Text(label, maxLines = 1, fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal) })
                        }
                    }
                    when (selectedTab) {
                        0 -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ENGINE TELEMETRY   /   REAL-TIME INSTRUMENTATION", color = cyan, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                                Text(if (connected) "● LIVE DATA" else "◌ ECU STANDBY", color = if (connected) cyan else muted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                            Row(Modifier.fillMaxWidth().weight(1.1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CockpitGauge("ENGINE RPM", gauges[0], "0 — 5,000 rpm", 5000f, cyan, Modifier.weight(1.15f))
                                CockpitGauge("ROAD SPEED", gauges[1], "0 — 180 km/h", 180f, Color(0xFFFFB84D), Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth().weight(.7f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(gauges[5], gauges[2], gauges[6], gauges[7]).forEach { g ->
                                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xE6121F2D)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26394C))) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(g.label.uppercase(), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                                            Text(if (g.available) g.value + " " + g.unit else "—", color = if (g.available) Color.White else muted, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                                            Text(if (g.available) "● LIVE SIGNAL" else "○ NO SIGNAL", color = if (g.available) cyan else muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(status.uppercase(), color = if (connected) cyan else muted)
                                    Text(adapterName, color = muted, style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(onClick = { connect() }, enabled = !connected) { Text("CONNECT") }
                                OutlinedButton(onClick = { disconnect() }, enabled = connected) { Text("DISCONNECT") }
                            }
                            Text("Informational display only. Do not operate the tablet while driving.", color = muted, style = MaterialTheme.typography.labelSmall)
                        }
                        1 -> LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("ENGINE ECU • OBD-II", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Read-only standard diagnostic modes: stored (03), pending (07), permanent (0A). Not a full vehicle scan.", color = muted)
                                        Button(onClick = { scanFaultCodes() }, enabled = connected && !scanningCodes) {
                                            Text(if (scanningCodes) "SCANNING…" else "SCAN ENGINE ECU")
                                        }
                                        Text(dtcStatus, color = muted)
                                    }
                                }
                            }
                            if (dtcCodes.isEmpty()) item { Text("No returned DTCs to display yet.", color = muted) }
                            else items(dtcCodes) { code ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(code, color = Color(0xFFFFB84D), style = MaterialTheme.typography.titleLarge)
                                        Text("ENGINE ECU", color = muted)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp)) {
                                        Text("OTHER MODULES", color = Color(0xFFFFB84D), style = MaterialTheme.typography.titleMedium)
                                        Text("BCM, ABS, airbag, immobilizer and body/chassis modules remain unsupported until Suzuki-specific read-only communication is verified.", color = muted)
                                    }
                                }
                            }
                        }
                        2 -> LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("ANDROID AUTO • HEAD UNIT", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Android Auto receiver: Open Headunit (separate app)", color = Color(0xFFFFB84D))
                                        Text("The receiver is kept separate rather than merged into the OBD app. Wired USB is the first route to validate on your Realme Pad 2.", color = muted)
                                        Button(onClick = {
                                            val launch = packageManager.getLaunchIntentForPackage("com.andrerinas.headunitrevived")
                                            if (launch != null) {
                                                startActivity(launch)
                                                headUnitStatus = "Launching Open Headunit…"
                                            } else {
                                                headUnitStatus = "Open Headunit is not installed. Opening its official project page."
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andreknieriem/open-headunit")))
                                            }
                                        }, modifier = Modifier.fillMaxWidth()) { Text("LAUNCH / INSTALL OPEN HEADUNIT") }
                                        Text(headUnitStatus, color = muted)
                                    }
                                }
                            }
                            item { Text("Projection, touch and audio still require on-device testing. Android Auto 17.4+ has changed legacy wireless launch behaviour; start with wired USB.", color = muted) }
                        }
                        else -> LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("OBD CONNECTION", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Adapter: " + adapterName + "\nTransport: Bluetooth Classic SPP\nStatus: " + status + "\nSupported standard PIDs: " + supportedPids.size, color = muted)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("WELCOME & ALERTS", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Planned: “Welcome to SX4 Airlines 2874”, calm female English TTS, original chime and RPM-based engine-start detection. Not active in this build.", color = muted)
                                    }
                                }
                            }
                            item { Text("Build v0.3.4 • UI PREVIEW • BUILD 15", color = muted) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CockpitGauge(title: String, gauge: GaugeValue, scale: String, max: Float, accent: Color, modifier: Modifier) {
        Card(modifier.fillMaxHeight().padding(vertical = 7.dp), colors = CardDefaults.cardColors(containerColor = Color(0xE6101B29)), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f))) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(title, color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 12.dp.toPx()
                        val arc = Size(size.width * .82f, size.height * 1.45f)
                        val top = androidx.compose.ui.geometry.Offset((size.width - arc.width) / 2f, size.height * .22f)
                        drawArc(Color(0xFF263747), 180f, 180f, false, top, arc, style = Stroke(stroke * 1.9f, cap = StrokeCap.Round))
                        drawArc(accent.copy(alpha = 0.12f), 180f, 180f, false, top, arc, style = Stroke(stroke * 3.2f, cap = StrokeCap.Round))
                        val fraction = (gauge.value.toFloatOrNull()?.div(max) ?: 0f).coerceIn(0f, 1f)
                        if (gauge.available) {
                            drawArc(accent.copy(alpha = 0.18f), 180f, 180f * fraction, false, top, arc, style = Stroke(stroke * 2.8f, cap = StrokeCap.Round))
                            drawArc(accent, 180f, 180f * fraction, false, top, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                            val cx = size.width / 2f; val cy = size.height * 0.92f
                            for (tick in 0..20) {
                                val angle = Math.PI * (1.0 + tick / 20.0)
                                val r1 = size.width * 0.34f; val r2 = r1 + if (tick % 5 == 0) 12.dp.toPx() else 5.dp.toPx()
                                drawLine(if (tick % 5 == 0) accent else Color(0xFF607589), androidx.compose.ui.geometry.Offset(cx + kotlin.math.cos(angle).toFloat() * r1, cy + kotlin.math.sin(angle).toFloat() * r1), androidx.compose.ui.geometry.Offset(cx + kotlin.math.cos(angle).toFloat() * r2, cy + kotlin.math.sin(angle).toFloat() * r2), strokeWidth = if (tick % 5 == 0) 2.dp.toPx() else 1.dp.toPx())
                            }
                            val needleAngle = Math.PI * (1.0 + fraction)
                            drawLine(Color.White, androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(cx + kotlin.math.cos(needleAngle).toFloat() * size.width * 0.31f, cy + kotlin.math.sin(needleAngle).toFloat() * size.width * 0.31f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                            drawCircle(accent, radius = 6.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx, cy))
                        }
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(if (gauge.available) gauge.value else "—", color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
                        Text(gauge.unit, color = accent)
                    }
                }
                Text(scale, color = Color(0xFF8FA6B9), style = MaterialTheme.typography.labelSmall)
                Text(if (gauge.available) "SIGNAL VALID" else "WAITING FOR ECU", color = if (gauge.available) accent else Color(0xFF8FA6B9), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    private fun connectAndRead(): Pair<String, Unit> {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Bluetooth permission not granted")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("Bluetooth is not supported on this device")
        if (!adapter.isEnabled) throw IOException("Turn on Bluetooth")
        val candidates = adapter.bondedDevices.filter {
            val n = it.name.orEmpty()
            n.contains("Vgate", true) || n.contains("iCar", true) || n.contains("OBD", true)
        }
        if (candidates.isEmpty()) {
            throw IOException("No recognised OBD adapter found. Pair your Vgate iCar Pro, then try again.")
        }
        if (candidates.size > 1) {
            throw IOException("Multiple OBD adapters are paired. Keep only the intended Vgate adapter connected.")
        }
        val device: BluetoothDevice = candidates.single()
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        adapter.cancelDiscovery()
        s.connect()
        socket = s
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { sendCommand(it) }
        supportedPids = discoverSupportedPids()
        return (device.name ?: "Bluetooth OBD adapter") to Unit
    }

    @Synchronized
    private fun sendCommand(command: String): String {
        val s = socket ?: throw IOException("Not connected")
        s.outputStream.write((command + "\r").toByteArray(Charsets.US_ASCII))
        s.outputStream.flush()
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + 3000
        var promptReceived = false
        while (System.currentTimeMillis() < deadline) {
            if (s.inputStream.available() > 0) {
                val b = s.inputStream.read()
                if (b == -1) throw IOException("OBD adapter closed the connection")
                if (b.toChar() == '>') {
                    promptReceived = true
                    break
                }
                if (out.length >= 4096) throw IOException("OBD response exceeded the safety limit")
                out.append(b.toChar())
            } else {
                Thread.sleep(12)
            }
        }
        if (!promptReceived) throw IOException("Timed out waiting for the OBD prompt")
        val response = out.toString().replace("\r", "").replace("\n", "").replace(" ", "").uppercase()
        if (response.contains("STOPPED") || response == "?") throw IOException("OBD adapter rejected command: $command")
        response
    }

    @Synchronized
    private fun discoverSupportedPids(): Set<Int> {
        val first = try { sendCommand("0100") } catch (_: Exception) { "" }
        val second = if (ObdPidSupport.decode(first).contains(32)) {
            try { sendCommand("0120") } catch (_: Exception) { "" }
        } else ""
        return ObdPidSupport.decode(first + second)
    }

    // Hold the same Activity monitor for the complete PID batch so a DTC scan cannot
    // consume a response intended for the live dashboard (sendCommand is re-entrant).
    @Synchronized
    private fun readDashboard(): List<GaugeValue> {
        val specs = listOf(
            Triple("Engine RPM", "010C", "rpm"),
            Triple("Vehicle speed", "010D", "km/h"),
            Triple("Coolant temperature", "0105", "°C"),
            Triple("Intake air temperature", "010F", "°C"),
            Triple("Manifold pressure", "010B", "kPa"),
            Triple("MAF airflow", "0110", "g/s"),
            Triple("Calculated engine load", "0104", "%"),
            Triple("Throttle position", "0111", "%")
        )
        val results = specs.map { (label, command, unit) ->
            val pid = command.substring(2).toIntOrNull(16)
            if (pid == null || pid !in supportedPids) {
                GaugeValue(label, "--", unit, false)
            } else {
                val response = try { sendCommand(command) } catch (_: Exception) { "" }
                val bytes = response.substringAfter("41" + command.substring(2), "")
                val parsed = parsePid(command, bytes)
                GaugeValue(label, parsed ?: "--", unit, parsed != null)
            }
        }
        val map = results.associateBy { it.label }
        val mapPressure = map["Manifold pressure"]?.value?.toDoubleOrNull()
        val boost = if (mapPressure != null) {
            val baroResponse = if (51 in supportedPids) try { sendCommand("0133") } catch (_: Exception) { "" } else ""
            val baroBytes = baroResponse.substringAfter("4133", "")
            val baro = if (baroBytes.take(2).length == 2 && baroBytes.take(2).all { it in "0123456789ABCDEF" }) baroBytes.take(2).toIntOrNull(16)?.toDouble() else null
            if (baro != null) GaugeValue("Turbo boost", String.format(java.util.Locale.US, "%.1f", (mapPressure - baro).coerceAtLeast(0.0)), "kPa", true)
            else GaugeValue("Turbo boost", "--", "kPa", false)
        } else GaugeValue("Turbo boost", "--", "kPa", false)
        return results.take(4) + listOf(map["Manifold pressure"]!!) + boost + results.drop(5)
    }

    // DTC modes are one transaction: do not interleave them with live PID polling.
    @Synchronized
    private fun readTroubleCodes(): List<String> {
        return listOf("03", "07", "0A")
            .flatMap { mode -> decodeTroubleCodes(sendCommand(mode), mode) }
            .distinct()
    }

    private fun decodeTroubleCodes(response: String, mode: String): List<String> {
        val prefix = when (mode) {
            "03" -> "43"
            "07" -> "47"
            "0A" -> "4A"
            else -> return emptyList()
        }
        if (!response.startsWith(prefix)) return emptyList()
        if (response.contains("NODATA") || response.contains("UNABLE") || response.contains("STOPPED")) return emptyList()
        val payload = response.removePrefix(prefix)
        if (payload.isBlank() || payload.length % 4 != 0 || payload.any { it !in "0123456789ABCDEF" }) return emptyList()
        val hex = payload
        return hex.chunked(4).filter { it.length == 4 }.mapNotNull { bytes ->
            val a = bytes.substring(0, 2).toIntOrNull(16) ?: return@mapNotNull null
            val b = bytes.substring(2, 4).toIntOrNull(16) ?: return@mapNotNull null
            if (a == 0 && b == 0) return@mapNotNull null
            val first = when ((a shr 6) and 0x03) {
                0 -> "P"
                1 -> "C"
                2 -> "B"
                else -> "U"
            }
            val second = ((a shr 4) and 0x03).toString()
            val remaining = "%02X%02X".format(a and 0x0F, b)
            first + second + remaining
        }
    }

    private fun parsePid(command: String, data: String): String? {
        val expectedLength = if (command in setOf("010C", "0110")) 4 else 2
        if (data.length < expectedLength || data.take(expectedLength).any { it !in "0123456789ABCDEF" }) return null
        val bytes = data.take(expectedLength)
        val a = bytes.substring(0, 2).toIntOrNull(16) ?: return null
        val b = if (bytes.length == 4) bytes.substring(2, 4).toIntOrNull(16) ?: return null else 0
        return when (command) {
            "010C" -> String.format(java.util.Locale.US, "%.0f", (a * 256 + b) / 4.0)
            "0105", "010F" -> (a - 40).toString()
            "010D" -> a.toString()
            "010B" -> a.toString()
            "0110" -> String.format(java.util.Locale.US, "%.2f", (a * 256 + b) / 100.0)
            "0104", "0111" -> String.format(java.util.Locale.US, "%.1f", a * 100.0 / 255.0)
            else -> null
        }
    }

    private fun playEngineWelcome() {
        // Fresh positive RPM samples trigger this; Bluetooth connection alone never does.
        chime?.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
        mainHandler.postDelayed({
            chime?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            mainHandler.postDelayed({
                if (speechReady) textToSpeech?.speak("Welcome to SX4 Airlines 2874", TextToSpeech.QUEUE_FLUSH, null, "sx4_engine_start")
            }, 260)
        }, 190)
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    override fun onDestroy() {
        closeSocket()
        mainHandler.removeCallbacksAndMessages(null)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        chime?.release()
        super.onDestroy()
    }
}
