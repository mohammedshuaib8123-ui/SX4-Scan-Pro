# CURRENT STATUS — SX4 Scan Pro V1

Last updated: 2026-11-16. Active branch: `claude/v1-realcar-fixes-and-sanity-monitor` (from `main` 601d97c; **not merged**, waiting for the owner). Sections below "Latest work" are the older history and are kept for the record.

## Latest work (2026-11-16): first real-car capture, fixes, and the data-check monitor
Source: session export of 2026-11-15 (4.7 h, SX4 ZDi, adapter V-LINK, ISO 14230-4 KWP fast, engine ECU address 0x11, 7,606 capture records, 15,850 log records). Findings table with file/symbol mapping: `docs/REAL_CAR_SESSION_2026-11-15.md`.

Fixed on this branch (each has tests in `RealCarFixesTest.kt` / `ConnectionRecoveryTest.kt` / `DataCheckTest.kt`):
- **F1 registry never left ADVERTISED** (VERIFIED 0 / DECODED 0 after thousands of valid replies): nothing in live polling called the registry. New `LiveEvidence.kt` (`LiveRegistryFeed`, `RegistrySaveGate`) records real replies as VERIFIED/DECODED, NO DATA/malformed as inconclusive only, throttled to one evidence entry per 60 s per PID, file written at most every 30 s and at trip end.
- **F2 ECU dropouts at engine stop/start**: 10 of 10 were fixed by `ATPC` and none by two `0100` probes, so `LinkMonitor.nextRecovery()` now starts with the light re-init (about 5 s less frozen time).
- **F3 wording**: "The ECU stopped answering (engine stopped or ignition off)".
- **F4 Bluetooth reconnect**: quick attempts (5, back-off 2-30 s) are followed by a bounded slow standby (36 tries, 30 s apart) so the app reconnects when the car is started later; a sleep that overran by more than 10 s is logged as "device was suspended". `RecoveryPolicy.bluetoothStandbyAttempts/StandbyMs/suspendSlackMs`.
- **F5 Mode 0A**: a `7F 0A 11/12` reply is remembered for the connection (`DtcModePolicy`) and 0A is not asked again; an all-empty scan now says "No fault codes stored or pending".
- **F6 poll rates**: the K-line carries about 3.5 requests/s (about 270-290 ms each) and the old plan asked for about 7/s, so slow PIDs updated every ~17 s while going stale after ~10 s. `PollPlan` now asks for about what the line can carry (RPM 0.8 s, speed 1.2 s, MAP 2 s, MAF/load 2.5 s, rail 3 s priority 2, coolant 8 s, EGR 10 s, IAT 20 s, voltage 15 s, baro 30 s) and alert freshness windows follow (`AlertLimits.COOLANT_MAX_AGE_MS` 20 s, RPM 5 s, boost 8 s; before, coolant updated in 4-5 s against a 4 s window).
- **F7 Mode 09**: read-only identity scan runs once after connecting when no profile is saved or it is older than 24 h (`EcuScanPolicy`, `autoEcuScanAtConnect`); the button still works.
- Not done on purpose: showing last gauge values dimmed during an ECU dropout (conflicts with "never show stale readings as live"); gauges keep showing `--`.

New feature: **Data Check** (Health/Trip tab, top card) and **Fuel Economy Log**.
- `DataCheck.kt` (pure): rules for impossible sensor values (coolant, intake air), charging voltage, rail pressure collapse/over-pressure, idle rail pressure and idle air flow against the car's own learned normal, rail pressure under load per speed/load bin, MAP vs barometric with engine stopped, boost at high load, air-flow efficiency (MAF vs engine speed and MAP, displacement 1.248 L), frozen sensors, thermostat never reaching 70 °C after 10 min driving. Warnings need a hold time, clear after 15 s, show a banner; ALERT level also gives one chime (no siren).
- Learned references: locked after 120 samples (usable from 40), only learned while no warning is active and never from a sample that already disagrees with a ready reference; saved in `filesDir/datacheck/baselines.json`; owner can reset.
- Calibrated on the real drive: replaying all 17,059 s through the monitor raised no false alarm; learned warm idle rail 28.8 MPa, idle air flow 7.4 g/s, steady air-flow efficiency 0.74.
- `FuelLog` (pure) + `DataCheckUi.kt`: the ECU does not support fuel level (2F) or fuel rate (5E), so fuel use cannot be measured by OBD. The owner enters odometer + litres at each full tank; tank-to-tank km/L is compared with the usual (median of earlier tanks, needs 3 tanks) and warns at 15 % worse (alert at 30 %).
- Wiring: `MainActivity.runDataCheck` (once a second while the ECU answers, only fresh readings), `dataCheckBanner`, `saveBaselines`, `addFillUp`.

Verification: 537 pure-JVM tests pass locally (harness: `/home/claude/tools/test.sh`, not in the repo). Android/Compose files (`MainActivity.kt`, `DataCheckUi.kt`, `BluetoothAccessTest`) are compiled only by the `Branch check` workflow on push; CI result: workflow "Branch check" run 43748410951 on 78ac9de: `testDebugUnitTest` and `assembleDebug` succeeded (BUILD SUCCESSFUL), debug APK `SX4-Scan-Pro-debug` attached to that run.

Still to verify on the car/tablet: re-init-first recovery time, the new poll rates (read `PERF` lines), standby reconnect when the car is started later, automatic Mode 09 scan (VIN/calibration IDs not yet seen from this ECU), whether the data-check thresholds stay quiet on a healthy drive and speak up when something is really wrong, and that the data-check card looks right on the tablet.

Known limits of the data check: references learned while the car is faulty will be wrong (reset after repairs); rail pressure is sampled only every ~3 s so short dips can be missed; generic limits (voltage 12.8 V, boost 40 kPa, over-pressure 170 MPa) are general engineering numbers, not SX4 specifications, and the messages say so.

## Product
Personal-use Kotlin/Compose tablet cockpit for a 2012 Maruti Suzuki SX4 ZDi (1.3 DDiS, manual), Vgate iCar Pro (Bluetooth Classic ELM327), Realme Pad 2 (Android 14, landscape). Never commercial, never Play Store.

## Baselines
| Ref | Commit | Meaning |
|---|---|---|
| `main` (current baseline) | 4dbff6a | PR #7 (red/black UI, drive modes, alerts, DTC explainer, freeze frame, maintenance, health report) + PR #8 (SX4 welcome badge, APK upload step) + OBD docs + owner screenshots |
| `claude/obd-recovery-and-ecu-info` | cdf5679 | OBD recovery, ECU Information, session log, Gauge Wall fix (PR #10) |
| `claude/v1-final-integration` | **fb37ae8** | `main` 4dbff6a merged with cdf5679 — the release candidate |
| PR #5 `feature/crystal-glass-cockpit` | — | Alternative cockpit redesign. **Not included**; remains open, decision left to the owner |

## What the release candidate contains
- Everything on `main` at 4dbff6a, unchanged (including the welcome badge).
- Live-data loop: one command at a time on an earliest-deadline schedule; readings carry their own time; older than max(4 s, 3 x interval) shows `--`.
- ECU recovery on the existing Bluetooth socket (probe `0100`, `ATPC`, `ATZ` + init). No automatic socket reopen.
- Rolling in-app session log, shared from Settings ("SHARE FULL SESSION LOG (FILE)").
- ECU Information page (top-bar "ECU INFO" chip): read-only Mode 09 scan, saved profile, comparison with the previous scan, JSON/CSV/PDF export.
- Fault-code decoding for K-line and CAN framing chosen from `ATDP`.
- **Gauge Wall sizing fix** (`CockpitFx.kt`, `DialLayout.kt`): every row now has the same height at any dial count (0.8 x tile width, scrolls); tick labels and ring scale with the dial; each size change is written to the session log as `gauge wall layout: ...`. Cause that was fixed: with 8 or fewer dials the wall used the leftover page height (small rings), with more than 8 it used fixed 250 dp rows (the owner's correct-looking render).

## Build evidence (fb37ae8)
- GitHub Actions workflow "Branch check", run 42179665034: `testDebugUnitTest` and `assembleDebug` succeeded on a clean runner (BUILD SUCCESSFUL).
- 189 unit tests pass locally with the same sources.
- APK artifact `SX4-Scan-Pro-debug` (about 16.9 MB, not expired) is attached to that run.
- Compared with the code behind the owner's working APK (`6a40c6c`): the only differences are the Gauge Wall fix, the welcome badge from `main`, and the CI workflow. No files were removed.

## Not verified yet
- Nothing has been run on the real car or the tablet since the merge. A passing unit test is not proof of vehicle compatibility.
- The Gauge Wall fix has not been seen on the tablet after a fresh install.
- Poll intervals (`PollPlan` in `LiveGauges.kt`) are first estimates to be tuned from the session log.
- `readDashboard()` in `MainActivity.kt` is unused and kept for now.

## Constraints that still apply
- ECU operations are read-only: no DTC clearing, actuator tests, security access, writes, coding, resets.
- No invented ECU identities, VINs, calibration IDs, PIDs, DTCs or telemetry; no mock readings; never show stale readings as live.
- No parallel ELM commands. No change to working Bluetooth behaviour without evidence and regression tests.
- Do not copy V2 reference code. Never touch `Scanner-v2`.
- No push to `main`, merge, force-push or history rewrite without explicit owner approval.

## Next steps
1. Owner approves moving `main` to fb37ae8 (or the documentation-only commit on top of it, see the branch tip).
2. Owner installs the APK, drives, and shares the session log.
3. Tune poll intervals; remove unused `readDashboard()`; decide on PR #5.

## ECU Discovery Engine (branch claude/ecu-discovery-engine, from main f5881b4)
Phases P1-P6 incremental; P7/P8 wait for a real vehicle session log. P1 and P2 done (P2 branch `claude/ecu-p2-parameter-registry`: parameter registry + persistence): read-only guard in the single send path, sourced extended-read allowlist, lossless raw capture store, build marker BUILD 27. Details: `docs/DEVELOPER_REQUIREMENTS.md` section 25. Not merged to main; not yet run on the vehicle.

## Phase map (current)
P1 guard/capture, P2 registry, P3 discovery, P4 decoder + digital speed, P5 connection recovery: merged into `main`. **P6 live-data performance** (priorities, conservative adaptive pacing, bounded metrics, overlap guard): implemented on `claude/v1-p6-live-performance`, 356 pure-JVM tests pass locally (33 in the P6 test file); CI green on 98f8b2e. Includes the review fix `PidCapabilities.clearTemporaryState()` (parked PIDs are released after a confirmed ECU recovery). **Session export is a separate outstanding requirement** (the older map that called P6 "session export" is superseded). Details: `docs/DEVELOPER_REQUIREMENTS.md` section 25.1.

## Still outstanding
- Real-car validation (Saturday): P5 ignition OFF/ON recovery and P6 performance (read `PERF` lines in the session log), plus P3 discovery and P4 speed. Nothing here is claimed verified on hardware.
- Session export (separate requirement), ECU Explorer, PR #5 cockpit decision.
- BCM/EPS feasibility (research only: not verified that the Indian diesel SX4 has these modules or what addresses/protocols they use) and verified multi-module discovery.
- Interval tuning from real `PERF` data; unused `readDashboard()` removal.

## P0 ECU diagnostics pipeline (branch claude/p0-ecu-diagnostics, Claude 2, from main 617c183)
Implemented, pending CI and a real-car run:
- DTC scan keeps a state per service (03 / 07 / 0A): codes reported, valid empty, partial, NO DATA, unsupported, malformed, transport failure. Only a complete scan with every service answering cleanly may say "engine ECU reported no codes", and never for the whole vehicle. Raw replies are kept.
- Local generic code catalogue (SAE J2012 titles in the app's own wording) with possible causes and next checks, shown as possibilities. Unknown codes are still listed by identifier. No severity, no "active" claim.
- Freeze frame reader: PID 02 (causing DTC), chained Mode 02 support list, each supported PID with its own status and raw reply. A timeout on one PID no longer closes the socket or ends the read.
- Full diagnosis report (multi-page PDF, wrapped, nothing cut off): codes with explanations, freeze frame, ECU identity, live readings, findings kept apart from the ECU facts, and a limitations section listing everything not scanned or not read.
- ECU Info: scan result summary says which identity items were not read and why; a profile from an earlier run is flagged as history.
- Cockpit health card shows "Fault scan incomplete" instead of a clean state when a service did not answer.
Tests: 575 pure-JVM tests pass locally (about 80 new). Compose and Android files are not compiled in this sandbox, so CI is the first compile of MainActivity, SxCockpit, SxLower and SxEcuInfo.
Not verified: behaviour against the real ECU and Vgate adapter.
