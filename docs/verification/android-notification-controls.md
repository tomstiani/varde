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

At initial local verification, this work had not observed physical-device notification behavior, real provider delivery, background scheduling, force-stop recovery, deployed HTTPS/n8n or hosted CI execution for the branch. The subsequent hosted-CI and merge receipt below does not establish those physical-device/provider/deployment gates. #23 and #24 remain downstream work; the independent #11/#16 gates are not waived. No Google provisioning, secret upload, homelab deployment, paid call, GitHub publication, issue closure or merge occurred. Independent code review and GitHub acceptance reconciliation are not claimed by these test results.

## Token-repair follow-up

Review of checkpoint `d20fd1a` found a P1: permanent destination failure advanced the backend ETag, and token refresh could retire the local session after `412`, replacing its recovery cursor with a fresh baseline and silencing eligible acceptances. A failing coordinator regression reproduced one expected catch-up alert as zero.

The fix reloads current registration state for new mutations and acknowledged reconnects, preserves the current baseline/cursor during destination repair, and uses a fresh mutation key after an explicitly rejected precondition changes. Already-bound uncertain requests still replay unchanged before observing a newer version. A genuinely retired ID remains fenced. Mutation attempts stay bounded at two per call, with further reconciliation persisted for later retry.

Seven dedicated coordinator tests cover changed/unchanged token repair, concurrent version advance, explicit removal, a lost acknowledgement across restart, a precondition-read outage across restart, and bounded repeated races. A real HTTPS/Go test verifies same-session server version changes, unrecovered acceptance, unchanged-token reconciliation, backend restart and explicit removal. The original external minimal reproduction also passed afterward; its temporary harness was removed in favor of the retained tests.

Follow-up results, separate from the initial counts above:

| Check | Result |
| --- | --- |
| Debug JVM tests | 86 passed, no skips |
| Release JVM tests | 86 passed, no skips |
| Real-Go HTTPS tests | 8 passed, no skips |
| Debug/release lint and builds | Passed |
| Full emulator suite | 27 tests, 25 passed, same 2 conditional skips, no failures |
| Fresh-install denial-dialog fixture alone | 1 passed, no skips |
| Backend tests, race tests, vet and fake-provider demo | Passed |

An independent, read-only Spec reviewer rechecked the fix and its immediate blast radius and reported no findings, confirming that the prior P1 is resolved by source inspection. The reviewer did not execute tests; the parent ran the commands above. This is scoped fix-review evidence, not a new verdict on every #22 acceptance criterion or on deferred live delivery. The fix was uncommitted at verification time; no publication, issue mutation or live push had occurred at that point.

## Hosted CI and merge receipt

[PR #31](https://github.com/tomstiani/varde/pull/31) was squash-merged into `main` as `9363a6c988712bc8795cca3871c12f40000f0c3a`. Its approved head was `472599effd255a7213f161690bedfaf4d91c505c`. GitHub automatically closed #22 through the approved PR closing reference; parent #19 and gates #11/#16 remained open.

All four required checks in [CI run 39262864830](https://github.com/tomstiani/varde/actions/runs/39262864830) completed successfully on that PR head before merge:

- [Backend and workflow checks](https://github.com/tomstiani/varde/actions/runs/39262864830/job/118094782667)
- [Android JVM, transport and build](https://github.com/tomstiani/varde/actions/runs/39262864830/job/118094782841)
- [Android emulator](https://github.com/tomstiani/varde/actions/runs/39262864830/job/118094782244)
- [Linux Compose smoke test](https://github.com/tomstiani/varde/actions/runs/39262864830/job/118094782653)

This receipt supplements, rather than rewrites, the earlier local observations and counts. Hosted success does not turn skipped live tests into evidence of FCM receipt, physical-phone notification behavior, periodic recovery or a deployed HTTPS/n8n path. Native dependency links are retained as history; current readiness and task state belong to GitHub.
