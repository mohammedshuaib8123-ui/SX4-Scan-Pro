# SX4 Scan Pro

Android starter app for the Indian 2012 Maruti Suzuki SX4 ZDi and a Vgate iCar Pro Bluetooth 3.0 (classic Bluetooth SPP) adapter.

## Current starter features
- Connects to a previously paired classic Bluetooth OBD adapter.
- Initializes an ELM327-compatible adapter.
- Reads common standard OBD-II PIDs when the ECU supports them: RPM, speed, coolant temperature, intake temperature, manifold absolute pressure, MAF, calculated load and throttle position.
- Estimates gauge boost as MAP minus barometric pressure only when both readings are available.
- Displays unsupported/missing values as unavailable rather than inventing readings.
- Includes an explicit note that BCM access is not implemented.

## Important limitations
This is an early development build, not a validated diagnostic tool. PID availability and protocol behavior must be tested on the actual SX4 ECU and adapter. Generic OBD-II does not imply access to Suzuki body-control modules. Oil pressure, oil temperature and radiator fan state are not assumed to be available. Do not use the app to operate the phone while driving.

## Build
A GitHub Actions workflow builds a debug APK on pushes to main and manual workflow dispatches. After a successful run, download the SX4-Scan-Pro-debug artifact from the Actions run.

## Next development work
- Improve ELM327 response parsing, PID support discovery and protocol reporting.
- Add diagnostic trouble codes (read-only first), logging and graphs.
- Investigate Suzuki-specific module discovery safely before claiming BCM support.
- Validate Bluetooth reconnect, lifecycle handling, phone/tablet layouts and actual vehicle readings.
