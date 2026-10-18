# Foreground missed-alert recovery verification

## Automated evidence

Issue #21 was exercised with synthetic data and no homelab, Google credentials, paid calls or real push:

- **66 debug JVM tests and 66 release JVM tests passed.** Recovery cases cover silent initialization, bounded multi-page resumption, reopen of persisted bookkeeping, per-source outages, cancellation, push/catch-up races, permission suppression, uncertain output, cursor reset, obsolete access failures, safe navigation, rename/removal and version-one journal compatibility. Invalid recovery counters fail closed.
- **4 real-Go transport/inbox tests passed.** Two independent Go/SQLite instances behind test-only HTTPS proxies verify cursor horizons through concurrent arrivals and restart, cross-store rejection, revoked credentials and coordinator catch-up counts. Recovery issues no archive or original-body reads; duplicate ingress and source recovery do not announce history again.
- **23 emulator instrumentation tests passed; 1 operator-only live test skipped.** The dedicated API 36.1 emulator ran at 1080×1920, matching CI's smaller viewport. New platform checks cover count-private public notification content, immutable targets, app-opening recovery, catch-up inbox navigation/refresh and connection-specific recovery retry without outage notifications.
- Debug/release APK builds and both lint tasks passed with no lint errors.
- Backend tests, race tests, vet and restart demo passed with Go 1.26.0. Backend tests cover immutable acceptance/presentation ledger entries, fixed-horizon paging, parameter/cursor errors, credential reauthentication, ordinary reopen, version-one migration and a positively detectable old-backup rollback.

The navigation test uses the activity ViewModel's public notification entry point for an already-open activity. Actual notification-output tests verify immutable explicit OS intents separately. Starting another instrumentation task through a PendingIntent was not used as a substitute for a real phone tap; no physical-device notification behaviour is claimed.

## Retained boundaries

Recovery is triggered by activity start or explicit retry, not a background worker. It has a finite four-page budget per connection; incomplete passes retain counts/cursors and expose a continuation action. Automatic insertion into an active history snapshot, original-body prefetch and offline original storage are not implemented.

SQLite reuses the existing ledger and store-owned cursor key. No new schema version is required. The existing version-one migration remains transactional and retains originals, credentials, duplicate identity and pending fallback work. Arbitrary backup rollback remains unsupported; only cursors/horizons provably ahead of a restored ledger are reset explicitly.

Android writes version-two notification bookkeeping but reads version-one files without replaying existing individual notifications. An older app cannot read the upgraded metadata and fails closed for notifications; connection settings and backend originals are unchanged.

OS notification output cannot share a transaction with local progress. Tests verify durable interruption reservations and no repeated requested interruption after uncertainty, not exactly-once human delivery. Android process recreation is simulated by reopening the journal in a new coordinator; no killed-process or power-loss guarantee is claimed.

FCM, remote registration and notification controls remain #22/#23 work; periodic background scheduling remains #24. The independent homelab HTTPS/n8n acceptance gate #16 remains open and unchanged.

See the [foreground recovery guide](../how-to/recover-alerts.md) for API examples, privacy constraints, reproduction and failure handling.
