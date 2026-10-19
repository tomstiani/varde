**Tracker:** [#25](https://github.com/tomstiani/varde/issues/25) owns current state and completion checkboxes. This file retains the requirements and delivery evidence.

## Parent

Part of #19: Android alert notifications and missed-delivery recovery. The approved parent specification is authoritative. This is integrated verification and operator documentation, not permission to defer feature tests/security or a requirement to deploy on the operator's homelab.

## What to build

Make the complete two-connection notification/recovery experience reproducible with synthetic local backends, reconcile automated evidence, and provide a separate opt-in live FCM/device acceptance procedure with honest observed/unobserved results.

## Acceptance criteria

- Extend the real Android-to-Go integration harness with two independent databases and synthetic credentials: register, accept alerts, dispatch through a fake provider, receive generic notifications, silently enrich and read unchanged originals, restart and recover missed changes.
- Exercise multiple recovery pages, interleaved sources, duplicate ingress/signals, reordered delivery, presentation updates and one-source outage/recovery. Prove one catch-up notification per source/pass with correct counts, no second interruption for covered alerts and no archive prefetch.
- Demonstrate mute/re-enable, permission denial/grant, token refresh, connection rename, endpoint/credential replacement and removal, including late responses and stale notification taps. Initial/disabled history stays silent and source identity never leaks across contexts.
- Include process-death and effect/cursor-boundary checks and preserve the explicit distinction between durable canonical recovery and best-effort OS display. Verify dismissed notifications cannot be resurrected by silent updates.
- Run backend tests/race tests/vet/restart demo and Android builds/lint/JVM/real-Go transport/platform tests with the documented toolchain. Record failures or unavailable device checks rather than waiving them after a happy path.
- Audit release HTTPS/redirect policy, app-private durable metadata and backup exclusions, Keystore-backed credentials, screen-capture protections, immutable non-secret intents, lock-screen public versions and sanitized backend/provider/app logs.
- Publish reproducible local fake-provider setup, exact approved wire examples, background recovery limitations, private credential configuration and cleanup instructions. Ordinary verification requires no homelab secrets, real alert data, paid calls or external push requests.
- Document a separate opt-in FCM/device procedure using temporary backends through Android-trusted HTTPS. With explicit operator permission and available configuration, observe foreground/background receipt, generic-before-fetch behaviour, silent enrichment, cold-start/unavailable-detail taps, missed-push recovery and denied-permission behaviour. Do not provision Google resources, upload secrets or send live pushes merely because this ticket exists.
- If live prerequisites or a device are unavailable, report the concrete blocker and leave the associated acceptance items open. Fake signals or provider acknowledgements are not observed real delivery; do not claim exact background deadlines or operation while force-stopped.
- Capture only redacted synthetic evidence. Do not commit credentials, tokens, routing identifiers, private hostnames, raw sensitive notification/UI dumps or databases.
- Keep the independent remote homelab HTTPS/n8n acceptance gate #16 open and unchanged. Do not close the parent automatically or imply that this milestone implements AI/rich views, an offline archive, another push provider or backup/restore tooling.
