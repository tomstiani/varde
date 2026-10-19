# Test local Android alert notifications

Exercise the first notification slice without Firebase, your homelab, n8n or a physical phone. The debug app can simulate an incoming signal for a saved connection, then use the normal backend transport to fetch the original.

**Scope:** the synthetic signal path from issue #20. [App-opening change recovery and catch-up](recover-alerts.md) now coexist with it but are a separate path. [Registration and notification controls](control-notifications.md) are now implemented separately, with activation pending until a token source is configured. FCM and background delivery are not implemented. A simulated signal is not evidence of genuine push delivery.

## Automated local verification

Use the JDK/SDK prerequisites in the [Android guide](../../android/README.md). Start a dedicated emulator running API 26 or newer. Permission-specific coverage requires API 33 or newer.

From `android/`:

```sh
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=no.varde.app.NotificationOutputTest,no.varde.app.NotificationNavigationTest
```

The managed connected-test task uninstalls its app/test APKs afterward and removes saved settings. **Do not run it on a phone installation whose settings must survive.** Use a dedicated emulator or the direct-instrumentation procedure in the Android guide.

Coordinator tests use actual temporary metadata files and recorded notification output. They exercise immediate source labels, delayed/failing reads, silent enrichment, duplicate/restart handling, dismissal, permission denial, source identity, session replacement, stale access and uncertain OS-output reservations. They contain no production credentials or external calls.

Platform tests use real Android notification output, private/public versions, distinct immutable tap intents, and an isolated app-private synthetic fixture. A fresh activity opens an alert absent from inbox memory, shows an unavailable-detail error, and retries its unchanged original. Fixture settings/transport replace the process-local runtime only during this test; normal connection settings and notification metadata are not overwritten. The tests make no backend or provider network requests.

For release transport and actual Go/SQLite compatibility, also run the existing tests:

```sh
./gradlew :app:goHistoryTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease
```

## Interactive debug demonstration

Use only synthetic alerts and a dedicated debug installation. First configure a synthetic local backend connection and load its inbox. The [two-backend USB fixture](local-acceptance.md) provides an optional physical-phone setup; it is not required for the emulator tests above. With an emulator, use its own `adb -e reverse` mappings for loopback fixtures and enter the synthetic connection through normal settings rather than using the physical-phone-only checker. Release still rejects all HTTP, including loopback; debug allows only literal `127.0.0.1`.

1. Build/install the debug APK, then open Varde and load the synthetic inbox.
2. Open **Settings** and find **Local notification test (debug only)**.
3. Keep that connection's notification toggle on. Select **Android notification settings** and allow Varde's alert notifications. Permission denial leaves the inbox usable and does not trigger repeated permission prompts.
4. Leave **Synthetic alert ID** blank to use the first already-loaded alert from each source. Alternatively, enter a known synthetic backend alert ID. The driver does not enumerate or download an archive.
5. Select **Send local signal for <connection>**. Varde shows a generic source-labelled notification before fetching, then silently replaces it with the original title/summary. A fast local read can make the generic stage too brief to see by eye; delayed-read tests verify the order.
6. Send the same signal again. It must not request another sound/vibration. Dismiss the notification, then repeat: it must not reappear. Use a different synthetic alert for another initial-notification demonstration.
7. To test failure, make the synthetic backend unavailable before sending a signal for a different known alert. Tap the generic notification: Varde opens its source-specific detail screen, explains that content is unavailable, and offers **Retry detail**. Restore the backend and retry.
8. Rename the source: active labels update silently. Removing or replacing access cancels its notifications and fences obsolete requests and taps. Other connections remain independent.

A debug synthetic session is stored locally and reused across app process restarts. No synthetic destination is registered with a server or provider, and this local fixture cannot initialize automatic recovery. The signal driver and its controls are absent from release builds; there is no exported injection receiver, deep-link injection endpoint or external debug HTTP service.

## Privacy and persistence limits

Notifications have private lock-screen visibility and a generic public version containing only the connection name. Device/user settings can impose or override visibility; verify the target device rather than promising universal lock-screen behaviour. The app retains its screen-capture protection.

Only notification metadata is persisted in the app's private, backup-excluded directory: preferences, session/connection identifiers, access fingerprints, alert/notification identities, dispositions and recovery cursors. Configured registration also stores private destinations and pending mutation metadata. It stores no original titles, summaries or backend bearer credentials. Existing credentials remain in Keystore-encrypted settings. OS notifications can retain their displayed content under Android's own controls; this is not an app-level offline archive.

The journal uses atomic replacement, syncs file contents before replacing, and fails closed on unreadable/unsupported metadata or its 8 MiB safety limit. It does not silently erase records or prune them on a timer and risk announcing delayed duplicates. This first slice is not an unlimited-size notification archive or a general backup/durability guarantee.

An interruption is reserved durably before requesting OS output. If the process dies between those steps, Varde prefers a potentially missed interruption over another sound. Push and human-visible notification are therefore best-effort, not exactly-once. Inbox durability remains the backend's responsibility.

No real FCM delivery, background scheduling, trusted remote HTTPS/n8n acceptance, or physical-device notification behaviour is established by this local demonstration. Those checks remain separate.
