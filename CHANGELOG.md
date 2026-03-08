# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog, and this project adheres to Semantic Versioning.

## [Unreleased]

- Initial project outlines and docs
- CLI-only Android project scaffolding with Gradle wrapper
- BLE + classic Bluetooth scan with local persistence
- Device list, detail, history, and diagnostics screens
- Rename user-facing branding to unagi
- Add `unagi.ninja` landing page and stable APK download path
- Restyle the app and website with a ninja-inspired black and electric-blue theme
- Improve scan preflight checks and recovery messaging for denied permissions, Bluetooth off, and location-services-off devices
- Bump the Android app to `0.1.1` / version code `2` and surface the installed version prominently in the main UI
- Mirror WiGLE's scan-relevant permission set by requesting coarse/fine location alongside Bluetooth scan/connect
- Make the installed build number much more prominent with a dedicated banner on the main screen
- Fix SDK 35 edge-to-edge toolbar overlap with the status bar and keep version text visible in a dedicated banner
- Resolve vendor names locally from bundled IEEE OUI registries and prefer BLE advertised names over generic Bluetooth names when available
- Let the live device filter match normalized MAC and OUI fragments, not just displayed text
- Add configurable device alerts with emoji, sound presets, and detail notifications for OUI, MAC, and Bluetooth-name matches
- Fix startup crash by making vendor-prefix asset loading resilient to APK asset renaming and plain-text packaging
- Resolve BLE manufacturer company IDs and advertised service UUIDs from bundled Bluetooth SIG assigned-number registries so unnamed devices surface richer identity hints
- Rename the Android namespace and application ID from `com.thingalert` to `ninja.unagi` so the shipped package matches the `unagi.ninja` brand
- Make the top controls banner collapsible and add a persisted compact-device-card option from the main menu
- Split device identity keys from passive classification fingerprints, add BLE address-type/vendor-confidence handling, and surface classification evidence in the list/detail UI
- Add opt-in BLE Device Information Service querying from Device Details, store active enrichment results in Room, and include those diagnostics in the copyable debug report
- Add passive vendor decoders for Apple, Google, Microsoft, Samsung, Nordic, and Tile-style payloads so unknown/randomized devices surface softer human-readable hints
- Bump the Android app to `0.2.0` / version code `10` for the device-intelligence release
- Seed default alert rules for Flipper, Axon/TASER, and Ray-Ban Meta-style name matches on first launch
- Bump the Android app to `0.2.1` / version code `11` for the seeded-default-alert release
- Stage website APKs under versioned filenames and update the site download links during each release
- Move scan controls into the main header, switch the title treatment to `UNAGI`, show a live device count, and add optional background-capable continuous scanning via a foreground service
- Bump the Android app to `0.2.2` / version code `12` for the active-scanning release
- Add direct starring from device cards, a starred-only filter, and continuous-sighting dedup so long-lived scans stop inflating the history count
- Bump the Android app to `0.2.3` / version code `13` for the starring and dedup release
- Replace the alerts screen’s large inline add form with a `+` modal editor and add in-place alert editing from the list
- Bump the Android app to `0.2.4` / version code `14` for the alert-editor release
- Keep scan sessions continuous once started instead of auto-completing after the old 20–30 second timeout window
- Bump the Android app to `0.2.5` / version code `15` for the continuous-scanning fix
- Add a dedicated UNAGI status-bar icon for the continuous-scan notification and prompt for battery-optimization exemption when continuous scanning is enabled
- Bump the Android app to `0.2.6` / version code `16` for the background-scan UX pass
- Ask whether continuous scanning should restart on boot, persist that choice, and start the foreground scan service from `BOOT_COMPLETED`
- Bump the Android app to `0.2.7` / version code `17` for the boot-start background scanning release
- Reduce continuous-scan UI jank by throttling database maintenance work and moving expensive device-list presentation off the main thread
- Request notification permission when starting continuous scanning and move the foreground-service notification onto a status-bar-visible channel
- Rename the background-capable passive scan mode to `Continuous scanning` so `active` stays reserved for explicit BLE info queries
- Steady the device list during scans by sorting `Recent` on deduped sighting sessions, ignoring tiny RSSI changes in row diffs, and disabling RecyclerView change animations
- Move the main-screen filters and recovery controls into a right-side drawer so the list can use the full vertical viewport
- Keep `Active BLE queries` separate from `Continuous scanning`; active BLE queries stay off by default and only enable the manual Device Details GATT/DIS action
- Move the filter drawer to the left edge and open it from a dedicated toolbar filter icon instead of a text `Filters` action
- Bump the Android app to `0.2.11` / version code `21` for the active-query toggle and left-drawer filter release
- Capitalize the user-facing app label and debug-report branding as `UNAGI` everywhere in the Android UI
- Bump the Android app to `0.2.12` / version code `22` for the branding-capitalization release
- Fix `First seen` / `Last seen` timestamp flicker on live device details by replacing the shared non-thread-safe formatter with immutable `java.time` formatting
- Bump the Android app to `0.2.13` / version code `23` for the timestamp-stability fix
- Add a `Live only` filter toggle to the drawer, using the same 30-second window as the header’s live-device count
- Bump the Android app to `0.2.14` / version code `24` for the live-only filter release
- Bump the Android app to `0.2.10` / version code `20` for the right-drawer filter layout release
- Bump the Android app to `0.2.9` / version code `19` for the continuous-scanning terminology split and list-stability release
- Move scan start/stop into the main toolbar, keep the live device count in the header, and move the installed version label into the overflow menu
- Bump the Android app to `0.2.8` / version code `18` for the scan-performance and header-control release
