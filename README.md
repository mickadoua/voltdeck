# Voltdeck

**English** · [Français](README.fr.md)

A **read-only** mobile dashboard for the 12/24 V electrical systems of vans, boats and off-grid sites. It brings the Bluetooth readings of lithium batteries with a BMS, MPPT solar controllers, DC-DC chargers and shunts into a single app, whatever their brand.

<p>
  <img src="docs/screenshots/accueil.png" width="200" alt="Overview: solar, battery, consumption">
  <img src="docs/screenshots/stats-jour.png" width="200" alt="Daily statistics: energy per hour">
  <img src="docs/screenshots/stats-semaine.png" width="200" alt="Weekly statistics: energy per day">
  <img src="docs/screenshots/accueil-clair.png" width="200" alt="Overview, light theme">
</p>

<sub>Demo mode screenshots: simulated values, with 60 days of history generated at launch. The interface is in French.</sub>

## Why

A typical installation mixes an Ultimatron battery, a Victron MPPT and shunt, sometimes a DC-DC charger: as many apps, each with its partial view, and none answering the question you actually ask: *how much did I produce, how much did I use, and how long will I last?*

Voltdeck reads all of these devices, combines them into one view of the system, keeps the history on the phone and never sends any command to the devices.

## What the app does

- **GX-style overview**: solar production, battery (state of charge, voltage, current, cells, temperature), calculated DC consumption, estimated runtime.
- **Statistics** by day, week and month: energy produced and consumed per hour or per day, battery charge curve, minimums and maximums, comparison with the previous period, accessible table view.
- **Screen-off collection** on Android (foreground service, notification with a stop button) so the history has no gaps.
- **Shunt position**: on the battery, on a load or on a source. Detected when the device reports it, configurable otherwise, so the consumption calculation stays right.
- **Shareable diagnostics**: state of collection, Bluetooth and each device, without any address or key.
- Full **demo mode**, without Bluetooth or permissions, to try the app before connecting anything.
- Light and dark themes.

## Supported equipment

| Driver | Equipment | Reading | Status |
|---|---|---|---|
| `victron.ir` | SmartSolar MPPT, SmartShunt / BMV, Orion Smart DC-DC, DC energy meter | Passive listening to encrypted advertisements (Instant Readout), no connection | Validated against public reference vectors and the simulator |
| `jbd.gatt` | Batteries with a JBD BMS (Ultimatron and other brands) | GATT connection, read frames only | Validated on the simulator |

Neither driver has been validated on real hardware yet. Drivers are pluggable: supporting a new brand means writing a `DeviceDriver` (`drivers/`).

## Read-only, enforced by four locks

1. **Module boundary**: only `ble/.../KableTransport.kt` calls a BLE write. Drivers (`drivers/`) only get `ReadOnlyGattLink`, which exposes neither `write` nor `writeWithoutResponse`.
2. **Allowlist**: `ReadOnlyGattLink.request()` only accepts an `AllowedCommand` (`ble/.../ReadAllowlist.kt`), a closed set of documented read frames (JBD: `DD A5 03|04|05 00 FF xx 77`). Any other frame (`DD 5A …` write, `FF AA …` authentication) throws `ForbiddenCommandException` before reaching the radio.
3. **CI check**: the build fails if `.write(` or `writeWithoutResponse` appears outside `KableTransport.kt` (`scripts/ci/check-readonly.sh`).
4. **No `INTERNET` permission**: the build fails if it appears in the merged manifest. No data leaves the phone.

## Victron keys

Victron advertisements are encrypted with a per-device key (VictronConnect, Product info, Instant readout via Bluetooth).

- Masked input, checked before anything is saved: check byte first, then trial decryption of several distinct advertisements with a plausibility check of the values, to reject a typo.
- Stored in the Android Keystore (non-exportable AES-GCM) or the iOS Keychain, never in the database or in a log.
- A "not available" field produces no reading, never a 0. A truncated frame is ignored and counted in the diagnostics.

## History and statistics

Local SQLDelight storage: aggregates per minute (30 days), hour, day and month, with idempotent roll-up. Energy is integrated at full resolution (Wh and Ah in and out). Consumption is **calculated** (battery-side production minus battery flow) and labelled as such; a period without readings stays empty, never zero.

## Project layout

| Module | Role |
|---|---|
| `core/` | Shared model: `DeviceId`, `DeviceRole`, `Metric`, `Sample`, `Reading` (current > 0 = the battery is charging) |
| `ble/` | `BleTransport` / `ReadOnlyGattLink` contracts, allowlist, `KableTransport` (Kable) |
| `drivers/` | Device drivers: `victron.ir`, `jbd.gatt` |
| `simulator/` | Deterministic simulated installation (battery, MPPT, SmartShunt) emitting real encrypted Victron advertisements and real JBD frames |
| `storage/` | Aggregates, retention, system statistics (production, consumption, runtime, cycles) |
| `composeApp/` | Compose Multiplatform application (Android, iOS) |
| `iosApp/` | Xcode iOS shell |
| `scripts/ci/` | CI checks (read-only, secret scan, self-test of the checks) |

## Install

The signed Android APK is attached to each [release](https://github.com/mickadoua/voltdeck/releases/latest): download `voltdeck.apk`, open it and allow installs from that source when Android asks. The app is not on the Play Store.

## Build

Requirements: JDK 21, Android SDK (platform 36). For iOS: a Mac with Xcode.

```sh
./gradlew check                       # unit tests, lint, manifest check
./gradlew :composeApp:assembleDebug   # APK: composeApp/build/outputs/apk/debug/composeApp-debug.apk
scripts/ci/selftest.sh                # checks that the CI guards fail when they should
```

iOS compiles but has not been run on a device yet.

## Demo mode

With no device registered, the app opens in demo mode: a simulated 200 Ah LiFePO4 battery, MPPT and SmartShunt, whose encrypted Victron advertisements go through the real driver with a public test key (TEST ONLY). Simulated time runs 60 times faster (one day in 24 minutes) and the database starts with 60 days of history with varying sunshine. The `wrong_victron_key` scenario shows a wrong key being rejected. The simulator is deterministic: same seed, same values.

## License

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Victron, Ultimatron and Jiabaida are trademarks of their respective owners; Voltdeck is not affiliated with any of them.
