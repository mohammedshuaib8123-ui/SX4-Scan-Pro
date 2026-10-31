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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SX4 Scan Pro") },
                    actions = { Text("v0.1.0", modifier = Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelMedium) }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ADAPTER STATUS", style = MaterialTheme.typography.labelMedium)
                            Text(status, style = MaterialTheme.typography.titleLarge)
                            Text(adapterName, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { connect() }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (status == "Connected") "Reconnect" else "Connect to Vgate")
                            }
                            Text(note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("LIVE ENGINE DATA", style = MaterialTheme.typography.titleMedium) }
                items(gauges) { gauge ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(gauge.label, style = MaterialTheme.typography.bodyMedium)
                                Text(if (gauge.available) gauge.value else "Unavailable", style = MaterialTheme.typography.headlineSmall)
                            }
                            Text(gauge.unit, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("OTHER MODULES", style = MaterialTheme.typography.titleMedium)
                            Text("BCM / body module: Not queried. Generic ELM327 OBD-II access does not guarantee Suzuki BCM support.")
                            Text("Oil pressure, oil temperature and radiator fan status: shown only if a supported ECU data definition is identified; not assumed available.")
                            Text("Use this dashboard while parked or have a passenger operate it. Do not interact with the phone while driving.")
                            OutlinedButton(onClick = { closeSocket(); status = "Disconnected"; note = "Adapter disconnected." }) {
                                Text("Disconnect")
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
