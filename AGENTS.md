# AGENTS

## Mission

Bluetooth/SDR situational awareness on Android — scan nearby devices, surface identity hints, alert on matches, share observations with trusted teams.

## Hard constraints

- No Android Studio — CLI + VS Code only
- No cloud; no remote logging
- Reproducible setup via `scripts/setup-android-sdk` and `docs/SETUP_ANDROID.md`

## Toolchain

- JDK 17, AGP 8.13.2, Gradle 8.13, targetSdk/compileSdk 35
- Pin AGP/Gradle versions from the official compatibility matrix; never use dynamic versions
- Android SDK at `~/Android/Sdk`; set `ANDROID_HOME` / `ANDROID_SDK_ROOT`
- Emulator AVD: `unagi_test`; source `scripts/dev-env.sh` for PATH and `start-emulator` helper
- Headless AVD can be flaky — fall back to `assembleDebug` + unit tests and verify on-device

## PR discipline

- Small PRs, each linked to a TODO item
- Commit and push after every completed sub-task, even mid-feature
- Track active work in `TODO.md`; remove items when done (not a completion log)
- Update docs with every new requirement
- If a push is blocked, surface the blocker immediately

## Deployment

- Package identity: `ninja.unagi` (old `com.thingalert` installs don't upgrade in place)
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for every release
- Run `scripts/stage-apk` before commit so the versioned APK and site links stay aligned
- GitHub Pages publishes from `main` at repo root to `https://unagi.ninja`
- Keep `index.html`, versioned APK in `downloads/`, and `CNAME` aligned

## Architecture pitfalls

- targetSdk 35: new top-level screens need explicit system-bar inset handling (Android 15 status bar overlap)
- Vendor-prefix asset may be `.txt` or `.txt.gz` in APK; the loader handles both
- `Formatters.formatTimestamp` runs across threads — use immutable `java.time` formatters, never shared mutable `DateFormat`
- Continuous scanning floods Room if maintenance runs on every observation; keep pruning throttled and heavy presentation work off the main thread
- Notification channels: foreground-service uses `ic_unagi_status` (monochrome); don't use low-importance channel or the status-bar icon is suppressed
- Alert notifications use a silent channel with manual audio playback so different sound presets stay distinct

## Device identity model

- Device identifiers are observations, not ground truth — addresses rotate, names change
- `DeviceKey` fallback: address → name → volatile token (stable signals only, no timestamp/rssi)
- BLE OUI confidence is downgraded for randomized/local addresses; classic addresses are treated as public
- Cross-transport merge (BLE randomized + classic public MAC) is not yet implemented
- Passive vendor decoders add soft hints only — no stable product identity claims
- `sightingsCount` = deduped presence sessions; `observationCount` = raw callback volume
- When unnamed, surface manufacturer company IDs and service UUID labels rather than bare "Unknown device"

## Active vs continuous scanning

- **Continuous scanning**: persisted background-capable passive mode via `ContinuousScanService`
- **Active BLE queries**: opt-in per-device GATT reads from Device Details; stops scanning first; results stored separately in `device_enrichments`
- Keep these terms and toggles distinct in UI and code

## SDR / TPMS

- Lives in `ninja.unagi.sdr/`; `SdrController` orchestrates USB and network paths
- TPMS sensors keyed by name (`"n:TPMS Toyota 0x00ABCDEF"` → SHA-256) since they have no MAC
- SDR transport → TPMS_SENSOR classification at HIGH confidence (score 100)
- Dev testing: network bridge mode with `scripts/tpms-simulator.py` or `rtl_433 -f 433.92M -F json:tcp:0.0.0.0:1234`

## Affinity groups

- Encrypted team sharing via `.unagi` file bundles (ZIP: plaintext manifest + AES-256-GCM payload)
- Group keys: AES-256, wrapped by Android Keystore; HKDF-SHA256 derives per-export encryption keys
- DB tables: `affinity_groups`, `affinity_group_members`, `affinity_import_log` (migration 5→6)
- Merge is additive-only (min firstSeen, max lastSeen, max counts)
- Advanced crypto (ECDH, epoch rotation, revocation) deferred to Phase 4

## Recursive learning

- Update this file when discovering new pitfalls, conventions, or collaborator preferences
- Keep notes concrete and actionable; replace stale guidance when superseded
- Before handoff, record any new command, deploy detail, or preference discovered during the task
