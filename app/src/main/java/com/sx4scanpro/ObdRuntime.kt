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
import android.content.ContextWrapper
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

/**
 * Process-owned OBD session: the Bluetooth socket, the one serialized command pipeline, the poll loop, recovery,
 * trip recording and the diagnostics entry points. It outlives MainActivity (rotation, recreation, swipe-away),
 * which only observes its state and may attach audio hooks. The foreground service keeps this process alive.
 */
internal class ObdRuntime private constructor(app: Context) : ContextWrapper(app) {
    companion object {
        @Volatile private var instance: ObdRuntime? = null
        fun get(ctx: Context): ObdRuntime = instance ?: synchronized(this) { instance ?: ObdRuntime(ctx.applicationContext).also { instance = it } }
    }

    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    val prefs by lazy { getSharedPreferences("sx4_scan_pro", MODE_PRIVATE) }
    var status by mutableStateOf("Not connected")
    var adapterName by mutableStateOf("Vgate iCar Pro • Bluetooth Classic")
    var gauges by mutableStateOf(listOf(
        GaugeValue("Engine RPM", "--", "rpm", false), GaugeValue("Vehicle speed", "--", "km/h", false),
        GaugeValue("Coolant temperature", "--", "°C", false), GaugeValue("Intake air temperature", "--", "°C", false),
        GaugeValue("Manifold pressure", "--", "kPa", false), GaugeValue("Turbo boost", "--", "kPa", false),
        GaugeValue("MAF airflow", "--", "g/s", false), GaugeValue("Calculated engine load", "--", "%", false),
        GaugeValue("Throttle position", "--", "%", false), GaugeValue("EGR commanded", "--", "%", false)
    ))
    var warningMessage by mutableStateOf("")
    val history = GaugeHistory()
    var historyVersion by mutableStateOf(0)
    /** UI hooks (audio). Null while no Activity is attached; the session itself never depends on them. */
    var onConnected: (() -> Unit)? = null
    var onAlert: ((Set<String>) -> Unit)? = null

    fun sessionActive(): Boolean = socket != null || connection.snapshot.busy || recovery.isRecovering()

    var selectedAddress by mutableStateOf<String?>(null)

    var connConfig by mutableStateOf(ConnectionConfig())
    var extraPids by mutableStateOf<Set<Int>>(emptySet())

    val tripSession = TripSession()
    val trip: TripStats get() = tripSession.stats
    var lastTripPersistMs = 0L

    var lastTripNotice = ""

    var alertSensors by mutableStateOf(emptySet<String>())

    var trackedKm by mutableStateOf(0.0)

    var tripTick by mutableStateOf(0)
    var tripStartMs by mutableStateOf(0L)
    var tripWriter: java.io.BufferedWriter? = null
    var tripFile: java.io.File? = null
    var tripHeaderWritten = false
    var tripNote by mutableStateOf("")

    var socket: BluetoothSocket? = null
    var supportedPids: Set<Int> = emptySet()
    @Volatile var protocolText: String = ""
    var ecuProfile by mutableStateOf<EcuProfile?>(null)
    var ecuPrevious by mutableStateOf<EcuProfile?>(null)
    var ecuScanning by mutableStateOf(false)
    var ecuNote by mutableStateOf("")
    @Volatile var adapterIdText: String = ""
    @Volatile var adapterVoltageText: String = ""
    var linkState by mutableStateOf(LinkState.COMMUNICATING)

    // Empty until verified SX4-specific rules are approved; pipeline is wired but inactive.
    @Volatile var warningEngine = WarningEngine()

    @Volatile var userInitiatedDisconnect = false
    /** P5: the one connection state machine, its recovery driver and the poll-stall watchdog. */
    var connSnapshot by mutableStateOf(ConnectionSnapshot())
    val connection = ConnectionMachine { old, new ->
        connSnapshot = new
        if (old.state != new.state || old.failure != new.failure) {
            sessionLog.append("EVT", "connection " + old.state + " -> " + new.state + (if (new.reason.isNotBlank()) ": " + new.reason else ""))
        }
    }
    val recoveryPolicy = RecoveryPolicy()
    val stallWatchdog = StallWatchdog(recoveryPolicy.stallMs)
    @Volatile var pollStalled = false
    val recoveryPorts = object : RecoveryPorts {
        override fun closeSocket() { this@ObdRuntime.closeSocket() }
        /** The same set-up as the first connection, minus discovery (no new PID requests). Throws when the ECU does not answer. */
        override fun reconnect(): Boolean { connectAndRead(reconnecting = true); return true }
        override fun pausePolling(): Boolean = pollGate.pause()
        override fun resumePolling() = pollGate.resume()
        override fun sleep(ms: Long) {
            var left = ms
            while (left > 0 && !aborted()) { val step = minOf(left, 250L); Thread.sleep(step); left -= step }
        }
        override fun aborted(): Boolean = userInitiatedDisconnect
        override fun now(): Long = System.currentTimeMillis()
        override fun log(message: String) { sessionLog.append("EVT", message) }
    }
    val recovery by lazy { RecoverySupervisor(connection, recoveryPorts, recoveryPolicy) }
    val traffic = TrafficLog()
    val sessionLog by lazy { SessionLog(java.io.File(filesDir, "logs")) }
    val capture by lazy { CaptureStore(java.io.File(filesDir, "capture")) }
    @Volatile var captureSession: String = "S0"
    /** Structured, lossless record of one adapter transaction (see RawExchange). Never throws. */
    fun recordExchange(command: String, raw: String, status: String, t0: Long, error: String? = null) {
        stallWatchdog.progress(System.currentTimeMillis())
        capture.record(captureSession, System.currentTimeMillis(), command, raw, status, System.currentTimeMillis() - t0, protocolText, error = error)
    }
    /** One place for adapter traffic: the on-screen preview and the persistent, shareable file. */
    fun logTraffic(tag: String, text: String) { traffic.add(tag, text); sessionLog.append(tag, text) }

    /** [reconnecting] repeats the same set-up after a lost link: same adapter init and header/protocol restore, no discovery, same capture session. */
    fun connectAndRead(reconnecting: Boolean = false): Pair<String, Unit> {
        if (!reconnecting) captureSession = "S" + System.currentTimeMillis()
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
        connection.socketOpened()
        connection.adapterInitializing()
        val config = connConfig
        sendCommand("ATZ", 5000)
        config.initCommands().forEach { sendCommand(it) }
        // Facts for the session log only; a failed query here never blocks connecting.
        sessionLog.append("EVT", "connect: adapter=" + chosen.name + " " + chosen.address)
        adapterNameForScan = chosen.name
        adapterIdText = runCatching { sendCommand("ATI", 3000) }.getOrDefault("")
        adapterVoltageText = runCatching { sendCommand("ATRV", 3000) }.getOrDefault("")
        // Adapter self-description for the session log only (helps tell a genuine ELM327 from a clone). Read-only AT queries; a failure here never blocks connecting.
        sessionLog.append("EVT", "adapter ATI=" + adapterIdText)
        sessionLog.append("EVT", "adapter AT@1=" + runCatching { sendCommand("AT@1", 3000) }.getOrElse { "failed: " + (it.message ?: it.javaClass.simpleName) })
        sessionLog.append("EVT", "adapter AT@2=" + runCatching { sendCommand("AT@2", 3000) }.getOrElse { "failed: " + (it.message ?: it.javaClass.simpleName) })
        connection.ecuInitializing()
        // "Connected" must mean the ECU answered, not just that the adapter did.
        val negotiation = EcuNegotiation { command, timeoutMs -> sendCommand(command, timeoutMs) }
            .apply { preferredProtocol = config.protocol.elmNumber }.run()
        if (negotiation.supportedPids.isEmpty()) {
            closeSocket()
            throw IOException(negotiation.failure)
        }
        supportedPids = negotiation.supportedPids
        // The protocol is only known once the ECU has answered, so ATDP is asked now, not before negotiation.
        protocolText = runCatching { sendCommand("ATDP", 3000) }.getOrDefault("")
        sessionLog.append("EVT", "protocol=" + protocolText + " framing=" + Framing.fromProtocolText(protocolText))
        // P3: the same discovery path a later manual request uses. It never decides whether the connection succeeds.
        if (!reconnecting) {
            pollingLive = false
            runCatching { requestDiscovery("connect") }
            // Mode 09 identity (VIN, calibration IDs, ECU name) once, when none is saved yet or it is old. Polling has not started.
            autoEcuScanAtConnect()
        }
        return (device.name ?: "Bluetooth OBD adapter") to Unit
    }

    /** Secure SPP first, then insecure SPP: many ELM327 clones only accept the second. */
    fun openSocket(device: BluetoothDevice): BluetoothSocket {
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
    fun sendCommand(command: String, timeoutMs: Long = 5000, keepLines: Boolean = false, timedOut: TimeoutFlag? = null, change: ChangeAuthorization? = null): String {
        // Single policy gate for every command: anything not on the read-only allowlist never reaches the adapter, except the one
        // action a valid, unused ChangeAuthorization names (see ChangeTier.kt).
        ReadOnlyGuard.check(command).let { first ->
            val v = if (first is GuardVerdict.Blocked && change != null) ChangeGuard.check(command, change, System.currentTimeMillis()) else first
            if (v is GuardVerdict.Blocked) {
                logTraffic("BLOCKED", "$command: ${v.reason}")
                recordExchange(command, "", RawExchange.STATUS_BLOCKED, System.currentTimeMillis(), v.reason)
                throw BlockedCommandException(command, v.reason)
            }
        }
        val s = socket ?: throw IOException("Not connected")
        if (!s.isConnected) throw IOException("OBD adapter connection is closed")
        val t0 = System.currentTimeMillis()
        val out = StringBuilder()

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
            logTraffic("TX", command)
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
            if (!promptReceived) {
                // Opt-in (live polling only): try to bring a late/busy adapter back to its prompt instead of dropping the link.
                if (timedOut != null && resyncStream(s)) {
                    timedOut.hit = true
                    recordExchange(command, out.toString(), RawExchange.STATUS_TIMEOUT, t0, "timed out after $timeoutMs ms, adapter prompt recovered")
                    logTraffic("ERR", "$command: timed out after ${timeoutMs} ms, adapter prompt recovered")
                    return ""
                }
                throw IOException("Timed out waiting for OBD response to $command")
            }
            recordExchange(command, out.toString(), RawExchange.statusOf(out.toString()), t0)
            logTraffic("RX", out.toString().trim().replace("\r", " ⏎ "))
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
            recordExchange(command, out.toString(), RawExchange.STATUS_ERROR, t0, e.message ?: e.javaClass.simpleName)
            logTraffic("ERR", "$command: ${e.message ?: e.javaClass.simpleName}")
            closeSocket()
            throw if (e is IOException) e else IOException("OBD transaction failed for $command", e)
        }
    }

    /** A lone CR interrupts a busy ELM327 (it prints STOPPED) or repeats a read-only command; either way a `>` means the stream is clean again. */
    fun resyncStream(s: BluetoothSocket): Boolean {
        return try {
            s.outputStream.write("\r".toByteArray(Charsets.US_ASCII)); s.outputStream.flush()
            val input = s.inputStream
            val end = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < end) {
                if (input.available() > 0) { if (input.read().toChar() == '>') return true } else Thread.sleep(12)
            }
            false
        } catch (_: Exception) { false }
    }

    val elmPort = object : ElmPort {
        override fun send(command: String, timeoutMs: Long): ElmResult {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val flag = TimeoutFlag()
            val raw = sendCommand(command, timeoutMs, timedOut = flag)
            return ElmResult(command, raw, ElmReply.classify(raw), android.os.SystemClock.elapsedRealtime() - t0, flag.hit)
        }
        override fun sendLines(command: String, timeoutMs: Long): ElmResult {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val flag = TimeoutFlag()
            val raw = sendCommand(command, timeoutMs, keepLines = true, timedOut = flag)
            return ElmResult(command, raw, ElmReply.classify(raw), android.os.SystemClock.elapsedRealtime() - t0, flag.hit)
        }
    }

    /** One bounded slice of polling (about 0.4 s): one command at a time, then control returns so the UI can refresh. */
    fun runPollSlice(poller: LivePoller): PollEvent.Recovery? {
        val end = android.os.SystemClock.elapsedRealtime() + 400
        while (android.os.SystemClock.elapsedRealtime() < end) {
            when (val ev = poller.step(System.currentTimeMillis())) {
                is PollEvent.Recovery -> { sessionLog.append("EVT", "ECU recovery " + ev.step + (if (ev.recovered) " succeeded" else " no answer")); return ev }
                is PollEvent.Idle -> return null
                is PollEvent.Polled -> {
                    // Real replies feed the capability registry (VERIFIED / DECODED); the file is written rarely, see RegistrySaveGate.
                    liveFeed.onPolled(ev.pid, ev.reading, ev.kind)
                    if (liveFeed.takeDirty()) registrySaveGate.markDirty()
                    if (registrySaveGate.shouldSave(System.currentTimeMillis())) flushRegistry()
                }
            }
            if (poller.monitor.state != LinkState.COMMUNICATING) return null
        }
        return null
    }

    val registryStore by lazy { RegistryStore(java.io.File(filesDir, "registry/parameters.json")) }
    val liveFeed = LiveRegistryFeed({ parameterRegistry })
    val registrySaveGate = RegistrySaveGate()

    // ---- data check (sensor sanity, learned normal values, trip tally) and fuel log ----
    val dataCheck by lazy {
        DataCheckMonitor(Baselines.fromJson(runCatching { java.io.File(filesDir, "datacheck/baselines.json").takeIf { it.exists() }?.readText() }.getOrNull()))
    }
    var dataFindings by mutableStateOf<List<DataFinding>>(emptyList())
    var dataTally by mutableStateOf<List<TallyRow>>(emptyList())
    var dataBaselines by mutableStateOf<List<BaselineRow>>(emptyList())
    var dataSummary by mutableStateOf(TripSummary(0, 0, 0, 0, 0, null))
    var dataCheckBanner by mutableStateOf("")
    var fuelFills by mutableStateOf<List<FillUp>>(emptyList())
    val announcedFindings = mutableSetOf<String>()
    var lastBaselineSaveMs = 0L
    /** Audio hook for a data-check ALERT; null with no Activity (the finding still shows and is logged). */
    var onDataAlert: (() -> Unit)? = null

    /** Called about once a second while the ECU is answering. Only readings that are still fresh are given to the monitor. */
    fun runDataCheck(nowMs: Long, poller: LivePoller) {
        val values = HashMap<Int, Double>()
        for ((pid, _, _) in DataCheckMonitor.TALLIED) {
            val r = poller.reading(pid) ?: continue
            val maxAge = when (pid) { 0x0C, 0x0D -> 5_000L; 0x33 -> 90_000L; else -> 25_000L }
            if (nowMs - r.atMs <= maxAge) values[pid] = r.value
        }
        dataCheck.update(nowMs, values)
        val found = dataCheck.findings()
        dataFindings = found
        dataSummary = dataCheck.summary()
        if (nowMs - dataTallyMs >= 3_000L) { dataTallyMs = nowMs; dataTally = dataCheck.tally(); dataBaselines = dataCheck.baselineRows() }
        dataCheckBanner = found.firstOrNull { it.level >= CheckLevel.WARN }?.let { "CHECK: " + it.title } ?: ""
        announcedFindings.retainAll(found.map { it.id }.toSet())
        for (f in found) if (announcedFindings.add(f.id)) {
            sessionLog.append("EVT", "data check ${f.level} ${f.id}: ${f.detail}")
            if (f.level == CheckLevel.ALERT) onDataAlert?.invoke()
        }
        if (nowMs - lastBaselineSaveMs >= 60_000L) saveBaselines(nowMs)
    }
    var dataTallyMs = 0L
    /** Latest raw readings (own timestamps) for the health rules; the rules drop anything stale, so an old snapshot cannot read as live. */
    @Volatile var ruleSnapshot: List<RuleReading> = emptyList()

    fun saveBaselines(nowMs: Long = System.currentTimeMillis()) {
        lastBaselineSaveMs = nowMs
        val json = dataCheck.baselinesJsonIfDirty() ?: return
        runCatching {
            val f = java.io.File(filesDir, "datacheck/baselines.json"); f.parentFile?.mkdirs()
            val tmp = java.io.File(f.parentFile, "baselines.json.tmp"); tmp.writeText(json); tmp.renameTo(f)
        }.onFailure { sessionLog.append("EVT", "data check references could not be saved") }
    }

    fun resetDataCheckLearning() {
        dataCheck.resetLearning(); saveBaselines()
        runCatching { java.io.File(filesDir, "datacheck/baselines.json").delete() }
        dataBaselines = dataCheck.baselineRows()
        sessionLog.append("EVT", "data check: learned normal values were reset by the owner")
    }

    fun addFillUp(odoKm: Double?, litres: Double?): String? {
        FuelLog.validate(fuelFills, odoKm, litres)?.let { return it }
        fuelFills = (fuelFills + FillUp(odoKm!!, litres!!, System.currentTimeMillis(), true)).sortedBy { it.odoKm }
        prefs.edit().putString("fuel_log", FuelLog.serialize(fuelFills)).apply()
        return null
    }

    fun removeLastFillUp() {
        fuelFills = fuelFills.dropLast(1)
        prefs.edit().putString("fuel_log", FuelLog.serialize(fuelFills)).apply()
    }

    /** Writes the registry now if a live state change is waiting to be saved. Safe to call from any thread, cheap when nothing changed. */
    @Synchronized
    fun flushRegistry() {
        if (!registrySaveGate.hasPending()) return
        val ok = runCatching { registryStore.save(parameterRegistry) }.getOrDefault(false)
        registrySaveGate.saved(System.currentTimeMillis())
        if (!ok) sessionLog.append("EVT", "parameter registry could not be saved")
    }
    /** Persisted capability states (P2). Loaded at start; nothing writes to it until the discovery phase. */
    var parameterRegistry: ParameterRegistry = ParameterRegistry.withBuiltInDefinitions()

    fun loadParameterRegistry() {
        val res = registryStore.load()
        parameterRegistry = res.registry
        sessionLog.append("EVT", "parameter registry loaded: ${res.registry.all().size} definitions" + res.issues.joinToString("") { "; $it" })
    }

    /** Lets ECU discovery borrow the adapter from the live poll loop (P3). */
    val pollGate = PollGate()
    @Volatile var pollingLive = false

    fun discoveryRefusal(): String? = when {
        socket?.isConnected != true -> "adapter not connected"
        ecuScanning -> "an ECU scan is running"
        pollingLive && linkState != LinkState.COMMUNICATING -> "ECU link is not communicating"
        else -> null
    }

    val discoveryController by lazy {
        DiscoveryController(
            gate = pollGate,
            registry = { parameterRegistry },
            persist = { registryStore.save(it) },
            clock = { System.currentTimeMillis() },
            log = { sessionLog.append("EVT", it) },
            refusal = { discoveryRefusal() },
            discover = {
                val cfg = connConfig
                PidDiscovery(
                    send = { c, t -> elmPort.send(c, t) },
                    framing = Framing.fromProtocolText(protocolText),
                    reinit = { (listOf("ATZ") + cfg.initCommands()).forEach { elmPort.send(it, 5000L) } },
                    exclusive = { block -> synchronized(this@ObdRuntime) { block() } }
                ).run()
            }
        )
    }

    /**
     * Mode 01 ECU discovery: reads the supported-PID bitmaps, updates and saves the parameter registry. Used at connect time and
     * for any later request, so both share one implementation. Read-only. Blocking: call from a background thread. No UI yet.
     */
    internal fun requestDiscovery(reason: String = "manual"): DiscoveryOutcome = discoveryController.request(reason).also { lastDiscovery = it }

    /** Latest discovery result of this app run (read-only; shown on the ECU Information screen). */
    var lastDiscovery by mutableStateOf<DiscoveryOutcome?>(null)
    /** When the current connection began; evidence recorded earlier is shown as historical. */
    var sessionStartMs by mutableStateOf(0L)

    fun ecuDir() = java.io.File(filesDir, "ecu").apply { mkdirs() }

    fun loadEcuProfiles() {
        runCatching {
            ecuProfile = java.io.File(ecuDir(), "profile_latest.json").takeIf { it.exists() }?.let { EcuProfile.fromJson(it.readText()) }
            ecuPrevious = java.io.File(ecuDir(), "profile_previous.json").takeIf { it.exists() }?.let { EcuProfile.fromJson(it.readText()) }
        }
    }

    /** Runs the read-only identity scan and stores the profile (latest + previous). Blocking: call from a background thread. */
    fun performEcuScan(): EcuProfile {
        val facts = AdapterFacts(adapterNameForScan, adapterIdText, adapterVoltageText, protocolText)
        val profile = EcuScan.run(elmPort, facts, supportedPids, BuildInfo.LABEL, System.currentTimeMillis()) { sessionLog.append("EVT", "ecu scan: $it") }
        val dir = ecuDir()
        val latest = java.io.File(dir, "profile_latest.json")
        if (latest.exists()) latest.copyTo(java.io.File(dir, "profile_previous.json"), overwrite = true)
        latest.writeText(profile.toJson())
        ecuPrevious = ecuProfile
        ecuProfile = profile
        runCatching { readCapabilityProbe() }.onFailure { sessionLog.append("EVT", "capability probe failed: " + (it.message ?: it.javaClass.simpleName)) }
        sessionLog.append("EVT", "ecu scan done: vin=" + profile.vin.status + " cal=" + profile.calIds.status + " cvn=" + profile.cvns.status)
        return profile
    }

    /** Latest readiness (01 01) and Mode 06 probe (06 00) of this app run. Both are plain reads. */
    @Volatile var capability: CapabilitySnapshot? = null

    fun readCapabilityProbe(): CapabilitySnapshot {
        val rRaw = sendCommand("0101", 6000)
        val mRaw = sendCommand("0600", 6000)
        val snap = CapabilitySnapshot(System.currentTimeMillis(), ReadinessDecoder.decode(rRaw), rRaw, Mode06Probe.classify(mRaw))
        capability = snap
        sessionLog.append("EVT", "capability: readiness raw=" + rRaw.replace("\n", " ") + " | mode06 " + snap.mode06.outcome + " raw=" + mRaw.replace("\n", " "))
        return snap
    }

    /** Once per fresh connection, when there is no saved profile or it is more than a day old (see [EcuScanPolicy]). Never decides whether the connection succeeds. */
    fun autoEcuScanAtConnect() {
        if (ecuScanning || !EcuScanPolicy.dueAtConnect(ecuProfile, System.currentTimeMillis())) return
        ecuScanning = true
        try {
            ecuNote = "Reading ECU identity (read-only)…"
            ecuNote = EcuReport.scanSummary(performEcuScan())
        } catch (e: Exception) {
            sessionLog.append("EVT", "ecu scan at connect failed: " + (e.message ?: e.javaClass.simpleName))
            ecuNote = "Scan failed: " + (e.message ?: "communication error")
            if (socket?.isConnected != true) throw e        // a lost socket must reach the normal connection handling
        } finally { ecuScanning = false }
    }

    fun runEcuScan(scope: kotlinx.coroutines.CoroutineScope) {
        if (ecuScanning || discoveryController.isRunning()) return
        scope.launch {
            ecuScanning = true
            ecuNote = "Scanning the ECU (read-only)…"
            try {
                val profile = withContext(Dispatchers.IO) { performEcuScan() }
                ecuNote = EcuReport.scanSummary(profile)
            } catch (e: Exception) {
                sessionLog.append("EVT", "ecu scan failed: " + (e.message ?: e.javaClass.simpleName))
                ecuNote = "Scan failed: " + (e.message ?: "communication error")
            } finally { ecuScanning = false }
        }
    }

    @Volatile var adapterNameForScan: String = ""

    fun buildPoller(): LivePoller {
        val scheduler = PollScheduler().also { PollPlan.configure(it, extraPids + HOME_PIDS) }
        val caps = PidCapabilities().also { it.setAdvertised(supportedPids) }
        val cfg = connConfig
        return LivePoller(
            port = elmPort, scheduler = scheduler, capabilities = caps,
            lightReinit = listOf("ATPC"),
            fullReinit = listOf("ATZ") + cfg.initCommands()
        )
    }

    // DTC modes are one transaction: do not interleave them with live PID polling.
    @Synchronized
    fun readTroubleCodes(): DtcScanReport {
        val framing = Framing.fromProtocolText(protocolText)
        val modes = mutableListOf<DtcModeScan>()
        var linkGone = false
        for ((mode, category) in DtcScanReport.SERVICES) {
            if (linkGone) {
                modes += DtcScanClassifier.failure(mode, category, "link lost earlier in this scan")
                continue
            }
            val raw = try {
                sendCommand(mode, 10000, keepLines = true)
            } catch (e: IOException) {
                modes += DtcScanClassifier.failure(mode, category, e.message ?: "communication error")
                if (socket?.isConnected != true) linkGone = true
                continue
            }
            // Line-aware: more than three codes arrive as several frames, and each line is checked with the
            // same strict rules as before. Every service keeps its own state and its verbatim reply, so a
            // failed or empty answer can never be shown as "no codes".
            modes += DtcScanClassifier.classify(mode, category, raw, framing)
        }
        return DtcScanReport(System.currentTimeMillis(), protocolText, modes)
    }

    /**
     * Clears the engine ECU's stored fault codes (service 04) after every safety check. Order: read engine speed, save the codes and freeze
     * frame as they are now, check the preconditions and the owner's confirmation, send the one authorised command, then read the codes
     * back. The saved snapshot stays on the tablet. Blocking: call from a background thread.
     */
    @Synchronized
    fun clearEngineDtcs(ownerConfirmed: Boolean): ClearResult {
        val rpm = runCatching { sendCommand("010C", 5000) }.getOrNull()?.let { ObdPidResponseDecoder.decode("010C", it)?.toDoubleOrNull() }
        val before = readTroubleCodes()
        val frozen = runCatching { readFreezeFrameBlocking().headline() }.getOrNull()
        val now = System.currentTimeMillis()
        val saved = runCatching {
            val dir = java.io.File(filesDir, "clear_snapshots").apply { mkdirs() }
            java.io.File(dir, "before_clear_$now.txt").writeText(ClearSnapshot.text(now, before, frozen)); true
        }.getOrDefault(false)
        val decision = ClearDtcPolicy.decide(ClearPreconditions(linkState == LinkState.COMMUNICATING, rpm, saved, ownerConfirmed), now)
        if (decision is ClearDecision.Refused) {
            sessionLog.append("EVT", "clear codes refused: " + decision.reason)
            return ClearResult(ClearOutcome.REFUSED_BY_APP, "", decision.reason, before, null)
        }
        val auth = (decision as ClearDecision.Authorized).auth
        val raw = try { sendCommand("04", 10000, change = auth) } catch (e: Exception) {
            sessionLog.append("EVT", "clear codes failed: " + (e.message ?: e.javaClass.simpleName))
            return ClearResult(ClearOutcome.NO_ANSWER, "", e.message ?: "communication error", before, null)
        }
        val (outcome, detail) = ClearReplyClassifier.classify(raw)
        sessionLog.append("EVT", "clear codes: outcome=$outcome reply=$raw")
        val after = if (outcome == ClearOutcome.CLEARED) runCatching { readTroubleCodes() }.getOrNull() else null
        return ClearResult(outcome, raw, detail, before, after)
    }

    fun tripDir() = java.io.File(filesDir, "trips").apply { mkdirs() }

    fun openTripWriter(file: java.io.File) {
        try {
            tripFile = file
            tripHeaderWritten = file.exists() && file.length() > 0
            tripWriter = java.io.FileWriter(file, true).buffered()
        } catch (e: Exception) {
            tripWriter = null
            tripNote = "Trip recording could not start: " + (e.message ?: "storage error")
        }
    }

    /** Starts a trip, or on a reconnect continues the held one (same file, totals kept, no gap bridged). */
    fun startTrip(forceNew: Boolean = false) {
        val now = System.currentTimeMillis()
        tripNote = ""
        if (!forceNew && tripSession.hasTrip()) {
            tripSession.resume(now)
            openTripWriter(java.io.File(tripDir(), tripSession.fileName))
        } else {
            endTrip()
            val name = "trip-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".csv"
            dataCheck.startTrip(); announcedFindings.clear(); dataFindings = emptyList(); dataTally = emptyList(); dataCheckBanner = ""
            tripSession.begin(now, name)
            openTripWriter(java.io.File(tripDir(), name))
        }
        tripStartMs = tripSession.startMs
        tripTick++
        persistTrip(now)
        tripClock(now)
    }

    fun persistTrip(now: Long) {
        lastTripPersistMs = now
        val snap = tripSession.snapshot(now)
        if (snap == null) prefs.edit().remove("trip_active").apply()
        else prefs.edit().putString("trip_active", TripCodec.encode(snap)).apply()
    }

    /** Once a second: pause a trip whose samples stopped, keep the notification truthful, save progress. */
    fun tripClock(now: Long) {
        if (!tripSession.hasTrip()) return
        val before = tripSession.phase
        tripSession.tick(now)
        if (tripSession.phase != before) tripTick++
        val text = if (tripSession.phase == TripPhase.RECORDING)
            String.format(java.util.Locale.US, "Recording • %.2f km", tripSession.stats.distanceKm)
        else tripSession.pause?.text.orEmpty()
        if (text != lastTripNotice) { lastTripNotice = text; TripService.show(this, text) }
        if (now - lastTripPersistMs >= 5_000L || tripSession.phase != before) persistTrip(now)
    }

    /** Link lost or app closing: keep the trip (paused, saved), release the file, stop nothing the driver didn't ask to stop. */
    fun holdTrip() {
        try { tripWriter?.flush(); tripWriter?.close() } catch (_: Exception) { }
        tripWriter = null
        if (tripSession.hasTrip()) { tripSession.tick(Long.MAX_VALUE / 2); persistTrip(System.currentTimeMillis()); tripTick++ }
    }

    fun recordTrip(list: List<GaugeValue>) {
        val now = System.currentTimeMillis()
        fun num(label: String) = list.firstOrNull { it.label == label }?.takeIf { it.available }?.value?.toDoubleOrNull()
        val sp = list.firstOrNull { it.label == "Vehicle speed" }?.takeIf { it.available }
        tripSession.onSample(now, sp?.value?.toDoubleOrNull(), sp?.capturedAtMs, num("Engine fuel rate"), connection.snapshot.ecuAvailable)
        tripTick++
        tripClock(now)
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

    /** The driver's Stop: closes the file, folds distance into the lifetime total once, clears the saved session. */
    fun endTrip(hideService: Boolean = true) {
        flushRegistry()
        saveBaselines()
        try { tripWriter?.flush(); tripWriter?.close() } catch (_: Exception) { }
        tripWriter = null
        if (tripSession.hasTrip()) {
            val done = tripSession.stop()
            if (done.distanceKm > 0.0) {
                trackedKm += done.distanceKm
                prefs.edit().putFloat("tracked_km", trackedKm.toFloat()).apply()
            }
        }
        prefs.edit().remove("trip_active").apply()
        tripStartMs = 0L
        lastTripNotice = ""
        if (hideService) TripService.hide(this)
        tripTick++
    }

    @Synchronized
    fun readFreezeFrameBlocking(): FreezeFrameResult =
        // The reader goes through the same port as live polling (one command at a time). A timeout on one PID is recorded
        // for that PID only; it no longer closes the Bluetooth socket or silently ends the read.
        FreezeFrameReader.read(elmPort, supportedPids, System.currentTimeMillis()) { sessionLog.append("EVT", "freeze frame: $it") }

    fun closeSocket() {
        pollingLive = false
        if (socket != null) sessionLog.append("EVT", "socket closed")
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    /** Ends the live session: the trip, the socket and the notification. Same path for DISCONNECT, power and notification Stop. */
    fun disconnect(stopTrip: Boolean = true) = disconnect(stopTrip, hideService = true)

    /**
     * Notification Stop. Runs on the main thread, completes before returning: poll loop told to stop, socket closed,
     * trip finalised and persisted exactly once ([endTrip] is a no-op without a trip). The service removes its own notification.
     */
    fun stopSession() = disconnect(stopTrip = true, hideService = false)

    private fun disconnect(stopTrip: Boolean, hideService: Boolean) {
        if (stopTrip) endTrip(hideService)
        userInitiatedDisconnect = true
        closeSocket()
        connection.userDisconnected()
        status = "Disconnected"
        gauges = gauges.map { it.copy(value = "--", available = false) }
    }

    fun connectSession() {
            scope.launch {
                disconnect(false)
                userInitiatedDisconnect = false
                pollStalled = false
                lastDiscovery = null
                sessionStartMs = System.currentTimeMillis()
                history.clear()
                status = "Connecting…"
                connection.connectRequested()
                var watchdog: kotlinx.coroutines.Job? = null
                try {
                    val result = withContext(Dispatchers.IO) { connectAndRead() }
                    adapterName = result.first
                    adapterNameForScan = result.first
                    connection.ready(System.currentTimeMillis())
                    status = "Connected"
                    startTrip()
                    onConnected?.invoke()
                    var poller = buildPoller()
                    linkState = LinkState.COMMUNICATING
                    var lastRecordMs = 0L
                    var lastPerfLogMs = System.currentTimeMillis()
                    // Backstop for a hung adapter read: closing the socket turns the stall into an ordinary lost-link recovery.
                    watchdog = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(2000)
                            if (stallWatchdog.isStalled(System.currentTimeMillis()) && !recovery.isRecovering()) {
                                pollStalled = true
                                stallWatchdog.disarm()
                                sessionLog.append("EVT", "polling stalled: no adapter reply for ${recoveryPolicy.stallMs / 1000} s, closing the socket")
                                closeSocket()
                            }
                        }
                    }
                    sessionLog.append("EVT", "live polling started: " + poller.scheduler.pids().size + " PIDs scheduled, " + supportedPids.size + " advertised")
                    pollingLive = true
                    var recoveryFailed = false
                    while (isActive && !userInitiatedDisconnect) {
                        val before = poller.monitor.state
                        var lost: Exception? = null
                        var recoveryEvent: PollEvent.Recovery? = null
                        val sliced = withContext(Dispatchers.IO) {
                            if (pollGate.tryEnterSlice()) {
                                stallWatchdog.arm(System.currentTimeMillis())
                                try { recoveryEvent = runPollSlice(poller) } catch (e: IOException) { lost = e }
                                finally { stallWatchdog.disarm(); pollGate.exitSlice() }
                                true
                            } else false
                        }
                        if (!sliced) delay(50)   // discovery is using the adapter
                        val nowMs = System.currentTimeMillis()
                        val after = poller.monitor.state
                        if (after != before) sessionLog.append("EVT", "ECU link $before -> $after")
                        linkState = after
                        // A lost socket (or a stalled adapter) is rebuilt; ECU silence alone never touches Bluetooth.
                        var lossKind: FailureKind? = null
                        var lossDetail = ""
                        val ioLoss = lost
                        if (ioLoss != null) {
                            lossKind = if (pollStalled) FailureKind.POLLING_STALLED else FailureClassifier.fromException(ioLoss, connection.snapshot.state)
                            lossDetail = ioLoss.message ?: ""
                        } else if (userInitiatedDisconnect) break
                        else {
                            val ev = recoveryEvent
                            if (recovery.observeEcu(after, ev != null, ev?.recovered == true, nowMs) == EcuAction.RECONNECT_BLUETOOTH) {
                                lossKind = FailureKind.ADAPTER_TIMEOUT; lossDetail = "adapter stayed silent"
                            }
                        }
                        val lossNow = lossKind
                        if (lossNow != null) {
                            pollStalled = false
                            status = "Reconnecting…"
                            gauges = gauges.map { it.copy(value = "--", available = false) }
                            val ok = withContext(Dispatchers.IO) { recovery.recoverBluetooth(lossNow, lossDetail) }
                            if (!ok) { recoveryFailed = true; break }
                            poller = buildPoller()
                            linkState = LinkState.COMMUNICATING
                            pollingLive = true
                            status = "Connected"
                            continue
                        }
                        // Readings are shown only while the ECU is really answering; never stale values as live.
                        gauges = if (!connection.snapshot.ecuAvailable) gauges.map { it.copy(value = "--", available = false) }
                        else GaugeAssembler.build({ poller.reading(it) }, supportedPids, extraPids, nowMs)
                            .map { GaugeValue(it.label, it.value, it.unit, it.available, it.capturedAtMs, it.staleAfterMs) }
                        if (nowMs - lastPerfLogMs >= 60_000L) {   // bounded: one short block a minute into the rolling session log
                            lastPerfLogMs = nowMs
                            poller.metrics.summary().forEach { sessionLog.append("PERF", it) }
                        }
                        if (nowMs - lastRecordMs >= 1000L) {
                            lastRecordMs = nowMs
                            gauges.forEach { g -> history.record(g.label, if (g.available) g.value.toDoubleOrNull() else null) }
                            historyVersion++
                            recordTrip(gauges)
                            if (connection.snapshot.ecuAvailable) runDataCheck(nowMs, poller)
                            ruleSnapshot = HealthRules.PIDS.mapNotNull { (pid, name, unit) -> poller.reading(pid)?.let { RuleReading(name, it.value, unit, it.atMs) } }
                        }
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
                        val fired = warningEngine.evaluate(sensorReadings, System.currentTimeMillis())
                        alertSensors = AlertTargets.sensorsFor(warningEngine.activeRuleIds())
                        if (fired.isNotEmpty()) {
                            warningMessage = AlertTargets.bannerText(alertSensors)
                            onAlert?.invoke(alertSensors)
                        } else if (alertSensors.isEmpty()) warningMessage = ""
                        // While the ECU is down, pace the retries with back-off (then slow standby) instead of a fixed 1 s loop.
                        val pace = recovery.ecuPauseMs().let { if (it > 0) it else poller.pauseMs(nowMs) }
                        var waited = 0L
                        while (waited < pace && isActive && !userInitiatedDisconnect) { val step = minOf(250L, pace - waited); delay(step); waited += step }
                    }
                    if (recoveryFailed) {
                        holdTrip()
                        closeSocket()
                        status = connection.snapshot.reason.ifBlank { "Connection lost" }
                        alertSensors = emptySet()
                        gauges = gauges.map { it.copy(value = "--", available = false) }
                    } else if (status == "Connected") {
                        connection.userDisconnected()
                        status = "Disconnected"
                        alertSensors = emptySet()
                        gauges = gauges.map { it.copy(value = "--", available = false) }
                    }
                } catch (e: SecurityException) {
                    holdTrip()
                    closeSocket()
                    connection.connectFailed(FailureKind.INITIALIZATION_INTERRUPTED, "Bluetooth permission is required")
                    status = "Permission required"
                    alertSensors = emptySet()
                } catch (e: Exception) {
                    val kind = FailureClassifier.fromException(e, connection.snapshot.state)
                    holdTrip()
                    closeSocket()
                    alertSensors = emptySet()
                    // Tapping DISCONNECT closes the socket mid-read; that is not a failure.
                    if (userInitiatedDisconnect) connection.userDisconnected()
                    else connection.connectFailed(kind, e.message ?: "Connection failed")
                    status = if (userInitiatedDisconnect) "Disconnected" else (e.message ?: "Connection failed")
                    if (!userInitiatedDisconnect) gauges = gauges.map { it.copy(value = "--", available = false) }
                } finally {
                    watchdog?.cancel()
                    stallWatchdog.disarm()
                }
            }
    }

    init {
        fuelFills = FuelLog.parse(prefs.getString("fuel_log", null))
        dataBaselines = dataCheck.baselineRows()
        trackedKm = prefs.getFloat("tracked_km", 0f).toDouble()
        // A trip saved before the process died comes back paused; it resumes only when live data returns.
        TripCodec.decode(prefs.getString("trip_active", null))?.let { tripSession.restore(it); tripStartMs = it.startMs }
        // The trip clock must run even with no Activity: a hung adapter pauses the trip here.
        scope.launch { while (true) { delay(1000); tripClock(System.currentTimeMillis()) } }
    }
}
