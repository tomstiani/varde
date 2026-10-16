## Parent

Part of #11: First Android slice: named connections and a combined plain-text inbox. The approved parent specification is authoritative for all field names, limits, security requirements, errors, and exclusions.

## What to build

A person can securely save one named backend connection in a native Kotlin/Jetpack Compose app, browse its newest plain-text alerts, refresh explicitly, and open an original alert. This is the first working backend-to-phone path, not an isolated backend or Android scaffolding task.

Build on the implementation from #9 / PR #10. It is already available on the planning branch; account for its eventual integration without duplicating commits after a squash merge. Landing the new work requires that foundation to be integrated.

## Acceptance criteria

- [ ] Establish a reproducible Android build with pinned tools/dependencies, Material screens and standard navigation. Check the acceptance phone before selecting and documenting the minimum SDK. Document local build and test prerequisites.
- [ ] Add authenticated `GET /v1/alerts` with the complete history contract from #11: limit 1–100/default 50, default acceptance-sequence order, explicit timestamp order, summary items, and nullable continuation cursor. Existing full-original detail reads remain unchanged.
- [ ] Implement snapshot-watermark keyset pagination in both orders, deterministic ties, parsed-instant timestamp comparison, and indexed bounded access. New acceptances do not enter an existing snapshot; duplicates and fallback completion do not reorder history.
- [ ] Authenticate every page. Reject malformed/duplicate/unknown parameters and invalid or mismatched cursors safely. Integrity-protected, store-bound cursors survive ordinary restart but grant no access without a current installation credential.
- [ ] Transactionally migrate populated version-one databases without losing originals, credentials, duplicate namespaces, processing work, or changes. Preserve raw acceptance timestamps and sequence order; failed migration must not start a partially upgraded app.
- [ ] Save one connection using a stable local ID, friendly name, HTTPS URL and installation credential. Apply the parent's name/URL validation and normalization. Settings survive process restart; alert content and cursors do not.
- [ ] Protect secrets using Keystore-backed encryption and app-private storage, exclude credentials from backup/export, and keep them out of logs/navigation. Reject untrusted TLS and redirects; do not persist authenticated HTTP content or add a release certificate bypass.
- [ ] Initial inbox load shows up to 50 newest timestamp-ordered rows with title, priority, event time and source name. Explicit refresh restarts the session. Returning from details or recomposition does not fetch again. No polling, push, or background work.
- [ ] Details fetch the full original from that connection and render complete plain text, including empty summaries. Loading, empty, unknown-alert, authentication and network failure states are distinguishable. A failed refresh retains displayed results as stale.
- [ ] Test through lifecycle/HTTP Interfaces using real temporary SQLite, including concurrency, restart, cursor integrity, migration and rollback, and backwards clocks. Test Android inbox behaviour, settings security, transport and screen navigation. Existing backend tests/race tests/vet/demo remain green.

## Scope boundary

This slice implements the complete backend continuation contract, but the Android load-older interaction comes in the pagination slice. Multiple saved connections and full connection-editing workflows follow separately. Do not introduce AI, typed data, push, offline alert persistence, or a generic storage abstraction.

## Blocked by

None within #11; the existing #9 implementation is the foundation and its integration remains a landing prerequisite.
