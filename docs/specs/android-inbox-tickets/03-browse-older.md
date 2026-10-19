## Delivery status

**Implemented:** Older-history browsing, delivered in [PR #17](https://github.com/tomstiani/varde/pull/17), on the merged #9/PR #10 foundation. Automated and USB inbox evidence is recorded in the [verification record](https://github.com/tomstiani/varde/blob/main/docs/verification/android-usb-acceptance.md). The original slice criteria below are complete; #16's remote HTTPS/n8n and full live-phone acceptance remain separate and deferred. Later notification/recovery behaviour is governed by #19.

## Parent

Part of #11: First Android slice: named connections and a combined plain-text inbox. The approved parent specification is authoritative. Extend the combined inbox and the existing snapshot history Interface; do not add another history endpoint or change its contract.

## What to build

A person can request older alerts across their saved connections without downloading entire archives, missing snapshot members, seeing duplicates, or having new arrivals disrupt their browsing session.

## Acceptance criteria

- [x] Provide an explicit load-older interaction that adds up to 50 globally merged rows per action and retains displayed rows. Consume bounded per-source buffers and request backend pages of 50 only as needed.
- [x] Correctly merge complete healthy snapshot streams: replenish a source when its buffer empties before emitting a candidate from another source. Sort by acceptance instant descending, connection ID ascending, then alert ID descending.
- [x] Handle interleaved sources, many consecutive pages from one source, equal instants, empty sources and independent source exhaustion. Do not prefetch or drain an archive to establish ordering.
- [x] Preserve each backend's initial snapshot watermark and continuation. New acceptances appear only after explicit refresh; duplicate sender retries and terminal fallback do not create rows or move existing ones.
- [x] Deduplicate using connection/alert identity. Serialize or coalesce repeated load/refresh gestures, ignore obsolete responses, and never advance a cursor on cancellation or failed fetch.
- [x] Explicit refresh replaces successful sources' browsing sessions and returns to newest results. Configuration/membership changes invalidate affected in-flight pagination consistently with connection-management behaviour.
- [x] Correctly show loading, more results and healthy-source exhaustion. Do not infer end-of-history merely from a full page or conflate a failed page with exhaustion. The outage slice adds the complete multi-source stale/recovery experience.
- [x] Verify pagination through the Android inbox Interface against deterministic backend streams and through the actual Go HTTP Interface with temporary SQLite. Include backwards acceptance clocks, concurrent arrivals, repeated timestamps, sender duplicates, restart-resumed server cursors and cancelled requests.
- [x] Screen tests verify load-older availability, unchanged detail/source identity and no surprise refresh on returning from details. Preserve secure transport, request bounds and no persistent content cache.

## Scope boundary

This slice delivers correct multi-page browsing for healthy sources and safe request/cursor behaviour on failure. Complete stale-content presentation and failed-source recovery are the next slice. No public change sync, polling, offset pagination or offline archive is introduced.
