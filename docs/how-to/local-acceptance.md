# Test two local backends on a phone over USB

Use synthetic alerts from this Mac instead of connecting n8n or your homelab. The debug app reaches two loopback-only Go backends through Android USB forwarding. No Tailscale, tunnel, firewall change, public endpoint, or model request is required.

**Scope:** the operator approved this local substitute for the live n8n/HTTPS demonstration in issue #16. It verifies a real phone and real backends, but does not establish remote HTTPS deployment or actual n8n integration. Those original acceptance criteria remain deferred. See the [redacted verification record](../verification/android-usb-acceptance.md).

## Prerequisites and safety

- macOS with Python 3.10+, the backend's Go toolchain/dependencies, JDK 17, and the Android SDK described in the [Android guide](../../android/README.md).
- One physical Android phone, unlocked, with USB debugging enabled and this Mac authorized. On Samsung: **Settings → About phone → Software information → Build number** (tap seven times), then **Developer options → USB debugging**.
- A data-capable USB cable. Set `ANDROID_HOME` or put `adb` on `PATH`.
- Free Mac loopback ports 18081 and 18082. The fixture refuses an existing state directory rather than overwriting data.

Use only the generated synthetic credentials. Debug builds allow HTTP only to the literal host `127.0.0.1`; DNS names, LAN addresses, other loopback addresses, and emulator host aliases remain disallowed. The debug network-security configuration has the same restriction. Release builds reject every HTTP URL and keep cleartext disabled. Neither variant bypasses HTTPS certificate checks or follows redirects.

All commands below run from the repository root unless noted. Keep the fixture's terminal running until you finish. Its owner process holds the backend process handles; do not manage them through guessed PIDs.

## 1. Start and seed the isolated fixture

In one terminal:

```sh
python3 scripts/local-acceptance.py serve
```

This builds the existing backend executable, creates two private SQLite databases, issues separate sender/installation credentials, and starts:

- **Mac A:** `127.0.0.1:18081`
- **Mac B:** `127.0.0.1:18082`

The default state directory is `.pi/acceptance/live`, which Git ignores. Its permissions are `0700`; credential files are `0600`. Only hashes are stored by the Go backend. The helper keeps sender secrets in memory and installation issuance output in its private connection file. It prints neither secret values nor alert bodies.

In another terminal:

```sh
python3 scripts/local-acceptance.py status
python3 scripts/local-acceptance.py seed
python3 scripts/local-acceptance.py duplicate
```

Expected seed result: 130 accepted alerts, 65 per source, submitted in interleaved order. Repeating seed returns duplicates rather than new alerts. Duplicate replay must report `sameAlert: true` and status 200.

The sender uses the real authenticated JSON submission Interface, not a fake queue. Original event time is fixed synthetic data; server acceptance time determines inbox order. No HTTP request is sent to the homelab. The local control socket is Unix-only, private to this user, and is not a backend administration endpoint.

## 2. Build and install the debug app and opt-in UI check

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
cd ..
"$ANDROID_HOME/platform-tools/adb" -d install --user 0 -r android/app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -d install --user 0 -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The test APK is development-only. It is not part of the release app. Installing with `-r` preserves app data; do not clear unrelated settings just to make acceptance pass.

Keep the phone unlocked. The opt-in check refuses a locked phone and holds its test window awake only while running. It never changes device-lock settings or asks for your PIN.

## 3. Connect, configure and browse

```sh
python3 scripts/check-phone-acceptance.py
```

The helper:

1. Requires one authorized physical phone and both seeded backends.
2. Adds `adb reverse` mappings for the two fixture ports, refusing conflicting mappings.
3. Transfers credentials through stdin into an app-private, backup-excluded temporary file. Secrets do not appear in shell arguments, chat, or normal output.
4. Explicitly runs `LiveSetupTest`, which uses the normal settings screens, secure storage, HTTP Adapter, and backend Interface. It does not inject another backend or trust manager.
5. Checks the initial 50-row combined inbox, source labels, an unchanged original, and bounded older-history browsing until all expected alerts are loaded.
6. Deletes its temporary plaintext input and prints only a redacted result. It leaves the normal app open, with the named connections stored encrypted.

Ordinary connected tests do not read the private profile or contact live backends: the live check requires an explicit instrumentation argument and does not launch the real activity before that gate.

The helper will not overwrite unrelated app connections. For this session's approved transition from the abandoned Tailscale fixture, a private names/URLs-only allowlist was used with `--previous-connections`; do not supply arbitrary real configurations through that option.

If the phone disconnects, reconnect and authorize it, then rerun the helper. USB forwarding does not survive every disconnect. If an interrupted run could not delete its temporary phone input, `--cleanup` removes that known test file after reconnection. Do not paste `.pi/acceptance/live/connections.json` into chat or a bug report.

## 4. Exercise arrivals, restart and outages

Keep the app open. Use these commands from the second Mac terminal, following the corresponding phone action:

| Mac command | Phone action and expected result |
| --- | --- |
| `python3 scripts/local-acceptance.py late` | While browsing older results, the new arrival stays absent. Tap **Refresh** to see it. |
| `python3 scripts/local-acceptance.py restart-a` | Refresh/open a retained Mac A alert; the original remains available. |
| `python3 scripts/local-acceptance.py duplicate` | Refresh; replay still identifies the existing alert, not another row. |
| `python3 scripts/local-acceptance.py pause-a` | Refresh, then load older Mac B results. Mac A is incomplete/stale, not an empty or completed history. Previously loaded Mac A details remain readable. |
| `python3 scripts/local-acceptance.py resume-a` | Tap **Retry Mac A**. Recovery refreshes both connections from newest snapshots. |
| `python3 scripts/local-acceptance.py rotate-a` | Refresh; the old installation credential is denied. Rerun the private phone helper to enter the replacement through the normal settings screen, without exposing it in arguments. |

After those checks, use settings to rename Mac A without re-entering its credential, and remove a connection. Verify source labels update and removal does not delete server history. Re-adding a removed connection creates a new local connection identity. These manual phone actions require their own observations; setup/browsing success alone does not mark them passed. The automated checker expects the fixture names Mac A/Mac B; restore those names before rerunning it.

## 5. Stop only the resources this fixture owns

While the same phone is connected:

```sh
python3 scripts/check-phone-acceptance.py --cleanup
python3 scripts/local-acceptance.py stop
```

USB cleanup checks the recorded physical device and removes only mappings it created that still have the expected targets. It does not reset all USB mappings. Fixture shutdown stops its owned backend processes and deletes its synthetic databases, executable and private credentials. This deletes the entire temporary fixture; it is not an alert-deletion operation on a real backend.

Remove the acceptance connections from the phone when finished. Encrypted settings can otherwise remain while the local servers are unavailable. No tailnet configuration is required by this procedure.

## Automated verification

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
(cd backend && go test ./... && go test -race ./... && go vet ./... && go run ./cmd/varde-demo)
(cd android && ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:goHistoryTest :app:lintDebug :app:assembleDebug :app:assembleRelease)
```

Run `connectedDebugAndroidTest` only on a dedicated test device/emulator: Gradle's managed runner uninstalls its application/test APKs and removes saved connections afterward. To preserve this phone demo, use the [direct instrumentation commands](../../android/README.md) instead; ordinary instrumentation skips the opt-in live check.

The Go-backed Gradle task uses two actual Go processes and temporary SQLite, with TLS trust restricted to its local test proxies. It covers authenticated reads, interleaved bounded pagination, retained details through an outage, full-refresh recovery, rename, credential replacement, and connection removal without deleting originals. Unit and ordinary UI tests need no live credentials or paid calls. The optional phone check uses only the operator-approved local fixture.
