# SX4 Scan Pro — DDiS Cockpit Feature Implementation Brief

> **Purpose:** This is the complete product and engineering handover for Claude Code. Implement these features in the existing SX4 Scan Pro Android application, not as a separate demo app. This document records the full scope discussed with the owner. Treat every requirement as intentional; do not silently omit, simplify, or replace features.

## 0. Instructions to Claude Code — read first

1. Inspect the entire repository before editing. Identify the actual app module, package names, navigation, existing Compose screens, ViewModels, OBD implementation, Gradle dependencies, permissions, and current build/test workflow.
2. **Preserve the existing working application and architecture.** Extend/refactor existing classes where appropriate; do not create duplicate ViewModels, Bluetooth managers, navigation systems, or competing cockpit screens.
3. The target is a native Android application built with **Kotlin and Jetpack Compose**. Use the repository's actual package names and conventions rather than blindly copying package declarations in this brief.
4. Do not assume that sample code previously discussed in chat is production-ready. Use it as a feature/visual reference only. Validate APIs, parsing, concurrency, lifecycle, permissions, and hardware support.
5. Build incrementally. Add unit tests for parsing, calculations, state transitions, and timer logic. Run the available Gradle build and tests. Report exactly what passed, failed, or remains blocked.
6. Do not fabricate telemetry. If a PID is unsupported, unavailable, stale, invalid, or not yet tested on this SX4 ECU, show **Unavailable / — / Stale** and explain why. Never show sample/default numbers as if they are live vehicle data.
7. Review the complete DTC requirements and all supplied reference snippets, including the original `clearDtcCodes()` function. Assess the technical behaviour, compatibility, user experience, and diagnostic implications of each option. Do not silently omit a requested capability or make an unrequested product decision; document any concerns and recommended approach for the owner to review. Reading standard powertrain codes must not be presented as a complete scan of ABS, airbag, BCM, immobilizer, or other modules.
8. Do not claim a feature is vehicle-tested until it has been tested with the owner's 2012 Maruti Suzuki SX4 ZDi 1.3 DDiS manual and Vgate iCar Pro Bluetooth 3.0 adapter.
9. Keep the UI responsive on the **Realme Pad 2 (Android 14, landscape/tablet-first)** and adapt sensibly to phone layouts.
10. Keep the existing build-preview marker (currently requested as **UI PREVIEW • BUILD n**) visible until the owner explicitly approves its removal.
11. Work on this branch only: `docs/sx4-ddis-feature-implementation-brief`. Do not push implementation changes to `main`, force-push, merge, or alter other branches without explicit approval. This document itself is the requested handover artifact.
12. Before making large UI changes, map the current UI and compare it with the intended design. Preserve existing parts the owner has already approved unless a change is explicitly listed below.

## 1. Vehicle and device target

- Vehicle: **2012 Maruti Suzuki SX4 ZDi, 1.3 DDiS / Fiat 1.3 Multijet turbo-diesel, manual transmission**.
- Tablet: **Realme Pad 2**, Android 14, landscape-first; phone compatibility is also required.
- OBD adapter: **Vgate iCar Pro Bluetooth 3.0**, classic Bluetooth SPP (not BLE).
- App stack: Kotlin + Jetpack Compose (verify the repository's actual implementation before changing it).
- The app is an OEM-plus enthusiast cockpit/head-unit companion, not a generic OBD demo.
- Design direction: premium aircraft-inspired / motorsport cockpit, sporty and technical, crystal-glass surfaces and icons, high-quality motion, legible telemetry, and restrained use of red/amber/cyan/green.

## 2. Product and visual design requirements

### 2.1 Sporty identity and typography
- Remove all user-facing **“SCAN PRO”** branding from the cockpit/welcome identity where requested.
- Use the vehicle identity: **“SX4 ZDi • DDiS 1.3”** and/or **“ZDi.DDiS”** in a consistent, sporty treatment.
- Use a bold, condensed, slanted motorsport-style type system (for example Chakra Petch / Russo One style, subject to Android font licensing and offline bundling). Use a clear secondary font for values and supporting text.
- Apply the sporty type treatment consistently to the brand block, cockpit title, driver-information headings, instrument labels, and vehicle information cards.
- Avoid excessive tracking or decorative fonts that reduce readability at driving distance.

### 2.2 Crystal-glass visual language
- Replace basic/flat navigation icons with custom multi-layer crystal-glass / translucent HUD-style icons.
- Use layered translucent surfaces, subtle bevel/specular highlights, controlled blur, depth, and restrained active-state glow.
- Use red as the primary Sport accent; green/white for Eco; subdued red/grey for Night HUD. Retain clear warning colours and strong contrast.
- Keep touch targets large enough for safe tablet use. Do not sacrifice legibility for visual effects.
- Prefer native Compose drawing, gradients, shapes, and animations. Do not assume CSS `backdrop-filter` exists in native Compose.

### 2.3 Welcome experience — cinematic
- Welcome identity should say **“SX4 ZDi”** and **“WELCOME ABOARD”**; do not show “SCAN PRO”.
- Create a polished cinematic welcome sequence with a staged typography/HUD reveal, subtle speed streaks, lens/headlight flare, and premium motion.
- The SX4 vehicle should appear to drive toward the viewer/screen in a convincing forward-perspective animation, then transition cleanly into the cockpit. Use a suitable 3D asset/rendering approach only if the project can support it; otherwise create a high-quality layered perspective animation. Do not fake a 3D model with an obviously crude zoom.
- Include tasteful ignition/headlight animation and a short aircraft/cockpit-style welcome chime/seatbelt-style cue, without excessive volume.
- Animation must be skippable, must not delay access to live driving information, and must respect reduced-motion/accessibility settings where practical.
- Use the owner's actual SX4 imagery/model if supplied. Do not substitute a visually different vehicle and imply it is the owner's car.

### 2.4 Welcome voice
- Replace robotic/obviously synthetic-sounding speech with a **natural, gentle, warm adult male voice**.
- Prefer a high-quality installed/offline voice when available, or a properly licensed natural voice service if explicitly configured. Do not promise a particular voice if Android TTS does not expose it on the device.
- Provide sensible volume/cadence controls and a way to mute voice/chime independently.
- Welcome phrase requested: **“Welcome to SX4 Airlines 2874.”**
- Critical alerts should use concise cockpit-style spoken warnings (example: “High temperature”), paired with visual warning indicators. Avoid repeated nuisance announcements; use cooldowns and state-change logic.

## 3. Cockpit features to implement

### 3.1 DDiS sequential shift-light bar — `ShiftLightBar.kt` or equivalent
- Eight-segment progressive LED bar, positioned above/near the RPM instrument.
- Initial configurable reference bands discussed:
  - Green: approximately 2,000–2,800 RPM.
  - Amber: approximately 2,800–3,600 RPM.
  - Red/upshift warning: approximately 3,600–4,000 RPM, with flashing only at the configured upper threshold.
- These are **starting display thresholds, not factory calibration claims**. Make thresholds configurable and validate with the actual engine/driver preference.
- Show inactive segments clearly; animate transitions smoothly. Avoid aggressive flashing during normal driving.
- Hide/mark unavailable if RPM data is invalid or stale.

### 3.2 Drive modes — `DriveModeSelector.kt` or equivalent
Provide one-tap selection with persistent state and a clear selected state:

**SPORT**
- Dark graphite/black with crimson/red highlights.
- Prioritise RPM, speed, boost (only when valid), intake temperature, and shift-light feedback.
- Sportier animations and high-contrast instrumentation without distracting motion.

**ECO / CRUISE**
- Calm green/white treatment.
- Prioritise speed, trip data, fuel-efficiency estimates, average speed, and gentle driving feedback.
- Show instantaneous/average economy only when the required data and calculation are valid. Do not invent fuel economy or range if the ECU does not provide enough information.

**NIGHT HUD**
- Very dark/low-glare theme, reduced brightness and restrained red/grey highlights.
- Prioritise digital speed, coolant temperature, fuel level only if supported, and active warnings.
- Keep critical warnings visible. Do not hide warnings or make the display so dim that it becomes unreadable.

Mode changes must update theme tokens and relevant instrument emphasis without resetting telemetry or trip history.

### 3.3 Live telemetry instruments
Build a consistent, tappable metric-card/instrument system. Include these metrics where supported by the ECU/adapter:

- Engine RPM — Mode 01 PID 0C.
- Vehicle speed — PID 0D.
- Manifold absolute pressure (MAP) — PID 0B.
- Barometric pressure — PID 33 if supported; required for a defensible relative-boost estimate.
- Engine coolant temperature — PID 05.
- Intake air temperature — PID 0F.
- Throttle position — PID 11, if supported.
- MAF airflow — PID 10, if supported.
- EGR commanded position — PID 2C, if supported.
- Fuel level — PID 2F, only if supported.
- Control-module/battery voltage — PID 42 where supported, or a clearly identified adapter/vehicle-voltage source.
- Fuel rail pressure — only if the exact supported PID/service and scaling are confirmed for this ECU. Do not invent a generic 0–1,600 bar value or display a simulated pressure.
- Oil temperature, oil pressure, radiator-fan state, and other advanced values: show only if a real supported source exists. Otherwise mark as unsupported/unavailable; do not infer them from coolant temperature or other unrelated PIDs.

### 3.4 Boost calculation and display
- PID 0B returns **absolute MAP in kPa**, not turbo boost directly.
- Relative boost estimate = MAP absolute pressure minus barometric pressure, converted to bar.
- If barometric pressure is unavailable, do not silently assume a fixed 101.3 kPa for a live, precise boost reading. Either label the value as estimated with its assumption or show boost unavailable.
- Handle vacuum/negative pressure sensibly if displayed; do not clamp every negative value to zero if the UI intends to show manifold pressure behaviour.
- Validate actual PID support and response on the target SX4.

### 3.5 Tap-to-inspect telemetry graph drawer — `TelemetryGraphDrawer.kt` or equivalent
- Tapping any supported metric opens a slide-up drawer/detail panel.
- Show a rolling **60-second history** with a readable live line graph/sparkline.
- Show current value, unit, recent peak, average, sample age/last-update time, and a clearly sourced/labelled expected range when a reliable reference is available.
- Mark stale gaps and invalid readings; do not draw fabricated continuous lines across missing data.
- Use a bounded history buffer and avoid unbounded memory growth.
- Graph axes/scales must adapt to the metric; do not use one arbitrary scale for all metrics.
- The graph should update from ViewModel state, not create a second polling loop.

### 3.6 G-force meter — `GForceMeter.kt` or equivalent
- Use the tablet accelerometer/linear-acceleration sensors to show longitudinal and lateral G in a motorsport-style friction circle.
- Show concentric reference rings (e.g. 0.5g, 1.0g, 1.5g) and a live dot/vector.
- Correct for device orientation, gravity, sensor noise, mounting angle, and filtering. Provide calibration/zeroing and clear axis labels.
- Include a sensor availability/status indicator. If sensor data is unavailable or uncalibrated, show unavailable rather than false zero.
- Explain that tablet placement and movement affect readings; this is an enthusiast display, not certified measurement or a safety instrument.

### 3.7 0–100 km/h performance timer — `PerformanceTimer.kt` or equivalent
- Provide a dedicated performance panel with ready/armed/running/completed states.
- Measure 0–100 km/h using vehicle speed data, with stable start/end threshold logic, sample validation, and timestamps.
- Do not start simply because speed is greater than zero; prevent false starts from rolling, noise, reconnects, or stale values.
- Require the vehicle to be stationary below a configurable low-speed threshold for an arming period, then detect a valid launch. Require valid fresh speed samples and complete at 100 km/h.
- Show the recorded time, allow reset, and optionally retain best/recent runs locally with date and data-quality metadata.
- Clearly label results as OBD-sampled estimates; polling rate and ECU/adapter latency limit accuracy.
- This is for closed-course/private testing only. Do not encourage timing runs on public roads; disable or gate performance timing while driving if appropriate.

## 4. OBD-II Bluetooth and data architecture

### 4.1 Existing connection and manager
- Extend the existing OBD layer (for example `ObdBluetoothManager.kt`) rather than creating a parallel Bluetooth stack.
- Adapter is classic Bluetooth SPP. Check Android 12+ Bluetooth runtime permissions and connection lifecycle, including reconnect/disconnect, socket close, cancellation, and activity/service lifecycle.
- Serialise ELM327 commands: one request at a time, with robust prompt/timeout handling. Do not issue concurrent commands into one stream.
- Initialise with appropriate ELM commands (reset, echo off, linefeeds/spaces as required, protocol auto where appropriate), but wait for each response and validate adapter readiness.
- Implement robust buffering until the ELM prompt (`>`) or timeout; a single `InputStream.read()` is not guaranteed to return a complete response.
- Normalise whitespace, CR/LF, headers, adapter messages, CAN multi-frame responses, `NO DATA`, `?`, BUS INIT errors, and malformed/partial responses.
- Parse hex bytes safely and return typed results/errors, not silent zero values.
- Discover supported PIDs (01 00, then subsequent supported-PID ranges) where possible; poll only supported PIDs.
- Use a configurable polling scheduler with priority for essential cockpit values, sensible update rates, timeouts, and graceful degradation. Avoid hammering the ECU or blocking the UI thread.
- Expose connection state, adapter identity where available, protocol, last successful response time, and per-PID availability/quality.

### 4.2 Suggested architecture
Use the repository's existing structure, but keep responsibilities separated:

- **OBD transport/manager:** Bluetooth SPP, ELM command queue, parsing, connection state.
- **PID definitions/decoders:** PID command, support detection, units, conversion formulas, validation.
- **Telemetry repository:** polling schedule, latest values, quality/staleness, bounded history.
- **CockpitViewModel:** expose immutable `StateFlow<CockpitState>`; own UI state, selected drive mode, graph selection, timer state and derived metrics.
- **Compose UI:** render state and dispatch user actions; no Bluetooth I/O or business calculations inside Composables.
- **Sensor repository:** accelerometer/linear acceleration, calibration, filtering and lifecycle.
- **Trip/performance storage:** local persistence for trip summaries and performance records, with explicit reset/export controls if implemented.

Possible model concepts (adapt to actual project; do not duplicate existing types):

- `DriveMode { SPORT, ECO, NIGHT_HUD }`
- `TelemetryPoint(timestampMs, value)`
- `TelemetryValue(value, unit, timestampMs, source, quality)`
- `TelemetryQuality { VALID, STALE, UNSUPPORTED, INVALID, ERROR }`
- `CockpitState`: drive mode, RPM, speed, boost, coolant, intake temperature, EGR, voltage, fuel level, connection status, G values, performance timer state, and metric histories.
- Use nullable values/typed availability rather than fake defaults such as 88°C coolant, 14.2V battery, 300 bar rail pressure, or 65% fuel.

### 4.3 PID references and calculation validation
Common standard OBD-II references (verify support and response format on the target ECU):
- `010C`: RPM; A*256+B divided by 4.
- `010D`: vehicle speed; A km/h.
- `010B`: MAP absolute pressure; A kPa.
- `0105`: coolant temperature; A−40 °C.
- `010F`: intake air temperature; A−40 °C.
- `0110`: MAF airflow; (256*A+B)/100 g/s.
- `0111`: throttle position; A*100/255 %.
- `012C`: commanded EGR; A*100/255 %.
- `012F`: fuel level input; A*100/255 %.
- `0133`: barometric pressure; A kPa.
- `0142`: control-module voltage; (256*A+B)/1000 V.

These are standard PID formulas, not a guarantee that the SX4 ECU supports each PID or exposes a value that matches every dashboard interpretation. Confirm positive responses, payload length, units, and plausibility. Manufacturer-specific data requires documented/validated support.

## 5. DTC diagnostics

- Add a diagnostic screen to request Mode 03 stored powertrain DTCs, and optionally Mode 07/0A only if properly supported and clearly labelled.
- Parse response framing correctly, including headers, multiple frames, zero padding, and adapter formatting. Add unit tests with representative raw responses.
- Display code, generic description, raw response, and a note that diagnosis requires inspection; a code is not proof that a named part has failed.
- Include a small curated DDiS/Multijet explanation catalogue for known codes (e.g. P0400 EGR flow, P0234 turbo overboost, P0100 MAF circuit, P0115 coolant-temperature circuit, P0087 fuel-rail/system pressure too low, P0670 glow-plug control module). Descriptions must be cautious and must not prescribe replacing a component based on a code alone.
- Clearly state: standard OBD Mode 03 is not a full-vehicle scan and may not access ABS, airbags, BCM, immobilizer, or manufacturer-specific modules.
- Evaluate whether and how Mode 04 code clearing should be supported, including an explicit user-initiated action, confirmation flow, ECU/adapter response handling, and the diagnostic consequences of clearing stored information. Explain the trade-offs and proposed behaviour to the owner; do not silently exclude the supplied function or assume a product decision that the owner has not made.

## 6. Trip computer and fuel economy log — `TripComputer.kt` or equivalent

- Provide trip distance, trip duration, average speed, estimated fuel consumed, average economy, and estimated trip cost where inputs are available.
- Distinguish **instantaneous** from **trip-average** economy; do not calculate trip cost from an instantaneous economy sample.
- Fuel consumption should be based on a validated source (supported fuel-rate PID, injector/fuel data if validated, or a clearly labelled user-entered/calibrated estimate). Do not invent ECU fuel-rate support.
- Allow the user to enter/update diesel price per litre; do not hard-code an assumed fuel price.
- Maintain trip start/reset and persist completed trip summaries locally if feasible.
- If fuel data is unavailable, still show distance/time/average speed and label fuel/economy/cost unavailable.
- Explain that OBD-derived fuel economy is an estimate and should be calibrated against fill-to-fill fuel records.

## 7. Maintenance and ownership features

- Add an optional maintenance log/reminder area for owner-entered service dates and odometer readings: engine oil, oil filter, fuel filter, air filter, coolant, brake fluid, gearbox oil, timing-chain inspection, tyres, brakes, and other service items.
- Reminders should be configurable by time and/or kilometres. Do not claim to read oil life or service intervals directly from the ECU unless a supported source exists.
- Include a service history note/log and optional notes/receipt references. Keep owner-entered information visually distinct from live OBD telemetry.
- Optional battery-voltage trend can be shown only when the voltage source is identified and validated; do not diagnose alternator health from one voltage sample.

## 8. Head-unit / Android Auto receiver direction

- The owner's broader goal is to have the same tablet app provide an Android Auto-like head-unit/receiver experience on the Realme Pad 2, with a phone connecting to the tablet.
- Treat this as a **separate integration workstream** from OBD cockpit telemetry. Investigate Android Auto receiver compatibility, supported protocols, licensing, and platform restrictions before implementation.
- Do not make a button simply open an unrelated Git repository. The app should provide a clear in-app receiver screen/status and a genuine supported connection flow if technically and legally feasible.
- Do not claim Android Auto receiver functionality until a real phone-to-tablet connection has been implemented and tested. Document any dependency, licence obligations (including AGPL obligations if relevant), and limitations before incorporating third-party code.

## 9. Safety, reliability, accessibility and privacy

- The app is a secondary display, not a replacement for the factory instrument cluster or warning lamps.
- Never encourage the driver to interact with menus, graphs, settings, or performance tests while moving. Keep essential cockpit data glanceable and provide a parked-only interaction mode for setup/diagnostics.
- Critical warning thresholds must be validated for this vehicle; avoid presenting generic thresholds as manufacturer specifications.
- Avoid false alarms: use debounce, hysteresis, stale-data detection, and alert cooldowns.
- Keep voice alerts concise, non-repetitive, and independently muteable.
- Handle screen rotation, app backgrounding, Bluetooth disconnects, tablet sleep, permission denial, sensor unavailability, and process recreation.
- Do not upload driving history or vehicle data without explicit owner consent. Explain any local persistence and provide clear deletion/export controls.
- Provide accessible contrast, scalable text, clear units, and reduced-motion handling.

## 10. Proposed screen placement / information architecture

Retain the existing app's four major areas if they already exist: **Cockpit, Diagnostics, Head Unit, Settings**.

**Cockpit**
- Main RPM and speed instruments, shift-light bar, selected drive mode.
- Sport/Eco/Night mode selector.
- Priority live metrics: boost (valid only), coolant, intake temperature, EGR, voltage, and connection/quality status.
- G-meter and performance timer as secondary panels or dedicated cockpit sub-tab.
- Tappable telemetry tiles open the 60-second graph drawer.
- Trip computer accessible without cluttering the primary driving view.

**Diagnostics**
- Bluetooth/ELM connection status and supported PID list.
- Read-only DTC list with explanations and limitations.
- Per-PID last update, quality and raw-response diagnostics for troubleshooting.

**Head Unit**
- Actual receiver connection experience only when supported; otherwise honest status and setup information, not a dead link.

**Settings**
- Drive-mode defaults, shift-light thresholds, units, voice/chime volume, voice enable/disable, diesel price, maintenance reminders, sensor calibration, data retention and theme preferences.

## 11. Required implementation files / modules (suggested names only)

Use or adapt these names to the repository's actual package/module layout:

- `CockpitModels.kt` — DriveMode, CockpitState, telemetry value/quality/history models.
- `CockpitViewModel.kt` — StateFlow state, orchestration, rolling bounded history, UI actions.
- `ShiftLightBar.kt` — 8-segment DDiS shift-light visual.
- `DriveModeSelector.kt` — Sport/Eco/Night HUD control and theme mapping.
- `GForceMeter.kt` — calibrated sensor-based friction circle.
- `PerformanceTimer.kt` — robust 0–100 km/h state machine and result UI.
- `TelemetryGraphDrawer.kt` — 60-second graph/detail panel.
- `TripComputer.kt` — trip and fuel economy presentation/calculation.
- `ObdBluetoothManager.kt` — robust serial ELM327 transport (extend existing implementation).
- `ObdPid.kt` / PID decoder files — supported PID definitions and validated conversions.
- `ObdDtcExtensions.kt` or equivalent — read-only DTC retrieval/parsing and descriptions.
- Sensor service/repository — accelerometer collection and calibration.
- Local persistence layer — trip/performance/maintenance records where implemented.
- Welcome screen/animation/audio components — cinematic welcome, vehicle approach, natural male voice and chime.

These are logical responsibilities, not a demand to create every file if the project already has equivalent classes.

## 12. Acceptance checklist — do not mark complete until verified

### Build and architecture
- [ ] Existing project builds before and after changes.
- [ ] No duplicate or parallel OBD connection manager/ViewModel/navigation stack.
- [ ] New code follows existing package structure and lifecycle patterns.
- [ ] Unit tests cover PID decoding, invalid responses, DTC parsing, boost calculation, shift-light thresholds, timer state transitions, and trip calculations.

### Visual and welcome
- [ ] Sporty typography applied consistently; “SCAN PRO” removed from requested identity locations.
- [ ] “SX4 ZDi • DDiS 1.3” / “ZDi.DDiS” identity is visible and consistent.
- [ ] Crystal-glass navigation icons and polished HUD surfaces are implemented.
- [ ] Welcome sequence includes cinematic reveal and car-forward approach animation.
- [ ] Welcome voice is a natural gentle male voice when a suitable voice is available; fallback and mute controls work.
- [ ] Welcome can be skipped and does not block the cockpit.

### Cockpit and telemetry
- [ ] Sport, Eco and Night HUD modes work and persist without corrupting telemetry.
- [ ] Shift-light bar responds only to fresh valid RPM and configurable thresholds.
- [ ] Every metric indicates valid/stale/unsupported status correctly.
- [ ] MAP is not mislabelled as relative boost; boost requires valid barometric reference or an explicit estimate label.
- [ ] Tapping supported metrics opens a live 60-second history panel.
- [ ] G-meter has correct orientation/calibration and honest sensor status.
- [ ] 0–100 timer uses a robust stationary-arm/launch/finish state machine and labels OBD timing accuracy limitations.
- [ ] Trip computer distinguishes live economy from trip-average economy and never invents fuel use/cost.

### Diagnostics, maintenance and head unit
- [ ] ELM327 command queue, response framing, timeout, reconnect and permissions are robust.
- [ ] Supported PIDs are discovered/validated; unsupported values remain unavailable.
- [ ] DTC reader is read-only; no clear/reset command or button exists.
- [ ] DTC descriptions are cautious; full-vehicle scan limitations are visible.
- [ ] Maintenance reminders are owner-configured and not falsely represented as ECU readings.
- [ ] Head-unit receiver work is implemented only if technically/legal feasibility is established and real connection is tested.

### Device validation
- [ ] Responsive on Realme Pad 2 landscape and usable on phone.
- [ ] Tested with the actual Vgate iCar Pro Bluetooth 3.0 adapter and the owner's SX4 before calling OBD features vehicle-verified.
- [ ] Disconnects, unsupported PIDs, stale data, sensor unavailability, and permission denial are handled visibly.
- [ ] No UI/build regression; existing approved UI elements remain intact.

## 13. Important corrections to earlier chat snippets

The code fragments pasted in the conversation were conceptual scaffolding, not a drop-in implementation. In particular:

- A single `InputStream.read()` does not guarantee a full ELM327 response. Read until prompt/timeout with buffering.
- Parsing a missing/invalid PID as zero is misleading. Return typed errors/unavailable values.
- PID 0B is absolute MAP, not boost. Relative boost needs barometric pressure.
- A default rail-pressure value (such as 300 bar) is fabricated unless read from a validated supported PID.
- Fuel level, fuel rate, oil temperature/pressure, fan state, and rail pressure must not be assumed available.
- A timer that starts whenever speed exceeds 1 km/h can false-trigger. Use a proper arming and launch state machine.
- Accelerometer axes are device-relative and affected by mounting/orientation/gravity. Calibrate and filter.
- DTC parsing must handle real ELM framing and multi-frame/zero-padded data; a simplistic token loop is not sufficient.
- Clearing codes was mentioned in an earlier example, but the required initial product behaviour is **read-only diagnostics**. Do not implement clearing.
- A hard-coded diesel price is not acceptable; use an owner-configured price.
- The example UI thresholds are initial preferences, not certified Suzuki factory specifications.

## 14. Delivery expectations

When implementation is complete, provide:
1. A concise summary of files changed and features delivered.
2. A list of features not implemented and the exact reason (hardware, PID support, licensing, time, or dependency).
3. Gradle build and test results.
4. Screenshots or a short recording of the Realme Pad 2 landscape UI if available.
5. A clear list of tests still requiring the physical SX4 and Vgate adapter.
6. The commit/branch details. Do not merge to `main` until the owner reviews and approves.

**Owner's priority:** Make this feel like a premium, sporty, aircraft-inspired OEM-plus SX4 cockpit while ensuring every live reading is honest, reliable, and genuinely sourced. The complete scope above matters; do not silently drop any section.


---

# Appendix A — Original Kotlin reference snippets supplied in the conversation

> These are included so Claude can see the actual code the owner supplied, not just the feature descriptions. They are **reference scaffolding, not production-ready code**. Preserve them for reference, inspect every issue, adapt them to the repository's real architecture, and write tests. The corrections in Section 13 and the read-only DTC rule in Section 5 take precedence over unsafe or incomplete behaviour in these snippets. Do not paste them blindly or create duplicate classes.

## A1. Cockpit models — `CockpitModels.kt`

```kotlin
package com.suzuki.sx4.cockpit.data

enum class DriveMode {
    SPORT,
    ECO,
    NIGHT_HUD
}

data class TelemetryPoint(
    val timestampMs: Long = System.currentTimeMillis(),
    val value: Float
)

data class CockpitState(
    val driveMode: DriveMode = DriveMode.SPORT,
    val rpm: Int = 0,
    val speedKmh: Int = 0,
    val boostBar: Float = 0.0f,
    val railPressureBar: Float = 300f,
    val coolantTempC: Int = 88,
    val intakeAirTempC: Int = 32,
    val egrPositionPercent: Int = 12,
    val batteryVoltage: Float = 14.2f,
    val fuelLevelPercent: Int = 65,
    val isObdConnected: Boolean = false,
    val lateralG: Float = 0.0f,
    val longitudinalG: Float = 0.0f,
    val zeroToHundredSec: Float? = null,
    val isTimerActive: Boolean = false
)
```

**Important adaptation:** the numeric defaults above are examples from the original snippet only. Replace them with nullable/typed unavailable values; never show these defaults as live vehicle readings.

## A2. Sequential shift-light bar — `ShiftLightBar.kt`

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ShiftLightBar(
    rpm: Int,
    modifier: Modifier = Modifier
) {
    val totalLeds = 8
    val greenThreshold = 2000
    val amberThreshold = 2800
    val redThreshold = 3600
    val flashThreshold = 4000

    val infiniteTransition = rememberInfiniteTransition(label = "FlashTransition")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(120, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Blink"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(16.dp)) {
        val spacing = 6.dp.toPx()
        val ledWidth = (size.width - (spacing * (totalLeds - 1))) / totalLeds
        val ledHeight = size.height
        val corner = CornerRadius(4.dp.toPx())

        for (i in 0 until totalLeds) {
            val stepRpm = greenThreshold + (i * ((flashThreshold - greenThreshold) / totalLeds))
            val isActive = rpm >= stepRpm

            val baseColor = when {
                i < 3 -> Color(0xFF00E676)
                i < 6 -> Color(0xFFFFD600)
                else -> Color(0xFFFF1744)
            }

            val finalColor = if (isActive) {
                if (rpm >= flashThreshold && i >= 6) {
                    baseColor.copy(alpha = flashAlpha)
                } else {
                    baseColor
                }
            } else {
                Color(0xFF222224)
            }

            drawRoundRect(
                color = finalColor,
                topLeft = Offset(x = i * (ledWidth + spacing), y = 0f),
                size = Size(ledWidth, ledHeight),
                cornerRadius = corner
            )
        }
    }
}
```

## A3. G-force friction meter — `GForceMeter.kt`

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun GForceMeter(
    lateralG: Float,
    longitudinalG: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(140.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.width / 2f - 8.dp.toPx()

        listOf(0.33f, 0.66f, 1.0f).forEach { scale ->
            drawCircle(
                color = Color(0xFF2C2C30),
                radius = maxRadius * scale,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        drawLine(Color(0xFF2C2C30), Offset(center.x, 8.dp.toPx()), Offset(center.x, size.height - 8.dp.toPx()))
        drawLine(Color(0xFF2C2C30), Offset(8.dp.toPx(), center.y), Offset(size.width - 8.dp.toPx(), center.y))

        val clampedLat = (lateralG / 1.5f).coerceIn(-1f, 1f)
        val clampedLong = (longitudinalG / 1.5f).coerceIn(-1f, 1f)

        val dotPosition = Offset(
            x = center.x + (clampedLat * maxRadius),
            y = center.y - (clampedLong * maxRadius)
        )

        drawCircle(
            color = Color(0xFFFF2A2A).copy(alpha = 0.35f),
            radius = 12.dp.toPx(),
            center = dotPosition
        )
        drawCircle(
            color = Color(0xFFFF2A2A),
            radius = 5.dp.toPx(),
            center = dotPosition
        )
    }
}
```

## A4. 60-second telemetry graph drawer — `TelemetryGraphDrawer.kt`

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.data.TelemetryPoint

@Composable
fun TelemetryGraphDrawer(
    metricTitle: String,
    currentValue: String,
    unit: String,
    history: List<TelemetryPoint>,
    minExpected: Float,
    maxExpected: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141416), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(metricTitle.uppercase(), color = Color(0xFF9E9E9E), fontSize = 12.sp)
            Text("$currentValue $unit", color = Color(0xFFFF2A2A), fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            if (history.size < 2) return@Canvas

            val width = size.width
            val height = size.height
            val path = Path()
            val range = (maxExpected - minExpected).coerceAtLeast(1f)

            history.forEachIndexed { index, point ->
                val x = (index.toFloat() / (history.size - 1)) * width
                val normalizedY = ((point.value - minExpected) / range).coerceIn(0f, 1f)
                val y = height - (normalizedY * height)

                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    val prevPoint = history[index - 1]
                    val prevX = ((index - 1).toFloat() / (history.size - 1)) * width
                    val prevNormY = ((prevPoint.value - minExpected) / range).coerceIn(0f, 1f)
                    val prevY = height - (prevNormY * height)
                    val controlX = (prevX + x) / 2f
                    path.cubicTo(controlX, prevY, controlX, y, x, y)
                }
            }

            drawPath(
                path = path,
                color = Color(0xFFFF2A2A),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("60s ago", color = Color(0xFF616161), fontSize = 10.sp)
            Text("Live", color = Color(0xFF00E676), fontSize = 10.sp)
        }
    }
}
```

## A5. ELM327 Bluetooth driver sketch — `ObdBluetoothManager.kt`

```kotlin
package com.suzuki.sx4.cockpit.obd

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class ObdBluetoothManager(private val socket: BluetoothSocket) {
    private val inputStream: InputStream = socket.inputStream
    private val outputStream: OutputStream = socket.outputStream

    suspend fun initializeElm327(): Boolean = withContext(Dispatchers.IO) {
        try {
            sendRawCommand("ATZ\r")
            sendRawCommand("ATE0\r")
            sendRawCommand("ATL0\r")
            sendRawCommand("ATSP0\r")
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readRpm(): Int = withContext(Dispatchers.IO) {
        val response = sendRawCommand("010C\r")
        parseRpm(response)
    }

    suspend fun readManifoldPressure(): Float = withContext(Dispatchers.IO) {
        val response = sendRawCommand("010B\r")
        val kpa = parseSingleByteHex(response)
        ((kpa - 101.3f) / 100f).coerceAtLeast(0.0f)
    }

    suspend fun readCoolantTemp(): Int = withContext(Dispatchers.IO) {
        val response = sendRawCommand("0105\r")
        parseSingleByteHex(response) - 40
    }

    private fun sendRawCommand(cmd: String): String {
        outputStream.write(cmd.toByteArray())
        outputStream.flush()
        val buffer = ByteArray(256)
        val bytes = inputStream.read(buffer)
        return String(buffer, 0, bytes).replace(">", "").trim()
    }

    private fun parseRpm(hexResponse: String): Int {
        val tokens = hexResponse.split(" ").filter { it.isNotBlank() }
        if (tokens.size >= 4 && tokens[0] == "41" && tokens[1] == "0C") {
            val a = tokens[2].toIntOrNull(16) ?: return 0
            val b = tokens[3].toIntOrNull(16) ?: return 0
            return ((a * 256) + b) / 4
        }
        return 0
    }

    private fun parseSingleByteHex(hexResponse: String): Int {
        val tokens = hexResponse.split(" ").filter { it.isNotBlank() }
        if (tokens.size >= 3) return tokens[2].toIntOrNull(16) ?: 0
        return 0
    }
}
```

**Required rewrite before use:** the original helper performs one stream read only, has no prompt/timeout framing, and treats invalid data as zero. Its MAP-to-boost conversion also assumes a fixed barometric pressure. Implement the robust transport and boost rules in Sections 3.4 and 4 instead.

## A6. Drive-mode selector and theme tokens — `DriveModeSelector.kt`

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suzuki.sx4.cockpit.data.DriveMode

data class CockpitColors(
    val primaryAccent: Color,
    val background: Color,
    val surface: Color,
    val textPrimary: Color
)

fun getThemeForMode(mode: DriveMode): CockpitColors = when (mode) {
    DriveMode.SPORT -> CockpitColors(
        primaryAccent = Color(0xFFFF2A2A),
        background = Color(0xFF0A0A0C),
        surface = Color(0xFF141416),
        textPrimary = Color(0xFFFFFFFF)
    )
    DriveMode.ECO -> CockpitColors(
        primaryAccent = Color(0xFF00E676),
        background = Color(0xFF0C0E0C),
        surface = Color(0xFF121814),
        textPrimary = Color(0xFFE0E0E0)
    )
    DriveMode.NIGHT_HUD -> CockpitColors(
        primaryAccent = Color(0xFFE02020),
        background = Color(0xFF000000),
        surface = Color(0xFF050505),
        textPrimary = Color(0xFF9E9E9E)
    )
}

@Composable
fun DriveModeSelector(
    selectedMode: DriveMode,
    onModeSelected: (DriveMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xFF141416), RoundedCornerShape(24.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DriveMode.values().forEach { mode ->
            val isSelected = mode == selectedMode
            val accent = when (mode) {
                DriveMode.SPORT -> Color(0xFFFF2A2A)
                DriveMode.ECO -> Color(0xFF00E676)
                DriveMode.NIGHT_HUD -> Color(0xFF757575)
            }

            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) accent.copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(20.dp)
                    )
                    .then(
                        if (isSelected) Modifier.border(1.dp, accent, RoundedCornerShape(20.dp))
                        else Modifier
                    )
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.name.replace("_", " "),
                    color = if (isSelected) accent else Color(0xFF888888),
                    fontSize = 12.sp
                )
            }
        }
    }
}
```

## A7. 0–100 km/h timer sketch — `PerformanceTimer.kt`

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class DragTimerManager {
    var startTimeMs: Long = 0L
    var isRunning by mutableStateOf(false)
    var elapsedTimeSec by mutableStateOf(0.0f)
    var recordTimeSec by mutableStateOf<Float?>(null)

    fun onSpeedUpdate(speedKmh: Int) {
        if (!isRunning && speedKmh > 1 && elapsedTimeSec == 0.0f) {
            startTimeMs = System.currentTimeMillis()
            isRunning = true
        } else if (isRunning) {
            elapsedTimeSec = (System.currentTimeMillis() - startTimeMs) / 1000f
            if (speedKmh >= 100) {
                isRunning = false
                recordTimeSec = elapsedTimeSec
            }
        }
    }

    fun reset() {
        isRunning = false
        elapsedTimeSec = 0.0f
    }
}

@Composable
fun PerformanceTimer(
    timerManager: DragTimerManager,
    currentSpeedKmh: Int,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(currentSpeedKmh) {
        timerManager.onSpeedUpdate(currentSpeedKmh)
    }

    Column(
        modifier = modifier
            .background(Color(0xFF141416), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("0 — 100 KM/H RUN", color = Color(0xFF9E9E9E), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(6.dp))

        val displayTime = when {
            timerManager.recordTimeSec != null -> String.format("%.2f s", timerManager.recordTimeSec)
            timerManager.isRunning -> String.format("%.2f s", timerManager.elapsedTimeSec)
            else -> "READY"
        }

        Text(
            text = displayTime,
            color = if (timerManager.isRunning) Color(0xFFFFD600) else Color(0xFFFF2A2A),
            fontSize = 28.sp
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (timerManager.isRunning) "RUN IN PROGRESS ($currentSpeedKmh km/h)" else "LAUNCH TO START",
            color = Color(0xFF757575),
            fontSize = 10.sp
        )
    }
}
```

**Required rewrite before use:** this original timer starts at any speed above 1 km/h and uses wall-clock updates from recomposition. Implement the proper stationary-arm/launch/finish state machine in Section 3.7.

## A8. DTC reader/parser sketch — `ObdDtcExtensions.kt`

```kotlin
package com.suzuki.sx4.cockpit.obd

suspend fun ObdBluetoothManager.readDtcCodes(): List<String> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val response = sendRawCommand("03\r")
        parseDtcCodes(response)
    }

private fun parseDtcCodes(hexResponse: String): List<String> {
    val dtcs = mutableListOf<String>()
    val tokens = hexResponse.split(" ").filter { it.isNotBlank() }
    var i = 0
    while (i < tokens.size - 1) {
        if (tokens[i] == "43") {
            i++
            continue
        }
        val byte1 = tokens.getOrNull(i)?.toIntOrNull(16) ?: break
        val byte2 = tokens.getOrNull(i + 1)?.toIntOrNull(16) ?: break
        if (byte1 == 0 && byte2 == 0) break

        val prefix = when ((byte1 and 0xC0) shr 6) {
            0 -> "P"
            1 -> "C"
            2 -> "B"
            else -> "U"
        }
        val codeNumber = String.format("%02X%02X", byte1 and 0x3F, byte2)
        dtcs.add("$prefix$codeNumber")
        i += 2
    }
    return dtcs
}

fun getDdiSCodeDescription(dtc: String): String = when (dtc) {
    "P0400" -> "EGR Valve Flow Malfunction (Clean EGR assembly)"
    "P0234" -> "Turbocharger VGT Overboost Condition"
    "P0100" -> "Mass Air Flow (MAF) Sensor Circuit"
    "P0115" -> "Engine Coolant Temperature Circuit"
    "P0087" -> "Fuel Rail / System Pressure Too Low"
    "P0670" -> "Glow Plug Control Module Circuit"
    else -> "Generic Fault Code ($dtc)"
}
```

**Reference note:** the original conversation also supplied a `clearDtcCodes()` function that sends Mode 04. It is preserved in the next subsection because the owner asked to keep every pasted code example in this document. Review this function alongside the main DTC requirements, assess its behaviour and limitations, and decide on an implementation recommendation to discuss with the owner. The parser above is only a simple reference; implement complete ELM response/frame handling and tests.

### A8.1. Original DTC clear function supplied in chat

The following is the original function supplied by the owner. Preserve it as part of the implementation brief and assess it as you would the other supplied code. Mode 04 can clear stored emissions-related diagnostic information and may erase evidence useful for diagnosis; account for this behaviour when evaluating the implementation and user-confirmation flow.

```kotlin
suspend fun ObdBluetoothManager.clearDtcCodes(): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val response = sendRawCommand("04\r")
        response.contains("44") || response.contains("OK")
    }
```

This is the original simple acknowledgement check. Assess whether its response validation is sufficient; do not assume that an acknowledgement confirms all vehicle modules or all fault records have been cleared.

## A9. Trip computer sketch — `TripComputer.kt`

```kotlin
package com.suzuki.sx4.cockpit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TripComputerCard(
    distanceKm: Float,
    avgSpeedKmh: Float,
    instantKmPerLiter: Float,
    dieselPricePerLiter: Float = 90.0f,
    modifier: Modifier = Modifier
) {
    val estimatedFuelUsedLiters =
        if (instantKmPerLiter > 0) distanceKm / instantKmPerLiter else 0f
    val tripCost = estimatedFuelUsedLiters * dieselPricePerLiter

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141416), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("TRIP COMPUTER", color = Color(0xFF9E9E9E), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TripMetric("DISTANCE", String.format("%.1f", distanceKm), "km")
            TripMetric("AVG SPEED", String.format("%.0f", avgSpeedKmh), "km/h")
            TripMetric("EFFICIENCY", String.format("%.1f", instantKmPerLiter), "km/L")
            TripMetric("EST. COST", String.format("₹%.0f", tripCost), "")
        }
    }
}

@Composable
private fun TripMetric(label: String, value: String, unit: String) {
    Column {
        Text(label, color = Color(0xFF616161), fontSize = 9.sp)
        Row {
            Text(value, color = Color.White, fontSize = 16.sp)
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(unit, color = Color(0xFF888888), fontSize = 11.sp)
            }
        }
    }
}
```

**Required rewrite before use:** do not estimate trip cost from a single instantaneous economy reading, and do not hard-code the diesel price. Use a validated trip fuel estimate and an owner-configurable price as specified in Section 6.

## A10. Other non-code requirements from the same conversation

The following are also part of the owner's request and must not be lost while implementing the Kotlin modules:

- The top-left header and all requested identity locations must replace “SCAN PRO” with sporty **SX4 ZDi • DDiS 1.3 / ZDi.DDiS** branding.
- Apply the sporty font treatment to “SX4 / DDiS 1.3”, “DRIVER INFORMATION SYSTEM”, “Cockpit”, “SX4 1.3 DDiS”, and “FIAT 1.3 MULTIJET” headings/cards where those labels exist.
- Cinematic staggered welcome typography, lens flares, speed streaks, glowing HUD borders, and an SX4 driving toward the viewer.
- Crystal-glass navigation icons with frosted/translucent layered appearance, bevel highlights, depth and restrained active red glow.
- Natural gentle male welcome voice, aircraft/cockpit chime, and spoken critical alerts.
- Drive mode switching between Sport, Eco/Cruise and Night HUD.
- DDiS sequential shift-light bar.
- Tap-to-inspect 60-second telemetry history graphs.
- G-force friction circle from tablet sensors.
- 0–100 km/h timer.
- OBD-II ELM327 Bluetooth integration and supported PID discovery.
- Read-only DTC reader with cautious plain-language DDiS/Multijet descriptions.
- Trip computer and fuel-economy/cost log.
- Maintenance/service reminders and owner-entered service history.
- Investigate a genuine Android Auto-like phone-to-tablet receiver experience in the same app, separately from the OBD workstream.
- Preserve the existing approved UI and all working app functionality; do not simplify the design or replace the current app with a disconnected sample.

**Claude: use this appendix together with the main brief.** The main brief defines the authoritative behaviour and safety constraints; this appendix preserves the original implementation sketches and labels the changes required before production use.
