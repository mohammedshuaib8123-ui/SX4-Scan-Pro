# SX4 Scan Pro — Developer Requirements & Project Decision Log

> **Purpose:** This is the canonical, AI-readable handover for developers and coding agents working on SX4 Scan Pro. Read it before changing code. Keep it current with every release and meaningful implementation change.
>
> **Last updated:** 2026-11-01  
> **Repository:** `mohammedshuaib8123-ui/SX4-Scan-Pro`  
> **Primary branch:** `main`  
> **Current UI label in source:** `v0.3.5 • UI PREVIEW • BUILD 16`  
> **Document status:** Living specification; requirements below distinguish confirmed requests, decisions, implemented foundations, and work still pending.

---

## 1. Product vision

Build a polished, tablet-first Android car head unit and OBD-II companion for the owner's **Indian-market 2012 Maruti Suzuki SX4 ZDi (1.3 DDiS diesel, manual)**.

The tablet is intended to be fitted flush into the SX4 dashboard, replacing/serving as the in-dash head unit and reusing the car's existing speakers where practical. The app should feel like a purpose-built automotive instrument cluster, not a generic phone app or a basic OBD reader.

The product has two related roles:

1. **SX4 Scan Pro / vehicle cockpit:** live engine information, diagnostics, alerts and a high-quality driving display.
2. **Head Unit:** a separate area for Android Auto receiver functionality, subject to technical and licensing feasibility.

The long-term goal is a stable, attractive, usable in-car system. Do not label unimplemented or unverified functionality as complete.

## 2. Target vehicle, hardware and environment

### Vehicle
- Indian-market 2012 Maruti Suzuki SX4 ZDi, 1.3 DDiS diesel.
- Manual transmission.
- Vehicle ECU/PID support must be verified on this actual vehicle; do not assume every generic OBD-II PID or Suzuki-specific module is available.

### Tablet
- Realme Pad 2, model RMP2204.
- Android 14.
- 11.5-inch display, 2000 × 1200 resolution.
- Design **landscape-first** and tablet-first.
- The UI must remain readable at the tablet's native resolution and must not clip, overlap, or truncate important information.

### OBD adapter
- Vgate iCar Pro Bluetooth 3.0.
- Classic Bluetooth / RFCOMM Serial Port Profile (SPP), not BLE.
- Adapter is already paired and known to work with the vehicle and other OBD apps.
- Use the standard SPP UUID where applicable: `00001101-0000-1000-8000-00805F9B34FB`.

### Development/build environment
- Native Android app, Kotlin and Jetpack Compose.
- GitHub repository and GitHub Actions are the current source/build workflow.
- Build debug APKs in CI and publish them as the `SX4-Scan-Pro-debug` workflow artifact.
- Java/JVM target must remain 17-aligned for Gradle builds.
- The previous JVM target mismatch was resolved by aligning Java and Kotlin targets to 17. Do not regress this.

## 3. Product and UX requirements

### 3.1 Overall visual direction — cockpit / gaming instrument cluster
The user explicitly wants a much more immersive and premium cockpit appearance. The early generic Material 3 cards and simple green semicircle gauges are not the desired final design.

Required direction:
- Full-screen or immersive dark cockpit presentation.
- Near-black / charcoal base.
- Cyan or blue instrument illumination.
- Amber for caution states.
- Red for critical states.
- Strong visual hierarchy, precise spacing and high-contrast typography.
- Large, immediately readable primary instruments.
- Professional OEM+ finish: clean, purposeful and not cluttered.
- Do not use random decorative effects that reduce readability.

### 3.2 Primary cockpit information
The driving/cockpit screen should prioritize:
- Engine RPM.
- Vehicle speed.
- Turbo/boost pressure, only when calculated from valid supported data.
- Coolant temperature.
- MAF airflow.
- Engine load.
- Other valid supported live parameters where they add value.

Secondary information can include:
- Intake air temperature.
- Manifold absolute pressure.
- Throttle position.
- Adapter/ECU connection state and data freshness.

Requirements:
- RPM and speed should be visually dominant (large instruments / prominent dial or equivalent cockpit treatment).
- Supporting readings should be grouped into clear information panels.
- Show units.
- Show unavailable or stale data honestly (for example, `—`, `Unavailable`, or `Stale`); never invent, interpolate or silently display a fabricated zero.
- Distinguish disconnected, connecting, connected-but-no-data, and live-data states.
- Ensure the display works in landscape on the Realme Pad 2 without overlap.

### 3.3 Navigation and screen structure
The intended top-level navigation is:
1. **Cockpit**
2. **Diagnostics**
3. **Head Unit**
4. **Settings**

The current source still has Dashboard / Fault codes / Modules tabs. This is an early structure and should be migrated to the intended navigation as the UI is redesigned.

### 3.4 Version/build label during development
- Show a visible app version and development/build marker on the UI in preview builds, so screenshots and APKs can be identified.
- Current source label: `v0.2.0 • UI PREVIEW • BUILD 10`.
- Keep a clear preview/development marker until the user explicitly approves the final build.
- Update version/build identifiers consistently with release/build policy; do not leave misleading stale labels.
- The UI label change is committed in source, but an APK containing it requires a subsequent successful Actions build.

### 3.5 Welcome / ignition-start announcement
On engine start, the app should play the custom welcome announcement:

**“Welcome to SX4 Airlines 2874”**

User-selected presentation:
- English.
- Calm female cockpit-style voice.
- Include a short airplane seat-belt warning-style chime/sound.
- Include the spoken announcement, warning sound and a dashboard visual flash/animation.

Implementation rules:
- This is an original custom sound/sequence; do not copy or extract a copyrighted airline recording.
- Female voice should use Android Text-to-Speech voice selection where available. Provide a sensible fallback if the device does not expose a suitable female English voice.
- Detect engine start from valid engine RPM becoming greater than zero, not merely from Bluetooth connection.
- Debounce start detection to avoid repeated announcements from noisy readings.
- Play once per engine-start event.
- Re-arm only after RPM has returned to zero / engine-off state for a suitable sustained interval.
- Do not announce on app launch, adapter connect, or reconnect if the engine-start event was not newly detected.
- Suppress the event when RPM is unavailable, invalid or stale.
- Audio focus and volume behavior must be handled responsibly.

### 3.6 Vehicle alerts and warnings
The user wants:
- Spoken warning announcements.
- Warning/chime sounds.
- A visible dashboard flash/visual warning state.

Safety and correctness:
- Only generate alerts from valid, supported, fresh ECU data.
- No alerts based on missing, stale, malformed or guessed values.
- Use sensible debounce, hysteresis and cooldown to prevent repeated warning spam.
- Separate caution (amber) and critical (red) severity.
- Do not invent thresholds. Any threshold must be justified by a verified vehicle specification or explicitly documented configurable choice.
- Do not imply the app replaces factory warning lamps, ECU protection, the instrument cluster or driver attention.
- Warnings must supplement, not replace, factory warnings.
- Avoid distracting animations or long spoken messages while driving; prioritize concise alerts.
- Keep the user informed when a warning feature is unavailable because the ECU does not expose the required data.

## 4. OBD-II and diagnostics requirements

### 4.1 Connection and adapter handling
- Connect to the already paired Vgate iCar Pro Bluetooth Classic adapter.
- Request and handle Android Bluetooth permissions correctly, including Android 12+ Nearby devices / Bluetooth Connect permission.
- Provide clear pairing, permission, adapter-off, connection-failed and disconnected guidance.
- Prefer paired devices matching Vgate / iCar / OBD names, but do not silently connect to an unrelated Bluetooth device if a better selection UI is needed.
- Provide explicit connect, reconnect and disconnect controls.
- Handle socket failures, timeouts, app backgrounding, activity destruction and reconnects without leaking sockets or leaving stale live values on screen.
- Do not block the UI thread with Bluetooth I/O.
- Serialize ELM327 commands; avoid interleaved request/response traffic.
- If a command times out or the stream becomes ambiguous, invalidate and close the socket rather than sending another command that could consume a late reply. First ECU PID discovery uses an extended timeout. This is a defensive foundation, not a substitute for real K-line/CAN timing validation.

### 4.2 ELM327 initialization and protocol
Current initialization sequence in source:
- `ATZ`
- `ATE0`
- `ATL0`
- `ATS0`
- `ATH0`
- `ATSP0`

This is a starter sequence, not a guarantee of protocol correctness. Improve it with robust response validation, adapter identification and protocol reporting. Handle `NO DATA`, `? `, `UNABLE TO CONNECT`, malformed frames, timeouts and partial responses explicitly.

### 4.3 Supported standard live PIDs
The current code requests these standard OBD-II PIDs:
- `010C` — engine RPM.
- `010D` — vehicle speed.
- `0105` — engine coolant temperature.
- `010F` — intake air temperature.
- `010B` — intake manifold absolute pressure.
- `0110` — mass air flow.
- `0104` — calculated engine load.
- `0111` — throttle position.
- `0133` — barometric pressure, currently used for a boost estimate if returned.

PID availability must be discovered from ECU-supported PID bitmaps (and subsequent bitmap ranges where applicable) before polling unsupported PIDs repeatedly. The current source does not yet implement full supported-PID discovery.

### 4.4 Boost calculation
- Display boost only when both manifold absolute pressure (MAP) and barometric pressure are valid and fresh.
- Basic gauge boost estimate: `MAP - BARO`.
- Clamp negative gauge boost to zero for a conventional boost gauge, but retain raw values if useful for diagnostics.
- Confirm units and conversion before display.
- Never label MAP itself as turbo boost.
- Do not display a boost number if BARO is missing or invalid.
- Diesel ECU behavior and actual SX4 readings must be validated on the vehicle.

### 4.5 Parsing and data quality
The current parser is a basic implementation and needs hardening.
- Parse complete ELM response frames, not arbitrary substrings.
- Validate service/mode and PID response headers.
- Validate byte counts before conversion.
- Handle headers on/off, spaces on/off, line feeds on/off, prompt termination, echoed commands, multi-frame responses and adapter-specific formatting.
- Avoid treating malformed/empty response bytes as valid zero values.
- Keep raw response logging available for development diagnostics, with privacy-conscious controls.
- Use correct PID formulas and units.
- Add tests for valid, partial, malformed, negative and no-data responses.
- Poll at a stable rate that does not overload the adapter/ECU; current loop is approximately 1.1 seconds for a full dashboard cycle and should be optimized only after measuring reliability.

### 4.6 Diagnostic trouble codes (DTC)
Current source attempts read-only scans of:
- Mode 03 — stored codes.
- Mode 07 — pending codes.
- Mode 0A — permanent codes.

Requirements:
- Keep code reading read-only initially.
- Correctly decode DTC bytes and associate the code with the ECU/module actually queried.
- Show scan progress, timestamp, result and clear no-data/unsupported messages.
- An empty result must not be presented as proof that the entire vehicle is fault-free.
- Do not add code clearing, resets, actuator tests or write commands without a separate explicit requirement, risk review and confirmation.
- Current implementation only queries the engine ECU through generic OBD-II; do not imply full-vehicle coverage.

### 4.7 Suzuki-specific modules / BCM
The user wants to investigate additional Suzuki-specific module access, especially BCM.
- BCM is not currently implemented.
- Generic ELM327 OBD-II access does not guarantee BCM access.
- ABS, airbags, immobilizer, BCM and other body/chassis modules must be marked unsupported until communication is verified.
- Investigate protocol/addressing and adapter hardware capability safely and read-only first.
- Do not send undocumented write/configuration commands.
- Do not claim module support based only on a menu item or a successful generic OBD connection.

### 4.8 Other desired/considered readings
Potential additional data includes:
- Oil pressure.
- Oil temperature.
- Radiator cooling fan status.
- Other Suzuki-specific engine/body information.

These are **conditional**, not guaranteed requirements: add only if a verified ECU PID/sensor/data definition exists and the SX4 actually supplies valid data. Clearly mark unsupported items rather than fabricating readings.

## 5. Android Auto / Head Unit page

The user wants Android Auto receiver functionality available as a separate page/area within the same app, alongside SX4 Scan Pro features.

Important technical decision already discussed:
- A normal Android tablet app cannot simply declare itself a native Android Auto receiver. In the usual projection model, Android Auto runs on the phone and projects to compatible receiver/head-unit software.
- Open-source **Open Headunit** (project by andreknieriem) was identified as a possible foundation for tablet receiver functionality.
- Wired USB projection was considered the more practical first validation target; wireless behavior is more complex and may be affected by Android Auto version changes.
- Open Headunit is AGPL-3.0. If its code is incorporated or modified and distributed, comply with the applicable AGPL source-disclosure and license obligations. Non-commercial use does not automatically remove distribution obligations.
- Do not copy proprietary Android Auto code or reverse engineer beyond applicable legal/licensing permissions.
- Before integration, verify Realme Pad 2 USB OTG/host capability, phone compatibility, audio routing to existing car speakers, display sizing, touch input and lifecycle.
- Keep the Head Unit area separate from OBD cockpit/diagnostics so one feature cannot break the other.
- No Android Auto receiver integration is currently implemented.

## 6. Audio and in-car integration

- The tablet is intended to act as the car's head unit and use the existing SX4 speakers where practical.
- Evaluate audio routing and output latency/quality on the actual tablet and vehicle installation.
- Ensure the welcome announcement and warning sounds can be heard clearly without unexpectedly changing system volume.
- Avoid interfering with other audio sources.
- Consider audio focus, interruption/resume behavior and startup timing.
- Do not assume the tablet's built-in speaker is the final audio output.

## 7. Safety, reliability and UX constraints

- The app is an informational companion, not a certified automotive diagnostic or safety system.
- Do not instruct or encourage the driver to operate the tablet while driving. Configuration, diagnostics and interaction should be done while parked or by a passenger.
- Never suppress, replace or contradict factory warning indicators.
- Never invent unsupported values, DTCs, module availability or warning thresholds.
- Display data age/freshness or otherwise clearly invalidate stale values after disconnect or polling failure.
- Recover cleanly from Bluetooth disconnects, adapter power loss, app backgrounding and tablet sleep/wake.
- Prefer predictable, stable behavior over flashy features.
- Avoid over-investigation and speculative diagnosis; only report evidence-supported findings.
- Do not modify unrelated repositories. The old `Scanner-v2` repository must remain untouched; all work belongs in `SX4-Scan-Pro`.

## 8. Current repository implementation snapshot

This section records what was observed in source on 2026-11-01. It is not a claim that these features have passed real-vehicle validation.

### Implemented foundation (source present)
- Kotlin Android application using Jetpack Compose / Material 3.
- Package/namespace: `com.sx4scanpro`.
- Bluetooth Classic SPP socket connection to a paired adapter.
- Basic Android 12+ Bluetooth Connect permission request.
- Basic ELM initialization commands.
- Basic polling and parsing for the listed standard live PIDs.
- Basic MAP minus BARO boost estimate when BARO is returned.
- Basic DTC scan requests for Modes 03, 07 and 0A.
- Explicit UI notes that BCM and other modules are unsupported.
- Basic connect/reconnect/disconnect controls.
- GitHub Actions debug APK build and artifact upload.
- Four-section Compose UI (Cockpit / Diagnostics / Head Unit / Settings) with first-pass landscape cockpit gauges.\n- Visible source label: `v0.3.4 • UI PREVIEW • BUILD 15`.

### Present but requires validation/hardening
- Bluetooth selection requires exactly one paired device whose name contains Vgate, iCar or OBD; zero or multiple matches fail safely. This name match is a guardrail, not cryptographic adapter identification.
- ELM command reader uses a prompt timeout and simple character collection. It requires robust framing and parsing.
- PID parser uses basic byte extraction and formulas; it does not yet perform complete supported-PID discovery.
- DTC decoder is basic and must be tested against real ECU responses and edge cases.
- The cockpit now has a more aggressive instrument treatment: layered dark gradient, cyan/amber accents, brighter dial rings, tick marks, centre hub and needle. This is an improved preview, not final visual sign-off; tablet screenshot review remains required.
- The four-section navigation exists, but its current visual treatment is still a minimal first pass and does not meet the desired gaming-style cockpit finish.
- Current code has not been validated against the actual SX4 in this development session because the vehicle was not available for live testing.

### Not implemented yet
- Final immersive landscape cockpit UI and large primary RPM/speed instruments.
- New four-section navigation.
- Robust PID bitmap discovery and protocol reporting.
- Hardened ELM response parser and automated parser tests.
- Data freshness/staleness model and robust reconnect lifecycle.
- Validated full DTC presentation.
- Suzuki-specific BCM / ABS / airbag / immobilizer communication.
- Verified extra oil/fan data.
- Engine-start RPM detection and “Welcome to SX4 Airlines 2874” voice/chime sequence (implemented in source with offline debounce tests; actual TTS voice/audio and vehicle trigger remain to be checked on-device).
- Caution/critical voice and visual warning engine.
- Android Auto receiver / Open Headunit integration.
- Final audio routing/in-car installation validation.
- Final release branding and removal of preview marker after user approval.

## 9. Build, versioning and release process

### Current CI
Workflow: `.github/workflows/android.yml`
- Trigger: push to `main` or manual workflow dispatch.
- Runner: `ubuntu-latest`.
- Java: Temurin 17.
- Gradle: 8.9.
- Build command: `gradle assembleDebug`.
- Artifact: `SX4-Scan-Pro-debug`.
- Artifact retention: 14 days.

### Release discipline
For every meaningful release:
1. Update implementation and tests.
2. Update this document in the same release/change set.
3. Record the version/build identifier and a concise change log.
4. Run the GitHub Actions build.
5. Confirm the workflow is green and the APK artifact exists before saying an APK is ready.
6. Report the exact run/artifact and any known limitations.
7. Keep the preview marker until the user approves the final build.

Do not state that a source commit means an APK has been built. A source change only appears in the downloadable APK after a successful build from that commit.

### Release history
| Date | Version / label | Change | Verification |
|---|---|---|---|
| 2026-10-31 | Early starter / build 9 artifact | Kotlin/Compose app, basic Bluetooth OBD connection, live standard PID dashboard, basic DTC modes, GitHub Actions APK artifact. | GitHub Actions run #9 succeeded; debug artifact was available. Not a validated vehicle release. |
| 2026-11-01 | `v0.2.0 • UI PREVIEW • BUILD 10` source label | Added visible preview/version/build label to top bar. | GitHub Actions run #14 succeeded for the version-label commit. Run #15 also succeeded for the documentation commit. Neither success proves real-vehicle validation. |

## 10. Screenshot and visual feedback workflow

- The user wants to provide screenshots by uploading them into the repository when direct chat attachment limits are reached.
- Screenshot folder: `docs/screenshots/`.
- Screenshot currently present in the repository: `docs/screenshots/Screenshot_20261101_010815.jpg`.

### User-uploaded application screenshot (not the concept mockups)

![SX4 Scan Pro uploaded application screenshot](screenshots/Screenshot_20261101_010815.jpg)

[Open the original uploaded application screenshot](screenshots/Screenshot_20261101_010815.jpg).

**Important distinction:** this is the user's uploaded screenshot of the existing application. It is not one of the previously presented cockpit / diagnostics / head-unit concept mockups. Do not treat it as the target visual design or as evidence that the cockpit design is implemented.

### Target screen concepts to recreate in the application

The earlier visual concepts are the design target, and must be recreated as actual Compose screens rather than substituted with the uploaded app screenshot.

1. **Cockpit / home screen:** full-bleed near-black graphite background; restrained neon-cyan instrument illumination; large circular RPM dial and speed dial as the visual anchors; subtle illuminated arcs / tick marks / needle treatment; compact telemetry panels for boost, coolant, MAF and engine load; amber caution and red critical states; a clear ECU connection indicator; landscape tablet composition with no clipping.
2. **Diagnostics screen:** same dark visual language; clear engine ECU scan area; stored / pending / permanent DTC groups; scan progress and timestamp; separate module tiles for BCM, ABS, airbag and immobilizer with explicit unsupported / not verified states until real communication is proven.
3. **Head Unit screen:** a separate, uncluttered receiver launch / connection surface, with wired Android Auto as the first integration target. It must not imply Android Auto is working until the receiver module is actually integrated and tested.
4. **Settings screen:** adapter selection and connection controls, sound / welcome preferences, display and brightness options, diagnostic data freshness / protocol information, and build/version identification.

**Visual review rule:** when the earlier generated concept images are available in chat history or re-uploaded, attach those exact concept references here as separate files. Do not rename the user's existing screenshot or present a newly invented generic image as an original prior mockup.

- The repository confirms that this image file exists (401,677 bytes). In the current review session, the GitHub file reader could list the JPG but could not decode its binary contents. Therefore, no visual assessment of this specific screenshot is claimed yet.
- When a screenshot is visually accessible, assess layout, clipping, hierarchy, typography, colors and tablet landscape fit. Record actionable feedback and resulting changes here.
- Add each new review screenshot to this folder and embed it here with a short date/build caption. Keep older screenshots so visual progress can be compared over time.

## 11. Development priorities / suggested implementation sequence

Prioritize in this order unless the user changes direction:

1. **UI foundation:** redesign landscape tablet cockpit; remove overlap/clipping; large RPM/speed instruments; clear live-data panels; intended navigation; visible preview/build label.
2. **OBD reliability:** supported PID discovery, robust ELM framing/parsing, correct units/formulas, data freshness and safe reconnect.
3. **Diagnostics:** harden read-only DTC scanning and clear module coverage disclosure.
4. **Engine-start experience:** RPM-based start detection, custom chime, calm female English TTS welcome, visual flash and anti-repeat logic.
5. **Warnings:** verified thresholds only, valid/fresh data only, debounce/hysteresis/cooldowns, caution/critical presentation.
6. **Head Unit:** evaluate Open Headunit integration, licensing, OTG and wired Android Auto prototype in an isolated module/page.
7. **Vehicle validation:** test with the actual SX4 and Vgate adapter; document observed ECU support and known limitations.
8. **Release polish:** audio routing, lifecycle, performance, final branding and user approval.

## 12. Acceptance criteria for final product

A feature is not complete merely because a screen or button exists. Mark it complete only when the relevant criteria are met.

### Cockpit UI
- Landscape Realme Pad 2 layout has no clipped, overlapping or unreadable content.
- RPM and speed are dominant and glance-readable.
- Dark cockpit visual language is consistent and polished.
- All live readings show correct units and unavailable/stale states.
- Navigation matches the agreed four sections.

### OBD
- Connects reliably to the paired Vgate iCar Pro Classic adapter.
- Handles permissions, reconnects, disconnects and app lifecycle.
- Supported PIDs are discovered and only valid values are shown.
- PID parsing/formulas are covered by tests and verified against real responses.
- MAP/BARO boost is only shown when both values are valid.
- DTC results accurately state which ECU/modes were scanned.

### Welcome and alerts
- Engine-start announcement triggers only on a new, validated RPM transition.
- Plays the exact requested phrase with calm female English voice where available.
- Original chime and dashboard visual effect play as designed.
- No repeat announcements from reconnects/noisy readings.
- Warnings only use verified thresholds and fresh supported data.

### Head Unit
- Android Auto receiver approach is technically and legally reviewed.
- Wired projection works on the target tablet/phone before wireless is attempted.
- Audio and touch routing work in the installed car setup.
- Open-source license obligations are met.

### Release
- CI build succeeds.
- APK artifact is confirmed and linked.
- Version/build marker matches the delivered build.
- This document is updated in the same release.

## 13. Instructions for future AI coding agents

When continuing this repository:
- Read this document first, then inspect the current source before making assumptions.
- Treat user-confirmed requirements as authoritative.
- Keep the implementation native Android/Kotlin/Compose unless the user approves a change.
- Target Realme Pad 2 landscape use first.
- Preserve the existing SX4/Vgate Classic Bluetooth context.
- Make changes in small, buildable steps; do not claim a feature works without test/build evidence.
- Prefer read-only vehicle communication until explicitly approved otherwise.
- Do not fabricate PIDs, DTCs, warning thresholds, ECU/module support or build status.
- Keep user-facing labels and the release/version marker synchronized.
- Update this file whenever requirements, architecture decisions, implemented behavior, limitations, or release status change.
- Never modify the unrelated `Scanner-v2` repository.

---

## 14. Change log for this document

- **2026-11-01:** Initial consolidated developer/AI handover created from project requirements, decisions and current source inspection. Includes target hardware, cockpit UI, OBD-II, diagnostics, welcome announcement, warning behavior, Android Auto investigation, safety constraints, implementation snapshot, CI/release workflow and acceptance criteria.
- **2026-11-01:** Embedded the uploaded current-UI screenshot in this document and recorded the image-inspection limitation. Updated build history after confirming Actions runs #14 and #15 succeeded.


## 15. Correction and active implementation plan (2026-11-01)

- Corrected the screenshot documentation: the JPG in `docs/screenshots/` is the user's uploaded current-app screenshot, not the previously shown cockpit/head-unit design mockups.
- The prior concept mockups are currently not stored in the repository. Their design requirements are now explicitly described in Section 10 so they are not lost or confused with the uploaded screenshot.
- Recovered user-confirmed requirements: Realme Pad 2 landscape cockpit, dark graphite / cyan / amber / red palette, dominant RPM and speed dials, four sections (Cockpit / Diagnostics / Head Unit / Settings), “Welcome to SX4 Airlines 2874” with calm female English TTS and original seat-belt-style chime, RPM-based engine-start detection, valid-data-only alerts, and separate Android Auto receiver functionality.
- Current source still needs implementation work. Do not mark cockpit, welcome audio, warning engine, or Android Auto receiver as complete merely because their requirements or placeholder screens exist.
- Android Auto receiver remains a separate technical workstream: evaluate AGPL-3.0 Open Headunit, wired USB first, Realme Pad 2 OTG, phone compatibility, touch, audio routing and lifecycle. No proprietary receiver implementation is claimed.
- Next source release must include the real four-section Compose UI and keep existing Bluetooth/OBD behavior intact. Subsequent changes must harden PID discovery / parsing / freshness / DTC handling, then add the RPM-triggered welcome sequence and safe alert framework. All changes require a successful Actions build; vehicle-dependent features remain unverified until tested on the user's SX4.
- Build run #16 (commit `1a037e24`) succeeded for the documentation-only screenshot embed. It does not validate any missing app features.


## 16. Build 11 implementation update — 2026-11-01

### Source changes committed
- Replaced the previous Dashboard / Fault codes / Modules navigation with the agreed four-section navigation: Cockpit / Diagnostics / Head Unit / Settings.
- Added a dark graphite landscape-first cockpit layout with cyan/amber accents, prominent RPM and speed arc instruments, and compact telemetry cards for boost, coolant, MAF and calculated load.
- Kept the preview/build marker visible: `v0.3.0 • UI PREVIEW • BUILD 11`.
- Diagnostics page now presents engine ECU scan scope and clearly states that BCM / ABS / airbag / immobilizer are not scanned.
- Head Unit page now explicitly identifies Android Auto receiver integration as not yet available, with wired Open Headunit evaluation documented as the intended isolated workstream.
- Settings page describes current Bluetooth Classic adapter status and the planned welcome/alert feature; it does not claim those features are active.

### Known remaining work — not completed in Build 11
- The previous OBD Bluetooth and PID/DTC implementation is still a basic foundation. Supported-PID bitmap discovery, rigorous response-frame parsing, automated parser tests, reliable timestamps/data-age handling, adapter selection UI and lifecycle hardening remain outstanding.
- Engine-start welcome voice/chime and warning system are not implemented in Build 11.
- Android Auto receiver is not integrated in Build 11.
- Suzuki-specific BCM / ABS / airbag / immobilizer protocols remain unverified and unsupported.
- Real SX4 + Vgate testing has not been performed for this build.
- The current instrument arcs are a first cockpit implementation pass, not the final animated dial / needle / lighting polish from the earlier concept direction.

### Verification
- Source commit: `3f6ebf2bf41f3083442a6c91b2bdccb61b314732`.
- GitHub Actions run #18 was started for this commit; result must be checked before treating the APK as ready.
- This build's UI changes do not establish vehicle compatibility or Android Auto functionality.


## Build 12 — adapter selection safety (2026-11-01)

- Retained the approved landscape-first tablet cockpit direction: large illuminated RPM/speed gauges, clear engine telemetry, graphite background, cyan/blue instrument lighting, amber caution and red critical states, with the four sections Cockpit / Diagnostics / Head Unit / Settings.
- Increased the visible preview identifier to v0.3.1 / Build 12.
- Removed the unsafe fallback that selected the first arbitrary paired Bluetooth device when no OBD adapter name matched. Connection now requires exactly one paired device identified as Vgate, iCar or OBD; zero or multiple matches produce an explicit error instead of risking connection to an unrelated device.
- This is a connection-selection safeguard only. It does not certify live ECU communication or vehicle compatibility.
- Build 11 source commit: `3f6ebf2bf41f3083442a6c91b2bdccb61b314732`.
- Build 12 source commit: 20371d5f7d0b86f710910d01ddfc888425140aa3.
- Still outstanding: serialise complete polling and DTC transactions; robust ELM response framing and PID support discovery; stale-data handling; verified SX4 alert thresholds; RPM-based welcome/chime; genuine Android Auto receiver integration; tablet visual validation and live SX4 testing.


## 17. Build 13 — OBD transaction serialization (2026-11-01)

- Serialized each complete live PID polling batch and each complete DTC scan batch on the Activity monitor. This prevents a DTC scan from interleaving commands with dashboard polling and consuming the wrong ELM response. The synchronized command method is re-entrant on the same monitor.
- DTC scan now uses one dedicated transaction method for modes 03 / 07 / 0A.
- Numeric formatting for RPM, MAF, load, throttle and boost now explicitly uses Locale.US so decimal separators remain stable regardless of the tablet's regional locale.
- This is a source-level concurrency and formatting hardening change. It does not yet add supported-PID bitmap discovery, a full ELM framing/parser rewrite, timestamps, or real-car validation.
- Build 12 source commit `20371d5f7d0b86f710910d01ddfc888425140aa3`; Build 12 workflow run #20 succeeded. Documentation workflow run #21 also succeeded.
- Build 13 OBD source commit: `afe9ddfae81a01c2ec089cf2760528ee6db81ec2`. The visible build-label synchronization commit is `dc9d53fefc1e692e75fcfcbfa6f5ef95580ea2f6`. Actions results must be checked before treating an APK as ready.
- User has clarified that the current UI feels too minimal and not gaming-like. The next UI pass must be a deliberate visual redesign, not incremental color tweaks: immersive landscape cockpit composition, oversized illuminated instruments, richer layered panels and clear typography, while retaining the four sections and truthful ECU states. The user has approved another redesign pass.

- Build 13 source label was synchronized to `v0.3.2 • UI PREVIEW • BUILD 13`; this does not change the vehicle-validation status.


## 18. Build 14 — cockpit visual pass, ELM validation and welcome sequence (2026-11-01)

### Implemented in source
- Reworked cockpit visual styling with a deeper layered graphite background, stronger typography/letter spacing, instrument-colour hierarchy, outlined telemetry panels, luminous gauge rings, tick marks, centre hub and a needle. This is a richer gaming-inspired preview; it still needs a Realme Pad 2 screenshot review and user approval.
- Hardened ELM command handling: requires the adapter prompt before considering a command complete, applies a bounded response size and timeout, and surfaces adapter command rejection instead of silently parsing an incomplete response.
- PID decoding now checks the expected payload length and hexadecimal characters before applying the standard formulas.
- DTC decoding now requires the correct positive-response prefix and a clean, correctly aligned hexadecimal payload. Malformed/no-data responses are ignored rather than accidentally interpreted as DTCs.
- Added `EngineStartDetector`: two fresh positive RPM samples confirm a start; five fresh zero-RPM samples confirm a stop; missing, stale or negative readings cannot trigger it. This is a transition detector, not a warning threshold.
- Added the “Welcome to SX4 Airlines 2874” TTS announcement with an original two-tone generated chime. It is triggered from fresh RPM polling only, never from Bluetooth connection alone. Android TTS voice/gender availability is device-engine dependent.
- Added JUnit tests for start debounce, stale/missing samples, stop/restart behaviour and invalid RPM.
- Added JUnit test dependency.

### Explicitly not claimed complete
- The warning system is not activated with arbitrary coolant/boost thresholds. It remains disabled until SX4-specific limits are sourced and then validated.
- Supported-PID bitmap discovery and full ISO-TP / ELM protocol support remain outstanding.
- The current OBD parser hardening is not a substitute for testing real Vgate/SX4 responses.
- Android Auto receiver is still not integrated. The Head Unit tab remains an honest placeholder until a compliant receiver implementation is built and tested.
- No ECU connection or live vehicle test has been performed as part of this build.

### Source commits
- Gaming cockpit pass: `d7d71a9722e7110015cce0f8cf0fd31bef863670`.
- ELM/PID/DTC validation: `523ead2cc114847b942cab554e4287ba584dd8b6`.
- RPM welcome/TTS integration: `8743e59b1b9d3b6d00ef8278316ab866c9107efb`.
- Engine start detector: `7414e77882d3de42e0e5ff4e6fb0592313a37852`.
- JUnit support: `1ba0e2223a9f2166e9ae76eb5078385520d8d135`.
- Detector tests: `fa79c19046b4e02a15f4a650e85c573f253f9e1e`.

### Validation status
- GitHub Actions build and unit-test execution must be checked before distributing an APK.
- User's only deferred tasks are intended to be physical Vgate/SX4 ECU connection, on-vehicle validation, and final audio/head-unit routing checks that require the actual tablet/car setup. Software work can continue independently.


### Build 14 follow-up — Android Auto hand-off and CI (2026-11-01)
- Added a Head Unit page action that launches the official Open Headunit app when package `com.andrerinas.headunitrevived` is installed; otherwise it opens the official project page. Added Android package-visibility declaration for that package.
- Open Headunit is deliberately treated as a separate receiver application, not falsely presented as code merged into SX4 Scan Pro. Its upstream README documents wired USB phone-to-tablet projection and current wireless limitations; Android Auto 17.4+ has changed older wireless launch methods. Wired USB remains the initial validation route.
- Upstream project: https://github.com/andreknieriem/open-headunit. The upstream app uses applicationId `com.andrerinas.headunitrevived`, namespace `com.andrerinas.openheadunit`, and the repository identifies AGPL-3.0. Do not bundle or modify its code without preserving licence notices and satisfying source-distribution obligations.
- CI now runs `gradle testDebugUnitTest` before `gradle assembleDebug`, so the offline engine-start detector tests are part of the build gate.
- Visible app label advanced to `v0.3.3 • UI PREVIEW • BUILD 14`.
- This makes the Head Unit page useful for hand-off but does not claim Android Auto is integrated into the SX4 Scan Pro APK. USB/OTG compatibility, touch, video and audio routing remain to be validated on the Realme Pad 2 and phone.
- Additional source commits: parser syntax correction `5907644487eaa14bcc6aa79cb7ece4957d307a7f`; follow-up annotation fix `09978a9102f8c8587b58265006afe50be7060f8a`; CI tests `5bbe530b6b665d785d326113ffbfe7050cd28a5c`; Head Unit launcher `d0cf196b02f475975f51015e97e01ceee07355e5`; manifest package visibility `9b8a966e4a53fe4afb23e65b6bce921e8cedc4fb`; Build 14 version label `6ee563bef7508b9a70d5b314336464dc9e32bfc8`.

- Build 15 adds Mode 01 supported-PID bitmap discovery (0100 and, when PID 20 indicates continuation, 0120). Dashboard requests are now limited to PIDs advertised by the ECU; unsupported readings remain unavailable. The BARO-based boost calculation also requires advertised PID 33 and a valid one-byte response.
- Added unit tests for bitmap decoding, malformed responses and continuation-block gating. The CI workflow runs these unit tests before assembling the debug APK.
- PID discovery is now source-implemented but still requires the deferred real ECU check. It does not imply that the Indian SX4 exposes all PIDs or that calculated boost is validated.
- Build 15 source commit: `4fda4df990c34c37afcb6366f12e475439ae60fa`; PID decoder `0e10805d4aa111a2404b84c13a96485f5bf752a5`; tests `001005ab3b5770ab7e1143b4f8fc7b1de1d040d7`; polling integration `6f24e4de9aa565cfd16a8290963dd0f4d2ab44be`.
- Build 15 follow-up correction: the validated ELM response reader now explicitly returns its response string (`92935825327d3b2dc2b57622a35737183929fc14`). Earlier intermediate Actions attempts failed during Kotlin compilation; the current source commit's workflow is the one to use for final verification.


---

## 13. Verified implementation log — 2026-11-01

### Baseline confirmed before this change
- Source contains `MainActivity.kt`, `EngineStartDetector.kt`, `ObdPidSupport.kt`, and unit tests for engine-start transitions and PID bitmap decoding.
- The latest verified baseline workflow was run #46, which completed unit tests, debug APK assembly and artifact upload successfully.
- Current vehicle ECU behaviour remains unverified because the SX4 is not connected during software development.

### Change in progress — ELM transaction desynchronisation guard
- Commit: `7ce05288f2c6d18d73d76764f9eec51911b871a2`.
- Increased the default command response timeout from 3 seconds to 5 seconds and allowed 12 seconds for initial supported-PID discovery.
- Drains already-buffered input before a new command, within a bounded limit.
- Treats timeout, stream read/write failure, rejected command and oversized response as a broken transaction; closes the socket and propagates an error.
- Dashboard PID reads no longer convert command I/O exceptions into empty strings and continue polling on a potentially desynchronised stream.
- Follow-up commit `abff975fd6f35284505c68b9bdf5f79a03946d43` advances the visible preview label to Build 16, corrects the Settings description of the welcome feature, and clears live gauges/PID state when a DTC transaction loses the adapter connection.
- Pending CI verification: do not call these changes build-verified until the latest Actions run is green and the APK artifact exists.

### Remaining work after this patch
- Extract DTC decoding into a pure tested component; handle supported ELM response forms and reject ECU headers rather than interpreting them as DTC bytes.
- Fix UI state after a DTC scan causes a transport failure.
- Move Bluetooth connection ownership out of the Activity before claiming rotation/background resilience.
- Reconcile the UI label with the next verified build number; keep the preview marker until user approval.
- Test actual protocol timing, PID support and DTC responses on the user's SX4 before declaring vehicle compatibility.


## Build 17 development — welcome audio and warning framework (2026-11-01)

### Welcome experience refinement
- Kept the exact requested announcement: “Welcome to SX4 Airlines 2874”.
- Refined the original generated chime into two distinct short tones before speech, with a short pause.
- Set English (UK) TTS, a slightly slower speaking rate and neutral pitch for a calmer delivery. Android TTS does not expose a dependable cross-device female-voice selector; the installed tablet voice must be checked on-device.
- Added a Settings preview action so the chime and announcement can be heard without starting the engine.
- Added a pending-speech hand-off so a slow TTS engine does not silently lose the welcome after the chime. Speech is sequenced after the chime.
- Automatic welcome remains tied only to the existing two-fresh-positive-RPM start detector. The manual preview is an explicit user action.

### Warning framework foundation
- Added pure Kotlin `WarningEngine` and unit tests.
- Rules support sensor validity/support/freshness checks, consecutive-sample confirmation, separate trigger and clear levels (hysteresis), active-state suppression and cooldown.
- No warning rules or SX4 thresholds are enabled by default. The evaluator is a framework only; no temperature/boost/load alert is presented as vehicle-calibrated.
- Next warning work: map each candidate warning to an authoritative SX4 diesel specification, decide safe display/voice/sound behaviour and test the configured rule with unit tests before enabling it.
- Voice, warning sound and dashboard flash must all be coordinated by one validated alert event; stale/disconnected data must suppress the event.

### Build marker and validation
- Preview marker advanced to `v0.3.6 • UI PREVIEW • BUILD 17`.
- Warning evaluator source: `7e65e0c8a5b796531025d2274ed87e41bbe04e19`; tests: `5a203dee8b4a86d3df1f170affe2df544cc12c39`.
- Welcome refinement source: `8748aae139a8364030f61e9ce068072f133d36d3`; chime/TTS sequencing correction: `ed7b3726f39ffecbd0336d6d2848628cab899474`; Build 17 label: `693422e56bc2379a4793eb93c155a2c730312e93`.
- These changes require the latest GitHub Actions run to pass before an APK is distributed. Audio voice identity, loudness, timing and tablet speaker behaviour still require Realme Pad 2 testing; engine-start detection and all warning rules still require real SX4 validation.


## Build 18 development — warning output preview, DTC decoder and head-unit guidance (2026-11-01)

### Warning experience
- Added a manual Settings preview that drives a visible red warning banner on the Cockpit page, a distinct warning tone and a spoken warning message.
- The preview is explicitly labelled as a test and is not presented as an active vehicle fault.
- Automatic warning rules remain at zero. The WarningEngine remains conservative and is not connected to invented thresholds or unverified SX4 calibration.
- Actual warning activation still requires authoritative engine-specific limits, validation against the SX4 ZDi/1.3 DDiS, and coordinated dispatch of a real WarningEvent to the same visual/audio outputs.

### DTC decoder hardening
- Extracted standard Mode 03/07/0A DTC conversion into pure Kotlin `ObdTroubleCodeDecoder`.
- Added tests for stored, pending and permanent code prefixes, zero padding, no-data/wrong-mode responses, malformed and unaligned payloads, and rejection of a leading ISO-TP/count byte.
- Decoder is deliberately conservative: it does not guess at CAN headers, ISO-TP PCI bytes or multiple ECU frames. The current ELM configuration suppresses headers, so multi-ECU attribution is not claimed.
- Real ELM/SX4 responses still need validation before relying on DTC output.

### Android Auto / Head Unit
- Expanded the Head Unit tab with a practical wired USB validation sequence for the Realme Pad 2: install the separate Open Headunit receiver, USB data/OTG connection, permission prompts, projection/touch validation, separate SX4 stereo audio routing, and reconnect/screen-off/charging checks.
- Reiterates the upstream AGPL-3.0 licence and separate-app architecture. SX4 Scan Pro does not embed or claim to implement the Android Auto receiver protocol.
- No claim of successful Android Auto projection, USB OTG, touch, video, audio or charging validation has been made. Wired USB remains the first target; wireless is deferred.

### Preview/build marker
- Advanced the preview marker to `v0.3.7 • UI PREVIEW • BUILD 18`.
- DTC decoder source commit: `2485d405f803cdc4d284e876fdce3fb2d9950498`; tests: `194042e6f2403d998a6863ba5da4602363ed6f5e`.
- Warning preview + decoder integration: `ca759a9eef88254447cdd21edd70a52ac595fcab`.
- Head Unit setup guidance: `474c285ec840068a17a679904b3833dedbb6b67c`.
- The latest source and test changes must pass GitHub Actions before distributing an APK. Tablet audio and real vehicle testing remain outstanding.


### Build 18 follow-up — coordinated warning event pipeline
- Each gauge now carries a capture timestamp in addition to value/availability.
- The live polling loop maps supported fresh telemetry into the pure WarningEngine input model for coolant, boost, MAF, calculated load and RPM.
- A validated WarningEvent is wired to the same dashboard warning banner, warning tone and severity-appropriate spoken message.
- The warning engine is intentionally constructed with an empty rule list. This completes the event/output plumbing without activating unverified warning limits. No automatic vehicle alert can currently fire until evidence-backed SX4 rules are explicitly configured.
- The manual preview remains visibly labelled as a preview and is separate from the live event pipeline.
- Follow-up source commit: `b947533c2b5f8c7a363ad9281d574f86af2da679`.

- Tablet cockpit follow-up: MainActivity is now explicitly requested in landscape orientation to match the Realme Pad 2's landscape-first 2000×1200 layout. This is a UI orientation request; Android large-screen policy/device behaviour still needs on-tablet confirmation.
- Manifest change commit: `5f6181d091a8b216cc8ee5511ea7117148c11203`.
