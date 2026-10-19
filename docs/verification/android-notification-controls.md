# Android registration and notification controls: local verification

Evidence for [#22](https://github.com/tomstiani/varde/issues/22), under the approved contract in [#19](https://github.com/tomstiani/varde/issues/19). This record does not update GitHub completion checkboxes or close either issue. The backend phase is committed as `383fe71`; the Android implementation described here was verified in the same worktree before its phase commit.

## Observed automated results

| Check | Result |
| --- | --- |
| Go 1.26.0 `go test ./...` | Passed |
| Go 1.26.0 `go test -race ./...` | Passed |
| Go 1.26.0 `go vet ./...` | Passed |
| `go run ./cmd/varde-demo` | Passed; explicitly fake-provider registration/retry, no live send |
| JDK 17 `:app:testDebugUnitTest` | 79 tests, no failures or skips |
| JDK 17 `:app:testReleaseUnitTest` | 79 tests, no failures or skips |
| `:app:goHistoryTest` | 7 tests, no failures or skips; includes registration and two-backend coordinator controls |
| `:app:lintDebug :app:lintRelease` | Passed |
| `:app:assembleDebug :app:assembleRelease` | Passed; release distribution/signing is not configured |
| Full `:app:connectedDebugAndroidTest` | 27 tests, 25 passed, 2 conditional skips, no failures |
| Fresh-install `NotificationPermissionDialogTest` alone | 1 passed, no skips or failures |

Connected checks used the existing Medium Phone API 36.1 emulator, serial `emulator-5554`, not a physical phone. Full-suite skips were the opt-in live setup test and the denial-dialog fixture because earlier output tests had already granted permission. The separate fresh-install run exercised the actual system denial button successfully. Permission restoration in that test used `UiAutomation.grantRuntimePermission`, then the app's real activity lifecycle; it was not a manual Android settings journey.

Commands and test prerequisites are in [Control notifications](../how-to/control-notifications.md). The denial-dialog fixture must run separately on a fresh managed install; revoking permission inside instrumentation can kill its process.

## Evidence scope

The Android HTTPS Adapter and actual temporary Go/SQLite backends agree on safe registration representations, ETags, conditional mutations, installation isolation, replay across restart, token refresh, removal, retired-session conflicts and revoked access. TLS trust is confined to test proxies; release validation, redirect refusal, request bounds and no-cache policy remain intact.

The coordinator uses real temporary journal files and recorded output at the approved Seam. Tests cover durable proposed identity, pre-acknowledgement signals, registration replay, baseline/cursor preservation, mute/re-enable, denied/restored permission, late acknowledgements, stale mutation preconditions, bounded cleanup and failed mute persistence. The two-backend transport fixture additionally checks silent history, isolated sources, partial outage, credential/URL replacement and removal without deleting originals. A fetched original is not copied into journal metadata.

New emulator UI/output tests exercise source controls, pending/active/muted states, explicit permission/settings actions, cancellation of actual Android notifications, obsolete tap rejection and a new session on re-enable. Existing private/public lock-screen representation, immutable navigation, source identity, secure settings and screen-capture protections also pass. The debug-only manual signal fixture remains separate from real registration and cannot initialize automatic recovery.

## Limitations and remaining acceptance

The ordinary token source is deliberately unconfigured. Enabled connections show activation pending until an Adapter supplies a token. Synthetic injected tokens in tests do not configure Google, send FCM, prove receipt, or establish human-visible delivery. No production callback URL or injection endpoint was added.

This work does not observe physical-device notification behavior, real provider delivery, background scheduling, force-stop recovery, deployed HTTPS/n8n, or hosted CI execution for this branch. #23 and #24 remain downstream work; the independent #11/#16 gates are not waived. No Google provisioning, secret upload, homelab deployment, paid call, GitHub publication, issue closure or merge occurred. Independent code review and GitHub acceptance reconciliation are not claimed by these test results.
