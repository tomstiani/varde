# Check pull-request verification

The `CI` GitHub Actions workflow runs on pull requests, pushes to `main`, and manual dispatch. Every change runs the same checks, including documentation-only changes, so check names remain available for branch rules.

## Read the results

Open the pull request's **Checks** tab or run:

```sh
gh pr checks PR_NUMBER
```

Four independent jobs run on disposable Ubuntu 24.04 runners:

| Check | Coverage |
| --- | --- |
| Backend and workflow checks | Actionlint; local tracker-contract/proposed-ruleset checks; Go tests, race detector, vet and restart demo; Python synthetic-fixture/tooling tests |
| Android JVM, transport and build | Debug/release JVM tests, explicit real-Go history/recovery/registration/control tests, both lint tasks and both APK builds |
| Android emulator | Ordinary instrumented UI/security/notification tests on an isolated API 36 Google APIs x86_64 emulator |
| Linux Compose smoke test | Compose validation, actual image build, synthetic credential issuance/submission, container recreation, retained reads and duplicate recovery |

The real-Go Gradle task is explicit: ordinary JVM tasks deliberately exclude those fixtures. The emulator's operator-only live acceptance test stays skipped. The denial-dialog fixture also skips if an earlier test already granted permission; run it separately on a fresh managed install to observe the actual dialog, as described in the [controls guide](control-notifications.md). A full-suite green result does not imply that a skipped dialog or live test ran. No test contacts the homelab, sends a real push, makes a paid model call, or needs Firebase/server credentials.

The workflow provisions JDK 17, the Go 1.26 series, SDK Platform 36 and Build Tools 36.0.0. Existing checksum-pinned Gradle Wrapper configuration supplies Gradle. All actions use full commit revisions; Gradle setup also validates wrapper JARs. Review and update action pins deliberately rather than replacing them with floating tags.

## Investigate a failure

1. Open the failed job and its first failing step.
2. For Android failures, download `android-jvm-reports` or `android-emulator-reports` from that run. Reports are uploaded after success or failure when present, except cancellation, and retained for seven days.
3. Reproduce the exact failed command using the toolchain prerequisites in the [Android guide](../../android/README.md), [backend guide](run-backend.md) or [Compose guide](run-compose.md).
4. For workflow changes, validate locally from the repository root:

   ```sh
   go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 -color
   ```

Managed Android instrumentation uninstalls its app/test APKs afterward. CI uses only a disposable emulator. For local reproduction, use a dedicated emulator, not a physical-phone installation with settings you want to retain.

Linux container checks exercise the real Docker daemon and host networking. A macOS cross-build or Compose syntax check alone is not equivalent. Emulator coverage is one OS/image configuration, not every supported Android version or physical-device behaviour.

## Permissions and enforcement

The workflow uses `pull_request`, not `pull_request_target`, grants the repository token only read access to contents, and disables persisted checkout credentials. Gradle caches are read-only on pull-request runs. There are no deployment steps, private secret inputs, PR comments or privileged follow-up jobs. GitHub may require maintainer approval before running a first-time external contributor's workflow.

The repository's `main-required-ci` ruleset is active for `main`: PRs, all four check names above and an up-to-date branch are required, with no bypass actors and no minimum human approval count. Preserve these names when editing CI. `.github/main-ci-ruleset.json` is the reviewed policy template; committing that file alone does not enable protection in another repository. Any settings change still requires separate approval. See [tracker consistency and rule operations](keep-tracker-current.md) for read-only verification, the approval boundary and the separate weekly tracker audit.

A local workflow-lint pass is not evidence that a hosted run passed. Inspect the actual pull-request run before merging the CI change, especially its first Linux Compose and hosted-emulator executions.
