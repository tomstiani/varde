# Backend architecture proposal

Varde will use one application and SQLite to accept alerts durably, generate validated rich views, and serve a native Android app. Push is a best-effort hint; authenticated reads and change sync are authoritative.

**Status:** architecture baseline based on the confirmed contract interview. The contracts below are agreed; the complete backend is not implemented. [Issue #9](https://github.com/tomstiani/varde/issues/9) selects Go, standard `net/http`, and SQLite for the first slice; see [local backend setup](../how-to/run-backend.md) for what currently works. Internal records and interfaces beyond that slice remain proposed. The concrete push provider is undecided.

## Scope

Each homelab runs its own backend. Alert senders such as n8n use credentials separate from Android installations. The backend remains independent of the network path: Pangolin, Tailscale, or public internet access are deployment choices.

The first release retains original alerts and generated rich views indefinitely. It is read-only: no remediation actions, incident grouping, external homelab lookups, or automatic regeneration of terminal presentations. Android owns styling; models select supplied facts through approved, versioned layouts rather than executable rendering code.

## Module design

### Alert lifecycle module

**Interface:** submit an authenticated original alert; read an alert; page retained history; read changes using a server-issued cursor. Administrative credential operations are available through a local operator command, not sender/device access.

The interface includes durable-acceptance guarantees, duplicate behavior, limits, permission rules, cursor recovery, and asynchronous presentation status. A successful submission does not promise a successful provider call or delivered notification.

**Implementation:** owns SQLite transactions, duplicate identity, processing records, durable attempt accounting, scheduling, final presentation storage, change tracking, and push scheduling. HTTP handlers and Android callers do not coordinate these operations themselves.

**Depth:** callers submit once and observe state. They do not reproduce database transaction or recovery logic. Deleting this module would spread that complexity across ingress, workers, reads, and delivery.

### Presentation module

**Interface:** turn a valid original alert into a validated rich view or text fallback. Transient provider failures suspend work for the lifecycle scheduler; they are not prematurely committed as terminal fallback.

**Implementation:** prepares approved candidates, requests bounded choices, optionally requests a relevance brief, resolves supplied data references, orders the selected candidates, and validates the complete result. It never changes the original title, summary, priority, or event timestamp. It never invents measurements, fills missing samples, fetches external facts, or accepts executable content.

Retain separate `DecisionModel` and `LLM` interfaces as internal seams. Credentials, provider-specific errors, and transport configuration stay server-side in adapters. The presentation module does not expose provider orchestration to ingress or Android.

The optional reasoning handoff remains experimental. If a required adapter is not configured, use terminal fallback rather than silently selecting another provider. A new reasoning provider requires separate evaluation; this proposal does not authorize live calls or choose one.

The lifecycle scheduler must observe each paid step separately. Do not retry an opaque end-to-end `present()` operation: that would repeat completed calls and defeat per-step budgets. Persist successful routing, brief, and selection results as needed before advancing. The exact resumable internal interface is an implementation design task, not an additional public interface.

### Push adapter

**Interface:** submit a notification hint for one installation and report success, a transient failure, or a permanent failure.

The lifecycle implementation owns durable scheduling and retry accounting. The adapter hides provider details, not canonical alert history. Device credentials may register, replace, or remove only their own push destination.

Push timing is intentionally undecided. Whether the hint follows acceptance or terminal presentation, its referenced state must already be readable. Notification-envelope content and the concrete provider still need a delivery specification.

### Seam discipline

- Inject model and push dependencies; do not construct clients inside presentation decisions.
- Keep persistence inside the lifecycle implementation. Do not introduce a generic repository interface for a single SQLite adapter.
- Use a real temporary SQLite database to test lifecycle guarantees.
- Test provider seams with deterministic adapters and transport fault injection. The local throwaway experiments exercised neutral model types and offline replay, but their runnable code is intentionally not committed.
- A module is not a separately deployed process. These modules live in one application.

## Confirmed contracts

### Acceptance and identity

- Success means the original alert and pending work are durably committed.
- Original content is immediately readable, even when generation is delayed or unavailable.
- Validate submissions before acceptance. Plain-text-only alerts are valid; malformed typed data is rejected.
- Initially accept at most 64 KiB of JSON and eight supplied data entries. Reject excess explicitly; never truncate silently.
- Duplicate identity is `(sender identity, sender event ID)`, retained indefinitely.
- Compare validated original content structurally. Ignore object-key order and JSON formatting; preserve meaningful values and array order. Exclude server-generated metadata.
- An identical duplicate returns the existing alert without new work or another notification. Changed content under the same identity is a conflict.
- Sender identity survives credential revocation. Rotating a credential must not accidentally create a new duplicate namespace.
- Initially allow at most 1,000 unfinished alerts. Reject new submissions with a retryable response when full; an existing identical duplicate still resolves normally.

### Processing and retry policy

- Allow one locally in-flight model request initially.
- Select the oldest accepted eligible work. A delayed retry does not block other work.
- Pending work does not expire after an outage. Preserve the original event time when processing an old alert.
- Each paid model step permits one initial attempt plus at most three repeats: four attempts total.
- Retry transport failures, timeouts, rate limits, and temporary provider failures. Do not retry known configuration errors, authentication failures, other permanent request failures, or invalid generated output.
- Delay retries approximately 5 seconds, 30 seconds, and 2 minutes, with small jitter. Honor a longer provider `Retry-After` up to 15 minutes. A longer requested wait produces terminal fallback rather than an early retry.
- Persist retry times and attempt accounting across restarts. An uncertain attempt counts even if the process cannot establish whether it was sent.
- Initially permit 1,000 provider attempts per UTC day across all alerts and model steps; make this configurable. Exhaustion pauses eligible paid work until the next day rather than spending an attempt or producing fallback.
- The daily request ceiling is not a guaranteed dollar cap. Repeats after uncertain outcomes may cause repeat charges.
- A direct path with routing and selection can consume up to eight attempts. An optional reasoning step would have its own four-attempt ceiling and also consume the global budget.
- Successful steps are not rerun merely because a later step fails.

### Terminal presentation

- A terminal result is either a validated rich view or original-text fallback.
- A relevant supplied error note can be a useful rich view. Missing history does not justify invented samples.
- An intentional empty selection can correctly yield text fallback. Invalid model output also yields fallback, but is a different diagnostic outcome.
- Invalid model output invalidates the whole rich view; do not silently retain a partial layout.
- Exhausted attempts, non-retryable failures, and known missing/invalid required configuration produce terminal fallback.
- Terminal fallback is final for the first release. Configuration changes do not silently regenerate old alerts.
- Retain successful generated layouts and generation/validation metadata. Retain safe failure categories without exposing secrets or private reasoning traces.

### Android reads and sync

- History loads on demand. Connecting an installation does not automatically download the archive or prefetch historical alert bodies.
- Order the inbox by server acceptance order, newest first. Show the original event timestamp separately. Duplicate submissions do not reorder history.
- Page retained history independently of change recovery.
- Use server-issued change cursors, not Android timestamps. Changes cover acceptance and terminal presentation updates, including updates to old alerts.
- Apply repeated results safely by stable alert identity and presentation revision.
- An original fetched while pending can later gain a rich view or terminal fallback without becoming a new alert.
- Sync initialization and pagination must not leave a gap during concurrent acceptance or completion. The proposed mechanism is described below.

### Push and credentials

- Commit referenced state before scheduling its push. Delivery failure cannot roll it back.
- Permit one initial push attempt plus three transient-failure retries using the same delay policy. Push attempts do not consume the model-attempt budget.
- Repeated pushes identify the same alert so Android can avoid duplicate notifications. Push exhaustion does not compromise sync recovery.
- Issue a separate revocable credential for each sender and Android installation through a local operator command. Show each secret only at issuance; store its verification hash, not a recoverable credential copy.
- Sender credentials submit under their assigned sender identity; they cannot read history.
- Device credentials read/sync alerts and manage their own push destination. They cannot submit alerts or administer credentials.
- Revocation prevents subsequent credential use. It does not erase history or cancel accepted alert processing. Revoking an installation disables future push work for it; an already dispatched external push cannot be recalled.

### Operations

- Ordinary logs contain IDs, stages, timings, attempt counts, and sanitized failure categories—not credentials, alert bodies, or raw provider responses.
- Retained alert content remains available through authenticated reads. Access controls and backup handling must account for potentially sensitive source text and push destinations.
- Provide a consistent backup procedure. Process or machine restarts with intact storage preserve accepted work; storage loss is not covered by that guarantee.
- Restoring an older backup requires invalidating existing sync cursors and resyncing devices. Alerts accepted after the backup may be lost; do not claim otherwise.

## Proposed internal records

These are responsibilities to represent in SQLite, not a prescribed table-per-row design.

| Record | Purpose |
| --- | --- |
| Sender / installation | Stable identity independent of issued credentials; installation push destination |
| Credential | Verification hash, owner, permissions, revocation state |
| Alert | Immutable original, sender event ID, structural-content digest, acceptance order/time |
| Presentation | Pending or terminal outcome, schema/revision metadata, validated layout or fallback category |
| Processing work | Current step, durable intermediate results, next eligible time, claim state |
| Provider attempt | Step, ordinal, reservation time, outcome, available usage, sanitized failure category |
| Daily budget | Durable paid-attempt reservations by UTC day |
| Change entry | Ordered committed alert identity/revision change for Android recovery |
| Push work | Installation, alert identity, triggering state, attempt count, next eligible time, outcome |
| Store generation | Cursor namespace that must change after restoring an older backup |

Do not turn the first release into a general event-sourcing platform. Canonical alert and presentation records plus an ordered change ledger are sufficient. Local scheduling updates need not each become a mobile change entry.

## Proposed transactional flow

### 1. Accept an alert

1. Enforce request size, authenticate the sender, and validate original content.
2. Begin a transaction and resolve the sender/event identity.
3. Return the existing alert for identical content, or reject conflicting content.
4. For a new identity, enforce unfinished-work capacity.
5. Insert the immutable original, pending presentation/work, and acceptance change entry atomically.
6. Include any acceptance-triggered push work in that transaction if that notification policy is later selected.
7. Commit before returning success.

A uniqueness constraint resolves concurrent duplicates. Capacity enforcement must be transactional too. A database or commit failure must not return acceptance success. If the response is lost after commit, the sender's stable event ID makes retry safe.

### 2. Reserve and execute a paid step

1. In a short transaction, claim eligible work and verify remaining step attempts and daily budget.
2. Reserve one attempt and consume its daily-budget slot durably before contacting the provider.
3. Commit and perform the external call outside the transaction.
4. Validate the result. In a new transaction, persist the step result or its failure and next action.

On restart, an interrupted reservation is uncertain, not unused. Count it and follow the transient-retry policy within the remaining budget. A crash after provider success but before persisting that success can cause a repeat charge. No local transaction can guarantee exactly-once execution at an external provider that lacks a suitable idempotency guarantee.

Use claim/attempt identity to prevent a stale response from overwriting a later result. A local concurrency limit cannot prove that a timed-out request has stopped executing at the provider. Adapters must not perform hidden retries that bypass lifecycle accounting.

### 3. Commit a terminal presentation

Atomically persist the rich view or fallback, mark processing finished, append its change entry, and insert any terminal-triggered push work selected by the future notification policy. This transaction releases unfinished-work capacity.

Store a successful intermediate result before starting the next step. Otherwise restart recovery would needlessly rerun completed paid work.

### 4. Recover changes without downloading the archive

Use an opaque cursor containing a store generation and committed change position. A new installation can establish a current position without downloading historical bodies. Opening history loads a page; older pages load on request.

Coordinate the initial position with the history-read watermark. One possible implementation reads a history watermark and cursor in a consistent transaction, pages alert identities at or before that watermark, and consumes subsequent changes separately. Later presentation changes remain discoverable even for older alerts.

Changes can identify an alert and its current presentation revision without returning its full body. Content loading remains demand-driven. Deduplicate overlap between page reads and changes on Android. Reject unknown or invalidated cursors explicitly rather than silently skipping changes. Exact request/response shapes and pagination consistency rules belong in the interface specification.

### 5. Deliver push independently

Claim committed push work and recheck installation revocation before dispatch. Send outside the database transaction; record the result and any retry schedule afterward. Repeated attempts use the same alert identity. A crash can still cause duplicate external delivery, so Android must handle duplicates safely.

Push scheduling cannot block ingestion, canonical reads, or presentation persistence. Keep it in the same application initially, not another deployment tier.

## Verification through module interfaces

Before a production slice is complete, demonstrate:

- Concurrent identical submissions produce one retained alert and one processing chain.
- Changed content conflicts; duplicates still resolve while capacity is full.
- Invalid or oversized input is not accepted or silently truncated.
- A crash after acceptance preserves the original and eligible work.
- A crash before or after an external call preserves attempt and daily-budget limits.
- A later-step retry does not repeat successful earlier steps.
- Retry delays, provider wait instructions, UTC budget rollover, and queued-work ordering survive restart.
- Daily-budget exhaustion pauses work; permanent errors and invalid views become terminal fallback.
- Original reads work during provider outages and after known configuration failure.
- Sync recovers terminal changes to previously fetched originals without an initialization/pagination gap.
- On-demand history does not require an automatic archive download.
- Revocation prevents access and future push dispatch without deleting history or cancelling accepted processing.
- Push failures and duplicate delivery do not lose or duplicate canonical alerts.
- A consistent backup can be restored, old cursors rejected, and clients resynced explicitly.
- Logs and ordinary errors contain no credentials or copied source/provider bodies.

Use deterministic clocks and fault injection alongside real temporary SQLite databases. Provider integration tests require separate request/spending approval; ordinary tests must not make live calls.

## Evidence, alternatives, and deferred choices

The [FOSS lifecycle comparison](../research/foss-alert-lifecycle-efe0c387-2704-4893-9ca3-71cfde044a47.md) found that ntfy and Gotify offer delivery infrastructure, while DBOS offers durable execution. None supplies the agreed Varde lifecycle in full. SQLite-owned work records avoid an extra deployment dependency while the workflow remains small. Revisit this choice if real implementation complexity warrants it.

The [retained experiment findings](../research/alert-view-experiments.md) support direct candidate selection on a small synthetic corpus. The opt-in relevance prompt passed ten service-style cases, including expected fallback. This is not production reliability evidence. Runnable experiments and raw reports remain local and uncommitted; the findings do not provide a reproducible test suite in this checkout.

Deferred deliberately:

- HTTP interfaces beyond the implemented plain-text and snapshot-history slices, including typed-data schemas and sync.
- Concrete push provider, notification payload privacy, and timing relative to generation.
- Production model configuration, optional reasoning adoption, and promotion of the tested relevance prompt.
- Transport deadlines, prepared-request/context limits, and shutdown/claim mechanics. These must be specified before implementing paid execution.
- Exact change-sync shapes; snapshot history and the first Android slice's memory-only content policy are specified separately.
- Schema migrations, backup commands, and deployment packaging details.

These choices must preserve the confirmed contracts above. The first implementation increment in issue #9 covers authenticated plain-text acceptance, retained original reads, and restart-safe no-model fallback. Issue #12 adds authenticated snapshot history reads and the first Kotlin/Compose inbox; issue #13 adds named connections and Android-side merging, and issue #14 adds snapshot-preserving older-history browsing. Canonical per-backend history retains acceptance-sequence order; the explicit `acceptedAt` history view supports Android's timestamp-ordered feed without rewriting timestamps when clocks move backwards. See the [history contract](../how-to/read-history.md) and [approved inbox specification](../specs/android-combined-inbox.md). Typed data, resumable paid generation, change recovery, push, and full failed-source recovery are still later increments.
