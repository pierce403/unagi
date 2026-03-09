# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog, and this project adheres to Semantic Versioning.

## [Unreleased]

## [0.2.22] — SQLCipher migration hotfix

- Fix startup crash when upgrading an existing plaintext install to the SQLCipher database build by correcting the export-key SQL and falling back to plaintext open if migration still fails

## [0.2.21] — Affinity groups and SDR/TPMS

- Affinity groups for encrypted device-observation sharing between team members
- SDR/TPMS integration via rtl_433 JSON pipeline for tire-pressure sensor observations
- TPMS sensor vendor identification from BLE advertisement payloads
- Scan robustness: stable unnamed-device dedup, structured permission diagnostics, callback samples in debug report
- QA scan checklist (`docs/QA_SCAN_CHECKLIST.md`)

## [0.2.20] — Device export: clipboard

- Add Device Details copy action for putting per-device JSON export on the clipboard

## [0.2.19] — Device export: save and share

- Add Device Details save/share actions for exporting full per-device JSON (excludes sightings list)

## [0.2.14] — Live-only filter

- Add `Live only` filter toggle to the drawer, using the same 30-second window as the header's live-device count

## [0.2.13] — Timestamp stability

- Fix `First seen` / `Last seen` timestamp flicker by replacing shared non-thread-safe formatter with immutable `java.time` formatting

## [0.2.12] — Branding capitalization

- Capitalize user-facing app label and debug-report branding as `UNAGI` everywhere in the Android UI

## [0.2.11] — Active query toggle and left-drawer filter

- Keep `Active BLE queries` separate from `Continuous scanning`; active queries stay off by default
- Move filter drawer to left edge, opened from a dedicated toolbar filter icon

## [0.2.10] — Drawer filter layout

- Move main-screen filters and recovery controls into a drawer so the list uses full vertical viewport

## [0.2.9] — Continuous scanning terminology and list stability

- Rename background passive scan mode to `Continuous scanning` (`active` reserved for BLE info queries)
- Steady device list by sorting on deduped sighting sessions, ignoring tiny RSSI diffs, disabling change animations

## [0.2.8] — Scan performance and header controls

- Move scan start/stop into main toolbar with live device count in header
- Move installed version label into overflow menu
- Request notification permission when starting continuous scanning
- Move foreground-service notification to a status-bar-visible channel
- Throttle database maintenance and move list presentation off the main thread

## [0.2.7] — Boot-start background scanning

- Prompt for boot autostart preference and persist in `StartOnBootPreferences`
- Start foreground scan service from `BOOT_COMPLETED` when both continuous scanning and start-on-boot are enabled

## [0.2.6] — Background scan UX

- Add dedicated UNAGI status-bar icon for continuous-scan notification
- Prompt for battery-optimization exemption when continuous scanning is enabled

## [0.2.5] — Continuous scanning fix

- Keep scan sessions continuous once started instead of auto-completing after 20-30 second timeout

## [0.2.4] — Alert editor

- Replace alerts screen's inline add form with FAB + modal editor
- Add in-place alert editing from the list

## [0.2.3] — Starring and dedup

- Add direct starring from device cards with starred-only filter
- Continuous-sighting dedup so long scans stop inflating history count

## [0.2.2] — Active scanning

- Move scan controls into main header with `UNAGI` title and live device count
- Add optional background-capable continuous scanning via foreground service

## [0.2.1] — Seeded default alerts

- Seed default alert rules for Flipper, Axon/TASER, and Ray-Ban Meta on first launch
- Stage website APKs under versioned filenames

## [0.2.0] — Device intelligence

- Split device identity keys from passive classification fingerprints
- Add BLE address-type and vendor-confidence handling
- Add opt-in BLE Device Information Service querying from Device Details
- Add passive vendor decoders for Apple, Google, Microsoft, Samsung, Nordic, and Tile payloads
- Resolve manufacturer company IDs and service UUIDs from bundled Bluetooth SIG registries

## [0.1.1] — Permission and identity improvements

- Mirror WiGLE's permission posture (coarse/fine location alongside Bluetooth scan/connect)
- Resolve vendor names from bundled IEEE OUI registries; prefer BLE advertised names
- Fix SDK 35 edge-to-edge toolbar overlap
- Add configurable device alerts with emoji and sound presets (OUI/MAC/name matching)
- Fix startup crash from vendor-prefix asset loading
- Rename namespace from `com.thingalert` to `ninja.unagi`

## [0.1.0] — Initial release

- CLI-only Android project scaffolding with Gradle wrapper
- BLE + classic Bluetooth scan with local persistence
- Device list, detail, history, and diagnostics screens
- `unagi.ninja` landing page and stable APK download path
