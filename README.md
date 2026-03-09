# unagi

Android app for quick Bluetooth awareness: scan nearby devices, review the list, tap into local history, and relaunch without losing what you saw.

The landing page is intended for `unagi.ninja`, and the publishable debug APK is staged as a versioned file in `downloads/` with matching links updated in `index.html`.

## Why it exists

Quick situational awareness for nearby "things" (headphones, trackers, consoles, dev boards, etc.).

Foreground alerts for "let me know when X is nearby."

## MVP scope

- BLE scan and device list
- Device detail screen with history
- Local-only storage (no cloud)

## Non-goals (MVP)

- No connecting or pairing during passive scans
- No "tracking" UI (no directional finder)
- No cloud relay or remote logging

## Tooling constraints

- VS Code workflow only (no Android Studio)
- Build and run via Gradle and Android command-line tools
- Android SDK Command-line Tools are supported for non-Android-Studio setups (see Android Developers docs)

## Prereqs

- JDK 17 (Android Gradle Plugin requires Java 17 to run)
- Android SDK Command-line Tools and Platform Tools (adb)

## How to build/run (high-level)

- Configure ANDROID_HOME (and/or ANDROID_SDK_ROOT) and add platform-tools to PATH
- Install required SDK packages via sdkmanager
- Use Gradle Wrapper and a pinned AGP/Gradle version pairing (avoid dynamic versions like 8.+)

## Toolchain setup (CLI-only)

- Run `scripts/setup-android-sdk` or follow `docs/SETUP_ANDROID.md`
- AGP is pinned to 8.13.2 and Gradle to 8.13 (see compatibility table in Android Developers docs)

## Build and install (CLI)

- `./gradlew assembleDebug`
- `./gradlew installDebug`
- `adb devices` to confirm device connection
- `scripts/stage-apk` to stage the built debug APK under a versioned filename in `downloads/` and update the website download links
- `scripts/update-vendor-prefixes` refreshes the bundled IEEE MA-L / MA-M / MA-S vendor-prefix asset at `app/src/main/assets/vendor_prefixes.txt.gz`
- `scripts/update-bluetooth-assigned-numbers` refreshes the bundled Bluetooth SIG company/service registries at `app/src/main/assets/bluetooth_company_identifiers.txt.gz` and `app/src/main/assets/bluetooth_service_uuids.txt.gz`

## Current features

- **BLE + classic Bluetooth scanning** with continuous background mode, boot-on-start, and battery optimization prompts
- **Device list** with sort, filter, search (name/vendor/MAC/OUI), starred devices, live-only toggle, and compact card option
- **Device details** with sighting history, RSSI stats, metadata JSON, copy/save/share export
- **Alert rules** matching by OUI, full MAC, or Bluetooth name with emoji + sound presets; default rules for Flipper, Axon/TASER, Ray-Ban
- **Active BLE queries** (opt-in per-device GATT reads for Device Information Service)
- **SDR/TPMS integration** via rtl_433 JSON pipeline for tire-pressure sensor observations
- **Affinity groups** for encrypted device-observation sharing between team members via file-based bundles (see [docs/AFFINITY_GROUPS.md](docs/AFFINITY_GROUPS.md))
- **Passive vendor decoders** for Apple, Google/Fast Pair, Microsoft, Samsung, Nordic, and Tile-style payloads
- **Diagnostics** with full debug report including callback samples, permission denial states, and scan session metrics

## Permissions notes

- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- Android 10/11 continuous scanning also requires `ACCESS_BACKGROUND_LOCATION`
- Continuous scanning runs in a `connectedDevice` foreground service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`)
- `neverForLocation` is not set to avoid filtering BLE beacons
- On Android 11 and below, location services must also be enabled for BLE results
- GrapheneOS: grant “Nearby devices” on Android 12+; no sensor-class permissions required

## Troubleshooting scans

- Open Diagnostics to inspect BLE/classic startup results, error codes, callback samples, and permission denial states
- Use `Copy scan debug report` to capture a full snapshot for bug reports
- If scans return zero callbacks, try Compatibility mode and compare BLE callback counters
- Compatibility mode uses balanced BLE scanning and skips classic discovery
- “Bluetooth LE scanner unavailable” usually means Bluetooth is off, restricted, or unavailable in the current profile
- On Android 11 and below, confirm both Location permission and location services are on
- On Android 13+, grant notification permission for continuous scanning and alert notifications
- See `docs/QA_SCAN_CHECKLIST.md` for a structured manual test plan

## Privacy stance

- Store sightings locally
- No uploading nearby device identifiers
- Clear "scanning is active" indicator in-app
