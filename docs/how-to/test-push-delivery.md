# Test durable delivery with the fake provider

Run the local demonstration to verify registration, silent history, durable enqueue and a deterministic transient retry. It sends no external push and requires no Google configuration, phone, homelab access or paid calls.

From the repository root:

```sh
cd backend
GOTOOLCHAIN=go1.26.0 go run ./cmd/varde-demo
```

Expected output:

```text
Verified: credential issuance, durable acceptance, restart, original retrieval, duplicate recovery, text fallback, and fake-provider push registration/retry. No live push was sent.
```

The command builds the backend, runs the existing restart demonstration, then starts a separate authenticated loopback HTTP scenario. All credentials, destinations and alerts are synthetic. Temporary private databases are removed afterward. Only the static verification summary is printed.

The delivery scenario accepts historical text, registers an installation, accepts a future alert and retries the identical sender request. It reopens SQLite, replays the uncertain registration and checks that the committed baseline is unchanged. An injected in-memory provider records a transient result followed by success, with an injected clock advancing the retry delay. Both attempts contain the same opaque envelope. Neither initial history nor duplicate ingress creates another delivery chain.

## What is implemented

The lifecycle Module inserts eligible installation work in the same transaction as alert acceptance and its change entry. Presentation fallback does not enqueue an initial push. Work stores an installation, registration version, alert ID, attempt count, next eligible time and completion state; it does not copy original content or historical destination tokens.

`NewPushDispatcher` accepts a provider-neutral `PushProvider`. `DispatchOne` reserves one due attempt, commits that reservation, rechecks access/session state, then calls the provider outside SQLite and ingress. `Run` drives the same operation with four concurrent workers independently of fallback processing. Use one dispatcher worker group per service. Adapters must honor the supplied context and must not implement hidden retries. Calls default to a 10-second timeout, bounded at 30 seconds.

`fakepush.New` is an injected development Adapter, not a production delivery option. It records private messages in memory for synthetic assertions and returns scripted outcomes, defaulting to success. Do not log its recorded messages or use real destinations. No callback URL, externally accessible injection endpoint or release transport exception is introduced.

The production server has no provider wired in yet. Startup, acceptance, reads, fallback and change recovery continue normally; queued work remains durable. A dispatcher with no provider reports `not_configured` and does not reserve attempts. Its `Run` waits for cancellation rather than consuming work. Provider configuration and genuine FCM delivery remain #23.

## Retry and failure policy

- One initial attempt plus at most three retries. Default delays are approximately 5 seconds, 30 seconds and 2 minutes with small jitter.
- A supported provider wait takes precedence over an earlier local delay. Waits up to 15 minutes are supported. A longer instruction exhausts that delivery chain rather than retrying before the instructed time or creating an unbounded wait.
- Attempt reservation commits before the call. If completion is lost, the attempt still counts. A retry waits through the original call timeout plus its retry delay. Restart does not reset the four-attempt budget.
- Success completes the work. Transient failures schedule within the remaining budget. Unrecognized Adapter outcomes are treated as transient, never as token invalidation.
- A permanent destination failure disables only its current destination, advances its registration version and invalidates its queued work. An obsolete result cannot disable a replacement. Repairing a retained session preserves its baseline without backfilling old work.
- Provider-wide configuration failure does not invalidate phone tokens. It persists a provider-wide cooldown of at least five minutes, honoring supported longer waits. Attempts still count; canonical change recovery remains available after exhaustion.
- Token refresh, session replacement, removal and revocation invalidate obsolete work transactionally. Dispatch rechecks the credential used for registration. Credential rotation alone does not transfer queued work to a new credential: registration must explicitly bind the current session/destination to it.

Schema version four adds queued work, credential binding and provider cooldown state transactionally. A development version-three registration has no credential binding, so upgrade retires it rather than guessing access ownership. Reload registration and create a new silent session; originals, change order, credentials, fallback work and replay acknowledgements remain retained.

An already dispatched hint cannot be recalled. Duplicate external delivery is possible after an uncertain result; the phone must deduplicate using the registration and alert IDs. A fake success is not evidence that a phone received or displayed anything.

## Verify failure and restart behavior

```sh
cd backend
GOTOOLCHAIN=go1.26.0 go test ./...
GOTOOLCHAIN=go1.26.0 go test -race ./...
GOTOOLCHAIN=go1.26.0 go vet ./...
```

Tests use authenticated HTTP, temporary file-backed SQLite, injected provider effects and clocks. They exercise retry deadlines/budget across reopen, a subprocess exiting after dispatch but before completion, provider-wide cooldown, unsupported waits, permanent failure repair, destination/session/revocation fencing, finite provider deadlines, and bounded worker concurrency. A blocked provider test also verifies that reads, acceptance, fallback and registration replacement remain usable. The existing deferred-constraint COMMIT failure test now observes fake-provider effects too: failed acceptance leaves nothing dispatchable, and a successful sender retry schedules one push.

These checks do not verify Android controls, genuine FCM receipt, platform scheduling, lock-screen behavior or a deployed HTTPS/n8n path. Those checks remain separate. [Registration wire reference](register-push.md) describes request limits, ETags and replay semantics.
