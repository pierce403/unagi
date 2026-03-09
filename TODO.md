# TODO

## How to use this file

- Keep only active, incomplete, or next-up work here
- Add tasks before starting multi-step work
- Remove tasks as soon as they are done; do not keep checked-off history here
- Record completed work in `CHANGELOG.md`, `README.md`, or `AGENTS.md` when the result should be remembered
- Prefer small, actionable items that map cleanly to the MVP

## Active backlog

### Alerts

### Scan robustness

- [ ] Cross-transport device merge: heuristics to link BLE randomized address + classic public MAC for the same physical device (e.g. same display name seen within a short time window on both transports, matching vendor OUI)

### SDR / TPMS

- [ ] SDR settings UI (frequency band, gain, USB/network source toggle)
- [ ] Unit conversion preferences for TPMS display (kPa/PSI, °C/°F)
- [ ] Cross-compile rtl_433 + librtlsdr for ARM64/ARM via NDK
- [ ] TPMS-specific alert rule type (sensor ID matching)

### Affinity Groups — Advanced Crypto (Phase 4)

- [ ] Per-member ECDH keypairs: generate P-256 keypair on join, store public key in AffinityGroupMemberEntity, encrypt bundle payloads to each recipient's public key
- [ ] Epoch-based key rotation: increment keyEpoch, re-wrap new group key, distribute via signed key-update bundles, reject bundles with stale epochs
- [ ] Member revocation: rotate group key on revoke, mark member as revoked, new bundles use new epoch so revoked members cannot decrypt
- [ ] Incremental export: track last-export timestamp per group, only export data newer than last export to reduce bundle size

### QA
