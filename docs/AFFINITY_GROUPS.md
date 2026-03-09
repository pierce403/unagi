# Affinity Groups

Share device observations with trusted team members using encrypted file bundles. No server, no cloud — data is exchanged directly between devices.

## Quick start

### Create a group

1. Open the overflow menu (three dots) and tap **Groups**
2. Tap the **+** button and choose **Create group**
3. Enter a group name and your display name
4. The group key is automatically copied to your clipboard — send it to your team members via a secure channel (Signal, in person, etc.)

### Join a group

1. Open **Groups** from the overflow menu
2. Tap **+** and choose **Join group**
3. Paste the group key JSON you received from the creator
4. Enter your display name

### Export observations

1. Open **Groups** and tap your group
2. Configure what to share using the toggles (devices, sightings, alert rules, enrichments, starred devices, TPMS readings)
3. Tap **Export bundle** — the encrypted `.unagi` file is saved to your Downloads folder
4. Send the `.unagi` file to group members via any channel (file share, AirDrop, email, etc.)

### Import a bundle

1. Open a received `.unagi` file — UNAGI handles the file type automatically
2. Review the bundle preview (sender name, content types, item counts)
3. Tap **Import** to merge the data into your local database

## What gets shared

Each export includes only the categories you enable. Defaults:

| Category | Default | Description |
|----------|---------|-------------|
| Devices | On | Device records (keys, names, vendor info, classification) |
| Sightings | On | Timestamped observation history |
| Alert rules | Off | Your custom alert rules (OUI/MAC/name matchers) |
| Enrichments | Off | Active BLE query results (Device Information Service) |
| Starred devices | On | Which devices you've starred |
| TPMS readings | On | Tire pressure sensor observations |

## How the merge works

Importing is **additive only** — it never deletes or downgrades your local data:

- **New devices** are added as-is
- **Existing devices** get the earliest `firstSeen`, latest `lastSeen`, and maximum counts (not summed, to prevent inflation from re-exchanging the same data)
- **Sightings** are deduped by device key + timestamp — duplicates are skipped
- **Alert rules** are deduped by match type + pattern — only novel rules are added
- **Enrichments** keep the newer timestamp
- **Stars** are only added, never removed

## Security model

- Each group has a shared **AES-256 group key** that all members hold
- The group key is stored encrypted on your device using the **Android Keystore** hardware-backed key store
- Each export derives a **unique encryption key** via HKDF-SHA256 using the export timestamp, so no two bundles share the same key even within the same group
- Bundle payloads are encrypted with **AES-256-GCM** (authenticated encryption — tampering is detected)
- The `.unagi` file is a ZIP containing a plaintext `manifest.json` (group ID, sender, item counts) and an encrypted `payload.enc`
- The group key is shared as a JSON blob with an HMAC checksum so recipients can detect corruption before storing it
- On Android 13+, the group key is marked as sensitive clipboard content to prevent it from appearing in the notification shade

### What the manifest reveals

The plaintext manifest contains the group ID, sender display name, export timestamp, and item counts. It does **not** contain any device data, observation details, or the encryption key. An attacker with only the `.unagi` file but not the group key can see *who* sent *how much* data but cannot read the actual content.

## Limitations

- **Symmetric key**: all group members hold the same key. If a member is compromised, all bundles (past and future) encrypted with that key are exposed. Per-member encryption and key rotation are planned for a future release.
- **No incremental export**: every export includes all selected data, not just changes since the last export. This means bundles can be large for groups with long scan histories.
- **Manual exchange**: there is no automatic sync. Members must explicitly export, send, and import bundles.
- **No member revocation**: removing a member from the group does not prevent them from decrypting bundles created before removal. Key rotation with epoch-based revocation is planned.

## Re-sharing the group key

If a new member needs to join after group creation:

1. Open **Groups**, tap the group
2. Tap **Share group key** — the key JSON is copied to your clipboard
3. Send it to the new member via a secure channel

Any existing member can share the key, not just the original creator.

## Deleting a group

Deleting a group removes the group key and all member records from your device. You will no longer be able to decrypt bundles for that group. Imported device data (observations, sightings) remains in your local database — it is not removed.
