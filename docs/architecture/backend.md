# Backend architecture proposal

Varde will use one application and SQLite to accept alerts durably, generate validated rich views, and serve a native Android app. Push is a best-effort hint; authenticated reads and change sync are authoritative.

**Status:** architecture baseline, not a claim that the complete release is implemented. The Go/`net/http`/SQLite foundation (#9), snapshot history/inbox (#12–#15), local notification path (#20), foreground recovery (#21), and registration/controls with durable fake-provider scheduling (#22) are merged. See [backend setup](../how-to/run-backend.md), [registration](../how-to/register-push.md), [controls](../how-to/control-notifications.md) and [recovery](../how-to/recover-alerts.md) for implemented Interfaces. Model execution, real FCM Adapters (#23), background recovery (#24) and integrated live acceptance (#25) remain separate. The ordinary backend provider and Android token source are unconfigured; automated fake-provider evidence is not live delivery.

The approved [rich-alert contracts](../specs/rich-alert-contracts.md), published in [#39](https://github.com/tomstiani/varde/issues/39), now define the Jev-first BYOK strategy, typed input, reference-only rich views and bounded execution. Contract delivery is not full rich-view implementation or live evaluation. [Local BYOK configuration](../how-to/configure-generation.md) now implements offline validation and nonsecret restart activation only; even valid configuration makes zero provider requests and retains original-text fallback. [#38](https://github.com/tomstiani/varde/issues/38) owns the implementation slices; GitHub remains canonical for their current state.

## Scope

Each homelab runs its own backend. Alert senders such as n8n use credentials separate from Android installations. The backend remains independent of the network path: Pangolin, Tailscale, or public internet access are deployment choices.

The first release retains original alerts and generated rich views indefinitely. It is read-only: no remediation actions, incident grouping, external homelab lookups, or automatic regeneration of terminal presentations. Android owns styling; models select supplied facts through approved, versioned layouts rather than executable rendering code.

## Module design

### Alert lifecycle module

**Interface:** submit an authenticated original alert; read an alert; page retained history; read changes using a server-issued cursor; manage the calling installation's own push registration. Administrative credential operations are available through a local operator command, not sender/device access.

The interface includes durable-acceptance guarantees, duplicate behavior, limits, permission rules, cursor recovery, and asynchronous presentation status. A successful submission does not promise a successful provider call or delivered notification.

**Implementation:** owns SQLite transactions, duplicate identity, processing records, durable attempt accounting, scheduling, final presentation storage, change tracking, and push scheduling. HTTP handlers and Android callers do not coordinate these operations themselves.

**Depth:** callers submit once and observe state. They do not reproduce database transaction or recovery logic. Deleting this module would spread that complexity across ingress, workers, reads, and delivery.

### Presentation module

**Interface:** turn a valid original alert into a validated rich view or text fallback. Transient provider failures suspend work for the lifecycle scheduler; they are not prematurely committed as terminal fallback.

**Implementation:** prepares candidates from supplied typed facts, requests one batch of finite omission/preferred-position choices, resolves supplied references, orders selected candidates with stable tie-breaking, and validates the complete result. It never changes original title, summary, priority or event time, invents measurements, fills missing samples, fetches external facts or accepts executable content. Without useful typed candidates, bypass the model and retain original text; exact original summary text may accompany structured candidates without automatic prose extraction.

Use native TypeSafe `jev-1.13.0` only initially. Keep its transport, credentials and error translation separate from presentation logic, but defer a generalized `DecisionModel` contract and second Adapter until another provider is actually evaluated. No `LLM`, routing, relevance-brief, reasoning or repair call is part of this release. The presentation Module does not expose provider orchestration to ingress or Android.

Generation is optional and disabled by default. The homelab operator supplies a backend-owned key through a local runtime secret file; configuration activates through controlled restart. Invalid configuration cannot prevent original-text service. No Android key entry, remote credential administration, hot reload or cross-provider failover. A new provider/account configuration never silently receives old pending alerts.

The lifecycle scheduler owns the single paid selection step's durable reservations, retry accounting and publication guard. Each invocation sends at most one provider request; hidden SDK retries are forbidden. Persist the outcome or retry eligibility before further work. The exact contracts and frozen native Jev request example are specified in [#39](../specs/rich-alert-contracts.md), not a general multi-step model workflow.

### Push adapter

**Interface:** submit a notification hint for one installation and report success, transient failure, permanent destination failure, or provider-wide configuration failure.

The lifecycle implementation owns durable scheduling and retry accounting. The adapter hides provider details, not canonical alert history. Device credentials may register, replace, or remove only their own push destination.

The approved policy schedules a minimal hint after durable acceptance, without waiting for terminal presentation or future rich-view generation. Android immediately posts a generic source-labelled notification, then fetches original text and silently enriches it. The implemented provider-neutral envelope contains only a version, registration routing ID and alert ID. The local path is implemented in #20; #22 adds registration, durable scheduling and an injected fake send Adapter. The selected real FCM Adapters remain #23 work. See the [notification specification](../specs/android-alert-notifications.md) for the full delivery/privacy contract.

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
- Delay retries approximately 5 seconds, 30 seconds and 2 minutes, uniformly jittered within 80–100% of nominal delay. Honor a longer provider `Retry-After` up to 15 minutes as a minimum wait; a larger requested wait produces terminal fallback rather than an early retry.
- Persist actual retry times and attempt accounting across restarts. An uncertain attempt counts even if the process cannot establish whether it was sent.
- There is no daily model-attempt quota, Varde dollar-spend enforcement or estimated-cost reservation ledger. Operators manage financial controls through their provider account/key; controls may be soft, account-level or unavailable.
- Bound requests to 48 KiB, provider responses to 32 KiB, and each selection attempt to 10 seconds through networking, decoding, assembly and validation. Queueing/retry delay and SQLite outcome-persistence contention are separate; no production latency is established by these limits.
- One selection step allows at most four attempts total. No routing or optional reasoning step adds more calls. Cancellation is best effort and does not prove a timed-out request was unbilled or stopped remotely.
- Persist an obtained result during storage contention without repeating the provider request. A crash before durable result storage may still cause a counted repeat charge.

### Terminal presentation

- A terminal result is either a validated rich view or original-text fallback.
- Exact supplied summary text may accompany useful typed candidates. A summary-only alert bypasses generation; missing history never justifies invented samples.
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
- Apply repeated results safely by stable alert identity and presentation revision. The approved rich contract uses pending revision 0 and terminal rich/fallback revision 1; intermediate retries do not publish partial revisions.
- Backend and Android use one current rich-alert contract with coordinated upgrades. No legacy projections, negotiation, downgrade path or mixed-version support; plain-text input remains valid because typed data is optional.
- An original fetched while pending can later gain a rich view or terminal fallback without becoming a new alert.
- Sync initialization and pagination must not leave a gap during concurrent acceptance or completion. The proposed mechanism is described below.

### Push and credentials

- Commit referenced state before scheduling its push. Delivery failure cannot roll it back.
- Permit one initial push attempt plus three transient-failure retries under the approved notification retry policy. Push attempts have separate bounded accounting from model attempts.
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
| Processing work | Pinned generation configuration, next eligible time, claim/attempt identity |
| Provider attempt | Selection-step ordinal, reservation time, outcome, available usage, sanitized failure category |
| Generation configuration | Nonsecret config/account/model/prompt/contract revision manifest; no provider key contents |
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
5. Insert the immutable original, pending presentation/work and acceptance change entry atomically; associate eligible generation work with the active valid nonsecret configuration revision.
6. Include eligible acceptance-triggered push work in that same transaction. #22 implements registration and durable provider-neutral scheduling with an injected fake; real FCM delivery remains separate.
7. Commit before returning success.

A uniqueness constraint resolves concurrent duplicates. Capacity enforcement must be transactional too. A database or commit failure must not return acceptance success. If the response is lost after commit, the sender's stable event ID makes retry safe.

### 2. Reserve and execute a paid step

1. In a short transaction, claim eligible pending work and verify its pinned active configuration, remaining selection attempts and local concurrency.
2. Reserve the numbered attempt and unique claim durably before contacting the provider.
3. Commit and perform exactly one external call outside the transaction.
4. Validate the result. In a new transaction guarded by the pending alert/attempt/claim/configuration, persist its terminal outcome or failure and next eligibility.

On restart, an interrupted reservation is uncertain, not unused. Count it and follow the transient-retry policy within the remaining four-attempt allowance. A crash after provider success but before persisting that success can cause a repeat charge. No local transaction can guarantee exactly-once execution at an external provider that lacks a suitable idempotency guarantee.

Use claim/attempt identity to prevent a stale response from overwriting a later result. A local concurrency limit cannot prove that a timed-out request has stopped executing at the provider. Adapters must not perform hidden retries that bypass lifecycle accounting.

### 3. Commit a terminal presentation

Atomically persist the rich view or fallback, mark processing finished, and append its change entry. The selected notification policy does not send another interrupting push on presentation completion; later detail enrichment stays silent. This transaction releases unfinished-work capacity.

Configuration retirement finalizes pinned old pending work as original-text fallback; it does not migrate content to a new account/provider. Same-account key rotation does not regenerate terminal alerts or reset attempt counts. See the [approved activation/execution contract](../specs/rich-alert-contracts.md).

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
- A crash before or after an external call preserves the four-attempt limit and uncertain-reservation accounting.
- Storage contention while persisting an obtained result does not issue another provider request.
- Retry delays, provider wait instructions, pinned configuration and eligible-work ordering survive restart.
- Permanent errors, invalid views, retired configuration and exhausted attempts become terminal original-text fallback without repair or failover.
- Original reads work during provider outages and after known configuration failure.
- Sync recovers terminal changes to previously fetched originals without an initialization/pagination gap.
- On-demand history does not require an automatic archive download.
- Revocation prevents access and future push dispatch without deleting history or cancelling accepted processing.
- Push failures and duplicate delivery do not lose or duplicate canonical alerts.
- A consistent backup can be restored, old cursors rejected, and clients resynced explicitly.
- Logs and ordinary errors contain no credentials or copied source/provider bodies.

Use deterministic clocks and fault injection alongside real temporary SQLite databases. Any live provider evaluation requires separate explicit payload/request approval; financial controls remain operator-managed. Ordinary tests must not make live calls.

## Implemented worker recovery

The plain-text fallback worker retries known SQLite `BUSY` contention with capped jittered backoff and no attempt limit. Fatal or uncertain-cleanup failures still stop the backend. This storage-recovery policy is separate from the bounded paid-step retry policy above; it does not implement paid execution. The HTTP adapter owns a five-second request budget, while the application writes bounded best-effort stderr diagnostics. See the [worker recovery contract](../specs/backend-worker-recovery.md) and [operator guide](../how-to/run-backend.md). Large-archive migration execution policy, supported backup/restore and full production operations remain deferred. [Initial Linux Compose packaging](../how-to/run-compose.md) is available without changing the loopback-only listener.

## Implemented local generation configuration

Application startup loads only the explicitly selected local configuration and key file. Offline `generation check` uses the same parser/file checks without opening SQLite or contacting the provider. The lifecycle Module persists nonsecret manifests and activation at schema version five; key contents, paths and fingerprints never enter those records. Controlled restart distinguishes same-account key rotation from semantic revision conflicts without reviving terminal alerts.

Disabled, missing or invalid generation configuration does not disable original-text service. Provider execution, durable paid-attempt scheduling and rich-view publication remain separate implementation slices. The existing Linux Compose CI job includes the synthetic non-root key-permission fixture, but its runtime execution remains unverified until ordinary approved PR CI runs it. See the [operator guide](../how-to/configure-generation.md) for exact file rules and limitations.

## Implemented registration and delivery scheduling

[PR #31](https://github.com/tomstiani/varde/pull/31) adds installation-only conditional registration with private destinations, quoted ETags, persisted idempotency and retired-session fencing. A new session captures its silent change baseline in the registration transaction; token refresh and destination repair preserve its existing baseline and Android recovery cursor.

Acceptance atomically inserts eligible push work with the original alert and acceptance change. Dispatch reserves attempts durably, rechecks the current registration and its credential, and sends outside SQLite transactions and HTTP ingress. The budget is one initial attempt plus three bounded retries. Permanent destination failures disable only that destination; provider-wide configuration failures establish a shared cooldown rather than invalidating phone tokens. Revocation, replacement and removal invalidate obsolete work.

The ordinary server has no live provider wired in. [The reproducible demo](../how-to/test-push-delivery.md) injects a deterministic fake and synthetic destinations; no production callback URL or live send is introduced. [Verification](../verification/android-notification-controls.md) records backend, real-Go Android transport, emulator and hosted-CI evidence separately from unverified physical-phone/FCM delivery. Registration/delivery introduced SQLite schema version four; local generation configuration advances it to version five. The current SQLite schema and Android journal version three require protected, consistent backups before upgrading important data; old binaries cannot simply consume those formats.

## Evidence, alternatives, and deferred choices

The [FOSS lifecycle comparison](../research/foss-alert-lifecycle-efe0c387-2704-4893-9ca3-71cfde044a47.md) found that ntfy and Gotify offer delivery infrastructure, while DBOS offers durable execution. None supplies the agreed Varde lifecycle in full. SQLite-owned work records avoid an extra deployment dependency while the workflow remains small. Revisit this choice if real implementation complexity warrants it.

The [retained experiment findings](../research/alert-view-experiments.md) support direct candidate selection on a small synthetic corpus. The opt-in relevance prompt passed ten service-style cases, including expected fallback. This is not production reliability evidence. Runnable experiments and raw reports remain local and uncommitted; the findings do not provide a reproducible test suite in this checkout.

Deferred deliberately:

- Implementation of approved typed input, reference-only rich views and durable paid Jev execution under [#38](https://github.com/tomstiani/varde/issues/38). Their contracts, request bounds, 10-second attempt deadline and five-second shutdown allowance are defined in [#39](../specs/rich-alert-contracts.md), not unresolved schema choices.
- Real FCM send/registration/receive Adapters and background Android scheduling; their timing/privacy policy is approved in #19. Registration/controls and durable provider-neutral scheduling are implemented, not deferred.
- Any live synthetic model evaluation and production generation enablement. The approved question/prompt artifacts and promotion targets are not evidence of measured model quality or latency.
- Additional providers/generalized model contracts, reasoning/brief/routing adoption, multi-series charts and automatic prose extraction. None is required for the initial Jev-only implementation.
- Background change scheduling and integrated live notification acceptance. The implemented foreground recovery and registration contracts are documented in the [recovery](../how-to/recover-alerts.md) and [registration](../how-to/register-push.md) guides; original alert content remains memory-only on Android.
- Large-archive migration execution policy, supported backup/restore commands and full production operations. Initial Linux Compose packaging and transactional upgrades through SQLite schema version five are implemented; the fixed startup budget remains unchanged.

These choices must preserve the confirmed contracts above. The first implementation increment in issue #9 covers authenticated plain-text acceptance, retained original reads, and restart-safe no-model fallback. Issue #12 adds authenticated snapshot history reads and the first Kotlin/Compose inbox; issue #13 adds named connections and Android-side merging, issue #14 adds snapshot-preserving older-history browsing, and issue #15 adds explicit failed-source recovery and retained in-memory details. Canonical per-backend history retains acceptance-sequence order; the explicit `acceptedAt` history view supports Android's timestamp-ordered feed without rewriting timestamps when clocks move backwards. See the [history contract](../how-to/read-history.md) and [approved inbox specification](../specs/android-combined-inbox.md). Issue #21 adds authenticated checkpoint/change reads and durable foreground catch-up, reusing the existing ledger and persisted cursor key without another schema migration. Issue #22 adds conditional registration, durable fake-provider scheduling and Android controls in PR #31. Typed data, resumable paid generation, real FCM delivery and background recovery remain later increments. Two-backend real-Go transport tests and physical-phone USB setup/browsing are verified. Original remote phone HTTPS/n8n acceptance and the complete live-phone lifecycle demonstration in issue #16 remain deferred; see the [verification record](../verification/android-usb-acceptance.md).
