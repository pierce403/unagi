# AGENTS

## Mission

Build the MVP: scan -> list -> tap -> history.

## Hard constraints

- No Android Studio usage (everything must work via CLI + VS Code)
- Reproducible environment setup (script + docs)
- No cloud; no remote logging by default

## Toolchain rules

- Use Android SDK Command-line Tools (sdkmanager, platform-tools, etc.)
- Require JDK 17
- Pin AGP/Gradle versions using the official matrix; avoid dynamic versions

## Definition of Done (MVP)

- On a physical Android device: can scan, see list, tap a device, see a persisted history after relaunch
- Permission handling is robust (clear messaging, recoverable states)
- No crashes when Bluetooth is off or permissions denied

## Implementation guidance

- Treat observed device identifiers as observations, not ground truth identity
- Assume devices may rotate addresses and change names; design for partial stability
- Optimize for battery by time-limiting scans and avoiding constant background behavior (MVP stays foreground)
- Keep passive identity/grouping separate from classification; soft fingerprints can inform category/confidence but should not silently become stable physical identity
- Do not trust OUI/vendor-prefix resolution on randomized/local BLE addresses; prefer manufacturer company ID, advertised services, and other passive fields with explicit confidence levels
- Active BLE enrichment must stay opt-in from the detail page, never happen during scans automatically, and remain local-only

## PR discipline

- Small PRs, each linked to TODO item
- Add or update docs with every new requirement
- Commit and push after every task; if a push is blocked, surface the blocker immediately
- Track active multi-step work in `TODO.md` and update the checklist before or during implementation as scope changes
- Keep `TODO.md` lean: it is an active backlog, not a completion log
- Remove completed items from `TODO.md` instead of leaving checked-off history behind
- For long-running work, commit and push each completed sub-task even when the larger feature is still in progress

## Recursive learning

- Update `AGENTS.md` whenever you learn anything important about the project, workflow, or collaborator preferences
- Capture both wins and misses: what to repeat, what to avoid, and any blocker that slowed delivery
- Keep notes concrete and reusable: build/test commands, deployment steps, project structure, coding conventions, pitfalls, and formatting preferences
- Prefer small, timely updates in the same task that revealed the learning, and replace stale guidance when it is superseded

## Agent memory checklist

- Build/test: `./gradlew assembleDebug`, `./gradlew installDebug`, and `scripts/stage-apk`
- Local validation needs `ANDROID_HOME` / `ANDROID_SDK_ROOT`; on this workstation the SDK is at `~/Android/Sdk`
- Headless AVD boot can be flaky on this workstation; if `thingalert_api35` exits immediately, fall back to `assembleDebug` + unit tests and verify on-device
- Compatibility mode is toggled from Diagnostics and uses BLE-only `SCAN_MODE_BALANCED` with a 30-second timeout
- Diagnostics now has a `Copy scan debug report` action with platform/build info, persisted device inventory, and recent scan events
- `unagi` now matches WiGLE's scan-relevant permission posture by requesting `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` alongside modern Bluetooth scan/connect permissions
- When shipping a user-visible APK change, bump `versionCode` and `versionName` in `app/build.gradle.kts` and keep the installed version obvious in the main UI
- Since `targetSdk` is 35, new top-level screens need explicit system-bar inset handling or the toolbar can overlap the Android 15 status bar
- Device identity is now richer: prefer BLE advertised local names over generic Bluetooth names, and resolve vendor prefixes locally from the bundled IEEE MA-L / MA-M / MA-S registries
- Android package identity is now `ninja.unagi`; changing `applicationId` means old `com.thingalert` installs do not upgrade in place
- MainActivity display prefs now persist in `MainDisplayPreferences`; the top controls banner can collapse and device cards can switch to a compact density from the overflow menu
- Alert rules now live in the dedicated Alerts screen and can match OUI, full MAC, or Bluetooth name with a chosen emoji and sound preset
- Alert notifications are posted on a silent channel and the audible alert is played manually, so different presets stay distinct without depending on per-channel OS sounds
- The vendor-prefix asset may be packaged in the APK as `vendor_prefixes.txt` even when the source file is `vendor_prefixes.txt.gz`, so the loader must handle both names and both plain/gzip content
- Deployment: GitHub Pages publishes from `main` at repo root to `https://unagi.ninja`
- Site artifacts: keep `index.html`, the current versioned APK in `downloads/`, and `CNAME` aligned when shipping landing-page changes
- On every release, run `scripts/stage-apk` so the staged APK filename includes the current version and the website download links are updated before commit/push
- Vendor-prefix data is refreshed with `scripts/update-vendor-prefixes`, which writes `app/src/main/assets/vendor_prefixes.txt.gz`
- Bluetooth SIG assigned-number data is refreshed with `scripts/update-bluetooth-assigned-numbers`, which writes `app/src/main/assets/bluetooth_company_identifiers.txt.gz` and `app/src/main/assets/bluetooth_service_uuids.txt.gz`
- When unnamed BLE devices still lack a human name, prefer surfacing manufacturer-company IDs and advertised service UUID labels rather than leaving the UI at a bare “Unknown device”
- The current `DeviceKey` fallback order is collision-prone for common manufacturer/service payloads; future work should separate identity keys from classification fingerprints before deepening device intelligence
- If active BLE querying is added, stop scanning first, keep it opt-in from `DeviceDetailActivity`, store enrichment separately from passive metadata, and treat DIS/GATT reads as hints rather than truth
- Passive scan metadata now stores address type, vendor confidence/source, classification category/confidence/evidence, and a separate classification fingerprint; keep those fields in `lastMetadataJson` rather than overloading `deviceKey`
- Treat classic Bluetooth discovery addresses as public for vendor-confidence purposes, but keep BLE OUI confidence downgraded unless the address type is explicitly public
- Active BLE enrichment now persists in the `device_enrichments` Room table; keep it separate from passive observation JSON and include it in Diagnostics/export paths
- `ThingAlertApp` now owns the shared `ScanController`, so detail-page BLE queries can explicitly stop scans before opening a GATT connection
- `Active scanning` is now a persisted mode in `ActiveScanPreferences`; when it is enabled, scanning should run via `ActiveScanService` instead of being tied to `MainActivity`
- Background-capable active scanning needs `ACCESS_BACKGROUND_LOCATION` on Android 10/11 plus a `connectedDevice` foreground service notification; keep the permission flow and manifest declarations aligned
- Device history now treats `sightingsCount` as deduped presence sessions, not raw callback volume; use `observationCount` for signal-stat sampling math and diagnostics
- Star state now lives on `DeviceEntity`; keep starred filters wired off persisted state instead of transient UI-only flags
- Passive vendor decoders now live in `PassiveVendorDecoderRegistry`; they should add soft hints (ecosystem, beacon/tracker/dev-board style) without claiming stable product identity
- Default alert rules are seeded once from `DefaultAlertSeeder`; use versioned seed keys so new defaults can ship without duplicating or constantly re-adding deleted user rules
- Reflection: before handoff, record any new command, pitfall, deploy detail, or collaborator preference discovered during the task
