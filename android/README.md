# Varde Android: combined newest-alert inbox

Native Kotlin + Jetpack Compose. [Issue #12](https://github.com/tomstiani/varde/issues/12) established the first inbox; [issue #13](https://github.com/tomstiani/varde/issues/13) adds multiple named connections, a combined newest-results batch, source-correct details, and connection management.

**Not yet implemented:** Android load-older, complete failed-source pagination/recovery, push, change sync, rich views, AI, or durable offline alert content. Backend history supports continuation; the initial merge uses it when necessary to select the newest batch correctly, but there is no load-older interaction yet. This is not the full #11 milestone.

## Prerequisites

- Android 8.0 / API 26 or newer. The operator confirmed this acceptance-phone baseline; physical-phone execution remains unverified here.
- JDK 17 and Android SDK Platform 36 with Build Tools 36.0.0. Install Platform Tools and an emulator system image for connected tests, or use a compatible test device.
- Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your Android SDK, or set `sdk.dir` in an uncommitted `local.properties`.
- Dependency downloads require network access on first setup. Gradle 8.14.3 is supplied through a checksum-pinned wrapper; AGP 8.13.1 and Kotlin 2.2.20 are pinned in the build.

From this directory:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
```

Connected tests require a running emulator or connected device. They use synthetic content and an isolated secure-settings test file; they do not contact the homelab. JVM transport tests use a local HTTPS test server with trust configuration confined to test code. Production uses Android's default certificate validation and refuses redirects and cleartext URLs.

The debug APK is generated under `app/build/outputs/apk/debug/`. Release signing/distribution is not configured in this proof of concept; do not mistake the unsigned release build for a deployment pipeline.

## Connect and read

1. Run the upgraded backend following [the backend guide](../docs/how-to/run-backend.md). Stop old backend processes before allowing the new binary to migrate a database. Retain a consistent backup before upgrading important data; this increment does not supply a backup command.
2. Issue an installation credential through the local operator command. Do not use a sender credential or put the secret in shell arguments, logs or committed files.
3. Provide an operator-managed HTTPS proxy or tunnel that reaches the backend's loopback listener. Its certificate must be trusted by Android. Do not disable certificate checks; a self-signed development certificate is not accepted by the app.
4. Install and open Varde. Select **Add connection**, then enter a friendly name, HTTPS base URL and installation credential. A base-path prefix is supported when the proxy consistently maps it to the backend; redirects are not followed.
5. Select **Save connection**. The newest alerts load automatically. Tap an alert to read the full original from its labelled source. Returning to the inbox does not fetch again; use **Refresh** to discover arrivals.
6. Use **Settings → Add connection** for more backends. Each needs a distinct friendly name and normalized HTTPS URL. Different aliases pointing to the same backend are not detected and can duplicate alerts.
7. Select **Edit <name>** to rename, replace credentials, change the URL, or remove a connection. Removal asks for confirmation and deletes local settings/content only; revoke the server credential separately if needed.

Connections are stored together in a versioned encrypted file using an Android Keystore key in app-private, backup-excluded storage. Existing single-connection files remain readable; the next successful settings write upgrades their format without changing identity or credentials. Alert content is not part of this file.

A rename keeps loaded rows and details, updates source labels, and does not issue network requests. Leave the credential field blank to retain it. Changing a URL requires explicit credential entry so the app cannot silently forward a saved secret to a new destination. URL or credential changes discard that source's content and start a fresh combined session. Add/remove membership changes also refresh; unrelated displayed content remains visible while loading. Old requests cannot restore removed or replaced source content.

Unreachable settings can be saved and corrected without waiting for a successful probe. Friendly names compare using full locale-independent Unicode case folding, including expansions such as `Straße`/`STRASSE`; their display spelling remains unchanged. ICU4J is pinned so comparisons behave consistently across supported Android versions. URL normalization uses the HTTP library for host/scheme casing and default ports, and normalizes the trailing base-path slash while preserving path casing.

Alert rows, details and cursors live only in memory. Activity recreation can retain a session; process death starts a fresh load from saved settings. There is no polling, background delivery, or persistent HTTP content cache. Screens are protected from capture/recents previews. A failed refresh retains already displayed rows as stale and distinguishes failure from empty history. Authentication errors direct you to settings. Network/TLS failures use safe messages, not raw server exceptions.

Each fresh load selects up to 50 alerts across responding sources, using bounded per-source pages of 50 rather than fetching archives. At most four backend requests run concurrently, including details, with a 15-second deadline per request and no hidden retries. Failed sources are explicitly marked incomplete; previously displayed source content can remain as stale, so a partial-refresh display can include retained rows beyond the fresh 50-row batch. Full failed-source older-history recovery follows in #15.

Inbox order uses backend acceptance instant, then stable local connection ID, then descending alert ID. Equal server alert IDs from different sources remain distinct. Renaming never changes the tie-breaker. Backend clock differences can affect apparent global order; this is not a cross-backend atomic snapshot. Rows show original event time separately. Ordinary event times display in the device timezone. Leap-second or unusually precise spellings that cannot be formatted remain visible verbatim. Priority and source are always readable text, not colour alone.

## Code and tests

- `app` contains the executable, screens, inbox Module, secure settings and HTTPS Adapter. Screens observe the inbox Interface rather than coordinating persistence or transport themselves.
- JVM tests exercise Unicode name/URL validation, merged ordering, cursor replenishment, batch/request limits, timeout/no-retry behaviour, source-scoped details, rename/edit/removal and obsolete responses, TLS, redirects and safe transport errors.
- Connected tests exercise multi-connection encryption/recreation/removal/corruption, the legacy encrypted format, and Compose setup, combined source labels, rename-without-secret-entry, removal, navigation and explicit refresh.

The local verification used an API 36.1 emulator. Real n8n submission and physical-phone HTTPS acceptance, and the two-backend compatibility harness, remain explicit work in #16. Do not claim those checks passed from mocked UI or JVM tests.
