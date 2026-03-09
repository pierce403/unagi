# Privacy

UNAGI stores data locally on the device. No data leaves the device unless you explicitly export or share it.

## What is stored locally

- Device sightings (timestamp, RSSI, observed metadata)
- Derived summary fields (first seen, last seen, observation and sighting counts)
- Alert rules and their match history
- Active BLE enrichment results (opt-in per-device GATT queries)
- Affinity group keys and imported observation data

## Affinity group sharing

Affinity groups allow encrypted device-observation sharing between team members via `.unagi` file bundles. Sharing is explicit — you choose when to export and with whom to share. Bundle contents are encrypted with AES-256-GCM; group keys are wrapped by Android Keystore at rest. No server or cloud service is involved; bundles are exchanged directly between devices via file sharing.

## What is not collected or uploaded

- No cloud sync
- No remote logging
- No upload of nearby device identifiers
- No automatic data sharing — all exports require manual action

## Retention and pruning

- Local-only storage with configurable retention rules
- Default policy: retain sightings for 30 days

## Transparency

- Clear in-app indicator when scanning is active
- Clear permission rationale screens with specific guidance for denied permissions
- Full diagnostics report available for user inspection
