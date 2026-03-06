# Architecture

## Modules (MVP)

- Scan: Bluetooth/BLE discovery and session management
- Storage: local persistence for devices and sightings
- UI: list, detail, and diagnostics screens

## Data model

### Device

- deviceKey (stable-ish derived key)
- firstSeen
- lastSeen
- labels (optional user or inferred tags)

### Sighting

- deviceKey (foreign key)
- timestamp
- rssi
- observed metadata snapshot (name, address if present, service UUIDs, manufacturer data)

## Device key strategy (rationale)

Device addresses and names can change (rotation, privacy features, device firmware changes). The "deviceKey" should be derived from the most stable observed identifiers available and should be treated as a best-effort grouping key, not a guarantee of identity.

Potential strategy (to refine during implementation):

- Prefer stable identifiers from manufacturer data or service UUIDs when available
- Fall back to address + name + other metadata hash when no stable identifiers exist
- Store raw observations so grouping logic can be revisited later without data loss
