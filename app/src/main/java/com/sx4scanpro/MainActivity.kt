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
/** Sensors the home screen always shows (fuel level, control module voltage), polled whether or not picked in Settings. */
internal val HOME_PIDS = setOf(0x2F, 0x42, 0x23, 0x5E)
private val CORE_PIDS = setOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x2C)

private const val RECEIVER_PACKAGE = "com.andrerinas.headunitrevived"
internal val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

internal class TimeoutFlag { var hit = false }
data class GaugeValue(val label: String, val value: String, val unit: String = "", val available: Boolean = true, val capturedAtMs: Long = System.currentTimeMillis(), val staleAfterMs: Long = STALE_MS)
data class TroubleCodeResult(val category: String, val code: String)

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshBluetoothAccess()
        refreshPairedDevices()
    }
    private val prefs by lazy { getSharedPreferences("sx4_scan_pro", MODE_PRIVATE) }
    // ---- Session state and engine live in ObdRuntime (process-owned); these are thin views of it. ----
    private val rt: ObdRuntime get() = ObdRuntime.get(this)
    private var selectedAddress: String?
        get() = rt.selectedAddress
        set(v) { rt.selectedAddress = v }
    private var connConfig: ConnectionConfig
        get() = rt.connConfig
        set(v) { rt.connConfig = v }
    private var extraPids: Set<Int>
        get() = rt.extraPids
        set(v) { rt.extraPids = v }
    private var alertSensors: Set<String>
        get() = rt.alertSensors
        set(v) { rt.alertSensors = v }
    private var trackedKm: Double
        get() = rt.trackedKm
        set(v) { rt.trackedKm = v }
    private var tripTick: Int
        get() = rt.tripTick
        set(v) { rt.tripTick = v }
    private var tripStartMs: Long
        get() = rt.tripStartMs
        set(v) { rt.tripStartMs = v }
    private var tripNote: String
        get() = rt.tripNote
        set(v) { rt.tripNote = v }
    private var socket: BluetoothSocket?
        get() = rt.socket
        set(v) { rt.socket = v }
    private var supportedPids: Set<Int>
        get() = rt.supportedPids
        set(v) { rt.supportedPids = v }
    private var protocolText: String
        get() = rt.protocolText
        set(v) { rt.protocolText = v }
    private var ecuProfile: EcuProfile?
        get() = rt.ecuProfile
        set(v) { rt.ecuProfile = v }
    private var ecuPrevious: EcuProfile?
        get() = rt.ecuPrevious
        set(v) { rt.ecuPrevious = v }
    private var ecuScanning: Boolean
        get() = rt.ecuScanning
        set(v) { rt.ecuScanning = v }
    private var ecuNote: String
        get() = rt.ecuNote
        set(v) { rt.ecuNote = v }
    private var adapterIdText: String
        get() = rt.adapterIdText
        set(v) { rt.adapterIdText = v }
    private var adapterVoltageText: String
        get() = rt.adapterVoltageText
        set(v) { rt.adapterVoltageText = v }
    private var linkState: LinkState
        get() = rt.linkState
        set(v) { rt.linkState = v }
    private var warningEngine: WarningEngine
        get() = rt.warningEngine
        set(v) { rt.warningEngine = v }
    private var userInitiatedDisconnect: Boolean
        get() = rt.userInitiatedDisconnect
        set(v) { rt.userInitiatedDisconnect = v }
    private var connSnapshot: ConnectionSnapshot
        get() = rt.connSnapshot
        set(v) { rt.connSnapshot = v }
    private var pollStalled: Boolean
        get() = rt.pollStalled
        set(v) { rt.pollStalled = v }
    private var captureSession: String
        get() = rt.captureSession
        set(v) { rt.captureSession = v }
    private var parameterRegistry: ParameterRegistry
        get() = rt.parameterRegistry
        set(v) { rt.parameterRegistry = v }
    private var pollingLive: Boolean
        get() = rt.pollingLive
        set(v) { rt.pollingLive = v }
    private var lastDiscovery: DiscoveryOutcome?
        get() = rt.lastDiscovery
        set(v) { rt.lastDiscovery = v }
    private var sessionStartMs: Long
        get() = rt.sessionStartMs
        set(v) { rt.sessionStartMs = v }
    private var adapterNameForScan: String
        get() = rt.adapterNameForScan
        set(v) { rt.adapterNameForScan = v }
    private val tripSession: TripSession get() = rt.tripSession
    private var tripWriter: java.io.BufferedWriter?
        get() = rt.tripWriter
        set(v) { rt.tripWriter = v }
    private var tripFile: java.io.File?
        get() = rt.tripFile
        set(v) { rt.tripFile = v }
    private val trip: TripStats get() = rt.trip
    private val connection: ConnectionMachine get() = rt.connection
    private val recoveryPolicy: RecoveryPolicy get() = rt.recoveryPolicy
    private val stallWatchdog: StallWatchdog get() = rt.stallWatchdog
    private val recovery: RecoverySupervisor get() = rt.recovery
    private val traffic: TrafficLog get() = rt.traffic
    private val sessionLog: SessionLog get() = rt.sessionLog
    private val capture: CaptureStore get() = rt.capture
    private val pollGate: PollGate get() = rt.pollGate
    private val elmPort: ElmPort get() = rt.elmPort
    private val discoveryController: DiscoveryController get() = rt.discoveryController
    private val registryStore: RegistryStore get() = rt.registryStore
    private var dataFindings: List<DataFinding>
        get() = rt.dataFindings
        set(v) { rt.dataFindings = v }
    private var dataTally: List<TallyRow>
        get() = rt.dataTally
        set(v) { rt.dataTally = v }
    private var dataBaselines: List<BaselineRow>
        get() = rt.dataBaselines
        set(v) { rt.dataBaselines = v }
    private var dataSummary: TripSummary
        get() = rt.dataSummary
        set(v) { rt.dataSummary = v }
    private var dataCheckBanner: String
        get() = rt.dataCheckBanner
        set(v) { rt.dataCheckBanner = v }
    private var fuelFills: List<FillUp>
        get() = rt.fuelFills
        set(v) { rt.fuelFills = v }
    private fun resetDataCheckLearning() = rt.resetDataCheckLearning()
    private fun addFillUp(odoKm: Double?, litres: Double?): String? = rt.addFillUp(odoKm, litres)
    private fun removeLastFillUp() = rt.removeLastFillUp()
    // ---- real-car validation checklist (owner-recorded; never set by the app itself) ----
    private var validation by mutableStateOf<List<ValidationRecord>>(emptyList())
    private var validationPhase by mutableStateOf<ValidationPhase?>(null)
    private fun tagValidationPhase(p: ValidationPhase) {
        validationPhase = if (p == ValidationPhase.ENDED) null else p
        sessionLog.append("EVT", ValidationPlan.logLine(p))
    }
    private fun recordValidationResult(id: String, s: ValidationStatus) {
        validation = if (s == ValidationStatus.UNTESTED) validation.filter { it.id != id }
        else ValidationPlan.record(validation, id, s, System.currentTimeMillis())
        prefs.edit().putString("validation", ValidationCodec.encode(validation)).apply()
        sessionLog.append("EVT", "validation result: $id ${s.name}")
    }
    private fun closeSocket() = rt.closeSocket()
    private fun sendCommand(command: String, timeoutMs: Long = 5000, keepLines: Boolean = false, timedOut: TimeoutFlag? = null): String = rt.sendCommand(command, timeoutMs, keepLines, timedOut)
    internal fun requestDiscovery(reason: String = "manual"): DiscoveryOutcome = rt.requestDiscovery(reason)
    private fun readTroubleCodes(): DtcScanReport = rt.readTroubleCodes()
    private fun readFreezeFrameBlocking(): FreezeFrameResult = rt.readFreezeFrameBlocking()
    private fun runEcuScan(scope: kotlinx.coroutines.CoroutineScope) = rt.runEcuScan(scope)
    private fun startTrip(forceNew: Boolean = false) = rt.startTrip(forceNew)
    private fun endTrip() = rt.endTrip()
    private fun holdTrip() = rt.holdTrip()
    private fun tripClock(now: Long) = rt.tripClock(now)
    private fun loadEcuProfiles() = rt.loadEcuProfiles()
    private fun loadParameterRegistry() = rt.loadParameterRegistry()
    private fun buildPoller(): LivePoller = rt.buildPoller()
    private fun ecuDir() = rt.ecuDir()
    private fun logTraffic(tag: String, text: String) = rt.logTraffic(tag, text)
    private var btAccess by mutableStateOf(AccessStatus.NEEDS_REQUEST)
    private var scanAccess by mutableStateOf(AccessStatus.NEEDS_REQUEST)
    private val pairedDevices = mutableStateListOf<BtDevice>()
    private val foundDevices = mutableStateListOf<BtDevice>()
    private var scanning by mutableStateOf(false)
    private var adapterNote by mutableStateOf("")
    private var speedUnit by mutableStateOf(SpeedUnit.KMH)
    private var boostUnit by mutableStateOf(BoostUnit.KPA)
    private var deviceG by mutableStateOf<Double?>(null)
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
    /** Gauge keys ("coolant", "rpm", "boost") currently above an owner-set limit. */
    private var welcomeSettings by mutableStateOf(WelcomeSettings())
    private var welcomePlayedOnce = false
    private var driveMode by mutableStateOf(DriveMode.SPORT)
    private var freezeResult by mutableStateOf<FreezeFrameResult?>(null)
    /** Last engine-ECU fault-code scan with the status and raw reply of every service (03 / 07 / 0A). */
    private var dtcReport by mutableStateOf<DtcScanReport?>(null)
    private var freezeNote by mutableStateOf("")
    private var freezeBusy by mutableStateOf(false)
    private val uiScope = kotlinx.coroutines.MainScope()
    private var maintVersion by mutableStateOf(0)
    private var reportNote by mutableStateOf("")
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
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    val n = try { d?.name } catch (_: SecurityException) { null }
                    sessionLog.append("EVT", (if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) "bluetooth link up: " else "bluetooth link down: ") + (n ?: d?.address ?: "unknown"))
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> sessionLog.append("EVT", "bluetooth adapter state " + intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    refreshPairedDevices()
                    foundDevices.removeAll { f -> pairedDevices.any { it.address == f.address } }
                }
            }
        }
    }
    private val engineStartDetector = EngineStartDetector()
    private var speechReady = false
    private var textToSpeech: TextToSpeech? = null
    private var chime: ToneGenerator? = null
    private var cabinChimeTrack: AudioTrack? = null
    private var welcomeOverlayVisible by mutableStateOf(false)
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
        trackedKm = prefs.getFloat("tracked_km", 0f).toDouble()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9101)
        driveMode = DriveMode.parse(prefs.getString("drive_mode", null))
        welcomeSettings = WelcomeSettings(
            durationSec = WelcomeSettings.clampDuration(prefs.getFloat("welcome_duration", WelcomeSettings.DEFAULT_DURATION_SEC)),
            sound = prefs.getBoolean("welcome_sound", true),
            everyConnection = prefs.getBoolean("welcome_every", true),
            volume = WelcomeSettings.clampVolume(prefs.getFloat("welcome_volume", 1f))
        )
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
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        sessionLog.append("EVT", "app started")
        loadEcuProfiles()
        validation = ValidationCodec.decode(prefs.getString("validation", null))
        loadParameterRegistry()
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
        // Audio hooks only; the session keeps running if this Activity goes away.
        rt.onConnected = { if (welcomeSettings.shouldPlay(welcomePlayedOnce)) playEngineWelcome(); welcomePlayedOnce = true }
        rt.onAlert = { sensors -> playAlert(sensors) }
        rt.onDataAlert = { chime?.startTone(ToneGenerator.TONE_PROP_NACK, 520) }
        setContent { MaterialTheme { Dashboard() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Dashboard() {
        val bg = Sx.Background
        val panel = Sx.Card
        val cyan = Sx.Primary
        val muted = Sx.MutedForeground
        var status by rt::status
        var adapterName by rt::adapterName
        var gauges by rt::gauges
        var selectedTab by rememberSaveable { mutableStateOf(0) }
        var showWall by rememberSaveable { mutableStateOf(false) }
        var dtcStatus by remember { mutableStateOf("Connect the Vgate adapter to read engine ECU fault codes.") }
        var dtcCodes by remember { mutableStateOf<List<TroubleCodeResult>>(emptyList()) }
        var dtcScanned by remember { mutableStateOf(false) }
        var scanningCodes by remember { mutableStateOf(false) }
        var headUnitStatus by remember { mutableStateOf("Open Headunit runs as a separate receiver app.") }
        var readiness by remember { mutableStateOf(computeHeadUnitReadiness()) }
        var logPreview by remember { mutableStateOf<List<String>>(emptyList()) }
        var warningPreview by remember { mutableStateOf(false) }
        var warningMessage by rt::warningMessage
        val scope = rememberCoroutineScope()
        var exportChoice by remember { mutableStateOf(false) }
        if (exportChoice) {
            AlertDialog(
                onDismissRequest = { exportChoice = false },
                title = { Text("Export diagnostic session") },
                text = {
                    Text("The export contains the adapter traffic, events, ECU replies and the ECU scan. An unredacted export includes the vehicle's VIN and the adapter's Bluetooth name and can identify your car. A redacted export masks the VIN, removes the VIN reply, and hides the adapter name and Bluetooth addresses. Your stored data is never changed either way.")
                },
                confirmButton = {
                    Button(onClick = { exportChoice = false; exportCompleteSession(true, scope) { logPreview = listOf(it) } }) { Text("EXPORT REDACTED (RECOMMENDED)") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportChoice = false }) { Text("CANCEL") }
                        OutlinedButton(onClick = { exportChoice = false; exportCompleteSession(false, scope) { logPreview = listOf(it) } }) { Text("UNREDACTED") }
                    }
                }
            )
        }
        val connected = status == "Connected"
        val ecuOnline = connected && linkState == LinkState.COMMUNICATING
        val connecting = status == "Connecting…"
        val haptic = LocalHapticFeedback.current
        val history = rt.history
        var historyVersion by rt::historyVersion
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        // One-second clock so readings that stop updating are marked STALE instead of looking live.
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                nowMs = System.currentTimeMillis()
                tripClock(nowMs)
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

        fun disconnect(stopTrip: Boolean = true) { rt.disconnect(stopTrip) }
        fun connect() {
            if (btAccess != AccessStatus.GRANTED) {
                status = if (btAccess == AccessStatus.BLOCKED) BluetoothAccess.message(btAccess)
                else "Bluetooth permission required — allow it, then tap Connect again"
                if (btAccess == AccessStatus.NEEDS_REQUEST) requestBluetoothPermissions(forScanning = false)
                return
            }
            // One connection or recovery at a time: a second tap must not open a second socket.
            if (connection.snapshot.busy || recovery.isRecovering()) return
            rt.connectSession()
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
                    val report = withContext(Dispatchers.IO) { readTroubleCodes() }
                    dtcReport = report
                    dtcCodes = report.codes.map { TroubleCodeResult(it.category, it.code) }
                    dtcScanned = report.hasAnyAnswer
                    dtcStatus = report.headline()
                    sessionLog.append("EVT", "dtc scan: " + report.modes.joinToString("; ") { "${it.mode}=${it.status.name}(${it.codes.size})" })
                } catch (e: Exception) {
                    dtcStatus = "Scan failed: " + (e.message ?: "communication error")
                    // A lost adapter is picked up by the poll loop, which reconnects; the scan only reports its own failure.
                    if (socket?.isConnected != true) sessionLog.append("EVT", "DTC scan lost the adapter; connection recovery takes over")
                } finally { scanningCodes = false }
            }
        }

        // Night driving: dim the real backlight from the light sensor (or the owner's Day/Night choice).
        val dim = Math.round(NightMode.dimAlpha(themeMode, lightLux) / 0.05f) * 0.05f
        LaunchedEffect(dim) { applyDim(dim) }

        // Drive mode recolours the whole cockpit with a 0.3 s fade.
        val modeColors = SxPalette.of(driveMode)
        val pAccent by animateColorAsState(modeColors.accent, tween(300), label = "mode-accent")
        val pBg by animateColorAsState(modeColors.background, tween(300), label = "mode-bg")
        val pCard by animateColorAsState(modeColors.card, tween(300), label = "mode-card")
        val pFg by animateColorAsState(modeColors.foreground, tween(300), label = "mode-fg")
        val pMuted by animateColorAsState(modeColors.mutedForeground, tween(300), label = "mode-muted")
        SideEffect { Sx.palette = SxPalette(pAccent, pBg, pCard, pFg, pMuted) }

        // Back returns to the cockpit from any other section instead of closing the app.
        BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

        MaterialTheme(colorScheme = darkColorScheme(primary = cyan, secondary = Color(0xFFFFB84D), background = bg, surface = panel, onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = muted)) {
            Scaffold(containerColor = bg) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).background(Sx.Background)) {
                SxTopBar(connected = connected, mode = driveMode, onMode = { m -> driveMode = m; prefs.edit().putString("drive_mode", m.name).apply() }, onPower = { disconnect(); finish() }, onEcuInfo = { selectedTab = 4 })
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
                                Surface(color = if (connSnapshot.ecuAvailable) Color(0xFF0B332F) else Color(0xFF252B35), shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(1.dp, if (connSnapshot.ecuAvailable) Color(0xFF26D7B1) else Color(0xFF566274))) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        LinkDot(connected = connSnapshot.ecuAvailable, connecting = connecting || connSnapshot.state == ConnectionState.RECOVERING || connSnapshot.state == ConnectionState.INITIALIZING_ADAPTER || connSnapshot.state == ConnectionState.ECU_INITIALIZING)
                                        Text(ConnectionPresenter.chip(connSnapshot), color = if (connSnapshot.ecuAvailable) Color(0xFF70F0D2) else muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                            AnimatedVisibility(visible = warningPreview || warningMessage.isNotBlank() || dataCheckBanner.isNotBlank(), enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 8 }, exit = fadeOut(tween(180))) {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF4B171D)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))) {
                                    Text(if (warningMessage.isNotBlank()) warningMessage else if (dataCheckBanner.isNotBlank()) dataCheckBanner else "WARNING SYSTEM PREVIEW — NOT A LIVE VEHICLE FAULT", Modifier.padding(12.dp), color = Color.White, fontWeight = FontWeight.Black)
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
                                    Modifier.weight(1f).fillMaxWidth(),
                                    onLayout = { sessionLog.append("EVT", it) }
                                )
                            } else {
                                val g = { l: String -> gauges.firstOrNull { it.label == l } ?: GaugeValue(l, "--", "", false) }
                                val spd = g("Vehicle speed")
                                val spdShown = spd.value.toDoubleOrNull()?.takeIf { spd.available }?.let { spd.copy(value = GaugeUnits.speedText(it, speedUnit), unit = speedUnit.label) } ?: spd.copy(unit = speedUnit.label)
                                val bst = g("Turbo boost")
                                val bstShown = bst.value.toDoubleOrNull()?.takeIf { bst.available }?.let { bst.copy(value = GaugeUnits.boostText(it, boostUnit), unit = boostUnit.label) } ?: bst.copy(unit = boostUnit.label)
                                val arrive by animateFloatAsState(if (welcomeOverlayVisible) 0f else 1f, tween(if (welcomeOverlayVisible) 1 else 1200), label = "cockpit-arrive")
                                SxCockpit(
                                    arrive = arrive,
                                    connected = connected,
                                    ecuOnline = ecuOnline,
                                    rpm = g("Engine RPM"), boost = bstShown, boostScale = GaugeUnits.boostScale(boostUnit),
                                    boostCaption = boostUnit.label.uppercase(),
                                    mode = driveMode,
                                    economy = run {
                                        val kmpl = Economy.kmPerLitre(
                                            g("Vehicle speed").takeIf { it.available }?.value?.toDoubleOrNull(),
                                            g("Engine fuel rate").takeIf { it.available }?.value?.toDoubleOrNull()
                                        )
                                        if (kmpl == null) GaugeValue("Fuel economy", "--", "km/L", false)
                                        else GaugeValue("Fuel economy", String.format(java.util.Locale.US, "%.1f", kmpl), "km/L", true, g("Engine fuel rate").capturedAtMs)
                                    },
                                    rail = g("Fuel rail pressure").let { r ->
                                        r.value.toDoubleOrNull()?.takeIf { r.available }
                                            ?.let { r.copy(value = String.format(java.util.Locale.US, "%.0f", it / 100.0), unit = "bar") } ?: r.copy(unit = "bar")
                                    },
                                    coolant = g("Coolant temperature"), intake = g("Intake air temperature"),
                                    egr = g("EGR commanded"), fuel = g("Fuel level"), battery = g("Control module voltage"),
                                    speed = g("Vehicle speed"),
                                    tripKm = if (connected && trip.dataMs > 0) trip.distanceKm else null,
                                    // 0 only when all three fault services answered cleanly; an inconclusive scan is flagged, never shown as clean.
                                    faultCount = dtcReport?.let { r -> if (r.codes.isNotEmpty()) r.codes.size else if (r.engineEcuReportsNoCodes) 0 else null },
                                    faultScanIncomplete = dtcReport?.let { r -> !r.isComplete } == true,
                                    headUnit = huSession, alertSensors = alertSensors,
                                    hotRpm = alertLimits.rpmMax?.let { it / DialScale.forLabel("Engine RPM").max },
                                    hotBoost = alertLimits.boostMaxKpa?.let { (GaugeUnits.boost(it.toDouble(), boostUnit) / GaugeUnits.boostScale(boostUnit).max).toFloat() },
                                    nowMs = nowMs,
                                    onOpenDiagnostics = { selectedTab = 1 }, onOpenHeadUnit = { selectedTab = 2 },
                                    onReplayWelcome = { playEngineWelcome() },
                                    receiverInstalled = readiness.receiverInstalled,
                                    onShowProjection = { headUnitStatus = receiverCommandStatus(HeadUnitProtocol.RAISE_PROJECTION, "Asked Open Headunit to bring the projection to the front.") },
                                    statusText = if (connected) adapterName else status,
                                    wallOn = showWall, onConnect = { connect() }, onDisconnect = { disconnect() }, onToggleWall = { showWall = !showWall },
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                            }
                            if (showWall) {
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
                        }
                        1 -> LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                DataCheckCard(dataFindings, dataTally, dataBaselines, dataSummary, connected, panel, cyan, muted) { resetDataCheckLearning() }
                            }
                            item {
                                ValidationCard(validation, validationPhase, panel, cyan, muted, { tagValidationPhase(it) }, { id, s -> recordValidationResult(id, s) })
                            }
                            item {
                                FuelLogCard(fuelFills, nowMs, panel, cyan, muted, { o, l -> addFillUp(o, l) }, { removeLastFillUp() })
                            }
                            item {
                                val tick = tripTick
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("TRIP COMPUTER", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        val elapsed = if (tripSession.hasTrip()) nowMs - tripSession.startMs else 0L
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
                                        if (tripSession.hasTrip()) Text(
                                            if (tripSession.phase == TripPhase.RECORDING) "Recording" else tripSession.pause?.text.orEmpty(),
                                            color = if (tripSession.phase == TripPhase.RECORDING) cyan else Color(0xFFFFB84D), fontSize = 14.sp, fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            if (l100 != null) String.format(java.util.Locale.US, "Fuel: %.1f L/100 km (from the ECU's fuel-rate reading)", l100)
                                            else "Fuel use: not available. It needs the ECU to report fuel rate (tick Engine fuel rate in Settings); a diesel's MAF reading cannot give a reliable figure.",
                                            color = muted, fontSize = 13.sp
                                        )
                                        Text("Distance is integrated from real speed readings only. Gaps in the data are skipped, never filled in. Each poll is also saved to a CSV file on this tablet.", color = muted, fontSize = 13.sp)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            OutlinedButton(onClick = { startTrip(forceNew = true) }, enabled = connected, modifier = Modifier.weight(1f)) { Text("NEW TRIP") }
                                            OutlinedButton(onClick = { endTrip() }, enabled = tripSession.hasTrip(), modifier = Modifier.weight(1f)) { Text("STOP TRIP") }
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
                                        dtcReport?.let { rep ->
                                            Text(DtcScanReport.SCOPE, color = muted, fontSize = 12.sp)
                                            Text("Scanned " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(rep.capturedAtMs)) + (if (rep.protocol.isNotBlank()) " • " + rep.protocol else ""), color = muted, fontSize = 12.sp)
                                            rep.modes.forEach { m ->
                                                Text(DtcScanText.modeLine(m), color = if (m.status.answered && m.status != DtcModeStatus.PARTIAL) Color.White else Color(0xFFFFB84D), fontSize = 13.sp)
                                            }
                                        }
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
                            if (dtcCodes.isEmpty()) item { Text(if (dtcReport == null) "No scan has been run yet." else "The engine ECU returned no DTCs in this scan (see the status of each service above).", color = muted) }
                            else items(dtcCodes) { result ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(result.code, color = Color(0xFFFFB84D), style = MaterialTheme.typography.titleLarge)
                                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                            Text(result.category, color = cyan, fontWeight = FontWeight.Bold)
                                            Text("ENGINE ECU", color = muted, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    val pres = DtcPresentation.of(result.code, result.category)
                                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(pres.title ?: "No description for this code in the app's catalogue; look it up in the service manual.", color = Color.White, fontSize = 14.sp)
                                        Text("ECU category: " + pres.categoryMeaning, color = muted, fontSize = 12.sp)
                                        pres.structure.forEach { Text(it, color = muted, fontSize = 12.sp) }
                                        if (pres.possibleCauses.isNotEmpty()) {
                                            Text("Possible causes (possibilities, not confirmed findings):", color = cyan, fontSize = 12.sp)
                                            pres.possibleCauses.forEach { Text("• $it", color = muted, fontSize = 12.sp) }
                                            Text("Sensible next checks:", color = cyan, fontSize = 12.sp)
                                            pres.nextChecks.forEach { Text("• $it", color = muted, fontSize = 12.sp) }
                                        }
                                        if (freezeResult?.dtc == result.code) Text("A freeze frame names this code (see below).", color = cyan, fontSize = 12.sp)
                                        Text("Reported by the engine ECU. This does not by itself show the fault is present right now.", color = muted, fontSize = 11.sp)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("FREEZE FRAME", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Sensor values the ECU stored when a fault was set (Mode 02). Read-only. Many ECUs return nothing when no fault is stored.", color = muted)
                                        Button(onClick = { readFreezeFrame() }, enabled = connected && !freezeBusy) { Text(if (freezeBusy) "READING…" else "READ FREEZE FRAME") }
                                        val fr = freezeResult
                                        if (fr != null) {
                                            Text(fr.headline(), color = if (fr.hasData) Color.White else Color(0xFFFFB84D))
                                            Text(FreezeFrameText.dtcLine(fr), color = Color.White)
                                            Text("Frame ${fr.frameNumber} • read " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(fr.capturedAtMs)), color = muted, fontSize = 12.sp)
                                            fr.items.forEach { Text(FreezeFrameText.itemLine(it), color = if (it.status.hasData) Color.White else Color(0xFFFFB84D), fontSize = 14.sp) }
                                            fr.notes.forEach { Text(it, color = muted, fontSize = 12.sp) }
                                        }
                                        if (freezeNote.isNotBlank()) Text(freezeNote, color = muted, fontSize = 13.sp)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("MAINTENANCE REMINDERS", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("Counts kilometres this app has tracked while connected (" + String.format(java.util.Locale.US, "%.0f", trackedKm) + " km so far). You set every interval; none are assumed. Distance driven without the app is not counted.", color = muted, fontSize = 13.sp)
                                        val mv = maintVersion
                                        Maintenance.ITEMS.forEach { name ->
                                            val iKey = Maintenance.key(name, "interval")
                                            val dKey = Maintenance.key(name, "done")
                                            val interval = prefs.getInt(iKey, -1).takeIf { it > 0 }
                                            val st = Maintenance.status(interval, trackedKm, prefs.getFloat(dKey, 0f).toDouble())
                                            key(mv) {
                                                LimitRow(name, interval, 1000..40000, 10000, 500, "km") { v -> prefs.edit().putInt(iKey, v ?: -1).apply(); maintVersion++ }
                                            }
                                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                Text(Maintenance.describe(st), color = if (st.state == Maintenance.State.OVERDUE) cyan else muted, fontWeight = if (st.state == Maintenance.State.OVERDUE) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                                if (interval != null) OutlinedButton(onClick = { prefs.edit().putFloat(dKey, trackedKm.toFloat()).apply(); maintVersion++ }) { Text("MARK DONE") }
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("HEALTH REPORT", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("A multi-page PDF with the current readings, every fault code from the last scan with its explanation, the freeze frame, the ECU identity, distance and maintenance status. Anything not read, and every failed request, is listed as such.", color = muted)
                                        Button(onClick = { shareHealthReport(dtcScanned, dtcCodes, gauges, connected, adapterName) }) { Text("SHARE HEALTH REPORT (PDF)") }
                                        if (reportNote.isNotBlank()) Text(reportNote, color = muted, fontSize = 13.sp)
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
                        4 -> SxEcuInfo(
                            profile = ecuProfile,
                            diff = ecuProfile?.let { cur -> ecuPrevious?.let { ProfileDiff.compare(it, cur) } },
                            linkText = EcuReport.linkText(linkState, connected),
                            connectionRows = ConnectionPresenter.rows(connSnapshot, System.currentTimeMillis()),
                            explorer = EcuExplorerView.build(parameterRegistry, lastDiscovery, ecuProfile, protocolText, if (connected && sessionStartMs > 0L) sessionStartMs else null),
                            liveFacts = AdapterFacts(if (connected) adapterName else "", adapterIdText, adapterVoltageText, protocolText),
                            mode01Count = supportedPids.size,
                            scanning = ecuScanning,
                            canScan = ecuOnline,
                            note = ecuNote,
                            onScan = { runEcuScan(scope) },
                            onExportJson = { exportEcu("json") },
                            onExportCsv = { exportEcu("csv") },
                            onExportPdf = { exportEcu("pdf") },
                            onBack = { selectedTab = 0 },
                            fromEarlierSession = ecuProfile?.let { EcuReport.isFromEarlierSession(it, processStartMs()) } == true
                        )
                        else -> LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("ALERT LIMITS (YOURS)", color = cyan, style = MaterialTheme.typography.titleMedium)
                                        Text("This app has no verified SX4 ZDi limits, so every alert is off until you choose a number. A limit shows a banner and speaks a caution after three fresh readings above it.", color = muted, fontSize = 13.sp)
                                        fun save(key: String, v: Int?) = prefs.edit().putInt(key, v ?: -1).apply()
                                        fun apply(next: AlertLimits) { alertLimits = next; warningEngine = WarningEngine(next.rules()); alertSensors = emptySet(); warningMessage = "" }
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
                                        fun saveWelcome(w: WelcomeSettings) {
                                            welcomeSettings = w
                                            prefs.edit().putFloat("welcome_duration", w.durationSec).putBoolean("welcome_sound", w.sound).putBoolean("welcome_every", w.everyConnection).putFloat("welcome_volume", w.volume).apply()
                                        }
                                        Text("Welcome length: " + String.format(java.util.Locale.US, "%.1f", welcomeSettings.durationSec) + " s", color = Color.White)
                                        Slider(
                                            value = welcomeSettings.durationSec,
                                            onValueChange = { saveWelcome(welcomeSettings.copy(durationSec = (Math.round(it * 2f) / 2f))) },
                                            valueRange = WelcomeSettings.MIN_DURATION_SEC..WelcomeSettings.MAX_DURATION_SEC
                                        )
                                        Text("Welcome volume: " + Math.round(welcomeSettings.volume * 100) + " %  (warnings always play at full volume)", color = Color.White)
                                        Slider(value = welcomeSettings.volume, onValueChange = { saveWelcome(welcomeSettings.copy(volume = WelcomeSettings.clampVolume(it))) }, valueRange = 0f..1f)
                                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            Text("Chime and spoken welcome", color = Color.White, modifier = Modifier.weight(1f))
                                            Switch(checked = welcomeSettings.sound, onCheckedChange = { saveWelcome(welcomeSettings.copy(sound = it)) })
                                        }
                                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            Text("Play on every OBD connection (off = once per app launch)", color = Color.White, modifier = Modifier.weight(1f))
                                            Switch(checked = welcomeSettings.everyConnection, onCheckedChange = { saveWelcome(welcomeSettings.copy(everyConnection = it)) })
                                        }
                                        Button(onClick = { playEngineWelcome() }, modifier = Modifier.fillMaxWidth()) {
                                            Text("PREVIEW WELCOME ANNOUNCEMENT")
                                        }
                                        Text("Preview plays the same chime and announcement without requiring the engine to start. The app prefers an installed English voice identified as female; if none is available, Android uses its English (UK) default voice.", color = muted)
                                        Divider(color = Color(0xFF26394C))
                                        Text("WARNING ALERT OUTPUT TEST", color = Color(0xFFFFB84D), style = MaterialTheme.typography.titleMedium)
                                        Text("Tests the dashboard warning banner, alert tone and spoken message. This is a manual preview only; no SX4 warning limits are enabled yet.", color = muted)
                                        Button(onClick = {
                                            warningPreview = true
                                            alertSensors = setOf("coolant")
                                            playAlert(setOf("coolant"))
                                            mainHandler.postDelayed({ warningPreview = false; alertSensors = emptySet() }, AlertTargets.SIREN_MS + 3000L)
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
                                                val header = "SX4 Scan Pro adapter log\n" + BuildInfo.LABEL + "\nStatus: " + status + "\nStandard PIDs advertised: " + supportedPids.size
                                                val send = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, traffic.asText(header))
                                                }
                                                startActivity(Intent.createChooser(send, "Share adapter log"))
                                            }, modifier = Modifier.weight(1f)) { Text("SHARE LOG") }
                                        }
                                        Button(onClick = {
                                            val header = "SX4 Scan Pro session log\n" + BuildInfo.LABEL + "\nStatus: " + status + "\nAdapter: " + adapterName + "\nStandard PIDs advertised: " + supportedPids.size
                                            val file = sessionLog.export(header)
                                            if (file == null) logPreview = listOf("Could not write the session log file.") else {
                                                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                                                val send = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    putExtra(Intent.EXTRA_SUBJECT, "SX4 Scan Pro session log")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                startActivity(Intent.createChooser(send, "Share session log"))
                                            }
                                        }, modifier = Modifier.fillMaxWidth()) { Text("SHARE FULL SESSION LOG (FILE)") }
                                        OutlinedButton(onClick = { exportChoice = true }, modifier = Modifier.fillMaxWidth()) { Text("EXPORT COMPLETE DIAGNOSTIC SESSION") }
                                        Text(if (logPreview.isEmpty()) "Tap REFRESH to show the latest adapter traffic." else logPreview.joinToString("\n"), color = Color.White, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    }
                                }
                            }
                            item { Text("Build " + BuildInfo.LABEL, color = muted) }
                        }
                            }
                        }
                        Crossfade(targetState = welcomeOverlayVisible, animationSpec = tween(550), label = "welcome-overlay-fade") { visible ->
                            if (visible) {
                                SxWelcome(durationMs = welcomeSettings.durationMs(reducedMotionOn()), onEnter = { welcomeOverlayVisible = false })
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



    /** Wall-clock time this app process started. A saved ECU profile older than this was read in an earlier run. */
    private fun processStartMs(): Long =
        System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())

    private fun exportEcu(kind: String) {
        val p = ecuProfile ?: return
        try {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date(p.capturedAtMs))
            val file = java.io.File(ecuDir(), "ecu_profile_$stamp.$kind")
            val mime: String
            when (kind) {
                "json" -> { file.writeText(p.toJson()); mime = "application/json" }
                "csv" -> { file.writeText(p.toCsv()); mime = "text/csv" }
                else -> {
                    val diff = ecuPrevious?.let { ProfileDiff.compare(it, p) }
                    val lines = EcuReport.lines(p, diff).flatMap { it.chunked(88) }
                    val doc = android.graphics.pdf.PdfDocument()
                    val paint = android.graphics.Paint().apply { textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE }
                    var pageNo = 1
                    var page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
                    var y = 50f
                    for (line in lines) {
                        if (y > 800f) { doc.finishPage(page); pageNo++; page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNo).create()); y = 50f }
                        page.canvas.drawText(line, 36f, y, paint)
                        y += 14f
                    }
                    doc.finishPage(page)
                    java.io.FileOutputStream(file).use { doc.writeTo(it) }
                    doc.close()
                    mime = "application/pdf"
                }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SX4 Scan Pro ECU information")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share ECU information"))
            ecuNote = "Saved " + file.name
        } catch (e: Exception) {
            ecuNote = "Export failed: " + (e.message ?: "storage error")
        }
    }

    /** P7: one ZIP with manifest, session events, structured capture, registry summary, ECU profile and validation results. Stored data is not changed; read-only. */
    private fun exportCompleteSession(redact: Boolean, scope: kotlinx.coroutines.CoroutineScope, report: (String) -> Unit) {
        scope.launch {
            val snap = connSnapshot
            val live = snap.state != ConnectionState.DISCONNECTED && snap.state != ConnectionState.ERROR
            // One snapshot of the saved validation text (the persisted source of truth), taken once for this export.
            val validationSnapshot = try { ValidationSnapshot.fromSavedText(prefs.getString("validation", null)) }
                catch (e: Exception) { ValidationSnapshot.unavailable(e.message ?: e.javaClass.simpleName) }
            val result = withContext(Dispatchers.IO) {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                val dir = java.io.File(filesDir, "logs")
                dir.listFiles { f -> f.name.startsWith("export-") }?.forEach { it.delete() }
                SessionExport.build(
                    java.io.File(dir, "export-$stamp" + (if (redact) "-redacted" else "") + ".zip"),
                    ExportRequest(BuildInfo.LABEL, adapterNameForScan, protocolText, captureSession, live, redact, System.currentTimeMillis()),
                    ExportSources(sessionLog.files(), capture.files(), parameterRegistry, ecuProfile, validationSnapshot)
                )
            }
            val file = result.file
            if (file == null) { report("Could not write the export: " + (result.error ?: "unknown error")); return@launch }
            runCatching {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "SX4 Scan Pro diagnostic session")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share diagnostic session"))
            }.onFailure { report("Export saved as " + file.name + " but could not be shared: " + (it.message ?: "")); return@launch }
            report((if (result.complete) "Export ready (" else "Export ready, marked INCOMPLETE: " + (result.manifest["completenessIssues"] as? List<*>)?.joinToString("; ") + " (") + (if (redact) "redacted" else "unredacted") + ")")
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
        val extras = (extraPids + HOME_PIDS).sorted().mapNotNull { PidCatalog.forPid(it) }.map { spec ->
            if (spec.pid !in supportedPids) GaugeValue(spec.name, "--", spec.unit, false)
            else {
                val v = PidCatalog.decode(spec, sendCommand(spec.command))
                if (v != null) GaugeValue(spec.name, PidCatalog.format(spec, v), spec.unit, true) else GaugeValue(spec.name, "--", spec.unit, false)
            }
        }
        return core + extras
    }


    private fun parsePid(command: String, response: String): String? =
        ObdPidResponseDecoder.decode(command, response)

    @Volatile private var welcomePending = false
    @Volatile private var welcomeChimeFinished = false

    private var siren: android.media.MediaPlayer? = null
    private var alertBusyUntilMs = 0L

    /** Siren for [AlertTargets.SIREN_MS], then the spoken "problem in vehicle" message. Re-triggers are ignored while it plays. */
    private fun playAlert(sensors: Set<String>) {
        val now = System.currentTimeMillis()
        if (now < alertBusyUntilMs) return
        alertBusyUntilMs = now + AlertTargets.SIREN_MS + 4000L
        try {
            siren?.release()
            siren = android.media.MediaPlayer.create(this, R.raw.alarm_tone)?.apply { setVolume(1f, 1f); start() }
        } catch (_: Exception) {
            chime?.startTone(ToneGenerator.TONE_PROP_NACK, 520)
        }
        mainHandler.postDelayed({
            runCatching { siren?.let { if (it.isPlaying) it.stop() }; siren?.release(); siren = null }
            if (speechReady) textToSpeech?.speak(AlertTargets.speech(sensors), TextToSpeech.QUEUE_FLUSH, null, "sx4_problem_in_vehicle")
        }, AlertTargets.SIREN_MS)
    }

    private fun reducedMotionOn(): Boolean =
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun playEngineWelcome() {
        welcomeOverlayVisible = true
        val shownMs = welcomeSettings.durationMs(reducedMotionOn())
        mainHandler.postDelayed({ welcomeOverlayVisible = false }, shownMs + 400L)
        if (!welcomeSettings.sound) { welcomePending = false; return }
        welcomePending = true
        welcomeChimeFinished = false
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
            track.setVolume(0.72f * welcomeSettings.volume)
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
        textToSpeech?.speak("Welcome to SX4 Airlines 2874", TextToSpeech.QUEUE_FLUSH, android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, welcomeSettings.volume) }, "sx4_engine_start")
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


    private fun savedTrips(): List<java.io.File> =
        java.io.File(filesDir, "trips").listFiles { f -> f.extension == "csv" }?.sortedBy { it.name }.orEmpty()


    private fun readFreezeFrame() {
        if (freezeBusy) return
        freezeBusy = true
        freezeNote = ""
        uiScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { readFreezeFrameBlocking() }
                freezeResult = result
                sessionLog.append("EVT", "freeze frame done: " + result.headline())
            } catch (e: Exception) {
                freezeNote = "Could not read freeze frame: " + (e.message ?: "unknown error")
            } finally { freezeBusy = false }
        }
    }

    private fun shareHealthReport(scanned: Boolean, codes: List<TroubleCodeResult>, gauges: List<GaugeValue>, isConnected: Boolean, adapterName: String) {
        try {
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            val readings = gauges.filter { it.available && it.label != "Manifold pressure" }.map { Triple(it.label, it.value, it.unit) }
            val maint = Maintenance.ITEMS.map { n ->
                val interval = prefs.getInt(Maintenance.key(n, "interval"), -1).takeIf { it > 0 }
                n to Maintenance.describe(Maintenance.status(interval, trackedKm, prefs.getFloat(Maintenance.key(n, "done"), 0f).toDouble()))
            }.filter { it.second != "No interval set" }
            val unavailable = gauges.filter { !it.available && it.label != "Manifold pressure" }.map { it.label }
            // Everything collected in this session goes into the report: the full per-service fault scan, the freeze frame
            // and the ECU identity as well as the live readings. Items that were not read are listed as not read.
            val lines = ReportLayout.wrap(HealthReport.lines(HealthReport.Input(
                now, isConnected, adapterName, readings,
                if (scanned) codes.map { it.code to it.category } else null,
                if (isConnected && trip.dataMs > 0) trip.distanceKm else null, trackedKm, maint,
                dtcScan = dtcReport, freezeFrame = freezeResult, ecuProfile = ecuProfile, unavailableReadings = unavailable,
                ruleReadings = if (isConnected) rt.ruleSnapshot else emptyList(),
                ruleNowMs = System.currentTimeMillis(),
                validation = ValidationPlan.reportLines(validation)
            )), 76)
            // The report is paginated and wrapped: no line is cut off and nothing is dropped when it runs past one page.
            val pages = ReportLayout.paginate(lines, 50)
            val doc = android.graphics.pdf.PdfDocument()
            val paint = android.graphics.Paint().apply { textSize = 11f; isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE }
            pages.forEachIndexed { pageIndex, pageLines ->
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
                var y = 48f
                pageLines.forEachIndexed { i, line ->
                    val isTitle = pageIndex == 0 && i == 0
                    paint.isFakeBoldText = isTitle || line.isNotEmpty() && line == line.uppercase() && !line.startsWith(" ")
                    paint.textSize = if (isTitle) 15f else 11f
                    page.canvas.drawText(line, 40f, y, paint)
                    y += if (isTitle) 24f else 15f
                }
                paint.isFakeBoldText = false
                paint.textSize = 9f
                page.canvas.drawText("Page ${pageIndex + 1} of ${pages.size}", 40f, 830f, paint)
                doc.finishPage(page)
            }
            val dir = java.io.File(filesDir, "reports").apply { mkdirs() }
            val file = java.io.File(dir, "health_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".pdf")
            java.io.FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SX4 Scan Pro health report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share health report"))
            reportNote = "Report saved as " + file.name
        } catch (e: Exception) {
            reportNote = "Could not create the report: " + (e.message ?: "unknown error")
        }
    }

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
        // Detach UI hooks. A live session (socket, poll loop, trip) belongs to ObdRuntime and keeps recording.
        rt.onConnected = null
        rt.onAlert = null
        rt.onDataAlert = null
        if (!rt.sessionActive()) { rt.holdTrip(); TripService.hide(this) }
        runCatching { if (BluetoothAdapter.getDefaultAdapter()?.isDiscovering == true) BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
        mainHandler.removeCallbacksAndMessages(null)
        if (isFinishing) sessionLog.append("EVT", "app closed")
        runCatching { siren?.release(); siren = null }
        uiScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
