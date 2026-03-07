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
