# Device-heavy UX performance audit

This audit covers the paths exercised while many Bluetooth devices are visible or retained: Android scan callbacks, alert evaluation, observation buffering, Room persistence, main-list presentation/filtering/diffing, foreground-service status, diagnostics, and device sighting history.

## Findings and controls

| Area | Observed bottleneck | Control |
| --- | --- | --- |
| Scan callbacks | Android delivered BLE/classic callbacks to the main thread, where vendor resolution, classification, passive decoding, SHA-256 fingerprints, payload hex conversion, and metadata construction ran synchronously. An unbounded worker queue could trade UI stalls for memory growth and stale latency. | A serial application-scope worker uses a 512-report bounded mailbox. Only byte-identical advertisements from the same session/address coalesce, as whole reports; overload drops and queue high-water marks are counted. |
| Foreground service | Every diagnostics snapshot could rebuild and repost the ongoing notification; callback counters make that effectively per advertisement. | Observe only distinct device-count changes and sample notification refreshes to at most once per second. State/error changes remain immediate. |
| Persistence | Each 750 ms flush performed one device read and one replace per unique device, plus individual sighting inserts. The observable query also paid for a SQL sort that the UI discarded. | Coalesce by key, fetch existing devices in SQLite-safe chunks, bulk upsert devices/sightings, and remove the redundant observer sort. |
| Database access | Per-device sighting history lacked a composite lookup/order index, while the old single-device index became redundant. | Schema 9 retains `sightings(timestamp)`, replaces `sightings(deviceKey)` with `sightings(deviceKey, timestamp)`, and avoids indexing the hot-changing device row. |
| Metadata | The full metadata JSON document was serialized for every callback before same-device callbacks were coalesced. Volatile RSSI/timestamp fields also invalidated otherwise stable presentation work. | Retain the latest raw observation in each buffer, serialize once per device per flush, and use the dedicated device/sighting columns for RSSI and time. |
| Main-list startup | ViewModel construction synchronously parsed the compressed vendor and assigned-number registries. | Lazy-load both registries on the background presentation path. |
| Main-list updates | Filtering and sorting resumed on the main dispatcher; two live-ticker combinations could traverse the same list twice; every row reparsed metadata. | Use one debounced, conditional-clock reducer on `Dispatchers.Default`, cache stable presentation strings by device, and use deterministic sort ties. |
| RecyclerView binding | A meaningful RSSI change triggered a full card bind, including repeated margin/padding/text-size/color/listener work. Hidden search text also participated in content equality. | Use RSSI-only payloads, install listeners once, cache density/shared styling per holder, and compare only rendered/callback-relevant content. |
| Device details | The detail screen loaded every sighting into a `wrap_content` RecyclerView nested in a scroll view and parsed metadata during main-thread binding. | Load only the latest 100 sightings, preformat them on `Dispatchers.Default`, and use the composite history index. |
| Diagnostics | The full report and large TextView could be rebuilt for every callback. | Sample to one render per second, cancel stale renders, and build the report off-main. |
| Hot-path encoding | Payload, device-key, and classification fingerprint hex conversion used formatter allocation per byte; stable hashes were recalculated repeatedly. | Use lookup-table hex encoding and bounded in-memory hash caches. |

## Correctness constraints

- Meta smart-glasses detection still evaluates company ID `0x01AB` and advertised service `0xFD5F` from the same queued BLE report. Reports are not merged before alert evaluation.
- Scan callbacks and receivers capture an immutable session. Stop/failure controls drain accepted reports before persistence flush, and late callbacks cannot contaminate the next session.
- Observation aggregation retains count, RSSI min/max/sum, chronological latest RSSI/metadata, and earliest/latest timestamps.
- Search still covers names, notes, addresses (formatted and normalized), vendors, classifications, and assigned-number labels.
- The full sightings table remains stored for retention/export/group behavior; only the detail-screen window is bounded.

## Verification target

For device-heavy manual QA, keep a scan running while repeatedly scrolling, changing sort order, typing a MAC/name filter, switching `All`/`Active`/`Alerts`, opening a frequently observed device, and opening Diagnostics. The list and drawer should remain responsive, the foreground notification should not churn, Meta paired/unpaired cases must retain their existing behavior, and the latest device stats should continue to advance.

## Residual limits and measurement

- If 512 distinct reports are already pending, later distinct reports are intentionally dropped rather than allowing unbounded memory growth. Diagnostics exposes queue depth/high-water, coalesced, dropped, and ignored-late counts.
- The detail screen still measures its nested history list eagerly, but the work is capped at 100 preformatted rows.
- Schema 9 is compiled by Room in CI, but the project does not yet include an end-to-end v8-to-v9 migration fixture.
- A future Macrobenchmark should seed at least 5,000 devices and sustain observation writes on a physical device while tracking frame timing, detail launch cost, queue backlog/drops, CPU, and memory.
