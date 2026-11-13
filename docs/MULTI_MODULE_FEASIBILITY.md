# Multi-module diagnostics: feasibility (V1, P9)

Status 2026-11-13. This is a record of what is known and what is not. It proposes no commands and contains no addresses.

## What the app does today
- Only the OBD-II Mode 01 / Mode 09 responder (assumed to be the engine ECU) has ever been talked to. Nothing probes any other module; `ReadOnlyGuard` is unchanged and default-deny.
- Module status (`ModuleStatusModel`, P8) is derived only from evidence recorded in the current connection. Older stored evidence is labelled historical and never raises a status. UNAVAILABLE is never produced.
- `ModuleCatalog` (P9) holds documented knowledge separately from observed evidence. It cannot change a status.

## Knowledge per module (from `ModuleCatalog`)
| Module | Fitment on this car | Address / protocol | DTC read | Data list |
|---|---|---|---|---|
| BCM | unverified | unverified | documented in the global workshop manual, not checked for the Indian 2012 ZDi | documented in the same manual, same caveat |
| EPS | unverified (manual lists steering type only as "Power"; hydraulic or electric not confirmed) | unverified | unverified | unverified |
| ABS/ESP | unverified | unverified | unverified | unverified |
| Instrument cluster | unverified | unverified | unverified | unverified |
| SRS / Airbag | unverified | unverified | unverified | unverified |

"Documented" means a workshop-manual statement about the global model. It is not advertised, reachable, tested, decoded or verified for this car.

## Needs vehicle confirmation (Saturday and later)
1. Which of BCM, EPS, ABS/ESP, cluster and SRS are fitted to this car (physical check and the Indian workshop manual or tool coverage list).
2. The protocol, addresses and request formats each module uses. These must come from an owner-supplied capture of a genuine Suzuki tool session or from a verified manual, never from guessing or scanning.
3. Whether the Vgate iCar Pro classic Bluetooth adapter can reach each module at all.
4. Only after 1-3: read-only identification and DTC reading, added behind a sourced allowlist like the existing Mode 09 one, one module at a time.

## Why no further code
The evidence model already separates advertised, reachable, decoded and verified, keeps current and historical evidence apart, and holds one entry per module. A per-module evidence store would have nothing real to hold until module addresses are confirmed, so it is deferred rather than built on assumptions.
