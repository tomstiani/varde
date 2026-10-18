# Varde Android: combined alert inbox

Native Kotlin + Jetpack Compose. [Issue #12](https://github.com/tomstiani/varde/issues/12) established the first inbox; [issue #13](https://github.com/tomstiani/varde/issues/13) adds named connections and source-correct merging. [Issue #14](https://github.com/tomstiani/varde/issues/14) adds older-history browsing; [issue #15](https://github.com/tomstiani/varde/issues/15) adds explicit partial-outage recovery and retained in-memory details.

**Not yet implemented:** real push, background recovery, rich views, AI, or durable offline alert content. [App-opening missed-alert recovery](../docs/how-to/recover-alerts.md) now uses authenticated change checkpoints and per-connection catch-up notifications. Two-backend transport tests and physical-phone USB setup/browsing are verified. Original remote HTTPS/n8n acceptance in #16 remains deferred; see the [verification record](../docs/verification/android-usb-acceptance.md).

A [debug-only local notification path](../docs/how-to/test-local-notifications.md) now exercises generic notifications, silent detail enrichment and notification taps without Firebase or homelab access. Only minimal notification/recovery bookkeeping persists; there is no live push provider or periodic background worker.

For the simplest local phone test, follow [two-backend USB acceptance](../docs/how-to/local-acceptance.md). It needs no homelab connection or tailnet changes.

## Prerequisites

- Android 8.0 / API 26 or newer. USB setup/browsing has run on a Samsung SM-S938B with Android API 36; this is not physical-device coverage of every supported API level.
- JDK 17 and Android SDK Platform 36 with Build Tools 36.0.0. Install Platform Tools and an emulator system image for connected tests, or use a compatible test device.
- Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your Android SDK, or set `sdk.dir` in an uncommitted `local.properties`.
- Dependency downloads require network access on first setup. Gradle 8.14.3 is supplied through a checksum-pinned wrapper; AGP 8.13.1 and Kotlin 2.2.20 are pinned in the build.

**Warning:** run Gradle's `connectedDebugAndroidTest` only on a dedicated test device/emulator. Its managed runner uninstalls the application/test APKs afterward and removes saved connections. Use direct instrumentation below to preserve an existing phone installation.

From this directory:

```sh
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
```

Connected tests require a running emulator or connected device. They use synthetic content and an isolated secure-settings test file; they do not contact the homelab. JVM transport tests use a local HTTPS test server with trust configuration confined to test code. Both variants use Android's default HTTPS certificate validation and refuse redirects. Release rejects every cleartext URL. Debug permits HTTP only to literal `127.0.0.1` for the isolated USB fixture; neither DNS aliases nor remote HTTP are permitted.

To run ordinary UI tests on a physical phone without managed-runner uninstall:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
"$ANDROID_HOME/platform-tools/adb" -d install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -d install --user 0 -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -d shell am instrument --user 0 -w no.varde.app.test/androidx.test.runner.AndroidJUnitRunner
```

The debug APK is generated under `app/build/outputs/apk/debug/`. Remove USB-only connections before switching to a release build: release will not load HTTP connection settings. Release signing/distribution is not configured in this proof of concept; do not mistake the unsigned release build for a deployment pipeline.

## Connect and read

1. Run the upgraded backend following [the backend guide](../docs/how-to/run-backend.md). Stop old backend processes before allowing the new binary to migrate a database. Retain a consistent backup before upgrading important data; this increment does not supply a backup command.
2. Issue an installation credential through the local operator command. Do not use a sender credential or put the secret in shell arguments, logs or committed files.
3. Provide an operator-managed HTTPS proxy or tunnel that reaches the backend's loopback listener. Its certificate must be trusted by Android. Do not disable certificate checks; a self-signed development certificate is not accepted by the app.
4. Install and open Varde. Select **Add connection**, then enter a friendly name, HTTPS base URL and installation credential. A base-path prefix is supported when the proxy consistently maps it to the backend; redirects are not followed.
5. Select **Save connection**. The newest alerts load automatically. Tap an alert to read the full original from its labelled source. Returning to the inbox preserves browsing position without fetching again; use **Refresh** to discover arrivals and return to newest results.
6. Use **Settings → Add connection** for more backends. Each needs a distinct friendly name and normalized HTTPS URL. Different aliases pointing to the same backend are not detected and can duplicate alerts.
7. Select **Edit <name>** to rename, replace credentials, change the URL, or remove a connection. Removal asks for confirmation and deletes local settings/content only; revoke the server credential separately if needed.
8. Scroll to **Load older** to add up to 50 older alerts to the combined inbox. Each source retains its original snapshot cursor; new submissions do not enter this browsing session. Repeat on demand, not automatically. **All alerts in this snapshot are loaded** means the current healthy snapshot has ended, not that no new alerts have arrived.

Connections are stored together in a versioned encrypted file using an Android Keystore key in app-private, backup-excluded storage. Existing single-connection files remain readable; the next successful settings write upgrades their format without changing identity or credentials. Alert content is not part of this file.

A rename keeps loaded rows and details, updates source labels, and does not issue network requests. Leave the credential field blank to retain it. Changing a URL requires explicit credential entry so the app cannot silently forward a saved secret to a new destination. URL or credential changes discard that source's content and start a fresh combined session. Add/remove membership changes also refresh; unrelated displayed content remains visible while loading. Old requests cannot restore removed or replaced source content.

Unreachable settings can be saved and corrected without waiting for a successful probe. Friendly names compare using full locale-independent Unicode case folding, including expansions such as `Straße`/`STRASSE`; their display spelling remains unchanged. ICU4J is pinned so comparisons behave consistently across supported Android versions. URL normalization uses the HTTP library for host/scheme casing and default ports, and normalizes the trailing base-path slash while preserving path casing.

Alert rows, details and history cursors live only in memory; notification identities/dispositions and independent change-recovery cursors are persisted separately without original bodies. Loaded detail bodies remain available after returning to the inbox, but process death discards them along with rows and cursors; activity recreation can retain the session. Access changes and connection removal discard that source's cached bodies. There is no polling, real push/background delivery, or persistent HTTP content cache. Debug-only synthetic signals can produce local notifications while the app is open. Screens are protected from capture/recents previews. Retained content is not a promise of access after server-side revocation across app restarts.

Each fresh load or load-older action selects up to 50 alerts across responding sources, using bounded per-source pages of 50 rather than fetching archives. Unused source candidates remain buffered in memory. A source is replenished before choosing an older candidate from another source; page concatenation is not a correct global merge. Repeated load gestures are coalesced; refresh cancels older work and replaces successful source sessions. Cancelled or obsolete operations cannot consume the committed session's buffers or advance its cursors. A failed source is paused without treating the failure as exhaustion; global history is marked complete only when every source is healthy and exhausted. At most four backend requests run concurrently, including details, with a 15-second deadline per request and no hidden retries. Failed sources are explicitly marked incomplete; previously displayed source content can remain as stale, so a partial-refresh display can include retained rows beyond the fresh 50-row batch. Recover failed sources with an explicit full combined refresh, never by inserting recovered pages behind an advanced continuation.

Inbox order uses backend acceptance instant, then stable local connection ID, then descending alert ID. Equal server alert IDs from different sources remain distinct. Renaming never changes the tie-breaker. Backend clock differences can affect apparent global order; this is not a cross-backend atomic snapshot. Rows show original event time separately. Ordinary event times display in the device timezone. Leap-second or unusually precise spellings that cannot be formatted remain visible verbatim. Priority and source are always readable text, not colour alone.

## Outages and recovery

The inbox distinguishes no saved connections, successfully empty history, partial results, failure without retained content, and all-source failure with stale results. One failed or slow connection does not prevent healthy history from loading or continuing once the failed source has timed out. Connection errors scroll with the feed so they cannot push healthy alerts off-screen.

Each error card names its connection and offers **Retry <name>** and **Edit <name>**. Retry starts a new browsing session for **all** connections, including fresh snapshots for healthy sources; it does not splice one recovered source into older results. Edit opens that connection directly, with its credential still masked and retained unless replaced. History retry remains explicit. Independent alert-notification recovery runs on app opening and exposes its own source-specific retry in settings; it neither recovers a paused browsing snapshot nor silently inserts arrivals into it.

During refresh, displayed content stays visible. Successful source results replace their previous browsing content. Failed sources keep only previously displayed rows and loaded bodies, marked stale and sorted with known healthy results. Unused old buffers are not exposed as if fetched in the fresh session. A failed continuation preserves its last committed position and pauses that source while healthy connections keep paging. Incomplete history is never labelled fully loaded.

A retained detail can be reopened without contacting a source whose history is already paused. If its body was never loaded, the app says that no retained detail is available rather than inventing a summary. **Retry detail** is an explicit body request: success can show the original, but it does not recover paused history. Use **Retry <name>** for that full refresh. A source-wide detail failure, such as revoked access, also pauses its history and marks retained content stale; a late earlier response cannot undo that failure or advance the paused source. A detail `404` is alert-specific and does not clear other rows or pause unrelated history.

The Android transport's 15-second request deadline includes the network path. The current backend separately applies a five-second processing/response budget after headers, so it can return `503` sooner. Backend worker recovery does not automatically retry Android reads or recover a paused source; use the explicit actions above. See the [backend recovery contract](../docs/specs/backend-worker-recovery.md).

Messages are locally defined safe categories, not copied server messages. Authentication and permission failures direct you to credentials; TLS failures direct you to the trusted certificate/URL; redirects require correcting the URL; an invalid history cursor requires a new combined refresh. A missing or unsupported history endpoint points to the URL/backend version rather than pretending an individual alert was deleted. No certificate bypass, credential forwarding, background recovery, or persistent offline content cache is introduced.

## Go-backed history contract tests

The regular JVM tests do not require Go. To exercise the Android transport and inbox against actual Go processes with temporary SQLite, install the backend's Go toolchain and dependencies, then run:

```sh
(cd ../backend && go mod download)
./gradlew :app:goHistoryTest
```

This separate task builds the existing backend executable, issues temporary synthetic credentials through its local commands, and uses its real HTTP Interface. A local HTTPS test proxy provides test-only certificate trust; no release certificate bypass or administrative test endpoint is added. The one-backend fixture verifies snapshot continuation after process restart, concurrent arrivals, sender duplicate recovery, unchanged open details, and refresh discovery. The two-backend fixtures also cover real change-stream continuation/horizons across restart, per-source catch-up and outage/revocation recovery without archive or body reads. The two-backend fixture adds interleaved merging, source identity, bounded continuation, stale retained details, outage recovery, rename, credential replacement, and connection removal without deleting originals. Credentials stay in memory rather than process arguments or redirected output; the temporary database and executable are removed afterward. Backend source changes invalidate the Gradle task's cached result.

Backwards acceptance clocks and fixed timestamp ties are deterministic lifecycle/Android inbox tests; real-process tests do not pretend to control the machine clock. Remote phone HTTPS and actual n8n integration remain deferred #16 acceptance, separate from the verified USB substitute.

## Code and tests

- `app` contains the executable, screens, inbox Module, secure settings and HTTPS Adapter. Screens observe the inbox Interface rather than coordinating persistence or transport themselves.
- JVM tests exercise connection validation, multi-batch ordering, bounded/cancelled requests, source-scoped identity, paused-source recovery, repeated stale refreshes, retained detail bodies and invalidation, revoked/slow sources, late responses, TLS, redirects, safe cursor errors and alert-specific `404` handling.
- Connected tests exercise secure settings and its legacy format, setup/edit/remove/navigation, load-older and reading position, partial/all-failed/empty states, source-specific retry/edit, cached stale details, missing retained bodies and explicit detail/history recovery.

Local verification includes emulator coverage, one- and two-backend real Go/SQLite contract fixtures, and opt-in USB setup/browsing on the physical Samsung. The [redacted record](../docs/verification/android-usb-acceptance.md) distinguishes passed checks from deferred or unobserved operator acceptance. Ordinary connected tests skip the live check unless explicitly opted in.
