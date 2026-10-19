# Control notifications for a connection

Use **Settings → Alert notifications** to mute a connection without removing inbox access. Settings show whether notifications are muted, permission is required, activation is pending, or a registration baseline is active.

The coordinator, controls and HTTPS registration transport are implemented. The ordinary app does not include a token-providing SDK yet: its token source is unconfigured, so activation stays pending. Real FCM registration/receipt belongs to #23. Do not interpret these controls, an injected synthetic token or a successful fake send as observed phone delivery.

## Mute and re-enable

1. Open **Settings** and turn off **Notifications for <connection>**. Local output is cancelled and the previous session/taps become obsolete immediately.
2. Continue browsing or recovering that connection. Recovery while muted records progress silently; it does not accumulate a count to announce later.
3. Turn notifications on again. The coordinator persists a new proposed routing identity before contacting the backend.
4. Wait for activation. Only the transactionally returned registration baseline enables future notification output. Earlier and muted history remains silent.

Remote removal is best-effort, independent of the local mute. It uses the old session ID and conditional mutation headers, so a delayed removal cannot delete a newer registration. A failed remote request does not undo local muting. One cleanup pass attempts at most four retired registrations; completed cleanup is recorded rather than repeated indefinitely.

If metadata persistence fails, output is cancelled and fenced within the running coordinator, and settings report the storage problem. Retry the change successfully before assuming the preference will survive process death. A failed disk write cannot establish a durable setting.

## Allow notification permission

When Android permission or the notification channel is unavailable, settings explain that inbox reads remain usable. Select **Allow notifications** explicitly; opening the app does not show a permission dialog automatically.

On Android 13+, Varde durably reserves its one permission request before opening the system dialog. After that request, the action becomes **Notification settings**, avoiding repeated prompts. If permission is already granted, the action opens Android notification settings, including channel controls. Earlier Android versions use the settings path.

When Varde observes permission loss, it invalidates notification eligibility and cancels current output. Permission restoration creates a fresh session/baseline rather than announcing the denied backlog. If the backend or token source is unavailable, activation remains visibly pending.

## Rename, replace or remove access

A rename preserves source identity and silently updates active labels; it does not fetch alert content. Removing a connection cancels output and obsoletes its taps, then attempts remote removal without deleting backend alerts.

URL or credential replacement retires the previous access generation and creates a new silent context. Cleanup uses the previous trusted connection and credential in memory, not the replacement endpoint. Old responses and signals cannot become notifications attributed to the new access context. Ordinary token refresh and reconnect preserve the current session and recovery position.

An incoming signal before registration acknowledgement cannot fetch details, post output or advance recovery. Once the baseline is acknowledged, recovery covers eligible acceptances during setup. Uncertain registration retries reuse the persisted mutation key, content and precondition before reading newer backend state; refresh does not replace the original baseline.

Acknowledged-session reconnects read the current registration even when the SDK token is unchanged. A healthy unchanged registration needs no PUT. A disabled destination or changed ETag is reconciled under the existing session, baseline and recovery cursor. After an explicit `412`, a changed version is retried with a fresh idempotency key. A different active session, or rejection of the old ID at the observed current version, retires local eligibility instead of reviving a removed session. Each call makes at most two mutation attempts; further version races remain pending for a later retry.

## Storage and development fixtures

Notification journal version three stores preferences, permission-request disposition, registration requests/results, cursors and notification dispositions in app-private, backup-excluded storage. It does not store original alert bodies or duplicate backend credentials. Private destinations are bounded UTF-8 values; retired sessions discard their destination and pending mutation key. Versions one and two remain readable and upgrade on write. Adopting controls retires their prior eligibility before establishing a new silent baseline; an older app fails closed on version three rather than replaying it.

The existing debug-only manual signal tool remains a local fixture. It obeys mute/permission, does not register a synthetic destination on a real provider, and cannot initialize automatic recovery. Its manually selected synthetic alert is not proof of normal registration or FCM receipt. Instrumentation can instead inject a `PushTokenSource` and backend at the existing test Seams. No provider token, registration ID or original content is added to navigation payloads or ordinary diagnostics.

## Verify locally

Use JDK 17, the Android SDK documented in [android/README.md](../../android/README.md), and Go 1.26.0:

```sh
cd android
GOTOOLCHAIN=go1.26.0 ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:goHistoryTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

On a dedicated emulator only, first run the denial-dialog fixture by itself after a fresh managed install:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=no.varde.app.NotificationPermissionDialogTest
./gradlew :app:connectedDebugAndroidTest
```

The first command exercises the actual system denial button, retains the settings action through activity recreation, then grants permission and checks silent registration. It requires API 33+ with permission initially denied; otherwise it skips rather than revoking permission and killing instrumentation. The full suite can skip this fixture if an earlier output test already granted permission, which is why the separate fresh-install run matters. Managed instrumentation removes the app/test installation afterward; never use important phone settings for these commands.

Coordinator tests use real temporary journal files and recorded notification effects. They exercise pre-acknowledgement signals, uncertain registration replay after reopen, token refresh/cursor preservation, mute/re-enable, denied/restored permission, late acknowledgements, precondition conflicts, cleanup progress and a failed preference write. Real Android HTTPS transport against two temporary Go/SQLite backends covers isolated sources, silent history, muted recovery, partial outage, access replacement and removal without alert deletion.

Emulator UI/output checks cover the source toggle, active/pending/muted states, explicit permission/settings actions, cancellation of actual Android output, obsolete taps and a fresh registration on re-enable. Existing private/public notification, navigation, screen-capture and secure-settings tests remain required. The separate denial-dialog fixture passed on the API 36.1 emulator with no skip. This is emulator platform evidence, not genuine FCM delivery, physical-device permission-denial/restoration, background scheduling or independent HTTPS/n8n acceptance; those remain unverified here.
