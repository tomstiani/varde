# Local Android notification verification

## Verified scope

Issue #20's local synthetic signal path was exercised without Firebase, homelab access, n8n or a physical phone. This is not observed provider delivery or background recovery.

- **53 debug JVM tests and 53 release JVM tests passed**, including coordinator tests using real temporary metadata files, delayed/failing reads, duplicate/restart handling, dismissal, permission suppression, stale access, session/source isolation, uncertain output and asynchronous OS publication.
- **2 real-Go transport/inbox tests passed**, retaining actual temporary SQLite databases and test-only HTTPS proxies. This slice does not add a provider or change-stream endpoint.
- **20 emulator instrumentation tests passed; 1 opt-in live test skipped** on Medium Phone API 36.1 / Android 16. New checks cover private/public notification versions, silent enrichment through Android output, immutable isolated tap identities, cold-start navigation without an inbox row, unavailable-detail retry and access-replacement fencing. Existing inbox/settings/security checks remain green.
- Debug/release builds and both lint tasks passed. Lint retains dependency-update/application-icon/style warnings; no lint errors were reported.
- Backend tests, race tests, vet and restart demo passed unchanged.

The navigation test uses an isolated app-private directory and process-local debug runtime fixture. It does not replace the user's encrypted connection file or contact a live endpoint. Managed connected tests ran only on the dedicated emulator, not the previously configured physical phone.

## Limits

- No real FCM send/receipt, physical-phone notification observation, denied-permission system-dialog automation, power-management guarantee or actual killed-process restart was demonstrated. JVM tests reopen the persisted journal to exercise process-recreation semantics; platform navigation starts a fresh activity and checks activity recreation.
- Lock-screen privacy is verified through Android notification visibility/public payloads, not an observed locked physical-phone screen. System/user visibility overrides remain outside the app's guarantees.
- Synthetic activation is local and debug-only. Registration controls, durable backend push work, change cursors and background scheduling are later tickets.
- An interruption reservation and Android display are not atomic. Tests prove no second requested interruption after uncertainty, not exactly-once human delivery.
- The original remote HTTPS/n8n acceptance gate #16 remains deferred and unchanged.

See the [local notification test guide](../how-to/test-local-notifications.md) for reproduction and safety constraints.
