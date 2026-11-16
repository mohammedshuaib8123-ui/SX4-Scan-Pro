# Real-car session 2026-11-15 — observations, findings and decisions

Source: `sx4-session-export` (build v0.8.0 BUILD 27, adapter V-LINK, `AUTO,ISO14230-4(KWPFAST)`, engine ECU 0x11). Capture 7,606 records, event log 15,850 records, two stretches: 13:35-13:52 (idle and engine stop/start tests) and 17:58-18:21 (drive to 137 km/h, coolant to 96 °C). Session ended INCOMPLETE because the link was still up at export. No ECU profile (the scan had never been run). The export is not redacted (adapter Bluetooth address present, VIN never read).

## Observations
- All 14 live PIDs decoded to plausible values (idle 570-830 rpm, boost up to 232 kPa MAP, rail 28 MPa at warm idle up to 130 MPa, 12.4-14.5 V).
- Mode 03 and 07: `43 00 00 00 00 00 00` / `47 00 ...` (no codes); distance with MIL on 0 km. Mode 0A: `7F 0A 11` every time. Mode 02 frame 0: zeros; `022C00` -> `7F 02 12`.
- Request time about 270-290 ms; update intervals: RPM 0.8 s, speed 1.25 s, load/MAP 2.5 s, coolant 3.8-5 s, MAF 3.8 s, IAT/EGR/rail/voltage about 17 s, baro 31 s.
- 10 ECU dropouts, all at engine stop/start (0 rpm, 12.7-13 V); each ignored two `0100` probes and was cleared by `ATPC` in about 8-9 s total. None while driving.
- Bluetooth: two socket failures before the first connect (about 85 s); link lost at 13:52:31 with the engine idling; attempts 1-2 failed, attempts 3-4 were logged 4 h later (17:56-17:57), attempt 5 succeeded at 17:58:27. Cause of the gap not proven (tablet suspended or car off; owner to confirm).

## Findings
| # | Finding | Evidence | Files / symbols | Change |
|---|---|---|---|---|
| F1 | Registry never promoted | VERIFIED 0 / DECODED 0 after thousands of replies; only timestamp 13:37:10 | `ParameterRegistry.onPositiveResponse/onDecoded` had no caller; `MainActivity.runPollSlice` | `LiveEvidence.kt`, hook in `runPollSlice`, save gate |
| F2 | Probes useless after engine stop | 10/10 dropouts: 2 probes fail, `ATPC` works | `LinkMonitor.nextRecovery` | light re-init first |
| F3 | Misleading wording | voltage proves ignition on | `ConnectionMachine.failureText` | new text |
| F4 | Quick Bluetooth attempts run out while the car is off | attempts 3-5 after a 4 h gap | `RecoverySupervisor`, `RecoveryPolicy` | 36 slow standby tries, suspend log |
| F5 | Mode 0A asked on every scan | `7F 0A 11` x6 | `MainActivity.readTroubleCodes`, `DtcModePolicy` | remember per connection; clearer empty-scan text |
| F6 | Poll plan asks for about 7 req/s, line gives 3.5; fresh window shorter than real update time | PERF intervals above | `PollPlan`, `AlertLimits` | achievable intervals; per-sensor freshness |
| F7 | No Mode 09 / ECU profile | 5 items UNTESTED | `EcuScan`, `MainActivity.autoEcuScanAtConnect` | once per connection when due |
| F8 | Empty DTC and freeze-frame replies | zeros | `ObdTroubleCodeDecoder` | tested: empty `43 00...` decodes to no codes, not P0000 |
| N1 | Owner request: warn about wrong sensor data / rail pressure; fuel gauge dropping fast | owner report | `DataCheck.kt`, `DataCheckUi.kt` | data-check monitor, fuel log |

Correction to the first analysis: the DTC scan is run by the owner, not on a timer; the six scans in the capture were six button presses.

## Decisions and limits
- Showing dimmed last values during a dropout was not implemented (conflicts with "never show stale readings as live").
- Fuel use cannot be measured by OBD on this ECU (2F fuel level and 5E fuel rate unsupported); the fuel log uses owner-entered fill-ups.
- Data-check numbers: warm idle rail 28-29 MPa, steady air-flow efficiency 0.48-0.88 (median 0.75), steady high-load boost 109-130 kPa came from this capture; generic limits are labelled generic in the app.
