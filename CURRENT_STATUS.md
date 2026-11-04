# CURRENT STATUS — SX4 Scan Pro

Last updated: 2026-11-05 · Active branch: `claude/obd-recovery-and-ecu-info` · Not merged to `main`.

## Product
Personal-use Kotlin/Compose tablet cockpit for a 2012 Maruti Suzuki SX4 ZDi (1.3 DDiS, manual), Vgate iCar Pro (Bluetooth Classic ELM327), Realme Pad 2 (Android 14, landscape). Never commercial, never Play Store.

## Branches
| Branch | State |
|---|---|
| `main` | ab80cf3 (PR #6 merged) |
| `claude/driving-features` | 5c6546d · PR #7 open · CI green · red/black UI port, drive modes, alerts, DTC explainer, freeze frame, maintenance, health report |
| `claude/obd-recovery-and-ecu-info` | **active** · S1 pushed (b965f24) · CI green |

## Task in progress
Implementing `docs/OBD_TEST_RESULTS_AND_REQUIRED_CHANGES.md` (Section 12 order). Owner-approved plan, with these decisions:
1. Plan approved.
2. "ECU INFO" entry goes in the empty space of the Cockpit top bar.
3. No automatic Bluetooth socket reopen. When ignition comes on, readings must resume on the existing connection without Disconnect/Connect (ECU-level recovery).
4. Rolling log stored inside the app and shareable as a file, so the owner can send it for diagnosis.

## Stages
| Stage | Scope | Status |
|---|---|---|
| S1 | `ElmReply`, `SessionLog` (3 × ~700 KB rolling file), BT ACL events, `ATI/ATRV/ATDP` capture, "Share full session log" button, `logs/` FileProvider path, APK artifact in CI | **Done, CI green** |
| S2 | `LinkMonitor`, `PidCapabilities`, `PollScheduler`, `LivePoller`, fake-port tests | **Done, CI green (ac44f34)** |
| S3 | Live polling wired into `connect()` (`LiveGauges.kt` plan + assembler, timeout resync, ECU recovery on the existing socket, link state in cockpit/health, "EGR commanded" rename, cached baro) | **Code written, CI pending** |
| S4 | `Mode09Decoder`, `EcuScan`, `EcuProfile` (+JSON), `ProfileDiff`, DTC decoder for K-line and CAN framing | Pending |
| S5 | ECU Information screen, top-bar chip, profile storage, JSON/CSV/PDF export | Pending |

## Verification
- JVM tests: all pure-Kotlin suites pass locally via `/home/claude/kc.sh` (139+ tests, plus new `ElmReplyTest`/`SessionLogTest`).
- Real compile + tests: GitHub Actions `branch-check.yml` on every push to `claude/**`; log on `ci-log` branch.
- **Nothing is verified on the real car or tablet yet.** A passing unit test is not proof of vehicle compatibility.

## Hard constraints
- Gauge Wall: do not inspect or modify.
- ECU operations are read-only: no DTC clearing, actuator tests, security access, writes, coding, resets.
- No invented ECU identities, VINs, calibration IDs, PIDs, DTCs or telemetry; no silent mock values; never show stale readings as live.
- No parallel ELM commands. Don't change working Bluetooth behaviour without evidence and regression tests.
- Don't copy V2 reference code. Never touch `Scanner-v2`. No push to `main`/merge without explicit approval. No force-push or history rewrite.

## Outstanding vehicle validations (owner to run)
The 18 acceptance tests from the OBD document, after S3 is built: install the debug APK from the CI artifact, drive, then use Settings → "Share full session log" and send the file.

## Known evidence gaps
Cause of the earlier `socket closed`; the real protocol (`ATDP` was never logged before S1); exact command count per poll cycle. The S1 session log will answer these on the next drive.

## S3 notes (for the next vehicle test)
- Poll intervals (`PollPlan` in `LiveGauges.kt`): RPM 600 ms, speed/MAP 1 s, MAF 1.5 s, load/throttle 2 s, coolant/IAT/EGR/extras 3.5 s, baro 10 s. These are starting values: the old loop managed about 200 ms per command, so this plan may be slightly over capacity. Tune from the session log timings (`RX` lines).
- A reading older than max(4 s, 3 x interval) is shown as `--`, never as live.
- Recovery on silence: probe `0100`, then `ATPC`, then `ATZ` + init. The Bluetooth socket is not reopened. A real socket failure still ends the session as before.
- Unchanged: `sendCommand` default behaviour, `EcuNegotiation`, DTC reading, Gauge Wall.
- `readDashboard()` is now unused (kept for one stage, to be removed once the car test confirms the new loop).
