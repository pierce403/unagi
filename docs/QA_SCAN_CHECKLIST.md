# Scan QA Checklist

Manual test plan for verifying scan behavior across Android variants.

## Prerequisites

- [ ] Debug APK installed (`./gradlew installDebug`)
- [ ] At least one BLE device nearby (phone, beacon, wearable)
- [ ] At least one classic Bluetooth device nearby (speaker, headphones, car kit)

## Permission Flow

### Fresh install
- [ ] Launch app: shows "Grant access" button, NOT "Open app settings"
- [ ] Grant all permissions: scan starts automatically
- [ ] Diagnostics shows no missing permissions

### Deny once
- [ ] Fresh install, deny permission prompt
- [ ] App shows "Grant access" button with rationale text
- [ ] Tap "Grant access": system prompt appears again

### Permanently deny (stock Android)
- [ ] Fresh install, deny twice (or check "Don't ask again")
- [ ] App shows "Open app settings" with specific guidance (e.g. "tap Permissions, then enable Nearby devices")
- [ ] Tap "Open app settings": navigates to app info page
- [ ] Grant permission in settings, return: scan starts

### GrapheneOS-specific
- [ ] Repeat above in main profile
- [ ] Repeat in secondary/work profile (if applicable)
- [ ] Diagnostics "GrapheneOS likely" flag is true
- [ ] Note any permission-related behavioral differences

## Scan Modes

### Normal mode
- [ ] Start scan: BLE and Classic both start (check Diagnostics)
- [ ] BLE callbacks incrementing
- [ ] Classic callbacks incrementing
- [ ] Device list populates
- [ ] RSSI values updating
- [ ] Stop scan: callbacks stop

### Compatibility mode
- [ ] Enable in Diagnostics
- [ ] Start scan: only BLE starts (Classic skipped)
- [ ] Diagnostics shows "Classic startup: Skipped in compatibility mode"

## Continuous Scanning

- [ ] Enable continuous scanning
- [ ] Foreground service notification appears
- [ ] Navigate away: scanning continues
- [ ] Return: device list still updating
- [ ] Disable continuous scanning: service stops

## Callback Samples (Diagnostics)

- [ ] Run a scan with nearby BLE and classic devices
- [ ] Open Diagnostics, copy report
- [ ] Report contains "Callback samples" section
- [ ] Samples show actual device addresses, names, RSSI, service/mfg data
- [ ] At most 20 samples are shown

## Permission States (Diagnostics)

- [ ] When permissions are blocked: report shows "Permission denial states:" with per-permission status
- [ ] States correctly distinguish "not yet requested", "denied, can ask again", "permanently denied"

## Dedup / Sighting Behavior

- [ ] Same device seen multiple times within 60s: observationCount increments, sightingsCount does NOT
- [ ] Same device seen after >60s gap: sightingsCount increments
- [ ] Unnamed BLE device: verify NOT creating new entries per observation (check device count stabilizes)

## Edge Cases

- [ ] Bluetooth off during scan: "Bluetooth is off" state shown with "Enable Bluetooth" button
- [ ] Airplane mode on: correct recovery UI shown
- [ ] Location services off (Android 11-): "Location services are off" state shown
- [ ] Revoke permission during active scan: scan stops, correct recovery UI shown
- [ ] Kill app during continuous scan: service restarts (if start-on-boot enabled)
