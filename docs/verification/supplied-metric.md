# Supplied metric verification

This record covers [#40](https://github.com/tomstiani/varde/issues/40) against baseline `28dc972a07e05cc216b946b3df0e7da434772d57`, the approved [#39 contract](../specs/rich-alert-contracts.md), and the user's subsequently approved [standalone-only rollout](../how-to/submit-supplied-metric.md). GitHub issue state was not changed.

## Verified behavior

- Authenticated standalone measurement acceptance, exact decimal bounds/equality and first-accepted number spelling, nullable values, strict timestamps, provenance inheritance/clearing, IDs and field bounds.
- Safe pre-acceptance rejection of malformed input and staged samples, records and sequences. No partial text-only acceptance.
- Deterministic metric/complete-summary catalogs, unknown metadata, distinct conflicting sources, finite omit/p0–p7 choices, stable band ties and whole-view rejection of missing/unknown/invalid/overflowing choices.
- Atomic terminal revision/change publication, rollback without partial publication, sender/event duplicates, pending and completed restart recovery, and production no-model fallback. Five applicable selection cases reuse the approved contract fixture file; later-slice fixtures are intentionally not accepted by this rollout.
- Current rich detail serialization over real local HTTP, body-free rich history state, and backend whole-view fallback for injected corrupt stored views. A maximum-size supplied original with a previously valid highly precise event time stays within the 128-KiB detail bound without narrowing that timestamp grammar.
- Android lossless metric tokens, exact complete summary text, original narrative reads independent of rejected typed/view content, strict whole-view validation, native styling and accessibility text, unknown metadata and narrow layouts with large text.
- Schema migration preserves originals, credentials, pending work, history/change order and push references; foreign keys are re-enabled. Notification behavior was not changed.

No provider call, real credential, real alert, homelab connection or real push delivery was used. Deterministic selectors are test-only; production has no selection override or fake generation mode.

## Test results

From `backend/`, all passed:

```sh
go test ./...
go test -race ./...
go vet ./...
go run ./cmd/varde-demo
```

From `android/`, with JDK 17 and Android SDK configured, all passed:

```sh
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:goHistoryTest \
  :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Results: **91 debug JVM tests, 91 release JVM tests and 8 real Go-backed tests**, zero failures/errors; lint and both APK builds passed.

Instrumentation used a newly created dedicated `Varde40_metric_test` emulator, serial `emulator-5580`. Existing emulators and physical devices were not used or repurposed. After installing only on that emulator:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  no.varde.app.test/androidx.test.runner.AndroidJUnitRunner
```

Runner result: `OK (30 tests)`, comprising **28 passed and 2 assumption skips**. All three new metric rendering tests passed. The skipped tests were the opt-in operator acceptance test and the notification-permission test whose denied-permission prerequisite was absent. Neither is claimed as new acceptance evidence.

Repository checks also passed:

```sh
python3 scripts/tracker-status.py check
python3 -m unittest discover -s scripts -p 'test_*.py'
git diff --check
```

Tracker findings: none. Python tests: **15 passed**.

## Direct review

Reviewed directly in the implementing session; no review subagents were launched.

- **Standards:** split measurement scalar validation, exact duplicate comparison and candidate preparation by responsibility. Kept SQLite and validation rules inside lifecycle, HTTP translation separate, and test selectors unavailable through runtime configuration or sender payloads. No outstanding findings.
- **Spec:** reviewed against #39/#40 and the approved staged rejection policy. Expanded tests exposed JVM/Android duplicate-key decoding differences; the shared detail decoder now reads original narrative independently and rejects the complete invalid rich view. Review also found HTML escaping could inflate a valid maximum-size original beyond the detail bound; original storage and JSON responses now avoid unnecessary HTML escaping. The regression test passed after first reproducing a 133,329-byte response. No outstanding findings within the approved slice.

## Limitations and integration

Production accepts and retains metrics but still publishes terminal original-text fallback. Rich success is demonstrated through test-only selections, not a production model or fake generation switch. BYOK administration, native Jev execution, durable paid attempts/retries, charts/history, tables and flows remain separate tickets. Model usefulness, live latency, physical-phone acceptance and live delivery are unverified.

#41 integration touchpoints are `backend/internal/lifecycle/open.go` (schema version 5), the new `rich_schema.sql`, `types.go` (original defaults/detail view), and `worker.go` (terminal publication). No application startup/configuration code was changed. Preserve its independent configuration work and serialize any additional schema migration after this one. The deterministic selection callback runs inside the publication transaction: do not attach provider networking there; native execution must reserve/commit before I/O and publish separately.
