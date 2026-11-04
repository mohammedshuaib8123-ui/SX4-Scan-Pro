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

The following six Kotlin files were supplied as part of the separate SX4 Scan Pro V2 diagnostic-explorer implementation work. They are recorded here as a cross-reference for future review and handover.

**Important:** This inventory is implementation context, not evidence from the real-vehicle test. It does not establish that these classes are present in, integrated into, or validated against the original SX4 Scan Pro application. Keep the original-version vehicle observations and V2 implementation work clearly separated.

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
