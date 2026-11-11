# CURRENT STATUS — SX4 Scan Pro V1

Last updated: 2026-11-07 · `main` = f5881b4 (V1 integration merged by owner). Active work: branch `claude/ecu-discovery-engine` (ECU Digital Profile & Discovery Engine, phase P1 done).

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
