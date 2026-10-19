**Tracker:** [#16](https://github.com/tomstiani/varde/issues/16) owns current state and completion checkboxes. This file retains the requirements and delivery evidence.

## Delivery evidence

**Recorded evidence at PR #17; current acceptance belongs in the linked tracker.** PR #17 delivered the automated two-backend harness/runbook and the operator-approved USB substitute: 130 synthetic rows across two sources on the physical phone. Actual n8n, Android-trusted remote HTTPS, live concurrent arrival/refresh, and the full restart/duplicate/outage/connection-management lifecycle have not all been observed together. Automated coverage is not a waiver. See the [USB verification record](https://github.com/tomstiani/varde/blob/main/docs/verification/android-usb-acceptance.md) and [Compose/Pangolin guide](https://github.com/tomstiani/varde/blob/main/docs/how-to/run-compose.md). This evidence does not establish completion of parent #11. Later #20/#21 notifications/foreground change recovery do not satisfy this deployment gate.

## Parent

Part of #11: First Android slice: named connections and a combined plain-text inbox. The approved parent specification is authoritative. This is integrated verification and operator acceptance of the working feature, not permission to defer feature-level tests or security until the end.

## What to build

Make the complete two-backend phone experience reproducible, verify that Android and Go agree on the real wire contract, and demonstrate the milestone using n8n and a physical Android phone.

## Acceptance criteria

- Provide an automated compatibility/integration test using the real Android transport and actual Go HTTP Interface backed by two temporary databases and synthetic alerts. Exercise authenticated summary/detail reads, timestamp ordering, bounded continuation, source identity and partial failure; mocks alone are insufficient.
- Ordinary tests require no live homelab credentials, private alert content or paid calls. Any test TLS configuration is isolated to tests and must not weaken release-app trust validation or enable credential-bearing redirects.
- Document reproducible backend setup, pinned Android build/install/test prerequisites, secure local credential issuance/transfer, connection setup, and expected results. Identify automated versus operator-only steps explicitly.
- Document the operator-chosen trusted HTTPS proxy/tunnel path reaching the loopback backend, including authentication and log-redaction requirements. Do not relax the listener, add a proxy-product dependency or claim unsupported production packaging.
- With operator participation, run two backend instances behind Android-trusted HTTPS, save distinct named connections on the acceptance phone, submit a synthetic alert through actual n8n, and open its unchanged original from the combined inbox.
- Demonstrate enough interleaved source alerts to span multiple combined pages, deterministic ordering, always-visible source labels, and an arrival that appears on refresh rather than during older-history browsing.
- Restart a backend with intact storage and retrieve its retained alert. Resubmit the same sender/event original and verify one source-scoped inbox item, not a duplicate.
- Make one backend unavailable, refresh and load older results from the healthy source, show connection-specific failure and retained stale content, then recover through explicit combined refresh.
- Demonstrate connection renaming, credential replacement and removal, with the specified label updates, content invalidation and no remote deletion.
- Run backend tests, race tests, vet and the existing restart demo; run the documented Android inbox, transport and UI tests. Record failures honestly rather than waiving them because a manual happy path succeeds.
- Capture redacted acceptance evidence without committing live credentials, private URLs containing secrets, real alert content or databases. Document remaining limits: no push, AI, rich data, change sync or durable offline cache.

## Operator acceptance gate

An agent can build the automated harness and prepare instructions, but cannot claim physical-phone, n8n or operator-managed HTTPS acceptance without actual observed execution. If access or provisioning is unavailable, report the concrete blocker and leave this ticket's corresponding acceptance items open. Do not close the parent issue automatically.

## Evidence interpretation

GitHub #16 owns the current completed/deferred criteria. The merged guides and verification records support only their recorded observations; showing a subset over USB does not establish full phone/deployment acceptance. The original no-change-sync limit describes the inbox milestone; foreground recovery subsequently landed in PR #28 and is documented separately. No actual FCM, rich views, typed data, model execution or durable offline original archive is claimed.
