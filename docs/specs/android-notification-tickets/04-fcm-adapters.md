## Parent

Part of #19: Android alert notifications and missed-delivery recovery. The approved parent specification is authoritative. This ticket implements the first real provider behind the already-tested Interfaces; it does not authorize provisioning Google resources or sending live pushes.

## What to build

Add FCM server-send and Android registration/receive Adapters so configured operators can deliver the same minimal signals and notification behaviour proven with the fake provider, without coupling lifecycle or notification policy to Firebase.

## Acceptance criteria

- [ ] The server Adapter authenticates using an explicitly configured private credential source, refreshes provider authorization safely, uses finite timeouts and maps provider results to the established success/transient/permanent/configuration outcomes without hidden delivery retries.
- [ ] Send data messages containing only the parent's version, registrationId and alertId string fields. No alert title/summary/priority, backend URL, connection label, credential or private diagnostic content enters the envelope.
- [ ] Android FCM token registration and refresh call the shared session lifecycle. Receive callbacks validate the envelope, resolve the local current connection/session and invoke the existing coordinator; unknown versions/sessions or removed/muted access produce no arbitrary reads/output.
- [ ] Post a generic visible notification promptly through the coordinator; do not wait for network detail fetching in the FCM callback. Enqueue follow-up work using supported bounded Android execution. Verify current FCM priority/background restrictions against primary provider/platform documentation and document the chosen policy.
- [ ] Preserve provider-neutral lifecycle/coordinator Interfaces and the fake-provider path. No shared policy depends on Firebase SDK types, FCM-specific token formats or provider error codes; document what another provider Adapter would need to implement.
- [ ] Ordinary Android builds and automated tests run without private Firebase configuration or live calls. Explicitly separate the real-provider configuration/build path; never package server credentials into Android, container images, test fixtures or Git.
- [ ] Server Adapter tests use local fake HTTP/provider authorization boundaries to cover payloads, token refresh, deadlines, malformed responses, rate limits, temporary errors, invalid destinations and project/authentication failures. Ensure error/log sanitization and no credential-bearing redirects.
- [ ] Android Adapter tests verify envelope validation, token refresh, obsolete-session rejection, foreground/background entry into the same coordinator, and bounded callback work. Do not replace existing coordinator policy tests with SDK mocks.
- [ ] Operator instructions identify client configuration versus server credentials, private mounted-secret setup, optional real-device prerequisites, safe logging and cleanup. No automatic live send, resource provisioning or secret upload occurs during build/test/startup verification.
- [ ] Clearly distinguish compiled/automatically tested FCM support from observed real delivery. Live FCM receipt, Android power-management behaviour and trusted-HTTPS fetch remain separately recorded opt-in checks; report unavailable checks rather than claiming success.
