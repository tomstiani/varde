## Delivery status

**Implemented:** delivered in [PR #28](https://github.com/tomstiani/varde/pull/28), merged as `41309cd`. [All four hosted CI jobs passed](https://github.com/tomstiani/varde/actions/runs/39142323932); [verification](https://github.com/tomstiani/varde/blob/main/docs/verification/android-missed-alert-recovery.md) records 66 JVM tests per variant, 4 real-Go tests, and 23 emulator passes with one operator-only live check skipped. This is foreground recovery, not FCM, background scheduling or completed #16 acceptance.

## Parent

Part of #19: Android alert notifications and missed-delivery recovery. The approved parent specification is authoritative, including its recovery wire contract and privacy limits.

## What to build

Recover missed alerts when opening Android, using the real backend change ledger and durable local progress. Establish a silent initial checkpoint, then announce one correctly counted catch-up notification per connection and completed recovery pass, without downloading the archive or disturbing older-history browsing.

## Acceptance criteria

- [x] Implement installation-authenticated checkpoint and bounded change reads with the parent's exact request/response semantics, role separation, limits, strict parsing and sanitized failure conventions. Sender, invalid and revoked credentials cannot read recovery data.
- [x] Use server-issued authenticated opaque cursors, not device clocks, history timestamps or visible inbox rows. Pages return immutable acceptance/presentation entries, never original bodies; later presentation changes do not count as new alerts.
- [x] Fix one committed horizon per recovery pass. Ascending continuation has no gaps under concurrent acceptance/completion; empty passes and final checkpoints do not skip future changes. Enforce the default 50/maximum 100 limits and fixed continuation options.
- [x] Reject oversized, tampered, cross-store and option-mismatched cursors safely. Ordinary restart preserves validity; positively detected generation invalidation has the explicit reset outcome. Undetected older-backup restoration remains documented as unsupported.
- [x] Upgrade populated databases transactionally, preserving original content, credentials, duplicate namespaces, unfinished fallback work, ledger order and existing history behaviour. Failed migration leaves the previous store recoverable.
- [x] Initial connection setup/checkpoint is silent. Android atomically persists processed changes, per-alert dispositions, pass aggregation and cursor progress; cancellation, partial failure or process death cannot consume unrecorded changes.
- [x] On app opening, complete/resume a bounded recovery pass and announce one source-labelled summary for previously unannounced acceptances. Exclude individually announced and previously catch-up-covered alerts; a one-alert recovery is still catch-up.
- [x] Serialize push/recovery decisions per connection. Signals racing before, during or after recovery commitment cannot turn covered alerts into later individual interruptions. Presentation entries and duplicate pages stay silent.
- [x] Persist unfinished passes for resumption, without holding a database transaction across HTTP requests. Limit work per execution; one failed connection cannot block healthy connections or generate outage notifications.
- [x] Catch-up taps open and refresh the existing combined inbox. Recovery neither fetches every historical body nor inserts arrivals into an active browsing snapshot or resets scroll position.
- [x] Extend the existing real Android-to-Go TLS harness with two actual temporary databases: concurrent arrivals, multiple pages, restart, duplicate ingress, revocation, invalid cursors and partial outage. Test TLS exceptions remain isolated from release transport.
- [x] Add deterministic coordinator and backend lifecycle/HTTP tests for progress/effect crash boundaries, baseline races and correct counts. Update wire examples and local reproduction instructions; ordinary verification requires no external services or private credentials.

## Integration note

Before the registration/control ticket lands, use the checkpoint operation for local/foreground recovery initialization. That later ticket replaces notification activation with the atomic registration baseline. Preserve coordinator state and the shared checkpoint semantics rather than creating a second recovery engine.
