# Recover missed alerts when opening Android

Varde checks each saved connection for missed alert acceptances when its activity starts. It posts one catch-up notification per connection and completed recovery pass, not one notification per recovered alert. This is foreground recovery: there is no FCM delivery, polling, periodic worker or notification toggle yet.

## Use recovery

1. Open Varde once to establish a silent checkpoint for each connection. Existing history does not produce notifications. Android must allow alert notifications to display catch-up; denied notifications leave inbox reads usable and recovered history silent.
2. Submit synthetic alerts while the app is closed, then reopen it. Newly recovered alerts produce a source-labelled count without downloading their original bodies. A single recovered alert still produces catch-up rather than an individual notification.
3. Tap catch-up to open and refresh the combined inbox. Without that explicit action, recovery does not insert arrivals into an older-history snapshot or reset browsing position. Open an alert normally to fetch its original.
4. If recovery fails, open **Settings**. Each failed connection shows **Alert recovery** and **Retry recovery <name>**. A healthy source still recovers independently. No connection-outage notification is posted.

Initial checkpointing must succeed before that source has an active recovery position. A source that has never initialized does not announce its archive when it eventually becomes reachable. Saving settings remains independent of network availability; open the app again or use the recovery retry action to initialize a corrected source.

Each app-start pass makes at most four change-page requests per connection, using pages of 50 changes. An unfinished fixed-horizon pass persists its cursor and accumulated count, shows a more-changes-remain status in settings, and resumes on a later opening/retry. It announces its exact catch-up count only after completion. The first initialization uses only one checkpoint request. Four shared backend request slots and finite transport deadlines bound concurrency; there is no automatic endless retry.

Counts exclude already individually announced alerts and earlier catch-up coverage. Later presentation changes do not count as new alerts. Delayed/repeated synthetic signals for covered alerts remain silent. A genuinely later recovery pass can interrupt for genuinely new missed alerts.

## Backend wire reference

For the separate backend registration API, see [Manage a backend installation's push registration](register-push.md). Android recovery does not use registration yet.

All operations require the current installation bearer credential. Sender credentials receive `403`; missing, invalid and revoked credentials receive `401`. Every response uses `Cache-Control: no-store`. The existing trusted-HTTPS, redirect-refusal and base-path rules still apply.

### Establish a silent checkpoint

`POST /v1/changes/checkpoint` accepts no query parameters or request body. A successful `200` response contains one opaque `cursor`, for example:

```json
{"cursor":"<opaque-server-checkpoint>"}
```

The checkpoint captures the committed end of the change ledger transactionally, not the latest event timestamp or visible inbox row. Do not repeat initialization during ordinary process restart; persist and resume the returned cursor.

### Read a bounded recovery pass

`GET /v1/changes?cursor=<url-encoded-cursor>&limit=50` returns:

```json
{
  "items": [
    {"alertId":"alert_<stable-id>","kind":"accepted","revision":0},
    {"alertId":"alert_<stable-id>","kind":"presentation","revision":1}
  ],
  "nextCursor":"<opaque-next-position>",
  "hasMore":false
}
```

The example identifiers/cursors are placeholders, not valid tokens. Items contain no title, summary or original body. An acceptance entry remains revision zero even if current authenticated detail already shows fallback revision one. Presentation entries describe later immutable ledger entries.

- `cursor` is required. `limit` defaults to 50 and permits 1–100. Reject unknown/repeated/empty parameters and malformed limits with `400 invalid_arguments`.
- The first request after a completed checkpoint fixes a committed upper horizon. Pages return changes in ascending ledger order through that horizon. Concurrent later changes belong to a subsequent pass.
- `hasMore=true` means `nextCursor` continues that same horizon with the same limit. The completed page returns `hasMore=false` and a non-null checkpoint cursor for starting a later pass. An empty completed pass returns `items: []` and a valid cursor too.
- Signed cursors are bound to the store-owned integrity key, a separate change-stream domain/version, exact progress, and any continuation horizon/options. That persisted key provides the store namespace; ordinary restart does not replace it. History cursors cannot be used as change cursors. No database transaction remains open between HTTP calls.
- Malformed/tampered/cross-store/oversized or option-mismatched cursors return `400 invalid_cursor`. Raw query input is limited to 4 KiB and decoded cursor length to 2 KiB. Invalid responses do not advance local progress.
- A valid checkpoint or horizon ahead of the current ledger is positively invalidated and returns `409 sync_reset_required`. Android retires only that request's current source session, shows the recovery-reset status, then establishes a new silent checkpoint on retry/opening. Late failures from replaced access cannot reset its replacement.
- Other storage failures use the existing safe retryable `503` convention. Client errors are locally defined messages, not copied server text or cursor values.

The current database schema already contains the immutable change ledger and persisted cursor key. No additional schema migration is needed for this slice. The existing transactional version-one history upgrade supplies the same prerequisites; tests retain credentials, sender identity, originals, unfinished fallback work and change order through that upgrade.

## Persistence, privacy and limits

Android atomically replaces its private backup-excluded notification journal when committing cursor advancement, per-alert dispositions, accumulated counts and notification reservations. It retains no original alert bodies. Version-one local notification journals remain readable; a write upgrades bookkeeping to version two. Rolling back to an older app leaves that older notification reader unable to use version-two metadata, failing closed rather than replaying it; existing connection settings and backend data are unchanged.

An interruption is reserved before requesting OS output. A crash between reservation and display can miss an interruption, but retries must not request another one for the same pass. OS display and a local transaction are not atomic. Dismissing catch-up does not undo coverage; late changes or hints do not recreate that pass's notification. Lock-screen public content stays generic and source-labelled; Android/system visibility settings still apply.

Read access and normal history remain available after a recovery error. Endpoint/credential replacement or removal invalidates old results and navigation; renamed sources update active labels silently. There is no offline content archive, remote destination registration, muted-backlog control or background scheduling in this slice.

A cursor does not solve arbitrary backup rollback. The implementation detects positions/horizons ahead of a restored ledger, not every old-backup restoration. Supported backup/restore tooling and general rollback detection remain deferred; do not claim otherwise.

## Verify locally

Use the documented backend and Android toolchains:

```sh
(cd backend && go test ./... && go test -race ./... && go vet ./...)
(cd android && ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:goHistoryTest)
```

On a dedicated emulator, run `:app:connectedDebugAndroidTest` to check catch-up output, app-opening recovery, safe navigation and source-specific retry. Managed instrumentation removes its app/test installation afterward; use neither an unrelated physical phone nor important saved settings. Coordinator tests use real temporary metadata files; transport tests use two actual temporary Go/SQLite backends behind test-only HTTPS proxies. Ordinary checks require no homelab, Google credentials, paid calls or real push.
