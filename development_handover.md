# Development Handover — SX4 Scan Pro

For anyone (or any later Claude session) continuing this work.

## Repo and workflow
- Repo: `mohammedshuaib8123-ui/SX4-Scan-Pro`. Source: `app/src/main/java/com/sx4scanpro/`. Tests: `app/src/test/java/com/sx4scanpro/`.
- Stack: Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.12.01, Material3 1.3.1, minSdk 26, target 35.
- Work in small chunks: one stage → commit → push → wait for CI → fix → next stage.
- Commit trailers: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01Tm9sX3cJ1JqaeoQYUvyZV7`.
- The owner edits branches too: always fetch before pushing; never overwrite newer remote commits.
- "Stop hook: unpushed commits" messages can be false; verify with `git ls-remote origin <branch>`.

## How to verify
1. Pure-Kotlin files (no `android`/`androidx` imports) compile and test locally: `/home/claude/kc.sh -cp <kotlin-stdlib,junit-4.13.2,hamcrest-core-1.3 jars from /opt/gradle-8.14.3/lib> -d out <files>`, then run `org.junit.runner.JUnitCore`.
2. Android/Compose files cannot be built in the sandbox (Maven Central/Google Maven blocked). Push to a `claude/**` branch; `.github/workflows/branch-check.yml` runs `gradle testDebugUnitTest assembleDebug`, uploads the debug APK, and publishes the log to the `ci-log` branch (`summary.txt`, `errors.txt`, `build.log`). Poller script: scratchpad `ci.sh`.
3. A build is not "ready" until CI is green.

## Architecture of the OBD path
- `MainActivity.sendCommand()` — the only place that writes to the adapter; `@Synchronized`, drains input, writes `cmd\r`, waits for `>`, logs via `logTraffic` (on-screen `TrafficLog` + file `SessionLog`). Any exception closes the socket.
- `connectAndRead()` — opens SPP socket, `ATZ` + init commands, captures `ATI/ATRV/ATDP`, runs `EcuNegotiation` (protocol fallback 5/4/3/6, `0100/0120/0140`).
- `readDashboard()` — currently a flat round-robin of 9 core PIDs + baro + extras, then `delay(1300)` in the `connect()` loop. This is the cause of the ~4.4 s refresh and is what S2/S3 replace.
- `ElmReply` — classifies adapter replies (NO DATA, UNABLE TO CONNECT, …).
- `SessionLog` — rolling file in `filesDir/logs`, exported through FileProvider (`logs/` in `file_paths.xml`).
- Decoders: `ObdPidResponseDecoder` (strict, no banner stripping), `ObdPidSupport`, `ObdTroubleCodeDecoder` (K-line framing only so far), `PidCatalog` (40 PIDs).
- Gauge Wall (`CockpitFx.kt` and related) is out of scope: do not touch. Its 5 s STALE rule means any wall-visible PID must be polled at least every ~4 s.

## Plan for remaining stages
- **S2 (pure, JVM-tested with a fake port):** `LinkMonitor` (COMMUNICATING / ECU_SILENT / RECOVERING / LINK_LOST from consecutive results), `PidCapabilities` (per-PID: unknown / supported / temporarily unavailable after repeated NO DATA / not advertised; never permanently disabled), `PollScheduler` (earliest-deadline-first: RPM/speed fast, others slower, rarely-used slowest), `LivePoller` (one command at a time, never parallel).
- **S3:** replace `readDashboard()` use in `connect()`; on ECU silence run recovery on the existing socket: probe `0100`, then light re-init (`ATPC`/`ATSP`), then full re-init; keep the socket open (owner rejected automatic Bluetooth reopen). UI: link state in top bar and Health ECU row; "SYSTEM ONLINE" only when COMMUNICATING; per-label stale thresholds; cache baro; rename "EGR duty" → "EGR commanded".
- **S4:** `Mode09Decoder` (K-line `49 PP NN data` vs CAN length line + indexed `N:` lines), `EcuScan` (read-only: VIN, CAL ID, CVN, ECU name, supported PIDs), `EcuProfile` with hand-written JSON, `ProfileDiff`, DTC decoder for both framings.
- **S5:** ECU Information screen (hidden page index 4), "ECU INFO" chip in the empty space of `SxTopBar`, profile persisted in app files, JSON/CSV/PDF export.

## Rules that must not be broken
Read-only ECU access; no invented values; no stale-as-live; no parallel commands; no changes to working Bluetooth behaviour without evidence and tests; don't copy V2 reference code; no merge to `main` without owner approval.

## After each stage
Update `CURRENT_STATUS.md` and `docs/DEVELOPER_REQUIREMENTS.md`, then report modified files, tests, build outcome and outstanding vehicle validations.
