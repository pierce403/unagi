# Features

## Feature: Explorer scan

- Start and stop scan (BLE + classic discovery)
- List of detected devices (name if present, approximate proximity via RSSI, last-seen timestamp)
- Sort and filter (strongest signal, recently seen, name contains, "unknown name" bucket)

## Feature: Device details

- Stable "Device Key" strategy (addresses and names may change)
- Raw-ish metadata view (what we observed, not what we claim it is)

## Feature: Device history

- Timeline of sightings
- Stats (first seen, last seen, count, RSSI range and average)

## UX expectations

- Fast scan sessions (battery-aware)
- Clear empty states and permission explanations
- "Unknown device" is normal, not an error

## Planned next (post-MVP)

- Alerts (notify when matched device pattern appears)
- Background scanning with platform-friendly delivery mechanism
- Rule builder (name contains / manufacturer id / service uuid / RSSI threshold)
- Export diagnostics (opt-in, user-controlled)
