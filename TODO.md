# TODO

## Repo bootstrap

- [x] Add README.md / FEATURES.md / TODO.md / AGENTS.md
- [x] Add PRIVACY.md (local-only policy, what's stored, retention)
- [x] Add ARCHITECTURE.md (high-level modules and data model)
- [x] Add CHANGELOG.md

## Android toolchain setup

- [x] Document non-Android-Studio workflow using Android SDK Command-line Tools (Android Developers)
- [x] Pin Java requirement to JDK 17 (Android Developers)
- [x] Pin AGP and Gradle versions using the official compatibility table (Android Developers)
- [x] Add "doctor" checklist (verify Java, verify sdkmanager, verify adb, verify build-tools/platforms installed)

## Project scaffolding (no Android Studio)

- [x] Create a minimal Android app module that builds from CLI
- [x] Ensure Gradle Wrapper is present and used consistently
- [x] Establish package name, app name "unagi", basic theme

## Core BLE discovery

- [x] Implement scan start/stop behavior
- [x] Capture per-result fields needed for history (timestamp, RSSI, display name if present, observed identifiers)
- [x] Decide on scan session duration defaults and manual stop

## Permissions and runtime flow

- [x] Implement Android 12+ Bluetooth permission requests (BLUETOOTH_SCAN) (Android Developers)
- [x] Decide whether to use neverForLocation (and document beacon filtering caveat) (Android Developers)
- [x] Add clear permission rationale screens and messages

## Data storage

- [x] Define schema:
  - Device table (deviceKey, firstSeen, lastSeen, labels)
  - Sightings table (deviceKey, timestamp, rssi, snapshot of observed metadata)
- [x] Implement pruning and retention rules (keep N days or N sightings per device)

## UI

- [x] Main list screen (scan controls, list, sort and filter)
- [x] Device detail screen (summary + history timeline)
- [x] Diagnostics screen (permissions state, scan state, SDK/API level notes)

## Scan robustness

- [x] Fix false `Scanning` state and only enter active scan after a path starts successfully
- [x] Return structured startup results for BLE and classic scan paths
- [x] Treat `BluetoothLeScanner == null` as a surfaced startup failure
- [x] Honor `BluetoothAdapter.startDiscovery()` boolean result
- [x] Track scan session outcome: startup failure vs zero results vs results
- [x] Add per-path callback counters and startup diagnostics
- [x] Add compatibility scan mode and expose it in diagnostics
- [x] Add focused unit tests for scan state transitions and timeout outcomes
- [x] Add troubleshooting docs for scan startup failures and zero-result sessions
- [x] Add copyable debug report with device inventory and platform snapshot

## Testing

- [x] Unit tests for deviceKey generation and deduping logic
- [x] Instrumentation tests for permission gating and navigation

## CI

- [x] Add GitHub Actions workflow: build, test, lint
- [x] Cache Gradle dependencies

## Release hygiene

- [x] App icon placeholder
- [x] Versioning and CHANGELOG.md updates
- [x] License selection (MIT/Apache-2.0/etc)
