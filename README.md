# unagi

Android app for quick Bluetooth awareness: scan nearby devices, review the list, tap into local history, and relaunch without losing what you saw.

The landing page is intended for `unagi.ninja`, and the publishable debug APK is staged at `downloads/unagi-debug.apk`.

## Why it exists

Quick situational awareness for nearby "things" (headphones, trackers, consoles, dev boards, etc.).

Foundation for later "alert me when X is nearby."

## MVP scope

- BLE scan and device list
- Device detail screen with history
- Local-only storage (no cloud)

## Non-goals (MVP)

- No connecting or pairing
- No "tracking" UI (no directional finder)
- No background scanning or alerts yet

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
- `scripts/stage-apk` to copy the built debug APK to `downloads/unagi-debug.apk` for the website

## Permissions notes (MVP)

- Bluetooth scanning permission model differs by Android version; plan explicitly for Android 12+ BLUETOOTH_SCAN
- Using `neverForLocation` can filter some BLE beacons; unagi currently does not set this flag to avoid filtering

## Privacy stance

- Store sightings locally
- No uploading nearby device identifiers
- Clear "scanning is active" indicator in-app
