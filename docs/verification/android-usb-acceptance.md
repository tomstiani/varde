# Android local acceptance: verified scope

This record covers the operator-approved USB/scripted-sender substitute for part of issue #16. It is not a claim that every original acceptance criterion passed.

## Observed physical-phone result

The opt-in live UI check ran on a Samsung **SM-S938B**, Android **API 36**, against two actual Go processes and separate SQLite databases on the Mac. Android USB forwarding connected the debug app to `127.0.0.1:18081` and `127.0.0.1:18082`.

The redacted result reported:

- `passed: true`
- `rows: 130`
- Sources: **Mac A: 65**, **Mac B: 65**
- Transport: **USB loopback HTTP**, not remote HTTPS

The check used the normal connection settings UI, encrypted credential storage, real HTTP Adapter, authenticated backend reads, source labels, original-detail view and load-older interaction. It verified a known synthetic original summary unchanged, all expected rows loaded, no duplicate connection/alert identities, and complete healthy snapshots. The normal app was reopened afterward with both acceptance connections configured. After review hardening, the 130-row phone check passed again using a validated names/URLs-only allowlist and an explicit 1–50-row bound on every load-older action. The real two-backend contract test enforces the same per-action bound.

The temporary plaintext phone input was removed. No credentials, phone serial, tailnet addresses, private hostnames, database contents or raw UI dumps are retained in this record. The app's screen-capture protection was not disabled.

## Automated coverage

Verification passed: **40 debug JVM tests, 40 release JVM tests, 2 real-Go contract tests, 7 helper CLI tests, and 17 ordinary physical-phone UI/security tests**. The live check is skipped in ordinary instrumentation and passed separately with explicit opt-in. Android debug/release builds and lint, Go tests/race tests/vet, and the restart demo also passed.

Gradle's managed connected-test run uninstalled the acceptance app afterward. Both APKs and the synthetic connections were restored, ordinary instrumentation was rerun directly without uninstall, and the live 130-row check passed again. The runbook warns against managed device tests on installations whose settings must survive.

- Two actual Go/SQLite instances behind test-local HTTPS proxies: authenticated summary/detail reads, interleaved ordering, bounded continuation, source identity, outage/stale-detail retention, full-refresh recovery, rename, credential replacement and removal without deleting originals.
- Existing one-backend contract check: snapshot continuation across process restart, concurrent arrivals excluded until refresh, duplicate submission and retained original details.
- Local sender fixture: interleaved submissions, duplicate replay, late arrival, owned-process restart/pause/resume, credential revocation/replacement and private-state cleanup through its CLI.
- USB helper safety: refuse a different physical phone; preserve changed and unrelated forwarding mappings; reject malformed or secret-bearing previous-connection allowlists before device access, without echoing their content.
- Debug URL policy: HTTP is accepted only for literal `127.0.0.1`. DNS aliases, LAN addresses, other loopback addresses and emulator host aliases are rejected.
- Release URL policy: all HTTP URLs, including loopback, are rejected. The packaged release network-security resource contains only `cleartextTrafficPermitted=false` and no HTTP exception. The debug resource enables only `127.0.0.1`, and that host restriction also passed on the physical phone.

Local HTTPS-proxy certificate trust is confined to JVM tests. There is no release certificate bypass, debug trust-anchor expansion, production pairing endpoint, or remotely accessible test administration Interface.

## Deferred or not observed

- **Actual n8n:** deferred at the operator's request. The scripted sender exercises equivalent authenticated JSON submission, not an n8n workflow.
- **Remote trusted HTTPS on the phone:** not passed. The abandoned tailnet path resolved DNS but timed out on TCP. No tailnet policy edit was applied, and the two temporary Serve routes were removed before USB verification.
- **Every lifecycle scenario in one live phone session:** restart/duplicate/outage/recovery/rename/replacement/removal are automated against the real backends and ordinary UI tests, but have not all been observed together on the live USB phone. The runbook provides those optional operator steps.
- **Push, rich views, typed data, model calls, change sync and durable offline alert content:** not implemented by this milestone.

The original #16 real-network/n8n acceptance gate and parent issue remain open. No tracker issue was closed or updated on the operator's behalf.
