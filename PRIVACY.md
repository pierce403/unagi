# Privacy

unagi stores data locally on the device to power scan history and device detail views.

## What is stored locally

- Device sightings (timestamp, RSSI, and observed metadata)
- Derived summary fields (first seen, last seen, count)

## What is not collected or uploaded

- No cloud sync
- No remote logging by default
- No upload of nearby device identifiers

## Retention and pruning

- Local-only storage with configurable retention rules
- Default policy: retain sightings for 30 days

## Transparency

- Clear in-app indicator when scanning is active
- Clear permission rationale screens
