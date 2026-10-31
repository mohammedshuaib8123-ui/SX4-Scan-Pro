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
        var status by remember { mutableStateOf("Not connected") }
        var adapterName by remember { mutableStateOf("No adapter selected") }
        var gauges by remember { mutableStateOf(
            listOf(
                GaugeValue("Engine RPM", "--", "rpm", false),
                GaugeValue("Vehicle speed", "--", "km/h", false),
                GaugeValue("Coolant temperature", "--", "°C", false),
                GaugeValue("Intake air temperature", "--", "°C", false),
                GaugeValue("Manifold pressure", "--", "kPa", false),
                GaugeValue("Turbo boost", "--", "kPa", false),
                GaugeValue("MAF airflow", "--", "g/s", false),
                GaugeValue("Calculated engine load", "--", "%", false),
                GaugeValue("Throttle position", "--", "%", false)
            )
        ) }
        var note by remember { mutableStateOf("Pair your Vgate iCar Pro BT3.0 in Android Bluetooth settings first.") }
        val scope = rememberCoroutineScope()

        fun connect() {
            scope.launch {
                status = "Connecting…"
                try {
                    val result = withContext(Dispatchers.IO) { connectAndRead() }
                    adapterName = result.first
                    status = "Connected"
                    note = "ELM327 initialized. Reading supported standard OBD-II parameters."
                    while (isActive && socket?.isConnected == true) {
                        val updated = withContext(Dispatchers.IO) { readDashboard() }
                        gauges = updated
                        delay(1100)
                    }
                } catch (e: SecurityException) {
                    status = "Permission required"
                    note = "Allow Nearby devices / Bluetooth permission, then retry."
                } catch (e: Exception) {
                    status = "Connection failed"
                    note = e.message ?: "Check adapter power, pairing and ignition."
                    closeSocket()
                }
            }
        }

        var selectedTab by remember { mutableIntStateOf(0) }
        var dtcStatus by remember { mutableStateOf("Connect the Vgate adapter to read ECU fault codes.") }
        var dtcCodes by remember { mutableStateOf<List<String>>(emptyList()) }
        var scanningCodes by remember { mutableStateOf(false) }

        fun scanFaultCodes() {
            if (socket?.isConnected != true) {
                dtcStatus = "Not connected. Fault-code scanning needs the vehicle and adapter."
                return
            }
            scope.launch {
                scanningCodes = true
                dtcStatus = "Reading ECU fault-code modes…"
                try {
                    val found = withContext(Dispatchers.IO) {
                        listOf("03", "07", "0A").flatMap { mode ->
                            val response = sendCommand(mode)
                            decodeTroubleCodes(response, mode)
                        }.distinct()
                    }
                    dtcCodes = found
                    dtcStatus = if (found.isEmpty()) "No codes returned by the ECU, or these modes are unsupported. This is not proof that every vehicle module is fault-free."
                    else "Codes returned by the engine ECU. BCM and other modules are not included."
                } catch (e: Exception) {
                    dtcStatus = "Scan failed: ${e.message ?: "communication error"}"
                } finally {
                    scanningCodes = false
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SX4 Scan Pro") },
                    actions = { Text("v0.2.0 • UI PREVIEW • BUILD 10", modifier = Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelMedium) }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = selectedTab) {
                    listOf("Dashboard", "Fault codes", "Modules").forEachIndexed { index, label ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                    }
                }
                when (selectedTab) {
                    0 -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 158.dp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("ADAPTER", style = MaterialTheme.typography.labelMedium)
                                        Text(if (status == "Connected") "● LIVE" else "○ OFFLINE",
                                            color = if (status == "Connected") Color(0xFF2E9D68) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(status, style = MaterialTheme.typography.titleLarge)
                                    Text(adapterName, style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { connect() }, modifier = Modifier.weight(1f)) {
                                            Text(if (status == "Connected") "Reconnect" else "Connect")
                                        }
                                        OutlinedButton(onClick = { closeSocket(); status = "Disconnected"; note = "Adapter disconnected." }) { Text("Disconnect") }
                                    }
                                    Text(note, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text("ENGINE INSTRUMENTS", style = MaterialTheme.typography.titleMedium)
                        }
                        items(gauges) { gauge -> GaugeCard(gauge) }
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text("Readings depend on ECU support. Unavailable means no valid response was received.", style = MaterialTheme.typography.bodySmall)
                            Text("Use while parked or have a passenger operate it. Do not interact with the phone while driving.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    1 -> LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("ENGINE ECU DIAGNOSTICS", style = MaterialTheme.typography.titleMedium)
                                    Text("Reads standard OBD-II stored (Mode 03), pending (Mode 07) and permanent (Mode 0A) codes when supported by the ECU.")
                                    Button(onClick = { scanFaultCodes() }, enabled = !scanningCodes && status == "Connected", modifier = Modifier.fillMaxWidth()) {
                                        Text(if (scanningCodes) "Scanning…" else "Scan fault codes")
                                    }
                                    Text(dtcStatus, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        if (dtcCodes.isEmpty()) {
                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    Text("No scan results yet", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        } else {
                            items(dtcCodes) { code ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(code, style = MaterialTheme.typography.titleLarge)
                                        Text("Engine ECU", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        item {
                            Text("This first version does not clear codes. It does not scan ABS, airbag, immobilizer or BCM modules.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("BODY CONTROL MODULE (BCM)", style = MaterialTheme.typography.titleMedium)
                                    Text("Not yet supported. Generic ELM327 OBD-II access does not guarantee Suzuki BCM communication.")
                                    Text("Status: Awaiting read-only protocol investigation with the vehicle.")
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ADDITIONAL ENGINE DATA", style = MaterialTheme.typography.titleMedium)
                                    Text("Oil pressure, oil temperature and radiator fan status are not assumed to be available. We'll add them only if the ECU exposes valid data definitions.")
                                    Text("ABS, airbag and other modules will remain marked unsupported until their protocols are verified.")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun connectAndRead(): Pair<String, Unit> {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Bluetooth permission not granted")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("Bluetooth is not supported on this device")
        if (!adapter.isEnabled) throw IOException("Turn on Bluetooth")
        val device: BluetoothDevice = adapter.bondedDevices.firstOrNull {
            val n = it.name.orEmpty()
            n.contains("Vgate", true) || n.contains("iCar", true) || n.contains("OBD", true)
        } ?: adapter.bondedDevices.firstOrNull() ?: throw IOException("No paired OBD adapter. Pair Vgate in Android settings.")
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
