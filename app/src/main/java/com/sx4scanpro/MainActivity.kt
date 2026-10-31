package com.sx4scanpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)

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
                    dtcCodes = withContext(Dispatchers.IO) {
                        listOf("03", "07", "0A").flatMap { decodeTroubleCodes(sendCommand(it), it) }.distinct()
                    }
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
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("SX4  /  SCAN PRO", color = cyan, style = MaterialTheme.typography.labelLarge)
                            Text("DIGITAL COCKPIT", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(if (connected) "● ECU LINK ACTIVE" else "○ ECU OFFLINE", color = if (connected) cyan else muted)
                            Text("v0.3.1 • UI PREVIEW • BUILD 12", color = muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    NavigationBar(containerColor = Color(0xFF0D1520)) {
                        listOf("Cockpit", "Diagnostics", "Head Unit", "Settings").forEachIndexed { i, label ->
                            NavigationBarItem(selected = selectedTab == i, onClick = { selectedTab = i },
                                icon = { Text(listOf("◉", "⌁", "▣", "⚙")[i]) }, label = { Text(label, maxLines = 1) })
                        }
                    }
                    when (selectedTab) {
                        0 -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ENGINE TELEMETRY", color = muted, style = MaterialTheme.typography.labelMedium)
                                Text(if (connected) "LIVE DATA" else "AWAITING ECU", color = if (connected) cyan else muted)
                            }
                            Row(Modifier.fillMaxWidth().weight(1.1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CockpitGauge("ENGINE RPM", gauges[0], "0 — 5,000 rpm", 5000f, cyan, Modifier.weight(1.15f))
                                CockpitGauge("ROAD SPEED", gauges[1], "0 — 180 km/h", 180f, Color(0xFFFFB84D), Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth().weight(.7f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(gauges[5], gauges[2], gauges[6], gauges[7]).forEach { g ->
                                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = panel)) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(g.label.uppercase(), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                            Text(if (g.available) g.value + " " + g.unit else "—", color = Color.White, style = MaterialTheme.typography.titleLarge)
                                            Text(if (g.available) "LIVE" else "UNAVAILABLE", color = if (g.available) cyan else muted, style = MaterialTheme.typography.labelSmall)
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
                                        Text("Receiver integration is not yet installed in this build.", color = Color(0xFFFFB84D))
                                        Text("Planned path: evaluate Open Headunit (AGPL-3.0) as an isolated module, starting with wired USB projection. Verify Realme Pad 2 OTG, phone compatibility, touch and audio routing first.", color = muted)
                                    }
                                }
                            }
                            item { Text("This page is an honest integration placeholder, not a functioning Android Auto receiver.", color = muted) }
                        }
                        else -> LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("OBD CONNECTION", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Adapter: " + adapterName + "\nTransport: Bluetooth Classic SPP\nStatus: " + status, color = muted)
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
                            item { Text("Build v0.3.1 • UI PREVIEW • BUILD 12", color = muted) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CockpitGauge(title: String, gauge: GaugeValue, scale: String, max: Float, accent: Color, modifier: Modifier) {
        Card(modifier.fillMaxHeight().padding(vertical = 7.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111B27))) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(title, color = Color(0xFF8FA6B9), style = MaterialTheme.typography.labelLarge)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 12.dp.toPx()
                        val arc = Size(size.width * .82f, size.height * 1.45f)
                        val top = androidx.compose.ui.geometry.Offset((size.width - arc.width) / 2f, size.height * .22f)
                        drawArc(Color(0xFF263747), 180f, 180f, false, top, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                        val fraction = (gauge.value.toFloatOrNull()?.div(max) ?: 0f).coerceIn(0f, 1f)
                        if (gauge.available) drawArc(accent, 180f, 180f * fraction, false, top, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(if (gauge.available) gauge.value else "—", color = Color.White, style = MaterialTheme.typography.displayMedium)
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
        return (device.name ?: "Bluetooth OBD adapter") to Unit
    }

    @Synchronized
    private fun sendCommand(command: String): String {
        val s = socket ?: throw IOException("Not connected")
        s.outputStream.write((command + "\r").toByteArray(Charsets.US_ASCII))
        s.outputStream.flush()
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < deadline) {
            if (s.inputStream.available() > 0) {
                val b = s.inputStream.read()
                if (b == -1) break
                if (b.toChar() == '>') break
                out.append(b.toChar())
            } else {
                Thread.sleep(15)
            }
        }
        return out.toString().replace("\r", "").replace("\n", "").replace(" ", "").uppercase()
    }

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
            val response = try { sendCommand(command) } catch (_: Exception) { "" }
            val bytes = response.substringAfter("41" + command.substring(2), "")
            val parsed = parsePid(command, bytes)
            GaugeValue(label, parsed ?: "--", unit, parsed != null)
        }
        val map = results.associateBy { it.label }
        val mapPressure = map["Manifold pressure"]?.value?.toDoubleOrNull()
        val boost = if (mapPressure != null) {
            val baroResponse = try { sendCommand("0133") } catch (_: Exception) { "" }
            val baroBytes = baroResponse.substringAfter("4133", "")
            val baro = baroBytes.take(2).toIntOrNull(16)?.toDouble()
            if (baro != null) GaugeValue("Turbo boost", "%.1f".format((mapPressure - baro).coerceAtLeast(0.0)), "kPa", true)
            else GaugeValue("Turbo boost", "--", "kPa", false)
        } else GaugeValue("Turbo boost", "--", "kPa", false)
        return results.take(4) + listOf(map["Manifold pressure"]!!) + boost + results.drop(5)
    }

    private fun decodeTroubleCodes(response: String, mode: String): List<String> {
        val prefix = when (mode) {
            "03" -> "43"
            "07" -> "47"
            "0A" -> "4A"
            else -> return emptyList()
        }
        val payload = response.substringAfter(prefix, "")
        if (payload.isBlank() || payload.contains("NODATA") || payload.contains("UNABLE")) return emptyList()
        val hex = payload.filter { it in "0123456789ABCDEF" }
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
        val bytes = data.take(4)
        if (bytes.length < 2) return null
        val a = bytes.substring(0, 2).toIntOrNull(16) ?: return null
        val b = if (bytes.length >= 4) bytes.substring(2, 4).toIntOrNull(16) ?: 0 else 0
        return when (command) {
            "010C" -> "%.0f".format((a * 256 + b) / 4.0)
            "0105", "010F" -> (a - 40).toString()
            "010D" -> a.toString()
            "010B" -> a.toString()
            "0110" -> "%.2f".format((a * 256 + b) / 100.0)
            "0104", "0111" -> "%.1f".format(a * 100.0 / 255.0)
            else -> null
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    override fun onDestroy() {
        closeSocket()
        super.onDestroy()
    }
}
