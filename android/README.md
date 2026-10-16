# Varde Android: first inbox slice

Native Kotlin + Jetpack Compose. This increment implements [issue #12](https://github.com/tomstiani/varde/issues/12): one named connection, its newest 50 plain-text alerts, manual refresh, and full-original details.

**Not yet implemented:** multiple connections, combined feeds, Android load-older, push, change sync, rich views, AI, or durable offline alert content. Backend history already supports continuation for later slices. This is not the full #11 milestone.

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
5. Select **Save connection**. The newest alerts load automatically. Tap an alert to read the full original. Returning to the inbox does not fetch again; use **Refresh** to discover arrivals.

The single connection is stored encrypted using an Android Keystore key in app-private, backup-excluded storage. Its credential is never displayed again. This first slice requires re-entering a credential when replacing settings; richer connection editing follows in #13. Unreachable settings can be saved and corrected without waiting for a successful probe.

Alert rows, details and cursors live only in memory. Activity recreation can retain a session; process death starts a fresh load from saved settings. There is no polling, background delivery, or persistent HTTP content cache. Screens are protected from capture/recents previews. A failed refresh retains already displayed rows as stale and distinguishes failure from empty history. Authentication errors direct you to settings. Network/TLS failures use safe messages, not raw server exceptions.

Inbox order uses backend acceptance time; rows show original event time separately. Ordinary event times display in the device timezone. Leap-second or unusually precise spellings that cannot be formatted remain visible verbatim. Priority and source are always readable text, not colour alone.

## Code and tests

- `app` contains the executable, screens, inbox Module, secure settings and HTTPS Adapter. Screens observe the inbox Interface rather than coordinating persistence or transport themselves.
- JVM tests exercise connection validation, inbox refresh/detail behaviour, obsolete responses, TLS, redirects and safe transport errors.
- Connected tests exercise settings encryption/recreation/corruption and Compose setup, source labels, navigation and explicit refresh.

The local verification used an API 36.1 emulator. Real n8n submission and physical-phone HTTPS acceptance, and the two-backend compatibility harness, remain explicit work in #16. Do not claim those checks passed from mocked UI or JVM tests.
