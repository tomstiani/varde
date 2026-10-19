**Tracker:** [#22](https://github.com/tomstiani/varde/issues/22) owns current state and completion checkboxes. This file retains the requirements and delivery evidence.

## Delivery evidence

[PR #31](https://github.com/tomstiani/varde/pull/31), merged as `9363a6c`, delivered registration, durable fake-provider scheduling and Android controls. The [verification record](../../verification/android-notification-controls.md) distinguishes local, emulator and hosted-CI evidence from unverified real-provider/physical-phone delivery. See the [registration wire reference](../../how-to/register-push.md), [controls guide](../../how-to/control-notifications.md) and [fake-provider demonstration](../../how-to/test-push-delivery.md). GitHub remains the source of current completion and dependencies; this receipt does not establish the separate #11/#16 gates.

## Parent

Part of #19: Android alert notifications and missed-delivery recovery. The approved parent specification is authoritative, including conditional registration, idempotency, retry accounting and silent-baseline semantics.

## What to build

Connect Android notification preferences to backend registration and durable delivery scheduling, using a deterministic fake provider instead of Google. Demonstrate safe activation, notification controls and queued delivery across restarts through the real HTTP/lifecycle Interfaces.

## Acceptance criteria

- Implement the parent's GET/PUT/DELETE installation push contract, including private destination handling, request bounds, quoted mutation ETags, If-Match fencing, Idempotency-Key replay and explicit conflict/precondition responses.
- Only the authenticated installation can manage its own destination. Preserve one active destination/session per installation and separate identities per phone; sender credentials cannot administer delivery.
- A new session atomically captures the silent change baseline and enables only future delivery. Retrying uncertain registration returns its committed baseline; refreshing a token under the same session does not skip changes, reset eligibility or announce history.
- Prevent delayed updates, removals and old idempotent requests from overwriting/reactivating a newer session, including after backend restart. Persist sufficient session/idempotency history and invalidate obsolete destination work.
- Atomically enqueue eligible push work with new-alert acceptance, never before commit. Duplicate submissions and fallback completion schedule no second initial notification. Provider calls run outside storage transactions and ingress requests.
- Inject a provider-neutral send Interface and deterministic fake Adapter. Lifecycle/coordinator policy contains no Firebase classes or FCM error types. An unconfigured provider preserves startup, acceptance, reads and recovery with honest safe status.
- Durably reserve attempts before dispatch; enforce one initial attempt plus three bounded transient retries with the parent's delay/provider-wait policy. Uncertain attempts count across restart; no hidden Adapter retries or model-budget coupling.
- Recheck revocation, current session and destination before dispatch. Permanent token failures disable that destination, provider-wide configuration failure is not mass token invalidation, and slow/failing providers do not starve reads or fallback processing.
- Add a per-connection toggle defaulting on subject to OS permission. Muting immediately cancels local output and invalidates local eligibility, unregistering remotely best-effort while retaining inbox/recovery access.
- Re-enable/permission grant creates a fresh baseline and session, leaving muted/denied history silent. If activation cannot reach the backend, show pending rather than announcing an unknown backlog. Permission denial leaves reads usable and offers a non-repeating settings path.
- Persist the proposed local routing identity before registration; handle signals arriving before acknowledgement through gap-free recovery. Token refresh/reconnect preserves the current session and cursor.
- Rename updates active labels silently. Removal cancels local work/output and best-effort unregisters without remote alert deletion; URL/credential replacement cancels old generations and initializes a new silent access context. Obsolete requests/signals/taps cannot revive it.
- Test real HTTP plus SQLite and real Android transport registration, fake-provider delivery, baseline races, mutation fencing, partial outages, crash/attempt boundaries, revocation and control changes. Keep UI/security tests alongside this feature, not deferred to final verification.
- Document a reproducible fake-provider demonstration. Any development destination driver is injected/test-only, not an arbitrary production callback URL or externally accessible notification injection endpoint. No credentials, tokens, routing IDs or original bodies enter ordinary logs/backups.
