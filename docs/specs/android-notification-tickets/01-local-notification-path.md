**Tracker:** [#20](https://github.com/tomstiani/varde/issues/20) owns current state and completion checkboxes. This file retains the requirements and delivery evidence.

## Delivery evidence

**Implemented:** delivered in [PR #26](https://github.com/tomstiani/varde/pull/26), with smaller-screen UI test hardening in PR #27. [Verification](https://github.com/tomstiani/varde/blob/main/docs/verification/android-local-notifications.md) covers the local synthetic path, not real FCM or physical-phone delivery. Later foreground recovery is #21; remaining registration/provider/background work is #22–#25.

## Parent

Part of #19: Android alert notifications and missed-delivery recovery. The approved parent specification is authoritative. This first slice is a complete local notification path, not permission to enable unregistered production push delivery.

## What to build

Demonstrate a synthetic incoming alert signal on Android: immediately show a generic connection-labelled notification, fetch its original through the existing backend transport, silently enrich it, and open its alert directly when tapped. Own notification decisions in one coordinator with durable metadata, behind replaceable receive/output Adapters.

## Acceptance criteria

- A deterministic, test/debug-only signal driver exercises the same coordinator production receivers will call. It adds no exported production receiver, insecure network endpoint or release trust bypass.
- A valid signal for a known synthetic session shows its connection before any detail fetch finishes. All priorities and foreground app states follow the same policy; invalid/unknown/obsolete sessions produce neither requests nor output.
- Successful reads silently replace the same notification with original title/summary. Failed reads retain the generic notification and an actionable unavailable-detail state. Existing HTTPS validation, redirect refusal, request bounds and plain-text rendering remain intact.
- Persist minimal source-scoped notification identities and dispositions across process death, not original bodies or an offline archive. The same server alert ID from different connections remains distinct.
- Duplicate signals, fetch retries and presentation updates cannot request a second interruption. Persist an interruption reservation before OS output; after an uncertain crash prefer silent reconciliation, without claiming exactly-once human notification.
- Dismissal prevents later enrichment from resurrecting the notification. Tests distinguish dismissal, already-announced state and current notification visibility.
- Private lock-screen visibility has an explicit generic public version containing only the connection name, never alert text. Respect Android channels/permission settings and existing screen-capture protections; denial does not break inbox reads or cause repeated prompts.
- Explicit immutable notification intents carry only stable non-secret identifiers. A cold-start tap opens the source alert even with no loaded inbox row; an unreachable source shows explanation/retry, and malformed or obsolete targets cannot redirect requests.
- Rename/removal/access replacement cannot apply stale results to a new connection context. Implement the safety gates needed by this slice, leaving remote destination lifecycle and full toggle UI to the registration/control ticket.
- Coordinator tests record output through the highest behavioural Interface, with deterministic delayed/failing reads, duplicate/race/crash boundaries and two-source identities. Platform tests cover public/private payloads, intent isolation and unavailable-detail navigation; report unavailable device execution honestly.
- Document a reproducible synthetic demonstration using temporary local data. Ordinary builds/tests require no Firebase configuration, homelab credentials, paid calls or real push delivery. Keep this synthetic demonstration distinct from verified FCM support.
