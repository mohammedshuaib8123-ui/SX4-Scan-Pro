package com.sx4scanpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbManager
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioAttributes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.shape.RoundedCornerShape
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

private const val RECEIVER_PACKAGE = "com.andrerinas.headunitrevived"
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class GaugeValue(val label: String, val value: String, val unit: String = "", val available: Boolean = true, val capturedAtMs: Long = System.currentTimeMillis())
data class TroubleCodeResult(val category: String, val code: String)
private data class DtcScanSummary(val codes: List<TroubleCodeResult>, val supportedModes: Int, val noDataModes: Int, val invalidModes: Int, val failedModes: Int, val partialModes: Int = 0)

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    private var socket: BluetoothSocket? = null
    private var supportedPids: Set<Int> = emptySet()
    private val engineStartDetector = EngineStartDetector()
    // Empty until verified SX4-specific rules are approved; pipeline is wired but inactive.
    private val warningEngine = WarningEngine()
    private var speechReady = false
    private var textToSpeech: TextToSpeech? = null
    private var chime: ToneGenerator? = null
    private var cabinChimeTrack: AudioTrack? = null
    private var welcomeOverlayVisible by mutableStateOf(false)
    @Volatile private var userInitiatedDisconnect = false
    private val traffic = TrafficLog()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark system bars with light icons, content drawn edge to edge; the dash stays awake.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)

        textToSpeech = TextToSpeech(this) { result ->
            speechReady = result == TextToSpeech.SUCCESS
            if (speechReady) {
                textToSpeech?.language = Locale.UK
                val installedEnglishFemaleVoice = textToSpeech?.voices
                    ?.firstOrNull { it.locale.language == "en" && it.name.contains("female", ignoreCase = true) }
                if (installedEnglishFemaleVoice != null) {
                    textToSpeech?.voice = installedEnglishFemaleVoice
                }
                textToSpeech?.setSpeechRate(0.92f)
                textToSpeech?.setPitch(1.0f)
                if (welcomePending && welcomeChimeFinished) speakEngineWelcome()
            }
        }
        chime = ToneGenerator(AudioManager.STREAM_MUSIC, 72)
        setContent { MaterialTheme { Dashboard() } }
        // Welcome sequence when the app opens (it also plays on a confirmed engine start).
        if (savedInstanceState == null) mainHandler.postDelayed({ playEngineWelcome() }, 900L)
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
        var selectedTab by rememberSaveable { mutableStateOf(0) }
        var dtcStatus by remember { mutableStateOf("Connect the Vgate adapter to read engine ECU fault codes.") }
        var dtcCodes by remember { mutableStateOf<List<TroubleCodeResult>>(emptyList()) }
        var scanningCodes by remember { mutableStateOf(false) }
        var headUnitStatus by remember { mutableStateOf("Open Headunit runs as a separate receiver app.") }
        var readiness by remember { mutableStateOf(computeHeadUnitReadiness()) }
        var logPreview by remember { mutableStateOf<List<String>>(emptyList()) }
        var warningPreview by remember { mutableStateOf(false) }
        var warningMessage by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val connected = status == "Connected"
        val connecting = status == "Connecting…"
        val haptic = LocalHapticFeedback.current
        val history = remember { GaugeHistory() }
        var historyVersion by remember { mutableStateOf(0) }
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        // One-second clock so readings that stop updating are marked STALE instead of looking live.
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                nowMs = System.currentTimeMillis()
            }
        }
        // Plugging or unplugging the phone updates the Head Unit checks immediately.
        DisposableEffect(Unit) {
            val usbReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    readiness = computeHeadUnitReadiness()
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(this@MainActivity, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { unregisterReceiver(usbReceiver) }
        }
        // Coming back from installing the receiver, or opening the tab, re-checks everything.
        LaunchedEffect(selectedTab) { if (selectedTab == 2) readiness = computeHeadUnitReadiness() }

        fun disconnect() {
            userInitiatedDisconnect = true
            closeSocket()
            status = "Disconnected"
            gauges = gauges.map { it.copy(value = "--", available = false) }
        }
        fun connect() {
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                status = "Bluetooth permission required — allow it, then tap Connect again"
                permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
            scope.launch {
                disconnect()
                userInitiatedDisconnect = false
                history.clear()
                status = "Connecting…"
                try {
                    val result = withContext(Dispatchers.IO) { connectAndRead() }
                    adapterName = result.first
                    status = "Connected"
                    while (isActive && socket?.isConnected == true) {
                        gauges = withContext(Dispatchers.IO) { readDashboard() }
                        gauges.forEach { g -> history.record(g.label, if (g.available) g.value.toDoubleOrNull() else null) }
                        historyVersion++
                        val rpmGauge = gauges.firstOrNull { it.label == "Engine RPM" }
                        if (engineStartDetector.update(rpmGauge?.value?.toIntOrNull(), rpmGauge?.available == true)) {
                            playEngineWelcome()
                        }
                        val sensorReadings = gauges.mapNotNull { gauge ->
                            val sensor = when (gauge.label) {
                                "Coolant temperature" -> "coolant"
                                "Turbo boost" -> "boost"
                                "MAF airflow" -> "maf"
                                "Calculated engine load" -> "load"
                                "Engine RPM" -> "rpm"
                                else -> null
                            } ?: return@mapNotNull null
                            sensor to SensorReading(
                                value = gauge.value.toDoubleOrNull(),
                                capturedAtMs = gauge.capturedAtMs,
                                supported = gauge.available,
                                valid = gauge.available
                            )
                        }.toMap()
                        warningEngine.evaluate(sensorReadings, System.currentTimeMillis()).forEach { event ->
                            warningMessage = "⚠ ${event.severity}: ${event.ruleId.replace('-', ' ').uppercase()}"
                            val spoken = if (event.severity == WarningSeverity.CRITICAL) "Critical warning. Please stop safely and check the vehicle." else "Caution. Please check the vehicle condition."
                            chime?.startTone(ToneGenerator.TONE_PROP_NACK, 520)
                            if (speechReady) textToSpeech?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "sx4_warning_${event.ruleId}")
                            mainHandler.postDelayed({ warningMessage = "" }, 8000)
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
                    // Tapping DISCONNECT closes the socket mid-read; that is not a failure.
                    status = if (userInitiatedDisconnect) "Disconnected" else (e.message ?: "Connection failed")
                    if (!userInitiatedDisconnect) gauges = gauges.map { it.copy(value = "--", available = false) }
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
                    val summary = withContext(Dispatchers.IO) { readTroubleCodes() }
                    dtcCodes = summary.codes
                    dtcStatus = when {
                        summary.supportedModes == 0 ->
                            "The engine ECU did not return a valid response for modes 03 / 07 / 0A. Check protocol support and adapter communication."
                        summary.partialModes > 0 ->
                            "Some reply lines from the engine ECU could not be read, so this list may be incomplete. Scan again before relying on it. Other modules were not scanned."
                        summary.codes.isNotEmpty() ->
                            "Valid responses from ${summary.supportedModes}/3 engine ECU modes. BCM, ABS, airbag and other modules were not scanned."
                        summary.noDataModes == summary.supportedModes && summary.failedModes == 0 && summary.invalidModes == 0 ->
                            "No DTCs reported by the ${summary.supportedModes} responding engine ECU mode(s). Other modules were not scanned."
                        else ->
                            "Scan summary: ${summary.supportedModes} valid, ${summary.noDataModes} NO DATA, ${summary.invalidModes} unsupported/malformed, ${summary.failedModes} communication failure(s). Engine ECU only; other modules were not scanned."
                    }
                } catch (e: Exception) {
                    dtcStatus = "Scan failed: " + (e.message ?: "communication error")
                    if (socket?.isConnected != true) {
                        closeSocket()
                        supportedPids = emptySet()
                        status = "Disconnected — adapter communication lost"
                        gauges = gauges.map { it.copy(value = "--", available = false) }
                    }
                } finally { scanningCodes = false }
            }
        }

        // Back returns to the cockpit from any other section instead of closing the app.
        BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

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
                            Text("v0.4.0 • PRE-RELEASE • BUILD 22", color = muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                val forward = targetState > initialState
                                (fadeIn(tween(180)) + slideInHorizontally(
                                    animationSpec = tween(220),
                                    initialOffsetX = { width -> if (forward) width / 5 else -width / 5 }
                                )) togetherWith
                                    (fadeOut(tween(140)) + slideOutHorizontally(
                                        animationSpec = tween(180),
                                        targetOffsetX = { width -> if (forward) -width / 5 else width / 5 }
                                    ))
                            },
                            label = "cockpit-page-slide-transition"
                        ) { page ->
                            when (page) {
                        0 -> Column(
                            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column {
                                    Text("SX4 / DDiS 1.3", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                                    Text("DRIVER INFORMATION SYSTEM  •  LIVE ECU TELEMETRY", color = muted, fontSize = 13.sp, letterSpacing = 1.4.sp)
                                }
                                Surface(color = if (connected) Color(0xFF0B332F) else Color(0xFF252B35), shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(1.dp, if (connected) Color(0xFF26D7B1) else Color(0xFF566274))) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        LinkDot(connected = connected, connecting = connecting)
                                        Text(if (connected) "ECU LINK ACTIVE" else if (connecting) "CONNECTING…" else "ECU DISCONNECTED", color = if (connected) Color(0xFF70F0D2) else muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    }
                                }
                            }
                            AnimatedVisibility(visible = warningPreview || warningMessage.isNotBlank(), enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 8 }, exit = fadeOut(tween(180))) {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF4B171D)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))) {
                                    Text(if (warningMessage.isNotBlank()) warningMessage else "WARNING SYSTEM PREVIEW — NOT A LIVE VEHICLE FAULT", Modifier.padding(12.dp), color = Color.White, fontWeight = FontWeight.Black)
                                }
                            }
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                InstrumentDial("ENGINE SPEED", gauges[0], DialScale.forLabel("Engine RPM"), Color(0xFF42DDF5), nowMs, Modifier.weight(1.12f).fillMaxHeight())
                                InstrumentDial("ROAD SPEED", gauges[1], DialScale.forLabel("Vehicle speed"), Color(0xFFFFB84D), nowMs, Modifier.weight(1f).fillMaxHeight())
                                PowertrainPanel(gauges, history, historyVersion, nowMs, Color(0xFF42DDF5), Modifier.weight(.72f).fillMaxHeight())
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (connected) "V-GATE / OBD-II • CONNECTED" else "V-GATE / OBD-II • STANDBY", color = if (connected) Color(0xFF70F0D2) else muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    val statusIsQuiet = connected || status == "Not connected" || status == "Disconnected"
                                    Text(if (connected) adapterName else status, color = if (statusIsQuiet) muted else Color(0xFFFFB84D), fontSize = 13.sp, maxLines = 3)
                                }
                                OutlinedButton(onClick = { connect() }, enabled = !connected, shape = RoundedCornerShape(12.dp)) { Text("CONNECT ADAPTER", fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { disconnect() }, enabled = connected, shape = RoundedCornerShape(12.dp)) { Text("DISCONNECT", fontWeight = FontWeight.Bold) }
                            }
                            Text("Informational display only • Do not operate the tablet while driving", color = Color(0xFF718397), fontSize = 13.sp)
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
                                        if (scanningCodes) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cyan)
                                        Text(dtcStatus, color = muted)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("LIVE DATA CAPABILITY", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("${supportedPids.size} standard Mode 01 PIDs advertised by the ECU", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(
                                            if (supportedPids.isEmpty()) "Connect the adapter to discover which standard parameters the ECU advertises."
                                            else supportedPids.sorted().joinToString("  •  ") { "01%02X".format(it) },
                                            color = muted
                                        )
                                        Text("Advertised support does not guarantee every reading will return valid data. Suzuki-specific modules are not included.", color = muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (dtcCodes.isEmpty()) item { Text("No returned DTCs to display yet.", color = muted) }
                            else items(dtcCodes) { result ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(result.code, color = Color(0xFFFFB84D), style = MaterialTheme.typography.titleLarge)
                                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                            Text(result.category, color = cyan, fontWeight = FontWeight.Bold)
                                            Text("ENGINE ECU", color = muted, style = MaterialTheme.typography.labelSmall)
                                        }
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
                        2 -> LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1B2A)), border = androidx.compose.foundation.BorderStroke(1.dp, cyan.copy(alpha = .55f))) {
                                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            Column {
                                                Text("SX4 HEAD UNIT", color = cyan, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                                                Text("ANDROID AUTO RECEIVER", color = muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp)
                                            }
                                            Text("▣", color = cyan, fontSize = 34.sp)
                                        }
                                        Text("Android Auto runs through the Open Headunit receiver, which SX4 Scan Pro opens beside the OBD cockpit. The receiver is a separate app and is not embedded.", color = muted)
                                        Text(readiness.nextStep, color = if (readiness.readyToStart) cyan else Color(0xFFFFB84D), fontWeight = FontWeight.Bold)
                                        readiness.checks.forEach { check ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(if (check.state == CheckState.OK) "READY" else "ACTION", color = if (check.state == CheckState.OK) cyan else Color(0xFFFFB84D), fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(check.title, color = Color.White, fontWeight = FontWeight.Bold)
                                                    Text(check.detail, color = muted, style = MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                        Button(
                                            onClick = { headUnitStatus = launchReceiver(adjacent = true) },
                                            enabled = readiness.receiverInstalled,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("OPEN BESIDE COCKPIT (SPLIT SCREEN)") }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            OutlinedButton(
                                                onClick = { headUnitStatus = launchReceiver(adjacent = false) },
                                                enabled = readiness.receiverInstalled,
                                                modifier = Modifier.weight(1f)
                                            ) { Text("OPEN FULL SCREEN") }
                                            OutlinedButton(
                                                onClick = { readiness = computeHeadUnitReadiness(); headUnitStatus = "Checks refreshed." },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("RECHECK") }
                                        }
                                        if (!readiness.receiverInstalled) {
                                            OutlinedButton(
                                                onClick = { headUnitStatus = openReceiverStorePage() },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("GET THE RECEIVER (PLAY STORE)") }
                                        }
                                        Text(headUnitStatus, color = Color(0xFFFFB84D), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("WIRED CONNECTION • FIRST BUILD TARGET", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        listOf(
                                            "01  PHONE" to "Enable Android Auto and unlock your phone before connecting.",
                                            "02  USB DATA" to "Use a known-good data cable and compatible USB OTG adapter or powered hub.",
                                            "03  TABLET" to "Enable OTG on the Realme Pad 2 if required and accept the USB permission prompt.",
                                            "04  PROJECTION" to "Confirm the receiver opens, projection appears and touch input responds.",
                                            "05  AUDIO" to "Route sound to the SX4 stereo separately over a supported Bluetooth or AUX input.",
                                            "06  RECONNECT" to "Test unplug/replug, screen-off, app switching and tablet charging."
                                        ).forEach { step ->
                                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(step.first, color = Color(0xFFFFB84D), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                                Text(step.second, color = muted, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("USB HOST STATUS", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Checks Android's currently enumerated USB devices. Detection alone does not confirm Android Auto compatibility or receiver permission.", color = muted, style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(onClick = { readiness = computeHeadUnitReadiness() }, modifier = Modifier.fillMaxWidth()) { Text("REFRESH USB DEVICES") }
                                        Text(if (readiness.usbDevices.isEmpty()) "No USB device currently enumerated." else readiness.usbDevices.joinToString("\n") { it.label }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("COMPATIBILITY CHECKLIST", color = Color(0xFFFFB84D), style = MaterialTheme.typography.titleMedium)
                                        Text("Not yet verified on this exact Realme Pad 2 + phone + SX4 setup: USB host mode, projection, touch, audio routing, reconnect and charging. This screen does not claim a live USB connection.", color = muted)
                                        Text("Receiver: Open Headunit • separate app • AGPL-3.0", color = muted, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
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
                                        Text("Engine-start welcome: two-tone cabin chime followed by a calm-paced English announcement. Automatic playback requires two fresh positive RPM samples; Bluetooth connection alone never triggers it.", color = muted)
                                        Button(onClick = { playEngineWelcome() }, modifier = Modifier.fillMaxWidth()) {
                                            Text("PREVIEW WELCOME ANNOUNCEMENT")
                                        }
                                        Text("Preview plays the same chime and announcement without requiring the engine to start. The app prefers an installed English voice identified as female; if none is available, Android uses its English (UK) default voice.", color = muted)
                                        Divider(color = Color(0xFF26394C))
                                        Text("WARNING ALERT OUTPUT TEST", color = Color(0xFFFFB84D), style = MaterialTheme.typography.titleMedium)
                                        Text("Tests the dashboard warning banner, alert tone and spoken message. This is a manual preview only; no SX4 warning limits are enabled yet.", color = muted)
                                        Button(onClick = {
                                            warningPreview = true
                                            chime?.startTone(ToneGenerator.TONE_PROP_NACK, 520)
                                            if (speechReady) textToSpeech?.speak("Warning. Please check the vehicle condition.", TextToSpeech.QUEUE_FLUSH, null, "sx4_warning_preview")
                                            mainHandler.postDelayed({ warningPreview = false }, 2200)
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Text("PREVIEW WARNING ALERT")
                                        }
                                        Text("Configured automatic warning rules: 0 — awaiting verified SX4 specifications.", color = muted)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("ADAPTER LOG", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Exactly what was sent to and received from the adapter. If a drive test goes wrong, share this log so the real ECU replies can be checked.", color = muted, style = MaterialTheme.typography.bodyMedium)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            OutlinedButton(onClick = { logPreview = traffic.tail(14) }, modifier = Modifier.weight(1f)) { Text("REFRESH") }
                                            Button(onClick = {
                                                val header = "SX4 Scan Pro adapter log\n" + "v0.4.0 • PRE-RELEASE • BUILD 22" + "\nStatus: " + status + "\nStandard PIDs advertised: " + supportedPids.size
                                                val send = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, traffic.asText(header))
                                                }
                                                startActivity(Intent.createChooser(send, "Share adapter log"))
                                            }, modifier = Modifier.weight(1f)) { Text("SHARE LOG") }
                                        }
                                        Text(if (logPreview.isEmpty()) "Tap REFRESH to show the latest adapter traffic." else logPreview.joinToString("\n"), color = Color.White, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    }
                                }
                            }
                            item { Text("Build v0.4.0 • PRE-RELEASE • BUILD 22", color = muted) }
                        }
                            }
                        }
                        Crossfade(targetState = welcomeOverlayVisible, animationSpec = tween(550), label = "welcome-overlay-fade") { visible ->
                            if (visible) {
                                val transition = rememberInfiniteTransition(label = "sx4-flight-3d")
                                val yaw by transition.animateFloat(-24f, 24f, infiniteRepeatable(tween(1800, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "aircraft-yaw")
                                val pitch by transition.animateFloat(-8f, 8f, infiniteRepeatable(tween(1250, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "aircraft-pitch")
                                val glow by transition.animateFloat(.28f, .9f, infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "aircraft-glow")
                                Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { welcomeOverlayVisible = false }.background(Brush.radialGradient(listOf(Color(0xFF153C55), Color(0xF9050A12), Color(0xFF03060B)))), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Column(Modifier.fillMaxWidth(.86f).padding(22.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("SX4 FLIGHT SYSTEMS", color = Color(0xFF8BAFC5), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                                        Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                            Canvas(Modifier.fillMaxSize()) {
                                                val cx = size.width / 2f
                                                val cy = size.height / 2f
                                                for (ring in 0..3) {
                                                    val radius = size.minDimension * (.18f + ring * .075f)
                                                    drawCircle(Color(0xFF42DDF5).copy(alpha = glow * (.30f - ring * .045f)), radius, androidx.compose.ui.geometry.Offset(cx, cy), style = Stroke(1.2.dp.toPx()))
                                                }
                                                drawLine(Color(0xFF42DDF5).copy(alpha = .35f), androidx.compose.ui.geometry.Offset(0f,cy), androidx.compose.ui.geometry.Offset(size.width,cy), 1.dp.toPx())
                                                drawLine(Color(0xFF42DDF5).copy(alpha = .25f), androidx.compose.ui.geometry.Offset(cx,0f), androidx.compose.ui.geometry.Offset(cx,size.height), 1.dp.toPx())
                                            }
                                            Canvas(Modifier.size(150.dp).graphicsLayer { rotationY = yaw; rotationX = pitch; rotationZ = -7f; cameraDistance = 18f * density }) {
                                                val w = size.width
                                                val h = size.height
                                                val plane = Path().apply {
                                                    moveTo(w * .50f, h * .04f)
                                                    lineTo(w * .56f, h * .30f)
                                                    lineTo(w * .96f, h * .58f)
                                                    lineTo(w * .96f, h * .68f)
                                                    lineTo(w * .56f, h * .54f)
                                                    lineTo(w * .54f, h * .80f)
                                                    lineTo(w * .70f, h * .92f)
                                                    lineTo(w * .70f, h * .98f)
                                                    lineTo(w * .50f, h * .92f)
                                                    lineTo(w * .30f, h * .98f)
                                                    lineTo(w * .30f, h * .92f)
                                                    lineTo(w * .46f, h * .80f)
                                                    lineTo(w * .44f, h * .54f)
                                                    lineTo(w * .04f, h * .68f)
                                                    lineTo(w * .04f, h * .58f)
                                                    lineTo(w * .44f, h * .30f)
                                                    close()
                                                }
                                                drawPath(plane, Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFF8FD8EE))))
                                                drawPath(plane, Color(0xFF42DDF5).copy(alpha = glow), style = Stroke(2.dp.toPx()))
                                            }
                                        }
                                        Text("WELCOME ABOARD", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                                        Text("SX4 AIRLINES 2874", color = Color(0xFFFFC46B), fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.4.sp)
                                        Text("ENGINE START CONFIRMED  •  SYSTEMS ONLINE", color = Color(0xFF8BAFC5), fontSize = 13.sp, letterSpacing = 1.5.sp)
                                        Box(Modifier.fillMaxWidth(.52f).height(2.dp).background(Color(0xFF42DDF5).copy(alpha = glow), RoundedCornerShape(3.dp)))
                                        Text("TAP ANYWHERE TO SKIP", color = Color(0xFF8BAFC5), fontSize = 13.sp, letterSpacing = 1.5.sp)
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(84.dp)
                            .background(Color(0xFF070E18))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Cockpit", "Diagnostics", "Head Unit", "Settings").forEachIndexed { i, label ->
                            val selected = selectedTab == i
                            val dockColor by animateColorAsState(
                                targetValue = if (selected) Color(0xFF123447) else Color(0xFF101B28),
                                animationSpec = tween(240),
                                label = "dock-item-color-$i"
                            )
                            val iconColor by animateColorAsState(
                                targetValue = if (selected) cyan else muted,
                                animationSpec = tween(240),
                                label = "dock-icon-color-$i"
                            )
                            val iconScale by animateFloatAsState(
                                targetValue = if (selected) 1.2f else 1f,
                                animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
                                label = "dock-icon-scale-$i"
                            )
                            val indicatorProgress by animateFloatAsState(
                                targetValue = if (selected) 1f else 0f,
                                animationSpec = tween(280),
                                label = "dock-indicator-$i"
                            )
                            Surface(
                                onClick = {
                                    if (selectedTab != i) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTab = i
                                },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                shape = RoundedCornerShape(14.dp),
                                color = dockColor,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) cyan.copy(alpha = 0.75f) else Color(0xFF26394C)
                                )
                            ) {
                                Column(
                                    Modifier.fillMaxSize().padding(vertical = 4.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = listOf(Icons.Outlined.Speed, Icons.Outlined.Build, Icons.Outlined.DirectionsCar, Icons.Outlined.Settings)[i],
                                        contentDescription = null,
                                        tint = iconColor,
                                        modifier = Modifier.size(26.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                                    )
                                    Text(
                                        label.uppercase(),
                                        color = if (selected) Color.White else muted,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                                        letterSpacing = 0.7.sp,
                                        maxLines = 1
                                    )
                                    Box(
                                        Modifier.fillMaxWidth(0.62f).height(3.dp)
                                            .background(Color(0xFF26394C), RoundedCornerShape(3.dp))
                                    ) {
                                        Box(
                                            Modifier.fillMaxWidth(indicatorProgress).height(3.dp)
                                                .background(cyan, RoundedCornerShape(3.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CockpitGauge(title: String, gauge: GaugeValue, scale: String, max: Float, accent: Color, modifier: Modifier) {
        val targetFraction = if (gauge.available) (gauge.value.toFloatOrNull()?.div(max) ?: 0f).coerceIn(0f, 1f) else 0f
        val animatedFraction by animateFloatAsState(targetFraction, animationSpec = tween(650), label = "dial-sweep")
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
                        if (gauge.available) {
                            drawArc(accent.copy(alpha = 0.18f), 180f, 180f * animatedFraction, false, top, arc, style = Stroke(stroke * 2.8f, cap = StrokeCap.Round))
                            drawArc(accent, 180f, 180f * animatedFraction, false, top, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                            val cx = size.width / 2f; val cy = size.height * 0.92f
                            for (tick in 0..20) {
                                val angle = Math.PI * (1.0 + tick / 20.0)
                                val r1 = size.width * 0.34f; val r2 = r1 + if (tick % 5 == 0) 12.dp.toPx() else 5.dp.toPx()
                                drawLine(if (tick % 5 == 0) accent else Color(0xFF607589), androidx.compose.ui.geometry.Offset(cx + kotlin.math.cos(angle).toFloat() * r1, cy + kotlin.math.sin(angle).toFloat() * r1), androidx.compose.ui.geometry.Offset(cx + kotlin.math.cos(angle).toFloat() * r2, cy + kotlin.math.sin(angle).toFloat() * r2), strokeWidth = if (tick % 5 == 0) 2.dp.toPx() else 1.dp.toPx())
                            }
                            val needleAngle = Math.PI * (1.0 + animatedFraction)
                            drawLine(Color.White, androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(cx + kotlin.math.cos(needleAngle).toFloat() * size.width * 0.31f, cy + kotlin.math.sin(needleAngle).toFloat() * size.width * 0.31f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                            drawCircle(accent, radius = 6.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx, cy))
                        }
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        AnimatedContent(
                            targetState = if (gauge.available) gauge.value else "—",
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(130)) },
                            label = "instrument-value"
                        ) { reading ->
                            Text(reading, color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
                        }
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
        val bonded = adapter.bondedDevices.toList()
        val candidates = bonded.filter { EcuNegotiation.looksLikeObdAdapter(it.name) }
        if (candidates.isEmpty()) {
            val names = bonded.mapNotNull { it.name }.joinToString(", ").ifBlank { "none" }
            throw IOException("No recognised OBD adapter among your paired devices ($names). Pair the Vgate adapter in Bluetooth settings, then try again.")
        }
        if (candidates.size > 1) {
            throw IOException("Multiple OBD adapters are paired. Keep only the intended Vgate adapter paired.")
        }
        val device: BluetoothDevice = candidates.single()
        adapter.cancelDiscovery()
        socket = openSocket(device)
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { sendCommand(it) }
        // "Connected" must mean the ECU answered, not just that the adapter did.
        val negotiation = EcuNegotiation { command, timeoutMs -> sendCommand(command, timeoutMs) }.run()
        if (negotiation.supportedPids.isEmpty()) {
            closeSocket()
            throw IOException(negotiation.failure)
        }
        supportedPids = negotiation.supportedPids
        return (device.name ?: "Bluetooth OBD adapter") to Unit
    }

    /** Secure SPP first, then insecure SPP: many ELM327 clones only accept the second. */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val makers = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
        )
        var last: IOException? = null
        for (make in makers) {
            val candidate = make()
            try {
                candidate.connect()
                return candidate
            } catch (e: IOException) {
                last = e
                try { candidate.close() } catch (_: Exception) { }
            }
        }
        throw IOException("Could not open the Bluetooth link to the adapter (${last?.message ?: "refused"}). Check it is plugged in, the ignition is on and no other app is using it.")
    }

    @Synchronized
    private fun sendCommand(command: String, timeoutMs: Long = 5000, keepLines: Boolean = false): String {
        val s = socket ?: throw IOException("Not connected")
        if (!s.isConnected) throw IOException("OBD adapter connection is closed")

        // A timed-out transaction makes the byte stream ambiguous. Never send another
        // command on that stream; close it so a late ECU reply cannot be misread.
        try {
            val input = s.inputStream
            // Drain bytes already waiting from a completed/abandoned transaction.
            // This is bounded to avoid hanging if an adapter continuously streams data.
            var drained = 0
            while (input.available() > 0 && drained < 4096) {
                if (input.read() == -1) throw IOException("OBD adapter closed the connection")
                drained++
            }

            s.outputStream.write((command + "\r").toByteArray(Charsets.US_ASCII))
            s.outputStream.flush()
            traffic.add("TX", command)
            val out = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            var promptReceived = false
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val b = input.read()
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
            if (!promptReceived) throw IOException("Timed out waiting for OBD response to $command")
            traffic.add("RX", out.toString().trim().replace("\r", " ⏎ "))
            // DTC replies keep one line per ECU frame so multi-frame answers can be decoded;
            // everything else is compacted as before.
            val response = if (keepLines) {
                out.toString().uppercase().split('\r', '\n')
                    .map { line -> line.filterNot { it.isWhitespace() } }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
            } else {
                out.toString().replace("\r", "").replace("\n", "").replace(" ", "").uppercase()
            }
            // A prompt-terminated "?" or STOPPED is a complete adapter response,
            // not a broken byte stream. Return it so one unsupported DTC mode does
            // not abort the remaining read-only mode checks.
            return response
        } catch (e: Exception) {
            traffic.add("ERR", "$command: ${e.message ?: e.javaClass.simpleName}")
            closeSocket()
            throw if (e is IOException) e else IOException("OBD transaction failed for $command", e)
        }
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
                val response = sendCommand(command)
                val parsed = parsePid(command, response)
                GaugeValue(label, parsed ?: "--", unit, parsed != null)
            }
        }
        val map = results.associateBy { it.label }
        val mapPressure = map["Manifold pressure"]?.value?.toDoubleOrNull()
        val boost = if (mapPressure != null) {
            val baroResponse = if (51 in supportedPids) sendCommand("0133") else ""
            val baro = ObdPidResponseDecoder.decode("0133", baroResponse)?.toDoubleOrNull()
            if (baro != null) GaugeValue("Turbo boost", String.format(java.util.Locale.US, "%.1f", (mapPressure - baro).coerceAtLeast(0.0)), "kPa", true)
            else GaugeValue("Turbo boost", "--", "kPa", false)
        } else GaugeValue("Turbo boost", "--", "kPa", false)
        return results.take(4) + listOf(map["Manifold pressure"]!!) + boost + results.drop(5)
    }

    // DTC modes are one transaction: do not interleave them with live PID polling.
    @Synchronized
    private fun readTroubleCodes(): DtcScanSummary {
        val modes = listOf("03" to "STORED", "07" to "PENDING", "0A" to "PERMANENT")
        var supported = 0
        var noData = 0
        var invalid = 0
        var failed = 0
        var partial = 0
        val codes = mutableListOf<TroubleCodeResult>()
        for ((mode, category) in modes) {
            val raw = try {
                sendCommand(mode, 10000, keepLines = true)
            } catch (e: IOException) {
                failed++
                if (socket?.isConnected != true) break
                continue
            }
            // Line-aware: more than three codes arrive as several frames, and each is checked
            // with the same strict rules as before. Unreadable lines are counted, never dropped.
            val read = ObdTroubleCodeDecoder.decodeResponse(raw, mode)
            when {
                read.validFrames > 0 -> {
                    supported++
                    codes += read.codes.map { TroubleCodeResult(category, it) }
                    if (read.unreadable.isNotEmpty()) partial++
                }
                read.noData && read.unreadable.isEmpty() -> noData++
                else -> invalid++
            }
        }
        return DtcScanSummary(codes.distinct(), supported, noData, invalid, failed, partial)
    }

    private fun decodeTroubleCodes(response: String, mode: String): List<String> =
        ObdTroubleCodeDecoder.decode(response, mode)

    private fun parsePid(command: String, response: String): String? =
        ObdPidResponseDecoder.decode(command, response)

    @Volatile private var welcomePending = false
    @Volatile private var welcomeChimeFinished = false

    private fun playEngineWelcome() {
        welcomePending = true
        welcomeChimeFinished = false
        welcomeOverlayVisible = true
        mainHandler.postDelayed({ welcomeOverlayVisible = false }, 6500L)
        playAircraftCabinChime()
        mainHandler.postDelayed({
            welcomeChimeFinished = true
            if (speechReady) speakEngineWelcome()
        }, 980L)
    }

    private fun playAircraftCabinChime() {
        // Original synthesized ascending three-note cabin chime; no sampled airline audio.
        try {
            val sampleRate = 44100
            val notes = doubleArrayOf(880.0, 1174.66, 1396.91)
            val noteFrames = (sampleRate * 0.24).toInt()
            val gapFrames = (sampleRate * 0.06).toInt()
            val samples = ShortArray(notes.size * (noteFrames + gapFrames))
            var cursor = 0
            notes.forEach { frequency ->
                repeat(noteFrames) { frame ->
                    val attack = (frame / (sampleRate * 0.025)).coerceIn(0.0, 1.0)
                    val release = ((noteFrames - frame) / (sampleRate * 0.055)).coerceIn(0.0, 1.0)
                    val envelope = minOf(attack, release)
                    val phase = 2.0 * Math.PI * frequency * frame / sampleRate
                    val tone = (kotlin.math.sin(phase) * 0.78 + kotlin.math.sin(phase * 2.0) * 0.12) * envelope * 0.22
                    samples[cursor++] = (tone * Short.MAX_VALUE).toInt().toShort()
                }
                repeat(gapFrames) { samples[cursor++] = 0 }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            cabinChimeTrack?.release()
            cabinChimeTrack = track
            track.setVolume(0.72f)
            track.write(samples, 0, samples.size)
            track.play()
            mainHandler.postDelayed({
                runCatching { track.stop() }
                track.release()
                if (cabinChimeTrack === track) cabinChimeTrack = null
            }, samples.size * 1000L / sampleRate + 150L)
        } catch (_: Exception) {
            chime?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            mainHandler.postDelayed({ chime?.startTone(ToneGenerator.TONE_PROP_ACK, 180) }, 220L)
        }
    }

    private fun speakEngineWelcome() {
        if (!speechReady || !welcomePending || !welcomeChimeFinished) return
        welcomePending = false
        welcomeChimeFinished = false
        textToSpeech?.speak("Welcome to SX4 Airlines 2874", TextToSpeech.QUEUE_FLUSH, null, "sx4_engine_start")
    }

    private fun computeHeadUnitReadiness(): HeadUnitReadiness {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values.map { d ->
            val name = runCatching { d.productName }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.deviceName
            UsbSeen(name, d.vendorId, d.productId)
        }
        return HeadUnitReadiness(
            receiverInstalled = packageManager.getLaunchIntentForPackage(RECEIVER_PACKAGE) != null,
            usbHostFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            usbDevices = devices
        )
    }

    /** Opens the Android Auto receiver, optionally in the other half of split screen. Returns the status line. */
    private fun launchReceiver(adjacent: Boolean): String {
        val launch = packageManager.getLaunchIntentForPackage(RECEIVER_PACKAGE)
            ?: return "Open Headunit is not installed. Use GET THE RECEIVER, then tap RECHECK."
        if (adjacent && !isInMultiWindowMode) {
            return "Split screen is not active. Put SX4 Scan Pro into split screen first, then tap this again, or use OPEN FULL SCREEN."
        }
        if (adjacent) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return try {
            startActivity(launch)
            if (adjacent) "Receiver opened beside the cockpit. Complete its USB and Android Auto prompts."
            else "Receiver launch requested. Complete its USB and Android Auto prompts."
        } catch (e: Exception) {
            "Could not open the receiver: " + (e.message ?: "unknown error")
        }
    }

    private fun openReceiverStorePage(): String = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$RECEIVER_PACKAGE")))
        "Opening the Play Store page for Open Headunit. Come back and tap RECHECK after installing."
    } catch (e: Exception) {
        "No app store is available on this tablet. Install Open Headunit another way, then tap RECHECK."
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
        cabinChimeTrack?.release()
        cabinChimeTrack = null
        super.onDestroy()
    }
}
