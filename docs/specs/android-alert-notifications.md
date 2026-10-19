# Android alert notifications and missed-delivery recovery

**Status:** approved and published as [issue #19](https://github.com/tomstiani/varde/issues/19). Implementation tickets are #20 through #25, linked as sub-issues with native blocking dependencies. GitHub is the canonical tracker. Live provider configuration is not authorized.

**Context:** extends the implemented combined plain-text inbox and durable Go backend. Advances map #1; the approved specification settles the notification-timing question in #8. The original remote HTTPS/n8n acceptance gate in #16 remains separate; its current acceptance state belongs in GitHub. This work does not require a homelab deployment.

## Ticket index

GitHub owns current completion, checkboxes and dependency readiness. This index preserves scope, not another progress dashboard:

- [#20](https://github.com/tomstiani/varde/issues/20): local notification path.
- [#21](https://github.com/tomstiani/varde/issues/21): foreground missed-alert recovery.
- [#22](https://github.com/tomstiani/varde/issues/22): registration and notification controls.
- [#23](https://github.com/tomstiani/varde/issues/23): real FCM Adapters.
- [#24](https://github.com/tomstiani/varde/issues/24): background recovery.
- [#25](https://github.com/tomstiani/varde/issues/25): integrated notification acceptance.

Historical delivery evidence: [PR #26](https://github.com/tomstiani/varde/pull/26) / [local notification verification](../verification/android-local-notifications.md), and [PR #28](https://github.com/tomstiani/varde/pull/28) / [foreground recovery verification](../verification/android-missed-alert-recovery.md). The user stories and implementation contract below describe the complete target, not a live feature/status inventory. Live checks still require operator approval.

## Problem Statement

A person must open Varde and refresh to discover new homelab alerts. They cannot rely on the app to announce an arrival or recover missed delivery in the background. A phone may reach a push service while its configured network path to a homelab backend is unavailable. Conversely, push delivery may fail even while the backend retains every alert.

Notifications must remain useful through those failures without exposing alert content through the push provider, flooding the person after an outage, or interrupting them repeatedly for the same alert. Multiple connections must retain distinct identities and recover independently.

## Solution

Use best-effort push to announce each new alert immediately with a generic, connection-labelled phone notification. Fetch its original through the existing authenticated HTTPS path and update that notification silently with its title and summary. Do not wait for presentation processing. Keep alert content private on the lock screen.

Start with Firebase Cloud Messaging (FCM), behind a replaceable push-provider Interface. Send only opaque routing and alert identifiers plus protocol metadata through the provider, not alert titles, summaries, priorities, credentials, backend URLs or connection names. Resolve the connection name locally.

Recover missed alerts on app opening and through battery-conscious background work. Notify once per connection for a recovery batch instead of posting an individual notification for every recovered alert. Initial history and muted backlogs remain silent. Preserve the existing on-demand history experience rather than downloading the full archive or automatically fetching every recovered body.

## User Stories

1. As a phone user, I want a new alert to notify me without opening Varde, so that I can discover arrivals promptly.
2. As a phone user, I want a generic notification before details are fetched, so that an unavailable network path does not hide a received signal.
3. As a phone user, I want the generic notification to identify its connection, so that I know which homelab sent it.
4. As a phone user, I want fetched original text to update the existing notification, so that I can understand the alert without a second interruption.
5. As a phone user, I want every alert priority to notify initially, so that no priority is silently filtered out.
6. As a phone user, I want notifications while the app is open, so that reading another alert or editing settings does not suppress arrivals.
7. As a phone user, I want title and summary hidden on the lock screen, so that nearby people cannot read alert content.
8. As a phone user, I want repeated push delivery and retries not to notify twice, so that unreliable delivery does not become noise.
9. As a phone user, I want later presentation updates to remain silent, so that one alert does not repeatedly interrupt me.
10. As a phone user, I want missed alerts discovered when I open the app, so that push is not the sole recovery mechanism.
11. As a phone user, I want best-effort background recovery, so that missed delivery can be discovered without opening Varde.
12. As a phone user, I want one catch-up notification per connection, so that reconnecting does not flood my phone.
13. As a phone user, I want catch-up counts to exclude already individually announced alerts, so that recovery does not announce them again.
14. As a phone user, I want delayed pushes for catch-up-covered alerts suppressed, so that recovery is not followed by duplicate individual notifications.
15. As a phone user, I want adding a connection to leave its existing history silent, so that setup does not announce an archive.
16. As a phone user, I want notification recovery to use bounded metadata reads, so that it does not download every retained original.
17. As a phone user, I want one failed connection not to block healthy connections, so that partial outages do not hide other alerts.
18. As a phone user, I want background connection failures to retry quietly, so that an outage does not create additional notification noise.
19. As a phone user, I want connection failures visible in the app, so that silent retry does not imply successful recovery.
20. As a phone user, I want tapping a notification to open its alert directly, so that I do not have to find it in history.
21. As a phone user, I want an unavailable alert screen to explain the failure and offer retry, so that a generic notification remains actionable.
22. As a phone user, I want notification taps to work after app process death, so that navigation does not depend on an in-memory inbox row.
23. As a phone user, I want a notification toggle per connection, so that I can mute one source without losing inbox access or recovery.
24. As a phone user, I want re-enabling notifications to leave the muted backlog silent, so that enabling does not trigger a catch-up flood.
25. As a phone user, I want connection renaming reflected in notification source labels, so that names remain consistent.
26. As a phone user, I want removing or changing a connection not to revive obsolete notifications, so that old requests cannot be attributed to new access settings.
27. As a phone user, I want notification permission denial not to break the inbox, so that Android permission choices do not make alert reads unusable.
28. As an operator, I want the backend to retain notification work across restarts, so that accepted alerts do not depend on an uninterrupted process.
29. As an operator, I want duplicate sender submissions not to schedule another notification, so that retrying ingress remains safe.
30. As an operator, I want credential revocation to disable future push dispatch for that installation, so that revocation applies beyond HTTP reads.
31. As an operator, I want separate credentials per Android installation, so that devices can register and revoke delivery independently.
32. As an operator, I want push-provider replacement not to change alert retention or mobile notification policy, so that FCM is not a permanent domain dependency.
33. As an operator, I want provider outages not to block submissions or reads, so that external delivery cannot compromise the durable backend.
34. As an operator, I want provider credentials, device destinations and alert content excluded from ordinary logs, so that delivery troubleshooting does not expose secrets.
35. As a developer, I want deterministic notification and recovery tests without Google credentials or homelab access, so that ordinary development can continue locally.
36. As an operator, I want live delivery and Android scheduling checks reported separately, so that automated success is not mistaken for observed phone delivery.

## Implementation Decisions

### Agreement and technical proposals

The notification behaviour, testing boundaries, protocol, scheduling, persistence and lifecycle details in this specification are approved implementation requirements. They are not claims of existing support.

This increment supersedes the earlier inbox milestone's foreground-only fetching and no-persisted-cursors restrictions only where necessary for notification recovery. It does not supersede transport security, bounded history browsing, source identity, stale-state handling or plain-text rendering.

### Ownership and replaceability

- Extend the existing lifecycle Module to own authenticated change recovery, installation destinations, durable push work and retry accounting. Keep SQLite and migrations inside it; do not introduce a generic repository Interface, message broker or another server process.
- Extend the existing HTTP Adapter for destination and recovery operations. It remains responsible for wire validation, request deadlines and sanitized responses.
- Add one Android notification coordinator owning per-connection recovery, notification eligibility, deduplication, effect ordering and durable bookkeeping. Push callbacks, background workers and app-opening recovery call this same coordinator rather than implementing separate policies.
- Keep inbox browsing and notification recovery separate. Background recovery must not splice arrivals into an active older-history snapshot or silently reset the person's scroll position. Existing refresh remains the way to establish a fresh browsing snapshot; notification taps may open an alert directly.
- Inject the backend transport, push-provider Adapter and Android notification-output Adapter at the approved test Seams. Keep provider-specific registration and SDK types at the edges. Shared lifecycle and Android notification policy must not depend on Firebase classes or FCM-specific error codes.
- Adding another push provider may require another Android registration/receive Adapter and operator configuration, not merely changing a server URL. Provider neutrality does not promise a second working provider in this increment.

### Delivery timing and provider envelope

- Commit the original alert, acceptance change and eligible installation push work in one transaction. Dispatch only after commit. Never wait for fallback or future rich-view generation before scheduling the initial push.
- Identical sender retries resolve the existing alert without another change or delivery chain. Presentation completion is recoverable as a change but does not schedule a new user-interrupting push in this increment.
- Bind each push to a current registration session. Its application data consists only of a version, an opaque registration routing identifier and the stable alert ID. Never trust a push-supplied URL, label or alert body.
- Resolve routing identifiers against locally persisted current registrations. Unknown, removed, muted or obsolete registrations cannot trigger reads or notifications. The provider message is a hint, not authorization to read a backend.
- The initial Adapter uses FCM data messages. Use Android delivery priority appropriate for immediately posting a user-visible notification; verify current FCM restrictions when implementing. Do not rely on long-running fetch work inside the receive callback. Post the generic notification promptly and enqueue bounded authenticated fetching separately.
- FCM delivery acknowledgement is not evidence of receipt or display. Do not promise an exact delivery deadline, exactly-once external delivery, or operation while the app is force-stopped or Android blocks execution.
- The generic notification uses only locally known source identity. Fetched title and summary replace it without another sound or vibration. Do not let app foreground state suppress normal delivery.

### Installation registration and lifecycle

- Use the existing installation identity and credential role. Destination operations require a current installation credential and can affect only its own installation. Sender credentials receive `403`; missing, invalid or revoked credentials receive `401`.
- Support one active provider destination and registration session per backend installation. Operators issue a distinct installation identity for each physical app installation. The same physical phone can register separately with several backends.
- Define idempotent registration, destination replacement and removal operations. A provider token update within the same registration session must not reset notification eligibility or lose changes.
- A fresh notification session atomically establishes a committed change baseline and enables future push eligibility. Persist the proposed local routing/session identity before sending the registration request. Retrying an uncertain registration must return the original baseline for that session rather than skipping intervening alerts.
- Coordinate the registration baseline with recovery: alerts before the baseline are silent, and every acceptance after it is either eligible for push or recoverable through changes, including arrivals during setup. A signal received before registration confirmation cannot advance recovery or bypass initialization; recovery covers the overlap.
- On re-enabling a muted connection, create a new notification session and current baseline before displaying new notifications. If the backend is unavailable, show activation as pending rather than announcing an unknown muted backlog. Preserve inbox access and retry activation later.
- Token refresh, reconnects and ordinary process restarts preserve the session and recovery position. Token replacement invalidates obsolete destination work without re-announcing history.
- Removing a destination disables its queued future work. Installation credential revocation disables future dispatch for that installation, as required by the existing architecture contract. An already dispatched provider message cannot be recalled.
- Registration must not silently backfill push work for historical alerts. An unconfigured provider or unavailable destination must not prevent normal backend startup, submissions or reads; expose safe configuration status rather than claiming push works.

### Durable push scheduling

- Own queued work, destination/session generation, attempt reservation and next eligible time in SQLite. Recheck installation access, session validity and destination before dispatch.
- Perform provider calls outside database transactions and outside HTTP ingress requests. Enforce finite timeouts and bounded concurrency; failures must not starve ordinary reads or the existing fallback worker.
- Follow the existing architecture policy: one initial provider attempt plus at most three transient retries, with approximately 5-second, 30-second and 2-minute delays and small jitter. Honor supported bounded provider retry instructions; do not retry earlier than instructed or add hidden Adapter retries.
- Persist attempt reservations before external calls. An interrupted reservation counts as an uncertain attempt; retries may deliver the same hint again. Do not claim exactly-once dispatch.
- Permanent destination failures disable that destination until repaired. Transient failures retry within budget; exhaustion leaves canonical recovery intact. Do not classify provider-wide configuration failures as evidence that every phone token is invalid.
- Keep provider delivery accounting separate from presentation work and any future paid-model budget. Ordinary tests never contact FCM or other external providers.

### Authenticated change recovery contract

- Add installation-authenticated operations to establish a current checkpoint and read bounded changes after an opaque cursor. The proposed HTTP operations below define the implementation-facing contract; review them with the complete specification.
- A checkpoint is the committed end of the change stream, obtained atomically with its store identity/generation. It does not download historical originals. Notification registration uses this same checkpoint mechanism transactionally rather than performing a racy separate read.
- Extend the existing committed change ledger; do not infer new arrivals from device clocks, timestamp-ordered history pages or the latest visible inbox row. Distinguish initial acceptance from later presentation changes.
- Change entries identify a stable alert ID, event kind and presentation revision. They do not contain original bodies or private diagnostics. Recovering an acceptance makes the alert eligible for catch-up; a later presentation change never counts as another alert.
- Each recovery pass fixes a committed upper watermark and pages changes after its starting cursor through that watermark in ascending change order. New arrivals beyond that watermark belong to a subsequent pass. Default page size is 50, maximum 100; bound decoded cursor and request/response sizes.
- Every successful page returns entries, a checkpoint representing exactly the committed progress returned, and an unambiguous indication of whether the fixed pass is complete. Empty pages must not falsely complete a pass or skip concurrent arrivals. Never hold a database transaction between requests.
- Bind opaque cursors to their version, store identity/generation, stream position and any continuation horizon/options. Authenticate them with persisted store-owned cursor integrity material. Reauthenticate every page; cursors are not access credentials. Reject malformed, tampered, cross-store or mismatched continuations safely, with no successful partial advancement.
- An ordinary backend restart preserves valid cursors. A detected replacement/invalidation requires explicit reinitialization at a current baseline, with visible safe status and silent existing history; never scan the archive or treat all history as new.
- An undetected rollback to an older backup is not solved by an opaque cursor alone. Preserve the current backup/restore limitation and document it; this increment does not claim automatic detection or safe continuation across arbitrary restored copies.
- Versioned migrations preserve originals, timestamps, sender namespaces, credentials, unfinished fallback work and existing change order. Failures roll back rather than serving a partially migrated store.
- HTTP errors retain the existing role separation, deadline, cursor validation and safe storage-failure conventions. Final wire examples and the real Android transport tests must agree before implementation is considered complete.

### Proposed HTTP operations

All operations authenticate against the calling installation, never an installation ID supplied by the client. JSON request bodies reject unknown fields, duplicate fields and invalid types; query parameters follow the existing strict history validation conventions. Bound JSON request bodies to 16 KiB and individual destination tokens to 8 KiB UTF-8. Unsupported provider names and malformed identifiers are safe `400 invalid_arguments` responses. Never echo destinations in responses or errors.

- `GET /v1/installation/push` returns `200` with `registrationId`, `provider`, `enabled` and `baselineCursor`. With no active registration, identifiers and baseline are null and `enabled` is false. Return a quoted integer mutation version in `ETag`, including for an installation with no destination. Do not return the provider destination token.
- `PUT /v1/installation/push` accepts `registrationId`, `provider` and `destination`. The registration ID is a client-generated opaque random 128-bit identifier encoded as 32 lowercase hexadecimal characters. The first supported provider is `fcm`; its destination is a nonempty registration token. Require the current version in `If-Match` and a fresh opaque `Idempotency-Key` in the same identifier format. A new session atomically replaces the previous one, invalidates old work, and captures a fresh baseline. Updating the destination under the current session preserves its original baseline. Return `200` with the same safe representation and new `ETag`.
- A retry using the same installation, idempotency key and structurally identical request returns the previously committed result before checking the now-obsolete precondition. Reusing a key with changed content returns `409 idempotency_conflict`. Never let a retry reactivate a retired session; a retired session's old result is only an acknowledgement, not a state mutation. Preserve sufficient registration/session history to enforce this across restart.
- `DELETE /v1/installation/push` requires `If-Match` and `Idempotency-Key`, disables the destination and invalidates its queued work, and returns `200` with the disabled representation and new `ETag`. Exact retries are idempotent. Missing or malformed mutation headers return `400 invalid_arguments`; an obsolete version returns `412 registration_changed` so the client reloads current state before reconciling its desired configuration. This fencing prevents late token updates, deletes and uncertain older registrations from overwriting newer state.
- `POST /v1/changes/checkpoint` has no body and returns `200` with `cursor` at the committed stream end. Use it for silent initialization/reset where registration is not being enabled. Enabling registration instead uses the transactionally returned registration baseline.
- `GET /v1/changes` requires `cursor` and accepts `limit` from 1 through 100, default 50. Return `200` with `items`, `nextCursor` and Boolean `hasMore`. Each item contains `alertId`, `kind` (`accepted` or `presentation`) and nonnegative integer `revision`, describing that immutable ledger entry. An acceptance entry has revision zero even if its current detail already has a later revision. The existing detail endpoint returns current content.
- For the first page after a completed checkpoint, fix the pass horizon transactionally. If `hasMore` is true, `nextCursor` includes the same horizon and exact returned progress. If false, `nextCursor` is a completed checkpoint at that horizon and starts a new pass on the next request. `nextCursor` is never null, including an empty stream. A continuation uses the same limit; changing it returns `400 invalid_cursor`. Empty completed passes return an empty list and a valid checkpoint without skipping future arrivals.
- Invalid, oversized, tampered or cross-store change cursors return `400 invalid_cursor`. A positively detected invalidated store generation returns `409 sync_reset_required`. Both prohibit cursor advancement; Android may obtain a fresh silent checkpoint after surfacing the reset rather than interpreting rejection as empty history. Generic storage failures remain safe retryable `503` responses.

The push envelope uses string fields `version` (initially `1`), `registrationId` and `alertId`, and no original-content fields. Unknown versions or invalid identifiers are ignored without network requests. Alert identifiers must pass the same stable-ID validation as authenticated detail reads. Local connection/access generation remains the authority for routing a valid registration ID.

### Android persistence and recovery

- Persist app-private per-connection notification settings, registration generation, opaque recovery cursor, in-progress recovery horizon/counts, alert notification identities and delivery dispositions. Protect routing/destination information and exclude it from backup/export along with existing credentials.
- Retain bookkeeping across process death so restart does not announce already handled alerts. Do not persist original alert bodies or introduce an offline archive. Keep metadata payloads minimal and indexes bounded per operation; do not prune deduplication records using an arbitrary time-to-live that lets delayed hints notify again.
- Atomically persist processed changes, notification dispositions, catch-up aggregation and cursor advancement. A failed page, cancellation, app crash or obsolete connection response must not silently consume unrecorded changes.
- Serialize/coalesce work per connection across push callbacks, app opening and background workers. One slow or failed source does not block other connections. Reuse bounded authenticated transport and prevent uncontrolled parallel fetches or retry loops.
- Recover on app opening and through unique, network-constrained Android background work. Use Android's supported periodic scheduling rather than an exact alarm or permanent foreground service. Scheduling is best-effort and affected by power management, connectivity and user settings; document force-stop limits.
- Process a finite number of bounded pages per worker execution, persist unfinished progress and resume later. Complete a fixed recovery pass before finalizing its exact catch-up count; a long pass may span worker executions without blocking the UI.
- Notification output and a local transaction are not one atomic system. Reserve an interruption durably before asking Android to post it, and use stable notification identities. After an uncertain interruption attempt, prefer silent reconciliation over a possible second sound. A process crash can therefore lose an interruption; retained inbox/recovery data must remain correct. Do not claim exactly-once human notification.

### Individual notifications and catch-up

- Use source-scoped identity: local connection ID plus alert ID, qualified by current access/registration generation where necessary. Identical server alert IDs from different connections must not collide.
- A normal valid push for an eligible unseen alert produces its generic individual notification immediately. Fetch through the configured trusted HTTPS endpoint; success silently enriches the same notification, while failure retains the generic notification and schedules bounded retry/recovery.
- One notification identity receives at most one requested audible/vibrating interruption. Duplicate signals, detail refreshes and later revisions update silently. Respect system permission, channel and user settings; Varde cannot guarantee that Android makes a sound.
- Recovery aggregates eligible acceptances not already individually announced or covered by catch-up. Announce one source-labelled summary per completed recovery pass, with the number of newly covered alerts. For a one-alert recovery, the summary may say “1 new alert from Home”; it is still catch-up, not a delayed individual notification.
- Persist catch-up coverage so a late individual push does not notify again. Race-test a push arriving before, during and after recovery commitment. Recovered presentation changes update known metadata silently and do not increase counts.
- Catch-up aggregation never crosses connections. Reuse/update an existing active connection summary without repeatedly interrupting for retries of the same pass. A genuinely later recovery pass containing new missed alerts may notify again.
- A notification dismissed by the person must not reappear merely because a fetch, retry or presentation update completes. Keep interruption disposition separate from whether a notification is currently visible.
- Notification toggles default on subject to Android permission. Disable locally immediately, cancel outstanding notification output for that connection, invalidate its notification session and remove its remote destination on a best-effort basis. Recovery and manual inbox reads remain available while muted; all changes discovered in that state are silent.
- Background fetch/recovery failures do not create outage notifications. Retain connection-specific status and actionable authentication/configuration failures in the app, without deleting saved settings or stale browsing content.

### Privacy, permission and navigation

- Request Android notification permission using an understandable user-facing explanation at the appropriate supported OS boundary. Denial or disabled channels must not break connection setup, reads or recovery, or cause repeated permission prompts. Surface a settings action.
- Treat notification permission denial as no-interruption eligibility, not an accumulating backlog to announce later. On enabling notifications after denial, establish a current baseline and resume future eligibility rather than announcing silent history. Suppress provider dispatch where practical when notifications cannot be shown; always gate output locally.
- Use private lock-screen visibility and an explicit public version containing only a generic message and current connection name. Do not put title, summary or future rich content in the public version. Respect stricter system settings and retain the app's existing screen-capture protections. Device/user overrides are outside the app's guarantees.
- Notification intents contain only stable non-secret identifiers. Use explicit immutable intents and distinct identities so one connection or alert cannot overwrite another's target. Reject malformed, removed or obsolete targets without performing arbitrary network requests.
- An individual tap opens that source alert directly even if its row is absent from memory or older than the current inbox page. Fetch the original on demand. If unavailable, identify the connection, explain the error and offer retry; do not invent a title or silently navigate elsewhere.
- A catch-up tap opens the combined inbox and refreshes through the existing safe browsing path, retaining source labels and partial-outage behaviour. It need not add connection filtering in this increment.
- Renaming preserves source identity and silently updates active labels. Removing a connection cancels its scheduled work and visible notifications, clears its local notification state and best-effort unregisters its destination without remotely deleting alerts or revoking credentials.
- URL or credential replacement invalidates old work/results and registration, cancels old notifications, and establishes a new silent baseline for the new access context. Never apply a previous response or notification tap to a replacement endpoint.

### Transport and operator configuration

- Preserve release HTTPS validation, redirect refusal, safe base-path handling and no persistent credential-bearing HTTP cache. Do not add LAN HTTP support or weaken the loopback server listener for local push tests.
- Keep FCM service credentials server-side, outside images and Git. Provide explicit operator configuration using private mounted secret files or an equivalent documented credential source; do not log credential paths containing secrets or credential contents. Public Firebase client configuration is not a substitute for server credentials.
- Implement provider-authentication refresh and response classification inside the FCM Adapter, with sanitized lifecycle outcomes. Destination tokens and registration identifiers must not appear in ordinary logs or user-visible errors.
- Provide reproducible fake-provider/local development instructions and a separate opt-in real-FCM/device procedure. Preserve ordinary Android builds and tests without private Firebase configuration or automatic live sends; document the distinct configuration needed to build/run the real provider path.
- Do not provision Google projects, upload secrets, change homelab settings or send live test pushes without explicit operator approval. No paid calls are required by ordinary verification.

## Testing Decisions

### Approved test Seams and prior art

Test caller-facing behaviour rather than private storage helpers, SDK internals or worker class names. Use the existing lifecycle Interface and real temporary file-backed SQLite; the real authenticated HTTP Adapter; the Android coordinator with recorded notification effects; and the real Android transport against temporary Go backends.

Existing prior art includes backend credential/authorization, commit-failure, migration, subprocess crash and worker-recovery tests; Android inbox partial-outage and connection-management tests; the two-real-Go-backend TLS transport harness; and existing physical-device UI/security tests. Extend these patterns rather than replacing the implementation with mocks. Use injected clocks, provider results and scheduler triggers where time/external output varies.

### Backend acceptance cases

1. Only current installation credentials can register, update or remove their own destination and read changes. Cross-installation, sender and revoked access fail safely.
2. Registration establishes a gap-free silent-history baseline. Concurrent submission, uncertain registration retry and token refresh neither skip eligible arrivals nor reset the baseline.
3. Acceptance and eligible push work commit atomically. Identical sender retries and terminal fallback produce no additional initial-notification work.
4. Crash before/after acceptance, attempt reservation, external success and completion preserves durable state and retry limits. Provider calls never hold a storage transaction open.
5. Transient/permanent/configuration failures classify correctly; bounded delays, retry exhaustion and interrupted attempts survive restart. Destination replacement, removal and installation revocation invalidate stale work.
6. Slow/unavailable fake providers do not block authenticated reads, normal submissions or healthy work. No configured provider is a supported non-push mode.
7. Recovery returns bounded, ordered acceptance and presentation changes without archive bodies. Concurrent arrivals during checkpointing/pagination appear exactly in the appropriate pass, with no gap.
8. Empty streams, multiple pages, fallback updates, clock reversal, malformed/oversized/tampered/cross-store cursors, restart and detected cursor invalidation have explicit safe outcomes.
9. Populated existing databases migrate without losing originals, credentials, duplicate behaviour, pending work or ledger order; failed migration rolls back.
10. Ordinary errors and logs contain no alert content, provider credentials, bearer secrets or destination tokens.

### Android coordinator and transport acceptance cases

1. A valid normal push posts a generic source-labelled notification before fetching; success enriches it silently and failure leaves it actionable. All priorities and app foreground states follow the same policy.
2. Duplicates, reordered callbacks, simultaneous worker/app-opening recovery, late fetches, process restart and presentation updates do not request another interruption for one alert.
3. Multi-page recovery produces one correctly counted summary per completed pass and connection, excluding individually announced alerts. Late pushes for covered alerts remain silent.
4. Initial registration, mute, re-enable, permission denial/re-enable, invalidated cursors and access replacement do not announce old or muted history, including concurrent arrivals at each boundary.
5. A crash at each cursor/effect boundary does not lose recorded recovery progress or cause a second requested sound. Tests distinguish canonical recovery correctness from the impossibility of atomic OS display.
6. One failed connection does not prevent healthy notification/recovery work. Worker runs are bounded, cancellation-safe and resumable; background failures do not produce outage notifications.
7. Registration/token rotation, rename, removal and access-generation changes cannot resurrect obsolete work or redirect a fetch to another source. Same alert IDs from two connections remain distinct.
8. Dismissed notifications do not reappear during silent enrichment. Catch-up summaries and individual notifications have distinct stable identities and safe tap targets.
9. Real Android HTTPS transport and actual Go servers agree on registration, checkpoint and change pagination, including concurrent alerts, restart, duplicate ingress, revocation and partial failure. Use synthetic credentials and test-only TLS trust, never relaxed release validation.
10. Recovery does not download the archive, prefetch recovered bodies, corrupt an active history snapshot or silently reset browsing. Direct notification reads work without an inbox row.

### Android platform and operator acceptance

- Instrumented tests verify notification channels/permission handling, private/public lock-screen content, silent replacement, explicit immutable navigation intents, cold-start alert taps, unavailable-detail retry and connection toggles.
- Verify that secrets and alert content do not enter public notification versions, navigation payloads, logs, backups or persistent HTTP caches. Existing screen-capture/Keystore security tests remain required.
- Separate fake-trigger instrumentation from genuine FCM delivery. Only an observed opt-in run can claim real delivery, including app-background receipt, source routing and content fetching over trusted HTTPS.
- On a supported device, record best-effort recovery after missed push and process death, behaviour under denied permission and constrained networking, and force-stop/OS scheduling limitations. Do not promise an exact periodic deadline from a successful sample.
- This device verification can use temporary local backends behind an Android-trusted test HTTPS path; it does not require the operator's homelab or n8n. Never claim that it closes the independent #16 deployment gate.

### Definition of done

- Approve this specification, including the proposed public wire contract and technical decisions, before marking implementation tickets ready. Add matching wire examples to the operator/developer reference during implementation; compatibility tests enforce registration/session idempotency, cursor pagination, limits and error categories.
- Backend tests, race tests, vet and restart demonstration pass. Android builds, lint, JVM, real-Go transport and instrumented platform tests pass with the documented toolchain. Report unavailable device checks explicitly.
- Deterministic tests cover every notification policy and crash/race boundary above without live credentials, external provider access, private content or paid calls.
- Documentation distinguishes implemented automated behaviour, fake-provider demonstrations and observed live provider/device results. Shipping notification delivery as verified requires the opt-in live checks; unavailable live checks remain open, not waived.
- Local automated implementation may be completed before real FCM/device acceptance. Existing homelab acceptance remains independently deferred.

## Out of Scope

- Homelab deployment, Pangolin provisioning, n8n workflow setup, or closing the earlier remote acceptance gate.
- A second implemented push provider, guaranteed Google-free delivery, guaranteed timing, exact alarms, permanent foreground services, or delivery while Android prevents execution.
- AI integration, typed-data/rich-view generation or rendering, and notifications for rich-view completion. Preserve the silent-update policy for future work.
- Full offline alert storage, automatic archive downloading, historical body prefetch, server-side federation, or automatic insertion into an active older-history browsing session.
- Per-priority filtering, custom sound rules, connection-health notifications, alert grouping as a domain feature, actions, acknowledgements, or remote deletion.
- General backup/restore tooling, automatic old-backup rollback detection, multi-device account management, and remote administration.

## Further Notes

A successful push send does not mean a phone displayed a notification. A displayed generic notification does not mean its backend is currently reachable. Neither condition changes whether the original alert was durably accepted. Keep these three facts separate in UI wording, tests and operator evidence.

The interview approved replaceable FCM delivery, immediate generic notifications, silent enrichment, all-priority/foreground notification, private lock-screen content, per-connection catch-up, background/app-opening recovery, retryable direct navigation, per-connection mute with silent backlog, and quiet background failures. The complete specification, technical details, six-ticket breakdown and tracker publication were subsequently approved. This approval does not authorize changing or closing existing issues, provisioning Google resources, uploading secrets or sending live pushes.
