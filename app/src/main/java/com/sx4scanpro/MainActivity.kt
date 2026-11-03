package com.sx4scanpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.hardware.Sensor
import android.view.WindowManager
import androidx.core.content.FileProvider
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.content.IntentCompat
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
//import android.view.WindowManager
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

/** Sensors that already have their own dial or tile; the picker skips them to avoid duplicates. */
private val CORE_PIDS = setOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x2C)

private const val RECEIVER_PACKAGE = "com.andrerinas.headunitrevived"
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class GaugeValue(val label: String, val value: String, val unit: String = "", val available: Boolean = true, val capturedAtMs: Long = System.currentTimeMillis())
data class TroubleCodeResult(val category: String, val code: String)
private data class DtcScanSummary(val codes: List<TroubleCodeResult>, val supportedModes: Int, val noDataModes: Int, val invalidModes: Int, val failedModes: Int, val partialModes: Int = 0)

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshBluetoothAccess()
        refreshPairedDevices()
    }
    private val prefs by lazy { getSharedPreferences("sx4_scan_pro", MODE_PRIVATE) }
    private var btAccess by mutableStateOf(AccessStatus.NEEDS_REQUEST)
    private var scanAccess by mutableStateOf(AccessStatus.NEEDS_REQUEST)
    private var selectedAddress by mutableStateOf<String?>(null)
    private val pairedDevices = mutableStateListOf<BtDevice>()
    private val foundDevices = mutableStateListOf<BtDevice>()
    private var scanning by mutableStateOf(false)
    private var adapterNote by mutableStateOf("")
    private var speedUnit by mutableStateOf(SpeedUnit.KMH)
    private var boostUnit by mutableStateOf(BoostUnit.KPA)
    private var deviceG by mutableStateOf<Double?>(null)
    private var connConfig by mutableStateOf(ConnectionConfig())
    private var extraPids by mutableStateOf<Set<Int>>(emptySet())
    private var alertLimits by mutableStateOf(AlertLimits())
    private var themeMode by mutableStateOf(ThemeMode.AUTO)
    private var lightLux by mutableStateOf<Float?>(null)
    private var phoneIp by mutableStateOf("")
    private var huSession by mutableStateOf(HeadUnitSession.None)
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != HeadUnitProtocol.SESSION_STATE_ACTION) return
            huSession = HeadUnitProtocol.parse(
                intent.getStringExtra("state"), intent.getStringExtra("transport"),
                intent.getStringExtra("reason"), intent.getLongExtra("uptime_ms", 0L)
            )
        }
    }
    private var trip by mutableStateOf(TripStats())
    private var tripTick by mutableStateOf(0)
    private var tripStartMs by mutableStateOf(0L)
    private var tripWriter: java.io.BufferedWriter? = null
    private var tripFile: java.io.File? = null
    private var tripHeaderWritten = false
    private var tripNote by mutableStateOf("")
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) { lightLux = event.values[0] }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
    }
    private var deviceGAtMs by mutableStateOf(0L)
    private val gListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            deviceG = DeviceG.magnitudeG(event.values[0], event.values[1], event.values[2])
            deviceGAtMs = System.currentTimeMillis()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
    }
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    val name = try { device.name } catch (_: SecurityException) { null }
                    if (foundDevices.none { it.address == device.address }) {
                        foundDevices.add(BtDevice(name ?: "Unnamed device", device.address, false))
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    adapterNote = if (foundDevices.isEmpty()) "Scan finished: no devices found. Check the adapter is plugged in and the ignition is on."
                    else "Scan finished. Tap PAIR next to your adapter."
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    refreshPairedDevices()
                    foundDevices.removeAll { f -> pairedDevices.any { it.address == f.address } }
                }
            }
        }
    }
    private var socket: BluetoothSocket? = null
    private var supportedPids: Set<Int> = emptySet()
    private val engineStartDetector = EngineStartDetector()
    // Empty until verified SX4-specific rules are approved; pipeline is wired but inactive.
    @Volatile private var warningEngine = WarningEngine()
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
        selectedAddress = prefs.getString("adapter_address", null)
        connConfig = ConnectionConfig(
            protocol = ConnectionConfig.protocolOf(prefs.getString("obd_protocol", null)),
            insecureFirst = prefs.getBoolean("insecure_first", false),
            adaptiveTiming = prefs.getBoolean("adaptive_timing", true)
        )
        extraPids = PidCatalog.parseSelection(prefs.getString("extra_pids", null))
        alertLimits = AlertLimits(
            coolantMaxC = AlertLimits.parse(prefs.getInt("alert_coolant", -1), AlertLimits.COOLANT),
            rpmMax = AlertLimits.parse(prefs.getInt("alert_rpm", -1), AlertLimits.RPM),
            boostMaxKpa = AlertLimits.parse(prefs.getInt("alert_boost", -1), AlertLimits.BOOST)
        )
        warningEngine = WarningEngine(alertLimits.rules())
        themeMode = NightMode.modeOf(prefs.getString("theme_mode", null))
        phoneIp = prefs.getString("phone_ip", "").orEmpty()
        speedUnit = GaugeUnits.speedUnitOf(prefs.getString("speed_unit", null))
        boostUnit = GaugeUnits.boostUnitOf(prefs.getString("boost_unit", null))
        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, btReceiver, btFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Open Headunit is another app, so its status broadcast needs an exported receiver.
        ContextCompat.registerReceiver(this, sessionReceiver, IntentFilter(HeadUnitProtocol.SESSION_STATE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        refreshBluetoothAccess()
        // Ask straight away on first launch: Android 12+ shows one "Nearby devices" dialog.
        if (btAccess == AccessStatus.NEEDS_REQUEST || scanAccess == AccessStatus.NEEDS_REQUEST && android.os.Build.VERSION.SDK_INT >= 31) {
            requestBluetoothPermissions(forScanning = false)
        }
        refreshPairedDevices()

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
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Dashboard() {
        val bg = Sx.Background
        val panel = Sx.Card
        val cyan = Sx.Primary
        val muted = Sx.MutedForeground
        var status by remember { mutableStateOf("Not connected") }
        var adapterName by remember { mutableStateOf("Vgate iCar Pro • Bluetooth Classic") }
        var gauges by remember { mutableStateOf(listOf(
            GaugeValue("Engine RPM", "--", "rpm", false), GaugeValue("Vehicle speed", "--", "km/h", false),
            GaugeValue("Coolant temperature", "--", "°C", false), GaugeValue("Intake air temperature", "--", "°C", false),
            GaugeValue("Manifold pressure", "--", "kPa", false), GaugeValue("Turbo boost", "--", "kPa", false),
            GaugeValue("MAF airflow", "--", "g/s", false), GaugeValue("Calculated engine load", "--", "%", false),
            GaugeValue("Throttle position", "--", "%", false), GaugeValue("EGR commanded", "--", "%", false)
        )) }
        var selectedTab by rememberSaveable { mutableStateOf(0) }
        var showWall by rememberSaveable { mutableStateOf(false) }
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
            endTrip()
            userInitiatedDisconnect = true
            closeSocket()
            status = "Disconnected"
            gauges = gauges.map { it.copy(value = "--", available = false) }
        }
        fun connect() {
            if (btAccess != AccessStatus.GRANTED) {
                status = if (btAccess == AccessStatus.BLOCKED) BluetoothAccess.message(btAccess)
                else "Bluetooth permission required — allow it, then tap Connect again"
                if (btAccess == AccessStatus.NEEDS_REQUEST) requestBluetoothPermissions(forScanning = false)
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
                    startTrip()
                    // The greeting plays only once the ECU has really answered (connectAndRead throws otherwise).
                    playEngineWelcome()
                    while (isActive && socket?.isConnected == true) {
                        gauges = withContext(Dispatchers.IO) { readDashboard() }
                        gauges.forEach { g -> history.record(g.label, if (g.available) g.value.toDoubleOrNull() else null) }
                        historyVersion++
                        recordTrip(gauges)
                        val rpmGauge = gauges.firstOrNull { it.label == "Engine RPM" }
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
                    endTrip()
                    closeSocket()
                    status = "Permission required"
                } catch (e: Exception) {
                    endTrip()
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

        // Night driving: dim the real backlight from the light sensor (or the owner's Day/Night choice).
        val dim = Math.round(NightMode.dimAlpha(themeMode, lightLux) / 0.05f) * 0.05f
        LaunchedEffect(dim) { applyDim(dim) }

        // Back returns to the cockpit from any other section instead of closing the app.
        BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

        MaterialTheme(colorScheme = darkColorScheme(primary = cyan, secondary = Color(0xFFFFB84D), background = bg, surface = panel, onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = muted)) {
            Scaffold(containerColor = bg) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).background(Sx.Background)) {
                SxTopBar(connected = connected, onPower = { disconnect(); finish() })
                Column(Modifier.weight(1f).fillMaxWidth()) {
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
                            if (btAccess != AccessStatus.GRANTED) {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2412)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB84D))) {
                                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(BluetoothAccess.message(btAccess), color = Color.White, modifier = Modifier.weight(1f))
                                        Button(onClick = {
                                            if (btAccess == AccessStatus.BLOCKED) openAppSettings() else requestBluetoothPermissions(forScanning = false)
                                        }) { Text(if (btAccess == AccessStatus.BLOCKED) "OPEN SETTINGS" else "ALLOW") }
                                    }
                                }
                            }
                            AnimatedVisibility(visible = warningPreview || warningMessage.isNotBlank(), enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 8 }, exit = fadeOut(tween(180))) {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF4B171D)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))) {
                                    Text(if (warningMessage.isNotBlank()) warningMessage else "WARNING SYSTEM PREVIEW — NOT A LIVE VEHICLE FAULT", Modifier.padding(12.dp), color = Color.White, fontWeight = FontWeight.Black)
                                }
                            }
                            if (showWall) {
                                val g = { l: String -> gauges.firstOrNull { it.label == l } ?: GaugeValue(l, "--", "", false) }
                                val speed = g("Vehicle speed")
                                val speedShown = speed.value.toDoubleOrNull()?.takeIf { speed.available }?.let { speed.copy(value = GaugeUnits.speedText(it, speedUnit), unit = speedUnit.label) } ?: speed.copy(unit = speedUnit.label)
                                val boost = g("Turbo boost")
                                val boostShown = boost.value.toDoubleOrNull()?.takeIf { boost.available }?.let { boost.copy(value = GaugeUnits.boostText(it, boostUnit), unit = boostUnit.label) } ?: boost.copy(unit = boostUnit.label)
                                val gShown = deviceG?.let { GaugeValue("Device G", String.format(java.util.Locale.US, "%.2f", it), "g", true, deviceGAtMs) } ?: GaugeValue("Device G", "--", "g", false)
                                GaugeWall(
                                    listOf(
                                        WallDial("REVS x1000", g("Engine RPM"), DialScale.forLabel("Engine RPM"), Color(0xFF42DDF5)),
                                        WallDial("SPEED " + speedUnit.label.uppercase(), speedShown, GaugeUnits.speedScale(speedUnit), Color(0xFFFFB84D)),
                                        WallDial("COOLANT °C", g("Coolant temperature"), DialScale.forLabel("Coolant temperature"), Color(0xFF42DDF5)),
                                        WallDial("DEVICE G", gShown, DialScale.forLabel("Device G"), Color(0xFF70F0D2)),
                                        WallDial("INTAKE °C", g("Intake air temperature"), DialScale.forLabel("Intake air temperature"), Color(0xFF42DDF5)),
                                        WallDial("EGR %", g("EGR commanded"), DialScale.forLabel("EGR commanded"), Color(0xFFFFB84D)),
                                        WallDial("BOOST " + boostUnit.label.uppercase(), boostShown, GaugeUnits.boostScale(boostUnit), Color(0xFF42DDF5))
                                    ) + extraPids.sorted().mapNotNull { PidCatalog.forPid(it) }.filter { it.pid !in CORE_PIDS }.map { spec ->
                                        WallDial(spec.name.uppercase(), g(spec.name).copy(unit = spec.unit), spec.scale, Color(0xFF42DDF5))
                                    },
                                    nowMs,
                                    Modifier.weight(1f).fillMaxWidth()
                                )
                            } else {
                                val g = { l: String -> gauges.firstOrNull { it.label == l } ?: GaugeValue(l, "--", "", false) }
                                val spd = g("Vehicle speed")
                                val spdShown = spd.value.toDoubleOrNull()?.takeIf { spd.available }?.let { spd.copy(value = GaugeUnits.speedText(it, speedUnit), unit = speedUnit.label) } ?: spd.copy(unit = speedUnit.label)
                                val bst = g("Turbo boost")
                                val bstShown = bst.value.toDoubleOrNull()?.takeIf { bst.available }?.let { bst.copy(value = GaugeUnits.boostText(it, boostUnit), unit = boostUnit.label) } ?: bst.copy(unit = boostUnit.label)
                                ClusterHome(
                                    rpm = g("Engine RPM"), speed = spdShown, speedUnitLabel = speedUnit.label,
                                    boost = bstShown, boostScale = GaugeUnits.boostScale(boostUnit),
                                    coolant = g("Coolant temperature"), intake = g("Intake air temperature"),
                                    load = g("Calculated engine load"), maf = g("MAF airflow"),
                                    nowMs = nowMs, modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (connected) "V-GATE / OBD-II • CONNECTED" else "V-GATE / OBD-II • STANDBY", color = if (connected) Color(0xFF70F0D2) else muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    val statusIsQuiet = connected || status == "Not connected" || status == "Disconnected"
                                    Text(if (connected) adapterName else status, color = if (statusIsQuiet) muted else Color(0xFFFFB84D), fontSize = 13.sp, maxLines = 3)
                                }
                                OutlinedButton(onClick = { showWall = !showWall }, shape = RoundedCornerShape(12.dp)) { Text(if (showWall) "MAIN VIEW" else "GAUGE WALL", fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { connect() }, enabled = !connected, shape = RoundedCornerShape(12.dp)) { Text("CONNECT ADAPTER", fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { disconnect() }, enabled = connected, shape = RoundedCornerShape(12.dp)) { Text("DISCONNECT", fontWeight = FontWeight.Bold) }
                            }
                            Text("Informational display only • Do not operate the tablet while driving", color = Color(0xFF718397), fontSize = 13.sp)
                        }
                        1 -> LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                val tick = tripTick
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("TRIP COMPUTER", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        val elapsed = if (tripStartMs > 0L && connected) nowMs - tripStartMs else 0L
                                        val avg = trip.averageMovingKmh()
                                        val l100 = trip.litresPer100Km()
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            listOf(
                                                "DISTANCE" to String.format(java.util.Locale.US, "%.2f km", trip.distanceKm),
                                                "TIME" to TripStats.clock(elapsed),
                                                "AVG MOVING" to (avg?.let { String.format(java.util.Locale.US, "%.0f km/h", it) } ?: "—"),
                                                "MAX SPEED" to String.format(java.util.Locale.US, "%.0f km/h", trip.maxSpeedKmh)
                                            ).forEach { (k, v) ->
                                                Column {
                                                    Text(k, color = muted, fontSize = 13.sp, letterSpacing = 1.sp)
                                                    Text(v, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                                                }
                                            }
                                        }
                                        Text(
                                            if (l100 != null) String.format(java.util.Locale.US, "Fuel: %.1f L/100 km (from the ECU's fuel-rate reading)", l100)
                                            else "Fuel use: not available. It needs the ECU to report fuel rate (tick Engine fuel rate in Settings); a diesel's MAF reading cannot give a reliable figure.",
                                            color = muted, fontSize = 13.sp
                                        )
                                        Text("Distance is integrated from real speed readings only. Gaps in the data are skipped, never filled in. Each poll is also saved to a CSV file on this tablet.", color = muted, fontSize = 13.sp)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            OutlinedButton(onClick = { startTrip() }, enabled = connected, modifier = Modifier.weight(1f)) { Text("NEW TRIP") }
                                            Button(onClick = { shareLastTrip() }, modifier = Modifier.weight(1f)) { Text("SHARE TRIP CSV") }
                                            OutlinedButton(onClick = { deleteSavedTrips() }, modifier = Modifier.weight(1f)) { Text("DELETE SAVED") }
                                        }
                                        Text(savedTrips().size.toString() + " saved trip file(s)" + if (tripNote.isNotBlank()) " • " + tripNote else "", color = Color(0xFFFFB84D), fontSize = 13.sp)
                                    }
                                }
                            }
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
                                        Text("LIVE STATUS", color = muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        Text(
                                            HeadUnitProtocol.describe(huSession),
                                            color = when (huSession.phase) {
                                                SessionPhase.PROJECTING -> Color(0xFF70F0D2)
                                                SessionPhase.FAILED -> Color(0xFFFF5252)
                                                else -> Color.White
                                            },
                                            fontWeight = FontWeight.Bold
                                        )
                                        Button(
                                            onClick = { headUnitStatus = connectViaHotspot() },
                                            enabled = readiness.receiverInstalled,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("CONNECT VIA PHONE HOTSPOT") }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            OutlinedButton(onClick = { headUnitStatus = connectUsb() }, enabled = readiness.receiverInstalled, modifier = Modifier.weight(1f)) { Text("CONNECT USB") }
                                            OutlinedButton(onClick = { headUnitStatus = receiverCommandStatus(HeadUnitProtocol.RAISE_PROJECTION, "Asked Open Headunit to bring the projection to the front.") }, enabled = readiness.receiverInstalled, modifier = Modifier.weight(1f)) { Text("SHOW PROJECTION") }
                                            OutlinedButton(onClick = { headUnitStatus = receiverCommandStatus(HeadUnitProtocol.DISCONNECT, "Asked Open Headunit to disconnect.") }, enabled = readiness.receiverInstalled, modifier = Modifier.weight(1f)) { Text("DISCONNECT") }
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
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("WIRELESS PHONE LINK", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Turn on the phone's hotspot (or put both on the same Wi-Fi), enable wireless Android Auto on the phone, enter the phone's IPv4 address here, then tap CONNECT PHONE. This asks Open Headunit to connect; whether projection works depends on your phone and Android Auto version.", color = muted, fontSize = 13.sp)
                                        OutlinedTextField(
                                            value = phoneIp,
                                            onValueChange = { phoneIp = it; prefs.edit().putString("phone_ip", it).apply() },
                                            label = { Text("Phone IPv4 address") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Button(
                                            onClick = { headUnitStatus = connectPhone() },
                                            enabled = readiness.receiverInstalled,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("CONNECT PHONE") }
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
                                        Text("ALERT LIMITS (YOURS)", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("This app has no verified SX4 ZDi limits, so every alert is off until you choose a number. A limit shows a banner and speaks a caution after three fresh readings above it.", color = muted, fontSize = 13.sp)
                                        fun save(key: String, v: Int?) = prefs.edit().putInt(key, v ?: -1).apply()
                                        fun apply(next: AlertLimits) { alertLimits = next; warningEngine = WarningEngine(next.rules()) }
                                        LimitRow("Coolant above", alertLimits.coolantMaxC, AlertLimits.COOLANT, AlertLimits.COOLANT_START, 5, "°C") { v -> save("alert_coolant", v); apply(alertLimits.copy(coolantMaxC = v)) }
                                        LimitRow("Engine speed above", alertLimits.rpmMax, AlertLimits.RPM, AlertLimits.RPM_START, 100, "rpm") { v -> save("alert_rpm", v); apply(alertLimits.copy(rpmMax = v)) }
                                        LimitRow("Boost above", alertLimits.boostMaxKpa, AlertLimits.BOOST, AlertLimits.BOOST_START, 10, "kPa") { v -> save("alert_boost", v); apply(alertLimits.copy(boostMaxKpa = v)) }
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("DISPLAY", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Auto dims the tablet's backlight from its light sensor at night. Night keeps it dimmed; Day never changes it.", color = muted, fontSize = 13.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ThemeMode.values().forEach { m ->
                                                FilterChip(selected = themeMode == m, onClick = { themeMode = m; prefs.edit().putString("theme_mode", m.name).apply(); sendReceiverCommand(HeadUnitProtocol.SET_NIGHT_MODE, "state" to HeadUnitProtocol.nightModeState(m)) }, label = { Text(m.label) })
                                            }
                                        }
                                        Text(lightLux?.let { "Light sensor: " + it.toInt() + " lux" } ?: "No light sensor reading (Auto will not dim).", color = muted, fontSize = 13.sp)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("CONNECTION CONFIGURATION", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Same options as Torque. Changes apply the next time you tap CONNECT ADAPTER.", color = muted, fontSize = 13.sp)
                                        Text("OBD protocol", color = Color.White, fontWeight = FontWeight.Bold)
                                        ObdProtocol.values().forEach { p ->
                                            Row(Modifier.fillMaxWidth().clickable {
                                                connConfig = connConfig.copy(protocol = p); prefs.edit().putString("obd_protocol", p.name).apply()
                                            }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                RadioButton(selected = connConfig.protocol == p, onClick = {
                                                    connConfig = connConfig.copy(protocol = p); prefs.edit().putString("obd_protocol", p.name).apply()
                                                })
                                                Text(p.label, color = Color.White)
                                            }
                                        }
                                        Text("For the SX4 ZDi (K-line) try Automatic first, then ISO 14230-4 KWP (fast init).", color = muted, fontSize = 13.sp)
                                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("Try insecure Bluetooth socket first", color = Color.White, fontWeight = FontWeight.Bold)
                                                Text("Many ELM327 clones only accept this. Turn on if the adapter pairs but will not connect.", color = muted, fontSize = 13.sp)
                                            }
                                            Switch(checked = connConfig.insecureFirst, onCheckedChange = {
                                                connConfig = connConfig.copy(insecureFirst = it); prefs.edit().putBoolean("insecure_first", it).apply()
                                            })
                                        }
                                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("Adaptive timing (ATAT1)", color = Color.White, fontWeight = FontWeight.Bold)
                                                Text("Lets the adapter adjust its reply timeout. Helps slow K-line ECUs.", color = muted, fontSize = 13.sp)
                                            }
                                            Switch(checked = connConfig.adaptiveTiming, onCheckedChange = {
                                                connConfig = connConfig.copy(adaptiveTiming = it); prefs.edit().putBoolean("adaptive_timing", it).apply()
                                            })
                                        }
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("SENSORS ON THE GAUGE WALL", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Tick the sensors you want, like adding gauges in Torque. Each extra sensor is one more request to the ECU, so the display refreshes more slowly. A sensor your ECU does not support shows NO SIGNAL.", color = muted, fontSize = 13.sp)
                                        PidCatalog.all.filter { it.pid !in CORE_PIDS }.forEach { spec ->
                                            val on = spec.pid in extraPids
                                            val tag = if (supportedPids.isEmpty()) "connect to check" else if (spec.pid in supportedPids) "supported" else "not advertised by ECU"
                                            val toggle = {
                                                extraPids = if (on) extraPids - spec.pid else extraPids + spec.pid
                                                prefs.edit().putString("extra_pids", PidCatalog.selectionText(extraPids)).apply()
                                            }
                                            Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                Checkbox(checked = on, onCheckedChange = { toggle() })
                                                Column(Modifier.weight(1f)) {
                                                    Text(spec.name, color = Color.White)
                                                    Text(spec.command + " • " + tag, color = muted, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("GAUGE UNITS", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("The ECU always reports km/h and kPa. Units only change how the Gauge Wall shows them.", color = muted, fontSize = 13.sp)
                                        Text("Speed", color = Color.White, fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SpeedUnit.values().forEach { u ->
                                                FilterChip(selected = speedUnit == u, onClick = { speedUnit = u; prefs.edit().putString("speed_unit", u.name).apply() }, label = { Text(u.label) })
                                            }
                                        }
                                        Text("Boost", color = Color.White, fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            BoostUnit.values().forEach { u ->
                                                FilterChip(selected = boostUnit == u, onClick = { boostUnit = u; prefs.edit().putString("boost_unit", u.name).apply() }, label = { Text(u.label) })
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("OBD ADAPTER", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text(BluetoothAccess.message(btAccess), color = if (btAccess == AccessStatus.GRANTED) muted else Color(0xFFFFB84D))
                                        if (btAccess != AccessStatus.GRANTED) {
                                            Button(onClick = {
                                                if (btAccess == AccessStatus.BLOCKED) openAppSettings() else requestBluetoothPermissions(forScanning = true)
                                            }, modifier = Modifier.fillMaxWidth()) { Text(if (btAccess == AccessStatus.BLOCKED) "OPEN APP SETTINGS" else "ALLOW BLUETOOTH ACCESS") }
                                        }
                                        Text("Paired devices: tap one to use it", color = Color.White, fontWeight = FontWeight.Bold)
                                        if (pairedDevices.isEmpty()) Text("None yet. Scan below to find and pair your adapter.", color = muted)
                                        pairedDevices.forEach { d ->
                                            Row(Modifier.fillMaxWidth().clickable { selectAdapter(d) }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                RadioButton(selected = d.address.equals(selectedAddress, ignoreCase = true), onClick = { selectAdapter(d) })
                                                Column {
                                                    Text(d.name, color = Color.White, fontWeight = FontWeight.Bold)
                                                    Text(d.address, color = muted, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                        Text("With nothing selected, a single paired Vgate, iCar, OBDII, ELM or V-LINK device is used automatically.", color = muted, fontSize = 13.sp)
                                        Divider(color = Color(0xFF26394C))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Button(onClick = { startScan() }, enabled = !scanning, modifier = Modifier.weight(1f)) { Text(if (scanning) "SCANNING…" else "SCAN FOR ADAPTERS") }
                                            OutlinedButton(onClick = { refreshBluetoothAccess(); refreshPairedDevices() }, modifier = Modifier.weight(1f)) { Text("REFRESH") }
                                        }
                                        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cyan)
                                        foundDevices.filter { f -> pairedDevices.none { it.address == f.address } }.forEach { d ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(d.name, color = Color.White, fontWeight = FontWeight.Bold)
                                                    Text(d.address, color = muted, fontSize = 13.sp)
                                                }
                                                OutlinedButton(onClick = { pairAdapter(d) }) { Text("PAIR") }
                                            }
                                        }
                                        if (adapterNote.isNotBlank()) Text(adapterNote, color = Color(0xFFFFB84D))
                                    }
                                }
                            }
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
                                        Text("Welcome: two-tone cabin chime followed by a calm-paced English announcement. It plays only after the ECU has answered on a successful OBD connection, never when the app opens or when only Bluetooth is connected.", color = muted)
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
                                                val header = "SX4 Scan Pro adapter log\n" + "v0.8.0 • PRE-RELEASE • BUILD 26" + "\nStatus: " + status + "\nStandard PIDs advertised: " + supportedPids.size
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
                            item { Text("Build v0.8.0 • PRE-RELEASE • BUILD 26", color = muted) }
                        }
                            }
                        }
                        Crossfade(targetState = welcomeOverlayVisible, animationSpec = tween(550), label = "welcome-overlay-fade") { visible ->
                            if (visible) {
                                Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { welcomeOverlayVisible = false }) {
                                    val rpmG = gauges.firstOrNull { it.label == "Engine RPM" }
                                    val bstG = gauges.firstOrNull { it.label == "Turbo boost" }
                                    val rpmNow = rpmG?.takeIf { it.available }?.value?.toDoubleOrNull()
                                    val bstNow = bstG?.takeIf { it.available }?.value?.toDoubleOrNull()
                                    val bstScale = GaugeUnits.boostScale(boostUnit)
                                    IgnitionSequence(
                                        linkActive = connected,
                                        left = IgnitionGauge("RPM", rpmNow?.let { DialScale.forLabel("Engine RPM").fraction(it) }, rpmNow?.let { it.toInt().toString() } ?: "—"),
                                        right = IgnitionGauge("BOOST " + boostUnit.label.uppercase(), bstNow?.let { bstScale.fraction(GaugeUnits.boost(it, boostUnit)) }, bstNow?.let { GaugeUnits.boostText(it, boostUnit) } ?: "—")
                                    )
                                }
                            }
                        }
                    }

                }
                SxBottomNav(
                    selectedTab = selectedTab,
                    onSelect = { if (selectedTab != it) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); selectedTab = it },
                    faultCount = dtcCodes.size
                )
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
        val bonded = adapter.bondedDevices.orEmpty().map { BtDevice(it.name ?: it.address, it.address, true) }
        // The user's explicit choice (Settings, OBD ADAPTER) wins; otherwise a single recognised adapter is used.
        val chosen = when (val choice = AdapterSelector.choose(bonded, selectedAddress)) {
            is AdapterChoice.Use -> choice.device
            is AdapterChoice.Unavailable -> throw IOException(choice.message)
        }
        val device: BluetoothDevice = adapter.getRemoteDevice(chosen.address)
        adapter.cancelDiscovery()
        socket = openSocket(device)
        val config = connConfig
        sendCommand("ATZ", 5000)
        config.initCommands().forEach { sendCommand(it) }
        // "Connected" must mean the ECU answered, not just that the adapter did.
        val negotiation = EcuNegotiation { command, timeoutMs -> sendCommand(command, timeoutMs) }
            .apply { preferredProtocol = config.protocol.elmNumber }.run()
        if (negotiation.supportedPids.isEmpty()) {
            closeSocket()
            throw IOException(negotiation.failure)
        }
        supportedPids = negotiation.supportedPids
        return (device.name ?: "Bluetooth OBD adapter") to Unit
    }

    /** Secure SPP first, then insecure SPP: many ELM327 clones only accept the second. */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val secure: () -> BluetoothSocket = { device.createRfcommSocketToServiceRecord(SPP_UUID) }
        val insecure: () -> BluetoothSocket = { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
        val makers = if (connConfig.insecureFirst) listOf(insecure, secure) else listOf(secure, insecure)
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
            Triple("Throttle position", "0111", "%"),
            Triple("EGR commanded", "012C", "%")
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
        val core = results.take(4) + listOf(map["Manifold pressure"]!!) + boost + results.drop(5)
        // Extra sensors the user chose (Torque-style). Only polled when the ECU advertises them.
        val extras = extraPids.sorted().mapNotNull { PidCatalog.forPid(it) }.map { spec ->
            if (spec.pid !in supportedPids) GaugeValue(spec.name, "--", spec.unit, false)
            else {
                val v = PidCatalog.decode(spec, sendCommand(spec.command))
                if (v != null) GaugeValue(spec.name, PidCatalog.format(spec, v), spec.unit, true) else GaugeValue(spec.name, "--", spec.unit, false)
            }
        }
        return core + extras
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

    private fun applyDim(alpha: Float) {
        val lp = window.attributes
        lp.screenBrightness = if (alpha <= 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else (1f - alpha * 1.4f).coerceIn(0.08f, 1f)
        window.attributes = lp
    }

    private fun startTrip() {
        endTrip()
        trip = TripStats()
        tripStartMs = System.currentTimeMillis()
        tripHeaderWritten = false
        tripNote = ""
        tripTick++
        try {
            val dir = java.io.File(filesDir, "trips").apply { mkdirs() }
            val name = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(dir, "trip-$name.csv")
            tripFile = file
            tripWriter = file.bufferedWriter()
        } catch (e: Exception) {
            tripWriter = null
            tripNote = "Trip recording could not start: " + (e.message ?: "storage error")
        }
    }

    private fun recordTrip(list: List<GaugeValue>) {
        val now = System.currentTimeMillis()
        fun num(label: String) = list.firstOrNull { it.label == label }?.takeIf { it.available }?.value?.toDoubleOrNull()
        trip.add(now, num("Vehicle speed"), num("Engine fuel rate"))
        tripTick++
        val w = tripWriter ?: return
        try {
            if (!tripHeaderWritten) {
                w.write(TripCsv.header(list.map { it.label }))
                w.newLine()
                tripHeaderWritten = true
            }
            val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))
            w.write(TripCsv.row(iso, list.map { if (it.available) it.value else null }))
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            tripNote = "Trip file write failed: " + (e.message ?: "storage error")
        }
    }

    private fun endTrip() {
        try { tripWriter?.flush(); tripWriter?.close() } catch (_: Exception) { }
        tripWriter = null
    }

    private fun savedTrips(): List<java.io.File> =
        java.io.File(filesDir, "trips").listFiles { f -> f.extension == "csv" }?.sortedBy { it.name }.orEmpty()

    private fun shareLastTrip() {
        endTripFlushOnly()
        val file = savedTrips().lastOrNull { it.length() > 0 }
        if (file == null) {
            tripNote = "No recorded trip yet. Connect the adapter and drive; every poll is saved."
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SX4 Scan Pro trip " + file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share trip CSV"))
        } catch (e: Exception) {
            tripNote = "Could not share the trip file: " + (e.message ?: "unknown error")
        }
    }

    private fun endTripFlushOnly() {
        try { tripWriter?.flush() } catch (_: Exception) { }
    }

    private fun deleteSavedTrips() {
        endTrip()
        val n = savedTrips().count { it.delete() }
        tripNote = "Deleted $n saved trip file(s)."
        tripTick++
    }

    /** Sends one command to Open Headunit's automation receiver (no overlay permission needed). */
    private fun sendReceiverCommand(name: String, vararg extras: Pair<String, String>): Boolean {
        if (packageManager.getLaunchIntentForPackage(HeadUnitProtocol.PACKAGE) == null) return false
        val intent = Intent(HeadUnitProtocol.action(name)).setClassName(HeadUnitProtocol.PACKAGE, HeadUnitProtocol.RECEIVER_CLASS)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        return try { sendBroadcast(intent); true } catch (_: Exception) { false }
    }

    /** Asks Open Headunit to connect to the phone at [phoneIp]. Returns the status line. */
    private fun connectPhone(): String {
        val ip = HeadUnitLink.normalizeIp(phoneIp)
            ?: return "Enter the phone's IPv4 address first, for example 192.168.43.1."
        return if (sendReceiverCommand(HeadUnitProtocol.CONNECT, "ip" to ip))
            "Asked Open Headunit to connect to $ip. Wireless Android Auto must be enabled on the phone. The status above shows what it reports."
        else "Could not reach Open Headunit. Is it installed and up to date?"
    }

    /** One tap when the tablet is on the phone's hotspot: the phone is the Wi-Fi gateway. */
    private fun connectViaHotspot(): String {
        @Suppress("DEPRECATION")
        val gateway = try {
            (applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager)?.dhcpInfo?.gateway ?: 0
        } catch (_: Exception) { 0 }
        val ip = HeadUnitProtocol.gatewayIp(gateway)
            ?: return "The tablet is not on a Wi-Fi network. Turn on the phone's hotspot and join it from the tablet first."
        phoneIp = ip
        prefs.edit().putString("phone_ip", ip).apply()
        return connectPhone()
    }

    private fun connectUsb(): String =
        if (sendReceiverCommand(HeadUnitProtocol.CONNECT)) "Asked Open Headunit to check the USB port. Plug the phone in and unlock it."
        else "Could not reach Open Headunit. Is it installed and up to date?"

    private fun receiverCommandStatus(name: String, done: String): String =
        if (sendReceiverCommand(name)) done else "Could not reach Open Headunit. Is it installed and up to date?"

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun accessFor(required: List<String>): AccessStatus = BluetoothAccess.status(
        required = required,
        isGranted = { isGranted(it) },
        askedBefore = prefs.getBoolean("bt_permission_asked", false),
        canShowRationale = { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
    )

    private fun refreshBluetoothAccess() {
        val sdk = android.os.Build.VERSION.SDK_INT
        btAccess = accessFor(BluetoothAccess.forConnecting(sdk))
        scanAccess = accessFor(BluetoothAccess.forScanning(sdk))
    }

    /** Shows Android's permission dialog for whatever is still missing (one dialog on Android 12+). */
    private fun requestBluetoothPermissions(forScanning: Boolean) {
        val sdk = android.os.Build.VERSION.SDK_INT
        val wanted = if (forScanning || sdk >= 31) BluetoothAccess.forScanning(sdk) else BluetoothAccess.forConnecting(sdk)
        val missing = wanted.filterNot { isGranted(it) }
        if (missing.isEmpty()) {
            refreshBluetoothAccess()
            return
        }
        prefs.edit().putBoolean("bt_permission_asked", true).apply()
        permissionRequest.launch(missing.toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun refreshPairedDevices() {
        pairedDevices.clear()
        if (btAccess != AccessStatus.GRANTED) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            adapter.bondedDevices.orEmpty().forEach { pairedDevices.add(BtDevice(it.name ?: it.address, it.address, true)) }
        } catch (_: SecurityException) { }
    }

    private fun selectAdapter(device: BtDevice) {
        selectedAddress = device.address
        prefs.edit().putString("adapter_address", device.address).apply()
        adapterNote = "Selected " + device.name + ". Go to the Cockpit and tap CONNECT ADAPTER."
    }

    private fun pairAdapter(device: BtDevice) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            adapter.cancelDiscovery()
            adapterNote = if (adapter.getRemoteDevice(device.address).createBond())
                "Pairing requested. If Android asks for a PIN, try 1234 or 0000, then choose the adapter above and tap CONNECT."
            else "Could not start pairing."
        } catch (_: SecurityException) {
            adapterNote = "Bluetooth permission is missing."
        }
    }

    private fun startScan() {
        if (scanAccess != AccessStatus.GRANTED) {
            adapterNote = BluetoothAccess.message(scanAccess)
            if (scanAccess == AccessStatus.NEEDS_REQUEST) requestBluetoothPermissions(forScanning = true)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            adapterNote = "This tablet has no Bluetooth."
            return
        }
        if (!adapter.isEnabled) {
            adapterNote = "Turn Bluetooth on first, then scan again."
            return
        }
        foundDevices.clear()
        try {
            adapter.cancelDiscovery()
            scanning = adapter.startDiscovery()
            adapterNote = if (scanning) "Scanning for nearby devices…" else "Could not start scanning."
        } catch (_: SecurityException) {
            adapterNote = "Bluetooth permission is missing."
        }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.apply { unregisterListener(gListener); unregisterListener(lightListener) }
    }

    override fun onResume() {
        super.onResume()
        // Device G: the tablet's own linear-acceleration sensor. Absent sensor = NO SIGNAL, never a made-up value.
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor != null) sm?.registerListener(gListener, sensor, SensorManager.SENSOR_DELAY_UI)
        sm?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        // Coming back from the system permission screen or Bluetooth settings.
        refreshBluetoothAccess()
        refreshPairedDevices()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(btReceiver) }
        runCatching { unregisterReceiver(sessionReceiver) }
        endTrip()
        runCatching { if (BluetoothAdapter.getDefaultAdapter()?.isDiscovering == true) BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
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


/** One owner-set alert limit: a switch and +/- buttons. Off by default; never a built-in number. */
@Composable
private fun LimitRow(label: String, value: Int?, range: IntRange, start: Int, step: Int, unit: String, onChange: (Int?) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = value != null, onCheckedChange = { on -> onChange(if (on) start else null) })
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
            Text(if (value == null) "Off" else "Set by you: $value $unit", color = Color(0xFF8FA6B9), fontSize = 13.sp)
        }
        OutlinedButton(onClick = { if (value != null) onChange((value - step).coerceAtLeast(range.first)) }, enabled = value != null) { Text("−") }
        OutlinedButton(onClick = { if (value != null) onChange((value + step).coerceAtMost(range.last)) }, enabled = value != null) { Text("+") }
    }
}
