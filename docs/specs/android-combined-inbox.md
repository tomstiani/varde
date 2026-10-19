# First Android slice: named connections and a combined plain-text inbox

**Status:** approved and published as [issue #11](https://github.com/tomstiani/varde/issues/11). Core implementation tickets #12–#15 are delivered in [PR #17](https://github.com/tomstiani/varde/pull/17). The original remote HTTPS/n8n and complete live-phone lifecycle acceptance are tracked in [#16](https://github.com/tomstiani/varde/issues/16). GitHub owns live issue state and acceptance checkboxes.

**Later increments:** [PR #26](https://github.com/tomstiani/varde/pull/26) adds local notifications and [PR #28](https://github.com/tomstiani/varde/pull/28) adds foreground change recovery under [spec #19](android-alert-notifications.md). The original problem statement, scope and acceptance contract below are retained as this milestone's planning record; they are not a current feature inventory.

**Context:** follows the durable backend increment in issue #9 / merged PR #10 and advances map #1 without completing its rich-view or notification release. The original planning baseline was commit `19ec01a`, including the leap-second validation fix. That foundation and this inbox are now integrated; the earlier squash-merge landing prerequisite is no longer pending.

## Problem Statement

The backend reliably accepts and retains plain-text alerts, but a person cannot browse them on a phone. Reading currently requires knowing an alert ID and making an authenticated request. There is no Android application, history listing Interface, or way to see alerts from several homelab backends together.

The next useful proof is a real n8n alert visible on a real Android phone. It must remain readable after a backend restart, be clearly attributed to its source connection, and coexist with alerts from other backends even when one is unavailable. It need not notify the person automatically yet.

## Solution

Build a native Kotlin application with Jetpack Compose, standard Android navigation, and a functional Material interface. Provide connection settings, a combined inbox, and plain-text alert details.

The person manually saves named connections using an HTTPS URL and an installation credential issued by the existing local operator command. The inbox loads on opening, combines recent alerts from all saved connections, and labels every alert with its source connection. Refresh is explicit after initial loading. Older alerts load on demand; there is no automatic archive download.

Backend changes add authenticated, bounded history reads. Existing submission, original-read, credential, duplicate, and fallback contracts remain intact. TLS remains an operator responsibility through a trusted HTTPS proxy or tunnel in front of the loopback listener.

## User Stories

1. As an operator, I want to use the existing local installation credential commands, so that the app needs no new account or remote administration system.
2. As a phone user, I want to add a connection with a friendly name, HTTPS URL, and credential, so that I can access my backend.
3. As a phone user, I want several saved connections, so that one app can read several homelab backends.
4. As a phone user, I want distinct connection names, so that source labels are unambiguous.
5. As a phone user, I want duplicate configured URLs rejected, so that I do not accidentally list one backend twice.
6. As a phone user, I want settings and credentials retained securely across app restarts, so that I do not repeat setup on every launch.
7. As a phone user, I want to rename a connection without losing its displayed alerts, so that I can improve its label.
8. As a phone user, I want changing a URL or credential to discard that connection's old displayed content, so that old results are not attributed to a new endpoint or access identity.
9. As a phone user, I want removing a connection to clear its local content without changing backend history, so that connection management is safe.
10. As a phone user, I want an actionable empty state when no connections exist, so that I know how to begin.
11. As a phone user, I want the newest alerts loaded when the inbox initially opens, so that it is useful without an extra refresh gesture.
12. As a phone user, I want a combined newest-first feed, so that I need not switch between backends.
13. As a phone user, I want every row and detail screen labelled with the source connection, even with only one connection, so that provenance is always explicit.
14. As a phone user, I want title, priority, and original event time in the inbox, so that I can scan alerts.
15. As a phone user, I want the complete original title, summary, priority, and event time in details, so that I can read the retained message.
16. As a phone user, I want backend acceptance time to determine feed ordering while event time stays visible separately, so that delayed submissions do not masquerade as recent events.
17. As a phone user, I want manual refresh to load new arrivals, so that the app does not poll or move my reading position unexpectedly.
18. As a phone user, I want to load older alerts on demand across all connections, so that I can browse without downloading every retained alert.
19. As a phone user, I want concurrent submissions and duplicate sender retries not to introduce pagination gaps or duplicate rows, so that history is trustworthy.
20. As a phone user, I want an unavailable backend not to block working backends, so that partial outages do not hide useful alerts.
21. As a phone user, I want connection-specific failure and stale indicators, so that incomplete results are not mistaken for an empty or current inbox.
22. As a phone user, I want failed refreshes to retain already displayed content, so that a transient outage does not erase what I was reading.
23. As a phone user, I want authentication failures to direct me to connection settings without deleting them, so that I can replace a credential.
24. As a phone user, I want trusted HTTPS and no certificate bypass, so that my credential is not exposed to an untrusted endpoint.
25. As a phone user, I want alerts fetched only during foreground interactions, so that this proof does not imply background delivery or offline availability.
26. As an operator, I want accepted alerts to survive backend restarts and duplicate resubmissions, so that the existing durability foundation remains intact.
27. As a developer, I want reproducible local verification without live homelab credentials or paid calls, so that ordinary tests are safe to run.
28. As an operator, I want a documented two-backend demonstration on a real phone, so that the proof covers both success and partial failure.

## Implementation Decisions

### Agreement and technical proposals

The product behaviour in this document was confirmed through the design interview. Exact wire contracts, limits, cursor mechanics, URL normalization rules, and internal Interface shape below were approved with the complete specification, not separately during the interview. They are implementation requirements, not claims of existing behaviour.

### Modules and ownership

- Extend the existing alert lifecycle Module with authenticated history listing. It owns ordering, snapshot membership, cursor validation, credential checks, and SQLite consistency. HTTP remains an Adapter for request parsing, status codes, and safe response formatting.
- Keep SQLite inside the lifecycle Implementation. Do not introduce a generic repository Interface or another deployed process.
- Add an Android inbox Module with a small Interface for observing state, managing connections, refreshing, loading older results, and opening alert details. Its Implementation owns merge buffers, per-connection pagination, stale state, request cancellation, and deduplication. Compose screens must not reimplement these rules.
- Inject a backend transport Adapter and secure settings storage at internal Seams where behaviour actually varies between production and deterministic tests. Do not create a separate abstraction for every operation.
- The app reads backends directly. Do not build a server-side federation gateway.

### History HTTP Interface

Add `GET /v1/alerts` with an installation bearer credential. Sender credentials receive `403`; missing, invalid, or revoked credentials receive `401`. Every page authenticates independently, including continuation requests.

Request parameters:

- `limit`: integer from 1 through 100; default 50.
- `order`: `acceptance` or `acceptedAt`; default `acceptance`.
- `cursor`: opaque server-issued continuation token; omitted for a new browsing session.

Reject duplicate or unknown query parameters, unsupported order values, and malformed limits with a safe `400 invalid_arguments`. Limit decoded cursor size and reject oversized input before expensive processing. A continuation must use the same order and limit as its initial request; a mismatch is `400 invalid_cursor`.

A successful `200` response contains `items` and `nextCursor`. Each item has fields `id`, `acceptedAt`, `title`, `priority`, `observedAt`, and `presentation`; the last has `state` and integer `revision`, matching the existing detail representation. Title, priority, and observation timestamp are copied from the original without rewriting. It excludes summary, event ID, typed data, and private diagnostic categories. The detail Interface remains the existing `GET /v1/alerts/{alertId}`, returning the full original.

Return an empty `items` list and null `nextCursor` for empty history. A non-null cursor means more snapshot members remain; do not return an unnecessary cursor merely because the page happens to equal the requested limit. Pages contain at most `limit` items and do not silently truncate individual fields.

Storage unavailability remains a safe retryable `503` with the existing retry-delay convention. Responses and ordinary logs must not echo credentials, cursor payloads, alert bodies, or SQLite exceptions. No history query may mutate alert content, revisions, acceptance order, or processing work.

### Snapshot membership, ordering, and concurrent arrivals

A first-page transaction establishes a committed acceptance-sequence watermark for that backend. All later pages in that session include only alerts at or below that watermark. New acceptances are discoverable on refresh, not inserted into an existing browsing session. Duplicate submissions do not move the watermark or change an existing alert's position.

Two order modes make the existing architecture contract and the combined feed compatible:

- `acceptance`: descending immutable backend acceptance sequence, preserving the architecture's canonical per-backend history order.
- `acceptedAt`: descending acceptance instant, then descending stable alert ID for equal instants. Android explicitly requests this mode for its combined feed.

The timestamp order is an intentional additional history view, not a replacement for canonical acceptance order. Never change retained timestamps or fabricate monotonic timestamps to make them match sequence order. A backwards clock adjustment may cause a newly accepted alert to sort below older acceptances in the combined feed after refresh; disclose this limitation.

Use keyset continuation with the snapshot watermark and last returned ordering key. Do not use offset pagination. Compare parsed instants at full retained precision; raw variable-width RFC 3339 strings are not a valid ordering key. Provide indexed timestamp-order access so a page request does not load or sort the entire archive in application memory.

The cursor binds its version, store identity, watermark, order, limit, and continuation key. Use a persisted store-owned integrity key to authenticate cursor contents. Tokens must be unusable with another database, invalid tokens must be rejected safely, and valid tokens must survive an ordinary process restart. Cursors grant no access without a current installation credential. Cursor state must not require holding a database transaction open between HTTP requests.

Snapshot membership is stable, but presentation state/revision is current as of each page read. This milestone does not freeze presentation state or implement change recovery. A detail read can therefore show a later fallback revision than the row.

There is no cross-backend atomic snapshot: each connection establishes its own watermark when it responds to initial load or refresh.

### Database compatibility

Existing databases containing accepted alerts must remain usable. Add any necessary normalized ordering keys, indexes, and store cursor metadata through a versioned transactional migration. Derive ordering keys from retained acceptance timestamps without rewriting their public values or acceptance sequences. Preserve originals, credentials, duplicate namespaces, pending work, and change records.

A failed migration must not start a partially upgraded application or discard existing data. Test reopening a populated version-one database. No bulk archive prefetch is permitted in normal reads; a one-time migration may scan retained records to build ordering keys.

Supported backup/restore workflows and automatic detection of older-backup restoration remain deferred. Do not claim cursor safety across an undetected rollback to an old backup. Replacing a database with a newly initialized store must reject the old store's cursors.

### Connection identity and settings

Each saved connection has an immutable app-local ID independent of friendly name, URL, and credential. Android alert identity is the pair of local connection ID and server alert ID. Identical server IDs from different connections must remain distinct.

Friendly names are required, nonblank after trimming, and at most 80 Unicode code points. Reject duplicates after trimming and locale-independent case folding. Preserve the accepted display spelling. Names do not identify or administer server-side installations.

URLs must use HTTPS and have a host. Reject embedded user information, query strings, and fragments. Permit an optional base-path prefix for operator-managed proxies. Append the versioned alert paths below that prefix consistently.

For duplicate detection normalize scheme/host casing, the default HTTPS port, and a trailing base-path slash; preserve meaningful path casing. Let the chosen HTTP URL parser handle valid host and escape syntax rather than handwritten parsing. Aliases and distinct proxy URLs that reach the same backend are not detected in this milestone; document that they can duplicate alerts.

Persist connection settings and secrets in app-private storage, protecting credentials with Android Keystore-backed encryption. Do not persist alert bodies, row content, cursors, or detail content across process death. Exclude credentials from backup/export and do not place them in logs, navigation arguments, crash messages, or analytics. Secret entry is masked; editing a friendly name does not reveal or require retyping the credential.

Renaming preserves connection ID and loaded content and updates source labels everywhere. A URL or credential change preserves connection ID but clears that connection's loaded content, merge buffers, cursors, errors, and open details. Cancel or ignore responses issued against the previous configuration. Start a new combined browsing session so the changed source can be merged correctly.

Removing a connection deletes its settings, secret, and loaded content. It performs no remote alert deletion or credential revocation. Other connections' already displayed content remains available; membership changes invalidate any in-flight combined pagination operation and establish a fresh session. Saving or editing a connection need not wait for successful network verification; unreachable configurations remain editable and show their failure state.

### Combined-feed merge and bounded loading

Android requests timestamp-ordered pages. The combined ordering key is acceptance instant descending, local connection ID ascending, then server alert ID descending. The local connection ID provides stable tie-breaking without making a rename reorder alerts.

A combined load emits up to 50 additional rows. Fetch backend pages of 50 and retain unused candidates in memory. For a complete combined batch, obtain the next candidate or exhaustion result from every participating healthy connection before selecting the next globally newest row. Do not merely concatenate backend pages and sort them: that can expose older rows before unseen newer rows from another source.

Never drain an archive to establish ordering. Keep bounded page buffers per source; consume them as the person requests older results. At most four backend requests run concurrently, each with a 15-second end-to-end deadline. No hidden automatic request retries or polling. Explicit retry/refresh initiates a new request; ordinary failure must not start a retry storm.

Initial load and refresh do not wait indefinitely for unavailable backends. Classify a failed or timed-out source as incomplete and merge successful sources. Report the incomplete source prominently. The global ordering guarantee applies to known results from responding sources, not unseen alerts from unreachable backends.

A refresh stages new results while keeping displayed content visible. Replace each successful connection's previous browsing content with its newly loaded results, discard its old pagination state, and return to the newest results. A failed connection retains its already displayed rows and loaded details as stale; stale rows participate in displayed timestamp sorting but are not treated as valid continuation candidates for the new session. They can overlap healthy results in time, so describe the feed as incomplete rather than claiming a consistent global snapshot.

Load-older advances healthy connections only, retaining already displayed rows and deduplicating by connection/alert identity. If one continuation fails, preserve that connection's last successful buffer and cursor, mark it incomplete/stale, and continue with other sources. Keep its requests paused until explicit recovery. Recovering a failed connection starts a full combined refresh rather than inserting recovered pages behind an already consumed global continuation. Never show a definitive all-connections end-of-history while a connection is incomplete.

Ignore obsolete responses after refresh, connection edits/removal, or session replacement. Serialize or coalesce repeated refresh/load gestures so responses cannot interleave cursor advancement. A failed or cancelled page request must not advance its stored cursor.

### Screens and lifecycle behaviour

- No connections: explain setup and offer an add-connection action.
- Connections configured: initial inbox entry starts a fresh load. Returning from details or recomposition does not trigger a new refresh. Process recreation starts a new session using saved connection settings; activity recreation may retain in-memory session state.
- Inbox: show original title, priority as text plus optional styling, original event time, and the current friendly source name on every row. Use acceptance time for ordering, not observed time. Parse instants correctly and display event time in the device timezone with accessible date/time information. Preserve original timestamp spelling in the backend; do not rewrite alert content.
- Distinguish loading, truly empty, partial results, all-sources failure, and loaded results. Show a connection-specific status and recovery action. If all sources fail and no rows are retained, show failure, not “no alerts.”
- Details: fetch the full original from the row's source connection on opening. Show full title and summary without destructive truncation, priority, original event time, and source name. Empty summaries are valid. Render original content as plain text, not HTML or executable markup.
- Keep loaded details during a transient fetch/refresh failure with a stale/error indicator. If details have never loaded and the source fails, show a retryable error rather than inventing content. An authenticated `404` means the requested alert is unavailable; it does not delete unrelated rows.
- Authentication failure is connection-specific and directs the person to edit credentials. Keep saved settings and already displayed session content, marked stale. An app-level cache is not a promise of access after server-side revocation.
- Source labels appear even with only one connection. Use stable connection identity for navigation, not the current list position or mutable friendly name.
- No background fetching, foreground polling, notification permission request, push registration, or background work scheduler in this milestone.

### Transport and operator configuration

Only Android-trusted HTTPS endpoints are supported in the release app. Do not disable certificate validation, add an “ignore errors” switch, or import custom trust certificates through this app. Do not follow HTTP redirects; surface a configuration error so bearer credentials cannot be forwarded to an unintended destination. Protect credential-bearing responses from persistent HTTP caching.

The operator configures a reverse proxy or tunnel that terminates trusted HTTPS and reaches the existing loopback listener. A proxy may share a host/network namespace or use an explicitly documented equivalent route; do not expose the unauthenticated administration surface or relax the listener merely to simplify the demo. Source and installation bearer authentication remain mandatory regardless of network path. Proxy logs must not record authorization headers or alert bodies.

Document setup, Android build/install/test prerequisites, secure credential transfer, and the chosen real-phone network path without committing live URLs containing secrets, credentials, databases, or real alert content. Selecting a proxy product is not a runtime dependency of the app.

## Testing Decisions

### Approved test Seams

Test through caller-facing Interfaces and observable outcomes. Prefer the existing lifecycle Interface with real temporary file-backed SQLite, the HTTP Adapter, and the Android inbox Interface. Mock backend transport responses, not the merge implementation itself. Compose tests exercise screen behaviour and navigation; they do not replace inbox behaviour tests.

Existing backend prior art covers role separation, credentials, duplicate identity, capacity, commit failures, validation, and deterministic subprocess crashes. Preserve those tests rather than rewriting them around private persistence helpers. Add only narrow internal checks where migration or cursor storage invariants cannot be observed externally.

### Backend acceptance cases

1. Installation-authenticated empty history, bounded pages, summaries, and full original detail reads work; sender and revoked credentials cannot list history.
2. Default sequence order and explicit timestamp order differ correctly under a backwards clock. Equal timestamps have stable ordering. Fractional timestamps compare by instant, not string representation.
3. Every alert at the session watermark is returned exactly once across pages, while concurrent new acceptances remain outside that session. A fresh session discovers them in the documented order.
4. Duplicate resubmission does not create another history item or change its order. Completing fallback does not change membership or reorder the alert.
5. Invalid, tampered, cross-store, oversized, and option-mismatched cursors fail safely. Valid cursors survive restart; revocation still blocks continuation.
6. Limit boundaries, malformed/duplicate/unknown query parameters, and storage failure produce safe responses without partial successful pages or leaked content.
7. A populated version-one database migrates without losing originals, credentials, duplicate history, pending work, or changes. Migration failure leaves a recoverable prior state.
8. Bounded-response and indexed-query checks demonstrate that listing does not load the archive into application memory. Tests require no external services.

### Android acceptance cases

1. Add, persist, rename, edit, and remove connections; enforce name/URL rules and protect secrets. Process restart retains settings but no alert content.
2. Feed merging is globally ordered over healthy snapshot streams, including equal instants, timezone/fractional spellings, many pages from one source between another source's items, empty sources, and source exhaustion.
3. Same server alert ID from two connections produces distinct labelled rows. Rename changes labels but not identity or tie-breaking.
4. New arrivals remain absent during load-older and appear on refresh. Repeated taps, overlapping requests, cancellation, and delayed obsolete responses cannot corrupt a session.
5. One failed source permits healthy results. Failed refresh preserves stale source rows; all-failed and genuinely empty states are distinct. Incomplete history is never labelled globally exhausted.
6. Recovery starts a fresh combined session and cannot insert missing recovered rows behind an advanced global continuation unnoticed.
7. URL/credential edits invalidate content and requests; removal cannot resurrect content through late responses. Configuration errors remain editable.
8. Detail reads always target the source connection and show full plain text. Empty summaries, unknown alerts, authentication failures, and unavailable sources have appropriate UI states.
9. Untrusted certificates, plaintext URLs, and redirects are rejected without leaking bearer credentials. Logs, persistent caches, exported state, and navigation do not contain secrets or retained alert bodies.
10. Screen tests verify source labels, loading/error/empty states, refresh, load-older, detail navigation, and settings actions, including accessible priority and source text. A two-backend integration test exercises the Android transport against the actual Go HTTP Interface using synthetic data; do not rely solely on mocks to establish wire compatibility.

### Real-phone acceptance demonstration

- Operate two backend instances with separate databases and installation credentials behind Android-trusted HTTPS. Use distinct connection names and synthetic alerts.
- Submit an actual n8n alert to at least one backend. Show it in the real phone's combined inbox and open its unchanged original.
- Populate enough interleaved alerts to cross multiple pages. Demonstrate newest-first combined ordering, source labels, load-older, and refresh discovering a concurrent arrival.
- Restart a backend with intact storage; refresh and open its retained alert. Resubmit the same sender/event original and prove there is still only one source-scoped item.
- Make one backend unavailable. Refresh and load older results from the working backend, with a clear error and retained stale content from the failed one. Restore it and explicitly refresh to recover.
- Rename a connection, replace a credential, and remove a connection, confirming the agreed content invalidation and label behaviour.
- Record reproducible commands and redacted evidence. Never commit live credentials or private alert content. Real network provisioning and physical-phone checks are explicit operator acceptance steps, not unattended test prerequisites.

### Definition of done

- Automated backend tests, race tests, vet, and the existing restart demo pass from the documented backend setup.
- Android builds reproducibly with pinned dependency/tool versions; documented local inbox, transport, and UI tests pass without live homelab credentials or paid calls. Establish and document the Android SDK baseline during implementation, checking the acceptance phone before committing to a minimum version; no specific minimum version has been agreed in the interview.
- Old populated databases upgrade safely, all public contracts above have behavioural tests, and the real-phone demonstration is completed with redacted evidence.
- Documentation distinguishes this milestone from the eventual notification/rich-view release and does not claim production operations or offline/sync guarantees it does not implement.

## Out of Scope

- Push, FCM, notification timing, Android notifications, background polling, automatic change sync, public change cursors, and archive prefetch.
- Typed measurements, charts, tables, diagrams, model calls, paid-step scheduling, provider configuration, and rich-view rendering.
- Durable offline alert caching, cross-device settings sync, account systems, QR pairing, remote credential administration, automatic server discovery, or a federation gateway.
- Search, filters, read/unread state, acknowledgement, alert deletion/editing, grouping, remediation actions, and external homelab enrichment.
- Custom visual design, custom certificate import, TLS bypasses, direct plaintext remote access, and built-in proxy/tunnel provisioning.
- Production deployment packaging, supported backup/restore commands, rollback detection after restoring an older backup, and a general schema-migration framework beyond the concrete upgrade needed here.

## Further Notes

- This milestone deliberately proves manual use before push. Notification timing was subsequently settled in spec #19; production model selection remains unresolved.
- Multiple connections are an explicit revision of the initial single-connection proposal. The combined feed exists only on Android; each backend retains independent history and authentication.
- Cross-backend timestamp ordering cannot establish true global event or acceptance order when clocks differ. Original event time is descriptive, not the merge key. Partial failures make the feed visibly incomplete.
- HTTPS URL aliases can list the same backend twice. That limitation is accepted for this milestone; future backend identity discovery could address it without changing current local connection identity.
- The spec is intentionally larger than one implementation session. After approval, split it into end-to-end tracer-bullet tickets with blocking edges rather than separate disconnected backend and Android projects.
- Published with approval as issue #11; tickets #12 through #16 record its original implementation chain. Track current completion and native blocking links in GitHub. This local specification retains the approved contract, not a second progress tracker. Update tracker issues only with explicit approval.
