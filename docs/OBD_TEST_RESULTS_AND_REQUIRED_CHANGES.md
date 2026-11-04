# SX4 Scan Pro — OBD Test Results & Required Changes

**Document status:** Investigation and implementation brief — approval required before coding  
**Test date:** 4 November 2026  
**Vehicle:** 2012 Maruti Suzuki SX4 ZDi, 1.3 DDiS, manual  
**Android device:** Realme Pad 2 (RMP2204), Android 14  
**OBD adapter:** Vgate iCar Pro Bluetooth 3.0 (classic Bluetooth RFCOMM/SPP)  
**Comparison app:** Torque Pro

---

## 1. Purpose

This document records the real-vehicle OBD observations collected so far and defines the investigations and changes required in SX4 Scan Pro.

The goals are to:

1. Improve live-data refresh without destabilising ECU communication.
2. Recover automatically when the ECU stops responding during an ignition cycle.
3. Handle NO DATA, timeouts and socket failures correctly.
4. distinguish advertised PIDs from PIDs that are actually readable.
5. Add a read-only ECU Identity & Capability Scan.
6. Preserve useful diagnostic evidence in exportable reports.

This is an evidence-led investigation. Do not infer ECU features from assumptions or display fabricated values.

## 2. Vehicle and adapter observations

- Torque Pro connects to the same vehicle and Vgate adapter and receives live ECU data.
- The adapter uses Bluetooth Classic SPP, not BLE.
- With the ignition switched OFF and the key left in ACC, the adapter can remain powered and Bluetooth can remain connected even though ECU readings stop.
- Switching the ignition ON again did not automatically restore readings in the observed SX4 Scan Pro test.
- The continued Bluetooth connection must not be treated as proof that the ECU is communicating.

## 3. Test results collected so far

### 3.1 Diagnostic snapshot

The shared diagnostic PDF `health_20261104_1041.pdf` captured the following values:

| Item | Recorded value |
|---|---:|
| OBD connection | Connected |
| Engine RPM | 820 rpm |
| Coolant temperature | 31 °C |
| MAF | 8.59 g/s |
| Rail pressure | 29,800 kPa |
| EGR commanded | 2% |
| Module voltage | 14.43 V |
| Vehicle speed | 0 |
| Intake air temperature | 28 °C |
| Boost | 0 kPa |
| Calculated load | 30.6% |
| DTC response | No DTC returned |

**Interpretation and limitations:**

- This is a single snapshot, not a time-series latency measurement.
- A zero boost reading at idle does not establish a boost-system fault.
- “No DTC returned” only describes the result of that scan; it is not proof that every vehicle module is fault-free.
- Values should be treated as observations from that test session, not permanent ECU specifications.

### 3.2 Raw ELM327 request/response observations

The log showed 19 standard PIDs advertised. Successful examples included:

| Request | Raw response |
|---|---|
| `0133` | `41335D` |
| `0123` | `412301E0` |
| `0142` | `41423507` / `414234ED` |
| `010C` | `410C0000` |
| `010D` | `410D00` |
| `0105` | `41055A` |
| `010F` | `410F42` |
| `010B` | `410B5E` |
| `0110` | `4110079D` |
| `0104` | `410400` |
| `012C` | `412C05` |

Other observations:

- Individual successful command/response durations were approximately 250–270 ms.
- RPM request at 10:48:03.129 returned at 10:48:03.384.
- The next RPM request at 10:48:07.552 returned at 10:48:07.817.
- The observed interval between RPM requests was approximately 4.4 seconds.
- At approximately 10:48:08.448, the first `NO DATA` appeared for PID `0105`.
- Multiple PIDs subsequently returned `NO DATA`.
- Requests continued for approximately 83 seconds.
- At 10:49:31.855, the log reported `ERR 012C: socket closed`.

**Interpretation and limitations:**

- Sequential polling across several PIDs may explain the 4.4-second RPM refresh, but this must be confirmed by inspecting the scheduler and command queue.
- `NO DATA` alone does not prove whether the ECU went to sleep, the adapter stopped responding, or another communication problem occurred.
- The cause of the socket closure is not yet known.
- The 19 advertised PIDs must not all be assumed to be consistently readable.

## 4. Required investigation and changes

### 4.1 Live-data refresh latency — high priority

**Observed:** RPM refresh was approximately 4.4 seconds in the captured log. Torque Pro was reported to refresh RPM in approximately one second using the same vehicle and adapter.

Inspect:

- PID polling scheduler, order and configured intervals.
- ELM command queue and request/response serialization.
- Command timeout and prompt handling.
- Whether unsupported or repeatedly failing PIDs consume polling time.
- Whether optional/user-selected PIDs delay critical gauges.
- UI state delivery and Compose recomposition.

Desired behaviour:

- Prioritise RPM and vehicle speed.
- Assign appropriate priorities to coolant, MAP/boost, intake temperature and other useful readings.
- Poll slow-changing values less frequently where safe.
- Avoid repeatedly polling known-unsupported PIDs.
- Keep exactly one ELM command in flight at a time.
- Investigate whether approximately one-second RPM updates are safely achievable.
- Do not reduce every polling interval indiscriminately.

### 4.2 Ignition OFF / ACC ON / ignition ON recovery — high priority

Inspect ECU timeout handling, ELM session state, socket lifecycle, Bluetooth state and recovery logic.

The app must distinguish:

1. Bluetooth disconnected.
2. Bluetooth connected, ECU not responding.
3. ECU initialising / protocol negotiation.
4. ECU communicating.
5. ECU communication lost / recovering.

Desired behaviour:

- Mark old readings stale when communication is lost.
- Never present stale readings as live.
- Avoid flooding the adapter with repeated requests while the ECU is unavailable.
- Detect when the ECU becomes responsive again.
- Reinitialise the ELM/ECU session when necessary.
- Resume supported PID polling automatically.
- Preserve a healthy Bluetooth connection when possible; do not reconnect Bluetooth unnecessarily.

### 4.3 NO DATA, timeouts and socket failures

Determine:

- Why the first `NO DATA` appeared and whether the adapter continued responding.
- Why polling continued after repeated failures.
- Whether the ELM prompt was received, delayed or incomplete.
- Whether the command queue could become desynchronised.
- Whether the socket was closed by the app, adapter, Android Bluetooth stack or another failure, to the extent observable.

Desired behaviour:

- Distinguish `NO DATA`, timeout, malformed response, transport error and socket closure.
- Track consecutive failures.
- Back off or pause polling appropriately when communication is unavailable.
- Recover the session safely.
- Never convert communication failures into zero-valued sensor readings.

### 4.4 PID discovery and verified capability reporting

Inspect supported-PID bitmap parsing for PID ranges `0100`, `0120` and `0140`, and the current discovery/polling implementation.

Requirements:

- Distinguish advertised PIDs from PIDs that have actually returned valid data.
- Record successful reads, unsupported responses and temporary failures separately.
- Do not assume every advertised PID is consistently readable.
- Do not show unsupported or unavailable values as zero.
- Prevent optional PIDs from starving high-priority live data.

## 5. New feature — ECU Identity & Capability Scan

### 5.1 Objective

Add a separate, user-initiated, read-only scan to identify the connected ECU and document the technical information and diagnostic capabilities it actually exposes.

This should create an evidence-based profile of the vehicle's ECU rather than relying on assumed specifications.

### 5.2 Identification information to investigate

Where supported by the ECU and standard diagnostic services, investigate retrieval of:

- ECU identification or reported ECU description.
- ECU/manufacturer identification, if exposed.
- VIN.
- Calibration ID.
- Calibration Verification Number (CVN).
- ECU response address/header, where available.
- Detected OBD protocol.
- Adapter identification and connection details.

Do not assume any field is supported. Record unavailable or untested fields honestly.

### 5.3 Capability information to collect

- Advertised standard PIDs.
- Successfully tested/readable PIDs.
- Unsupported PIDs.
- Temporarily unavailable PIDs.
- Supported diagnostic services/modes.
- Emissions-related readiness monitor information, where available.
- Available diagnostic trouble-code information.
- Individual request response times and outcomes.

Keep “advertised” separate from “verified readable”.

### 5.4 Manufacturer-specific information and limitations

Investigate whether additional Suzuki-specific identification or technical information can safely be accessed.

Standard OBD-II does not necessarily expose complete ECU configuration. Do not claim to retrieve injection maps, turbo-control maps, immobilizer configuration, adaptations, coding or proprietary parameters unless a supported and verified diagnostic method actually provides them.

Do not attempt security access, programming, coding, adaptations, resets or write operations. Identify any information that would require Suzuki-specific diagnostic protocols or proprietary tools, and document those limitations.

### 5.5 Persistent SX4 ECU Profile

Propose a persistent **SX4 ECU Profile** containing, when available:

- Vehicle identification.
- ECU identification details.
- Calibration ID and CVN.
- Detected protocol and relevant connection details.
- Advertised and verified PID capabilities.
- Supported diagnostic services.
- Scan date/time and scan completion status.
- Relevant raw ECU responses.
- Errors and unavailable fields.

Allow a fresh scan and comparison with the previous profile. Highlight:

- Newly discovered capabilities.
- Previously readable capabilities that no longer respond.
- Changed identification/calibration information.
- Changes in readable PIDs.
- Differences that may need investigation.

A temporary communication failure must not automatically reclassify a PID as permanently unsupported.

### 5.6 Raw response preservation and export

For each identification/capability request, preserve:

- Request/service sent.
- Raw hexadecimal response.
- Decoded value, if decoding is supported.
- Timestamp and response duration.
- Success/failure status.
- Explanation when a field cannot be decoded.

Provide export in PDF and/or JSON so the report can be shared for further analysis. Keep raw responses available for future decoder improvements.

### 5.7 Separate UI section

Propose a separate **ECU Information** section without changing the existing Cockpit, Diagnostics or Gauge Wall layout.

Suggested contents:

1. ECU Identity.
2. Vehicle Information.
3. ECU Capabilities.
4. Advertised and Verified PIDs.
5. Diagnostic Services.
6. Raw ECU Responses.
7. Saved ECU Profile.
8. Scan Comparison.
9. Export Report.

Use explicit statuses such as:

- Retrieved.
- Supported.
- Advertised but not verified.
- Unsupported.
- Not tested.
- Temporarily unavailable.

Do not fabricate or infer missing technical details.

### 5.8 Safety and compatibility

- Read-only scanning only.
- No ECU writes, resets, adaptations, coding or programming.
- Use the existing serialized ELM327 command queue.
- Do not introduce parallel diagnostic requests.
- Do not run a full identity scan continuously.
- Run scans on user request and save their results.
- Ensure scans do not interfere with live-data polling.
- Handle unsupported services gracefully.
- Preserve the working Bluetooth transport architecture unless code inspection proves a change is necessary.

## 6. Diagnostic logging

Improve logging enough to compare SX4 Scan Pro behaviour against Torque Pro and diagnose future failures.

Capture, with timestamps:

- ELM command and raw response.
- Request/response duration.
- PID and support/verification status.
- Polling priority and configured interval.
- NO DATA, timeout and malformed-response counts.
- Consecutive failure count.
- Bluetooth and ECU communication states.
- ELM initialisation and protocol negotiation events.
- Socket closure reason, wherever available.
- Recovery attempts and outcomes.
- Time from ignition ON to first valid ECU reading.
- ECU identification/capability requests and raw responses.
- ECU scan duration and completion status.

Provide a shareable diagnostic log/report. Logging must not itself create excessive polling delays or overwhelm storage.

## 7. Acceptance tests

Before considering the work complete, validate:

1. Normal ignition ON connects and produces live readings.
2. RPM refresh latency is measured against the observed 4.4-second baseline.
3. Multiple PIDs are polled without response corruption.
4. Ignition OFF with ACC ON can leave Bluetooth connected while ECU readings become stale/unavailable.
5. Ignition ON again restores ECU communication automatically where the adapter/ECU permit it.
6. Repeated NO DATA does not cause uncontrolled polling.
7. Unsupported PIDs never appear as valid zero readings.
8. Socket closure is handled without an app crash.
9. Existing Bluetooth pairing and connection functionality remains intact.
10. Gauge Wall shows real readings and correct stale/unavailable states.
11. ECU identity scan retrieves only information actually exposed by the vehicle.
12. Unsupported identification services are handled gracefully.
13. Advertised PIDs are distinguished from successfully readable PIDs.
14. Raw responses are retained and exportable.
15. ECU Profile can be saved, retrieved and compared with a fresh scan.
16. Temporary unavailability is not misclassified as permanent lack of support.
17. ECU scanning does not interfere with normal live-data polling.
18. No write, reset, adaptation, coding or programming operation is performed.

## 8. Required deliverables — plan first, no coding yet

Inspect the repository, Bluetooth/ELM327 implementation, PID discovery/scheduler, logging implementation and available diagnostic evidence. Then provide:

1. Root-cause analysis for the approximately 4.4-second RPM refresh.
2. Root-cause analysis for ignition recovery failure.
3. Explanation of repeated NO DATA and socket closure, separating known facts from hypotheses.
4. Current polling architecture and identified bottlenecks.
5. Exact files/classes/functions that need modification.
6. Proposed changes separated into mandatory and optional.
7. Risks, compatibility considerations and regression tests.
8. Standard ECU identification services that can safely be investigated.
9. Proposed ECU Identity & Capability Scan architecture.
10. What is realistically retrievable through standard OBD-II versus Suzuki-specific diagnostics.
11. Proposed ECU Profile data structure and storage approach.
12. Proposed PDF/JSON report structure.
13. Estimated implementation scope and dependencies.

### Development rules

- Inspect existing code and logs before proposing changes.
- Do not rewrite the Bluetooth architecture without evidence and approval.
- Do not introduce parallel ELM327 commands.
- Do not change unrelated UI, welcome animation, Gauge Wall or other features.
- Do not invent ECU capabilities or use simulated telemetry.
- Preserve existing working functionality.
- First return the root-cause analysis and implementation plan.
- **Do not modify code, commit, push or merge anything until the owner explicitly approves the plan.**
- After approval, work only on an isolated branch, run relevant tests and build the debug APK.
- Do not merge into `main` without explicit permission.

---

## 9. Evidence still needed

The current material is a useful starting point, but it is not yet a complete controlled test campaign.

For the next vehicle test, capture:

- A continuous timestamped log from initial connection through stable idle.
- A comparable RPM refresh measurement in Torque Pro and SX4 Scan Pro.
- Ignition OFF with ACC ON, recording Bluetooth state, adapter response and ECU response separately.
- Ignition ON recovery, recording time to first valid PID.
- A user-triggered ECU identity/capability scan and all raw responses.
- Repeated scans only where needed to distinguish transient failures from unsupported services.

Do not claim a root cause until the relevant code and evidence support it.

## 10. Related V2 diagnostic-explorer code inventory

The following six Kotlin files were supplied as a diagnostic-explorer reference implementation for review. They are recorded here as a cross-reference for future review and handover.

**Important:** This inventory is implementation context, not evidence from the real-vehicle test. It does not establish that these classes are present in, integrated into, or validated against the original SX4 Scan Pro application. Treat these listings as reference material only; inspect the actual source in this repository and do not access or modify any other repository or project.

Paths below are relative to the Kotlin source root; package names are recorded exactly as supplied.

| File | Package | Classes / types |
|---|---|---|
| `model/DiagnosticModels.kt` | `com.sx4.zdi.diagnostics.explorer.model` | `DiagnosticProtocol` (enum), `EcuTarget` (enum), `DiagnosticTroubleCode` (data class), `DtcSeverity` (enum), `DtcStatus` (enum), `LiveParameter` (data class), `ParameterCategory` (enum), `EcuProfile` (data class) |
| `engine/ObdTransport.kt` | `com.sx4.zdi.diagnostics.explorer.engine` | `ObdTransport` (interface), `DiagnosticCommandPipeline` (class) |
| `engine/SuzukiParamDecoders.kt` | `com.sx4.zdi.diagnostics.explorer.engine` | `SuzukiParamDecoders` (object / singleton decoder utility) |
| `mock/MockDiagnosticEngine.kt` | `com.sx4.zdi.diagnostics.explorer.mock` | `MockDiagnosticEngine` (class implementing `ObdTransport`) |
| `ui/SuzukiEcuExplorerScreen.kt` | `com.sx4.zdi.diagnostics.explorer.ui` | `ExplorerTab` (enum), `EcuCardItem` (data class), `SuzukiEcuExplorerScreen` (main `@Composable` screen function) |
| `test/SuzukiParamDecodersTest.kt` | `com.sx4.zdi.diagnostics.explorer.test` | `SuzukiParamDecodersTest` (JUnit 4 test class) |

### How this inventory relates to the investigation

- `DiagnosticModels.kt` describes the data model for diagnostic protocols, ECU targets, trouble codes, live parameters and ECU profiles.
- `ObdTransport.kt` defines the transport abstraction and serialized diagnostic command pipeline; review it against the single-command-in-flight requirement in Sections 4 and 5.
- `SuzukiParamDecoders.kt` contains decoder logic; validate every decoder against documented raw responses and verified protocol information before displaying decoded values.
- `MockDiagnosticEngine.kt` is a mock implementation. Any mock/simulated values must remain visibly identified as simulation and must never be presented as live vehicle data.
- `SuzukiEcuExplorerScreen.kt` is the explorer UI reference; it should remain separate from the original Cockpit, Diagnostics and Gauge Wall unless a separately approved integration plan says otherwise.
- `SuzukiParamDecodersTest.kt` provides decoder test coverage; passing unit tests do not replace real-vehicle validation.

### Code evidence still required for a complete handover

The file/class inventory is not a substitute for the actual source. For a reproducible code review, retain the exact source snapshots or immutable commit reference for these six files, along with test output. Record which decoders are standard OBD-II, which are Suzuki-specific, the source/specification for each formula or identifier, and which have been confirmed on the user's vehicle.

Do not copy V2 source into the original repository merely to make this document self-contained. Keep the original application baseline and the V2 implementation as separate workstreams.


---

## 11. V2 diagnostic-explorer source snapshots — reference only

The following six Kotlin source files are reproduced from the code supplied in this conversation. They belong to the separate V2 diagnostic-explorer workstream. They are preserved for review, not as proof that this implementation exists in or has been validated in the original SX4 Scan Pro app.

**Important:** The VIN in the sample profile/mock data is a dummy example, not the user's vehicle VIN. The mock engine returns simulated data. Hard-coded ECU identities, addresses, DTCs, telemetry values and module statuses are examples only until verified against actual raw vehicle responses. The source comments' use of “verified” is not proof of validation.

### 11.1 model/DiagnosticModels.kt
Package: \`com.sx4.zdi.diagnostics.explorer.model\`

\`\`\`kotlin
package com.sx4.zdi.diagnostics.explorer.model

import java.time.Instant

enum class DiagnosticProtocol(val description: String, val elmAtCommand: String) {
    ISO_15765_4_CAN_11BIT_500K("ISO 15765-4 CAN (11-bit, 500 kbps)", "ATSP6"),
    ISO_14230_4_KWP_FAST("ISO 14230-4 KWP (Fast Init)", "ATSP5"),
    UNKNOWN("Auto-Scanning Protocol...", "ATSP0")
}

enum class EcuTarget(val displayName: String, val requestHeaderHex: String, val responseFilterHex: String) {
    ENGINE("Magneti Marelli MJD Engine ECU", "7E0", "7E8"),
    ABS("Bosch ABS 8.0 ESP Controller", "720", "728"),
    BCM("Suzuki Body Control Module (BCM)", "740", "748"),
    CLUSTER("Instrument Panel Cluster (IPC)", "760", "768")
}

data class DiagnosticTroubleCode(
    val code: String,
    val description: String,
    val severity: DtcSeverity,
    val ecuSource: EcuTarget,
    val status: DtcStatus
)

enum class DtcSeverity { CRITICAL, WARNING, ADVISORY }
enum class DtcStatus { CONFIRMED, PENDING, PERMANENT }

data class LiveParameter(
    val id: String,
    val name: String,
    val valueFormatted: String,
    val numericValue: Double,
    val unit: String,
    val minThreshold: Double,
    val maxThreshold: Double,
    val category: ParameterCategory,
    val verified: Boolean = false
)

enum class ParameterCategory {
    AIR_TURBO,
    FUEL_INJECTION,
    TEMPERATURES,
    EMISSIONS,
    ELECTRICAL
}

data class EcuProfile(
    val vin: String,
    val ecuHardware: String,
    val ecuSoftware: String,
    val detectedProtocol: DiagnosticProtocol,
    val supportedStandardPids: Set<Int>,
    val verifiedParameters: Set<String>,
    val lastScanTimestamp: Instant
)
\`\`\`

### 11.2 engine/ObdTransport.kt
Package: \`com.sx4.zdi.diagnostics.explorer.engine\`

\`\`\`kotlin
package com.sx4.zdi.diagnostics.explorer.engine

import com.sx4.zdi.diagnostics.explorer.model.EcuTarget
import kotlinx.coroutines.flow.Flow

interface ObdTransport {
    suspend fun sendCommand(rawCmd: String, timeoutMs: Long = 200): String
    fun observeConnectionState(): Flow<Boolean>
}

class DiagnosticCommandPipeline(
    private val transport: ObdTransport
) {
    private var currentHeader: String = ""

    suspend fun configureForEcu(ecu: EcuTarget) {
        if (currentHeader != ecu.requestHeaderHex) {
            transport.sendCommand("ATSH\${ecu.requestHeaderHex}")
            transport.sendCommand("ATCRA\${ecu.responseFilterHex}")
            currentHeader = ecu.requestHeaderHex
        }
    }

    suspend fun queryPid(serviceAndPidHex: String): String {
        val rawResponse = transport.sendCommand(serviceAndPidHex, timeoutMs = 180)
        return cleanHexResponse(rawResponse)
    }

    private fun cleanHexResponse(raw: String): String {
        return raw.replace("\\s+".toRegex(), "")
            .replace(">", "")
            .replace("\\r", "")
            .replace("\\n", "")
            .uppercase()
    }
}
\`\`\`

### 11.3 engine/SuzukiParamDecoders.kt
Package: \`com.sx4.zdi.diagnostics.explorer.engine\`

\`\`\`kotlin
package com.sx4.zdi.diagnostics.explorer.engine

object SuzukiParamDecoders {

    fun decodeRailPressureBar(hexPayload: String): Double? {
        val bytes = extractDataBytes(hexPayload, expectedService = "41", expectedPid = "23") ?: return null
        if (bytes.size < 2) return null
        val kpa = ((bytes[0] * 256) + bytes[1]) * 10.0
        return kpa / 100.0
    }

    fun calculateBoostBar(mapKpa: Double, baroKpa: Double): Double {
        val delta = mapKpa - baroKpa
        return if (delta > 0) delta / 100.0 else 0.0
    }

    fun decodeEgrDutyPercent(hexPayload: String): Double? {
        val bytes = extractDataBytes(hexPayload, expectedService = "41", expectedPid = "2C") ?: return null
        if (bytes.isEmpty()) return null
        return (bytes[0] * 100.0) / 255.0
    }

    fun decodeCoolantTempC(hexPayload: String): Double? {
        val bytes = extractDataBytes(hexPayload, expectedService = "41", expectedPid = "05") ?: return null
        if (bytes.isEmpty()) return null
        return (bytes[0] - 40.0)
    }

    fun decodeMafGramsPerSecond(hexPayload: String): Double? {
        val bytes = extractDataBytes(hexPayload, expectedService = "41", expectedPid = "10") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 100.0
    }

    private fun extractDataBytes(hexPayload: String, expectedService: String, expectedPid: String): List<Int>? {
        val clean = hexPayload.trim()
        val prefix = "$expectedService$expectedPid"
        val idx = clean.indexOf(prefix)
        if (idx == -1) return null
        val dataPart = clean.substring(idx + prefix.length)
        if (dataPart.length % 2 != 0) return null

        return dataPart.chunked(2).mapNotNull { it.toIntOrNull(16) }
    }
}
\`\`\`

### 11.4 mock/MockDiagnosticEngine.kt
Package: \`com.sx4.zdi.diagnostics.explorer.mock\`

\`\`\`kotlin
package com.sx4.zdi.diagnostics.explorer.mock

import com.sx4.zdi.diagnostics.explorer.engine.ObdTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.random.Random

class MockDiagnosticEngine : ObdTransport {

    private var selectedHeader: String = "7E0"

    override suspend fun sendCommand(rawCmd: String, timeoutMs: Long): String {
        delay(Random.nextLong(15, 35))
        val clean = rawCmd.trim().uppercase()

        if (clean.startsWith("ATSH")) {
            selectedHeader = clean.removePrefix("ATSH")
            return "OK"
        }
        if (clean.startsWith("AT")) return "OK"

        return when (selectedHeader) {
            "7E0" -> handleEngineEcu(clean)
            "720" -> handleAbsEcu(clean)
            "740" -> handleBcmEcu(clean)
            else -> "NO DATA"
        }
    }

    override fun observeConnectionState(): Flow<Boolean> = flowOf(true)

    private fun handleEngineEcu(cmd: String): String = when (cmd) {
        "0100" -> "4100BE3EB811"
        "0120" -> "41208005A011"
        "0105" -> "4105" + "%02X".format(128)
        "010B" -> "410B" + "%02X".format(165)
        "0133" -> "4133" + "%02X".format(100)
        "0123" -> "4123" + "%04X".format(5200)
        "012C" -> "412C" + "%02X".format(115)
        "0110" -> "4110" + "%04X".format(4250)
        "03"   -> "430202380101"
        "07"   -> "47010401"
        "0902" -> "4902014D413345594231315330303130323334"
        else   -> "NO DATA"
    }

    private fun handleAbsEcu(cmd: String): String = when (cmd) {
        "03" -> "4301C0035"
        else -> "NO DATA"
    }

    private fun handleBcmEcu(cmd: String): String = when (cmd) {
        "03" -> "4300"
        else -> "NO DATA"
    }
}
\`\`\`

### 11.5 ui/SuzukiEcuExplorerScreen.kt
Package: \`com.sx4.zdi.diagnostics.explorer.ui\`

\`\`\`kotlin
package com.sx4.zdi.diagnostics.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sx4.zdi.diagnostics.explorer.model.*

val ExplorerBg = Color(0xFF0D0F12)
val ExplorerCard = Color(0xFF15191E)
val ExplorerBorder = Color(0xFF262D35)
val AccentCrimson = Color(0xFFE52020)
val AccentCyan = Color(0xFF00E5FF)
val AccentAmber = Color(0xFFFFB300)
val TextMuted = Color(0xFF8A98A5)

@Composable
fun SuzukiEcuExplorerScreen(
    onNavigateBack: () -> Unit = {}
) {
    var activeTab by remember { mutableStateOf(ExplorerTab.ECU_OVERVIEW) }
    val isSimulationMode = remember { true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ExplorerBg)
            .padding(16.dp)
    ) {
        TopExplorerHeader(
            isSimulationMode = isSimulationMode,
            onBackClick = onNavigateBack
        )

        Spacer(modifier = Modifier.height(12.dp))

        ExplorerTabRow(
            selectedTab = activeTab,
            onTabSelected = { activeTab = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            when (activeTab) {
                ExplorerTab.ECU_OVERVIEW -> EcuOverviewSubscreen()
                ExplorerTab.LIVE_PARAMETERS -> LiveParametersSubscreen()
                ExplorerTab.ADVANCED_DTC -> AdvancedDtcSubscreen()
                ExplorerTab.VEHICLE_PROFILE -> VehicleProfileSubscreen()
            }
        }
    }
}

enum class ExplorerTab(val title: String, val icon: ImageVector) {
    ECU_OVERVIEW("ECU Overview", Icons.Default.DirectionsCar),
    LIVE_PARAMETERS("DDiS Telemetry", Icons.Default.Speed),
    ADVANCED_DTC("DTC Explorer", Icons.Default.Warning),
    VEHICLE_PROFILE("Vehicle Profile", Icons.Default.Settings)
}

@Composable
fun TopExplorerHeader(isSimulationMode: Boolean, onBackClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "SUZUKI ECU EXPLORER",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "MARUTI SUZUKI SX4 ZDi • 1.3 DDiS DIESEL",
                    color = AccentCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isSimulationMode) {
            Surface(
                color = AccentAmber.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentAmber),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SIMULATION MODE — NOT CONNECTED TO VEHICLE",
                        color = AccentAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ExplorerTabRow(selectedTab: ExplorerTab, onTabSelected: (ExplorerTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ExplorerCard)
            .border(1.dp, ExplorerBorder, RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        ExplorerTab.values().forEach { tab ->
            val isSelected = selectedTab == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) AccentCrimson.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = if (isSelected) AccentCrimson else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tab.title,
                        color = if (isSelected) Color.White else TextMuted,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun EcuOverviewSubscreen() {
    val modules = listOf(
        EcuCardItem("Magneti Marelli Engine ECU", "0x7E0 / 0x7E8", "MJD 6F3.S3 CAN", "ONLINE", AccentCyan),
        EcuCardItem("Bosch ABS 8.0 Controller", "0x720 / 0x728", "ISO 14230 CAN", "ONLINE", AccentCyan),
        EcuCardItem("Body Control Module (BCM)", "0x740 / 0x748", "Suzuki Enhanced CAN", "STANDBY", AccentAmber),
        EcuCardItem("Instrument Cluster (IPC)", "0x760 / 0x768", "CAN Gateway", "VERIFIED", AccentCyan)
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(modules) { mod ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ExplorerCard)
                    .border(1.dp, ExplorerBorder, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(mod.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Header: \${mod.address} • Protocol: \${mod.protocol}", color = TextMuted, fontSize = 11.sp)
                }
                Surface(
                    color = mod.statusColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, mod.statusColor)
                ) {
                    Text(
                        text = mod.status,
                        color = mod.statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

data class EcuCardItem(val name: String, val address: String, val protocol: String, val status: String, val statusColor: Color)

@Composable
fun LiveParametersSubscreen() {
    val params = listOf(
        LiveParameter("P1", "Common Rail Pressure (Actual)", "520.0 bar", 520.0, "bar", 250.0, 1600.0, ParameterCategory.FUEL_INJECTION, true),
        LiveParameter("P2", "VGT Turbo Boost (Derived)", "0.65 bar", 0.65, "bar", 0.0, 1.45, ParameterCategory.AIR_TURBO, true),
        LiveParameter("P3", "Coolant Temperature", "88.0 °C", 88.0, "°C", 80.0, 98.0, ParameterCategory.TEMPERATURES, true),
        LiveParameter("P4", "EGR Commanded Position", "45.1 %", 45.1, "%", 0.0, 100.0, ParameterCategory.EMISSIONS, true),
        LiveParameter("P5", "Mass Air Flow (MAF)", "42.5 g/s", 42.5, "g/s", 8.0, 220.0, ParameterCategory.AIR_TURBO, true)
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(params) { param ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ExplorerCard)
                    .border(1.dp, ExplorerBorder, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(param.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Category: \${param.category.name} • Verified PID", color = TextMuted, fontSize = 10.sp)
                }
                Text(
                    text = param.valueFormatted,
                    color = AccentCyan,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AdvancedDtcSubscreen() {
    val dtcs = listOf(
        DiagnosticTroubleCode("P0238", "Turbocharger Boost Sensor 'A' Circuit High", DtcSeverity.WARNING, EcuTarget.ENGINE, DtcStatus.CONFIRMED),
        DiagnosticTroubleCode("P0401", "Exhaust Gas Recirculation (EGR) Flow Insufficient", DtcSeverity.ADVISORY, EcuTarget.ENGINE, DtcStatus.PENDING),
        DiagnosticTroubleCode("C0035", "Left Front Wheel Speed Sensor Circuit", DtcSeverity.CRITICAL, EcuTarget.ABS, DtcStatus.CONFIRMED)
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(dtcs) { dtc ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ExplorerCard)
                    .border(1.dp, ExplorerBorder, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dtc.code,
                            color = AccentCrimson,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("•  \${dtc.ecuSource.displayName}", color = TextMuted, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(dtc.description, color = Color.White, fontSize = 12.sp)
                }
                Surface(
                    color = when (dtc.status) {
                        DtcStatus.CONFIRMED -> AccentCrimson.copy(alpha = 0.2f)
                        DtcStatus.PENDING -> AccentAmber.copy(alpha = 0.2f)
                        DtcStatus.PERMANENT -> Color.Gray.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (dtc.status == DtcStatus.CONFIRMED) AccentCrimson else AccentAmber)
                ) {
                    Text(
                        text = dtc.status.name,
                        color = if (dtc.status == DtcStatus.CONFIRMED) AccentCrimson else AccentAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun VehicleProfileSubscreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(ExplorerCard)
            .border(1.dp, ExplorerBorder, RoundedCornerShape(8.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("STORED VEHICLE PROFILE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        ProfileRow("VIN (Mode 09)", "MA3EYB11S0010234")
        ProfileRow("Target Chassis", "2012 Maruti Suzuki SX4 ZDi (India)")
        ProfileRow("Engine Assembly", "Fiat 1.3 Multijet DDiS 89 bhp VGT")
        ProfileRow("Active Protocol", "ISO 15765-4 CAN (11-bit, 500 kbps)")
        ProfileRow("Powertrain Header", "Request: 0x7E0  |  Response: 0x7E8")
        ProfileRow("ABS Header", "Request: 0x720  |  Response: 0x728")
        ProfileRow("Read-Only Safety Lock", "ENFORCED (Mode 04 & 08 Excluded)")
    }
}

@Composable
fun ProfileRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
\`\`\`

### 11.6 test/SuzukiParamDecodersTest.kt
Package: \`com.sx4.zdi.diagnostics.explorer.test\`

\`\`\`kotlin
package com.sx4.zdi.diagnostics.explorer.test

import com.sx4.zdi.diagnostics.explorer.engine.SuzukiParamDecoders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuzukiParamDecodersTest {

    @Test
    fun testRailPressureDecoding() {
        // Raw ELM response: 41 23 14 50 -> 0x1450 = 5200 decimal -> 52,000 kPa -> 520.0 bar
        val response = "41231450"
        val railBar = SuzukiParamDecoders.decodeRailPressureBar(response)
        assertEquals(520.0, railBar ?: 0.0, 0.1)
    }

    @Test
    fun testBoostCalculation() {
        val map = 165.0  // kPa (1.65 bar abs)
        val baro = 100.0 // kPa (1.00 bar atm)
        val boostBar = SuzukiParamDecoders.calculateBoostBar(map, baro)
        assertEquals(0.65, boostBar, 0.01)
    }

    @Test
    fun testCoolantTempDecoding() {
        // Raw: 41 05 80 -> 0x80 = 128 -> 128 - 40 = 88°C
        val response = "410580"
        val temp = SuzukiParamDecoders.decodeCoolantTempC(response)
        assertEquals(88.0, temp ?: 0.0, 0.1)
    }

    @Test
    fun testInvalidHexReturnsNull() {
        val badResponse = "NO DATA"
        assertNull(SuzukiParamDecoders.decodeCoolantTempC(badResponse))
    }
}
\`\`\`

### 11.7 Mandatory review checklist

Claude must review rather than assume correctness:

- Verify PID 23 rail-pressure scaling against the applicable specification; confirm that formula, comments and expected test value agree.
- Verify EGR PID 2C semantics and scaling for the actual ECU.
- Make the hex parser reject malformed characters rather than silently dropping invalid byte pairs; validate exact payload length, service/PID, headers, multi-frame and multi-ECU responses.
- Confirm ELM command syntax and adapter support for ATSH/ATCRA, and ensure header/filter changes cannot desynchronise the existing serialized command queue.
- Verify all ECU addresses and module identities on the actual vehicle before querying non-engine modules.
- Validate Mode 09 VIN framing; never treat the dummy VIN as the user's VIN.
- Validate mock ABS DTC response framing and encoding.
- Remove hard-coded example DTCs, telemetry, VIN and ECU status from live UI. Live values must come only from validated responses; otherwise show unavailable, not tested or stale.
- Add tests for malformed/truncated/extra payloads, wrong service/PID, boundaries, multi-frame responses and multiple ECU replies.
- Treat 180 ms as an unverified proposal. Measure actual adapter/ECU latency; do not use a fixed aggressive timeout as a blanket fix for the 4.4-second refresh.
- Keep the app read-only: no clear-DTC, actuation, security access, programming, coding, adaptation or reset commands.
- Ensure the mock transport cannot be mistaken for a live vehicle connection.

This checklist is for investigation. It does not imply that the supplied code has passed validation.


---

## 12. Consolidated implementation brief — observations first, then code fixes

**Target:** Original SX4 Scan Pro repository only.

**Repository boundary:** Work only in the repository containing this document. Do not access, clone, inspect, modify, or otherwise interact with any other repository or project. Do not transfer changes to another project.

**Scope exclusions:** Ignore the Gauge Wall / gauge-wall implementation completely for this task. Do not inspect, redesign, refactor, or modify its UI or components. This task concerns OBD transport, ECU communication, ECU information discovery, diagnostic data correctness, logging, and the dedicated ECU Information capability only.

### 12.1 Required execution order

Claude must follow this order:

1. Read Sections 1–9 and understand the real-vehicle observations and test evidence.
2. Read Sections 10–11 as diagnostic-explorer reference material, not proof that those classes exist in this repository.
3. Inspect the actual source code in this repository. Locate the real Bluetooth/ELM327 transport, command queue, PID discovery, polling scheduler, response parser, decoders, diagnostic models, logging, persistence and export code. Do not assume the reference file paths are present here.
4. Map every relevant observation to the actual source file, class and function.
5. Produce a findings table with evidence, classification, likely root cause and proposed correction.
6. Present the audit and implementation plan before making code changes.
7. Wait for explicit owner approval before coding.
8. After approval, implement only justified changes on an isolated feature branch, run tests and build the debug APK.
9. Report modified files, tests, build outcome and vehicle validations still outstanding.
10. Do not merge into the default/main branch without explicit approval.

### 12.2 Start with real-vehicle observations

Use the measured results in Sections 2–3 as the starting point:

- Torque Pro refreshed at approximately 1 second; SX4 Scan Pro's recorded RPM interval was approximately 4.4 seconds.
- Successful command/response durations were approximately 250–270 ms.
- Some PIDs returned NO DATA.
- Polling continued for approximately 83 seconds before a socket-closed error.
- With ignition OFF and key in ACC, the adapter/Bluetooth connection could remain alive while ECU readings stopped.
- Turning ignition ON did not reliably resume readings automatically.
- The recorded session advertised 19 PIDs, but advertised PIDs must not be assumed to be consistently readable.
- The shared PDF is a single diagnostic snapshot, not a time-series test.
- A zero boost reading at idle is not proof of a fault.
- A no-DTC result is not proof that all vehicle modules are fault-free.

Do not assume a root cause. Correlate each observation with actual code and logs.

### 12.3 ECU information capture — mandatory feature

The app must be able to run a user-initiated, read-only ECU information and capability scan and preserve everything the vehicle actually exposes.

For every responding ECU/module, where supported, investigate and capture:

- ECU response address/header and detected protocol.
- ECU identity or reported description.
- VIN through supported Mode 09 services.
- Calibration IDs and CVNs, where available.
- Supported diagnostic services.
- Advertised standard OBD-II PIDs.
- PIDs that have actually returned valid, correctly framed data.
- Readiness monitor information, where available.
- DTC information returned by the queried ECU/service.
- Adapter identification and connection details, where available.

Do not assume that an ECU address, module, calibration field, PID or service exists. Do not label a module as present/online until a valid response has been received and correctly attributed.

For each request, preserve:

- Timestamp.
- ECU address/header, if known.
- Service/PID/request bytes sent.
- Exact raw hexadecimal response.
- Decoded value, only if the decoder is validated.
- Request/response duration.
- Outcome: success, NO DATA, timeout, malformed response, transport error, unsupported, or not tested.
- Relevant connection state and error detail.

Keep raw responses as the evidence source so future decoder corrections can be made without repeating every vehicle test.

### 12.4 ECU profile, repeat scan and exports

Create a persistent ECU profile from actual scan results. Store available vehicle/ECU identity, protocol, calibration information, discovered modules, advertised and verified PIDs, supported services, raw response references, timestamps, scan status and errors.

Allow the owner to:
- Run a fresh scan on demand.
- Save the scan as a dated session.
- Compare a new scan with the previous scan.
- Identify newly discovered, changed, previously responsive and currently unavailable capabilities.
- Export the results as structured JSON and CSV, plus a readable PDF report if the existing app architecture supports it.

A timeout or temporary NO DATA response must not permanently classify a capability as unsupported. Distinguish confirmed-supported, advertised-but-not-verified, unsupported, not tested and temporarily unavailable.

### 12.5 Diagnostic capture and performance logging

Capture timestamped ELM commands, raw replies, response duration, PID, polling priority, success/failure, NO DATA, timeout and malformed-response counts, Bluetooth state, ECU communication state, socket errors, recovery attempts, and time from ignition ON to first valid ECU response.

Logging must not create excessive polling delays or storage growth. Provide an exportable session report so the owner can share the evidence for further analysis.

### 12.6 Implementation audit — inspect the actual original-app code

Inspect the actual implementation in this repository and locate equivalents of the following responsibilities. The six files in Sections 10–11 are reference examples only; do not assume their paths or packages exist here.

- Diagnostic data models and ECU profile models.
- Bluetooth Classic SPP connection and lifecycle.
- ELM327 initialisation and protocol negotiation.
- Serialized command pipeline and response framing.
- Supported-PID bitmap discovery.
- PID scheduler, prioritisation and polling intervals.
- PID decoders and unit conversions.
- DTC and Mode 09 response parsing.
- ECU identity/capability scan.
- Mock/simulation data isolation.
- Diagnostic logging, persistence and exports.
- Existing UI entry point for a separate ECU Information screen.

Investigate the specific risks described in Section 11.7, including rail-pressure scaling, EGR PID semantics, strict hexadecimal parsing, MAP/barometric boost derivation, ELM header/filter behaviour, ECU address assumptions, VIN framing, DTC framing, and mock/live separation. Verify each against the actual code and applicable protocol documentation before changing it.

### 12.7 Gauge Wall exclusion

The Gauge Wall is explicitly outside this task. Do not inspect or change its design, gauge components, animations, layout, styling, or behaviour. Do not use Gauge Wall changes as a delivery criterion. Preserve it untouched.

### 12.8 Safety and data integrity

- All ECU discovery and diagnostic operations must remain read-only.
- No DTC clearing, actuator tests, security access, ECU writes, coding, programming, adaptations or resets.
- Do not invent ECU identities, VINs, calibration IDs, PIDs, DTCs or telemetry.
- Never silently substitute mock values for missing live values.
- Never display stale readings as live.
- Do not send parallel ELM commands; preserve serialized request/response handling.
- Do not change working Bluetooth behaviour without evidence and regression tests.
- Do not treat a successful unit test as proof of real-vehicle compatibility.

### 12.9 Findings and approval gate

Before coding, return this table:

| Real-vehicle observation / requirement | Actual repository file and symbol | Evidence | Finding classification | Root cause or uncertainty | Proposed fix |
|---|---|---|---|---|---|

Classify each item as confirmed defect, potential defect requiring vehicle validation, unsupported assumption, improvement opportunity, or no issue found.

Then provide:
1. Root-cause analysis for the slow refresh.
2. Root-cause analysis or evidence gaps for NO DATA and socket closure.
3. Ignition OFF/ACC/ON recovery analysis.
4. Current ECU discovery and data-capture capability.
5. Missing ECU information, raw logging, persistence and export capabilities.
6. Exact files/classes/functions proposed for modification.
7. Mandatory fixes versus optional improvements.
8. Regression tests and risks to existing Bluetooth functionality.
9. A staged implementation plan.

**Do not modify, commit, push or merge code until the owner explicitly approves the findings and plan.** After approval, work only in this repository on an isolated branch. Do not merge without explicit approval.
