# Manage a backend installation's push registration

The backend supports conditional registration, removal, a silent recovery baseline and durable delivery scheduling. [#22](https://github.com/tomstiani/varde/issues/22) remains incomplete: Android does not call these operations or expose notification controls yet. The `fcm` provider name identifies the destination format; it does not imply that a provider is configured. The server has no live provider configured. Use the [injected fake-provider demonstration](test-push-delivery.md) to exercise dispatch without Google or a phone.

Use this reference when implementing an installation client. [Parent #19](https://github.com/tomstiani/varde/issues/19) defines the complete target contract. [Change recovery](recover-alerts.md#backend-wire-reference) defines the returned baseline cursor.

## Read the current registration

All operations use the installation's current bearer credential and affect only that installation. Do not supply an installation ID. Sender credentials receive `403`; missing, invalid and revoked credentials receive `401`, including on replay. Credential rotation within an installation preserves its registration.

`GET /v1/installation/push` accepts no body or query parameters. Before registration, it returns `200`, `ETag: "0"` and:

```json
{"registrationId":null,"provider":null,"enabled":false,"baselineCursor":null}
```

Every successful operation returns this representation and a quoted integer mutation version in `ETag`. Responses use `Cache-Control: no-store` and never contain the destination token. `enabled` describes registration state, not provider availability, phone receipt or display.

## Register or refresh a destination

1. Read the current registration and ETag.
2. Persist a new random 128-bit session ID locally before starting a new notification session.
3. Send `PUT /v1/installation/push` with `Content-Type: application/json`, the ETag in `If-Match`, and a new random 128-bit `Idempotency-Key`.
4. Persist the returned baseline before using it for recovery. Do not obtain a separate checkpoint to initialize registration.

Session IDs and idempotency keys use exactly 32 lowercase hexadecimal characters. A request has exactly these fields:

```json
{
  "registrationId":"11111111111111111111111111111111",
  "provider":"fcm",
  "destination":"<private-device-token>"
}
```

The repeated-digit identifier is synthetic, not a suitable production random ID. `fcm` is the only accepted provider. Destinations must be nonempty UTF-8 strings of at most 8 KiB. The entire JSON body is limited to 16 KiB. Unknown fields, duplicate fields, nulls, invalid types, malformed identifiers and invalid UTF-8 are rejected with `400 invalid_arguments`. Unsupported media types return `415`.

For `If-Match: "0"`, a successful first registration returns `200`, `ETag: "1"` and:

```json
{
  "registrationId":"11111111111111111111111111111111",
  "provider":"fcm",
  "enabled":true,
  "baselineCursor":"<opaque-server-checkpoint>"
}
```

The baseline and registration commit in one SQLite transaction against the acceptance ledger. Existing history is silent. Concurrent acceptance falls on one committed side of that baseline; recovery from it includes subsequent changes.

Token refresh uses the current session ID, a new idempotency key and current ETag. It preserves the session's original baseline, even if alerts arrived since registration. A new session captures a fresh baseline. A retired session ID cannot be used for a new mutation, even after restart.

## Remove a destination

Send `DELETE /v1/installation/push` with the current `If-Match` and a new `Idempotency-Key`. Send no body or query parameters. Success returns `200`, a new ETag and the disabled representation shown above. Removal does not delete alerts or revoke read credentials.

Re-enabling requires a fresh session ID and captures a current silent baseline. Muted history must not be announced. Removal atomically invalidates queued destination work. Local Android muting and cancellation are still pending in #22.

## Retry without overwriting newer state

Persist the original request, ETag and idempotency key until its outcome is known. Retry an uncertain request unchanged. JSON field order and whitespace do not matter. Replay returns the originally committed representation and ETag before checking the obsolete precondition. Its ETag may be older than current state: replay acknowledges the original operation, not the current registration. It cannot reactivate or remove a newer session. Reload current state before another mutation.

| Response | Meaning |
| --- | --- |
| `400 invalid_arguments` | Missing, repeated or malformed mutation headers; invalid body or query |
| `409 idempotency_conflict` | The installation already used that key with different content, method or precondition |
| `412 registration_changed` | The version is obsolete, or the requested session is retired; reload before reconciling |
| `503 storage_unavailable` | Safe retryable storage failure; preserve the original request |

The backend persists session history and mutation acknowledgements across restart. It retains only a digest of historical request content, not historical destination tokens. The current destination remains private SQLite data. Queued work references the current registration version and the credential used for registration; revoking that credential disables its future dispatch. A permanent destination failure disables dispatch, advances the ETag and exposes the disabled representation. Repair under the same retained session preserves its original baseline; explicit removal retires that session. Protect the database and do not copy it into ordinary diagnostic exports or logs. Existing arbitrary-backup-rollback limitations still apply; restart safety is not a rollback-detection guarantee.

## Verify locally

From the repository root, run:

```sh
cd backend
GOTOOLCHAIN=go1.26.0 go test ./internal/httpapi ./internal/lifecycle -run 'TestHTTP.*Push|TestRegistration|TestPushRegistration|TestRemoval' -count=1
GOTOOLCHAIN=go1.26.0 go test ./...
GOTOOLCHAIN=go1.26.0 go test -race ./...
GOTOOLCHAIN=go1.26.0 go vet ./...
GOTOOLCHAIN=go1.26.0 go run ./cmd/varde-demo
```

Registration tests use synthetic destinations and real file-backed SQLite through lifecycle and authenticated HTTP Interfaces. They cover replay/restart, installation isolation, revoked access, strict request bounds, token refresh, removal/re-enable, and concurrent acceptance against a second store connection. The demo checks backend startup, HTTP acceptance, restart, and injected fake-provider registration/retry. No Google service, live push, homelab deployment or Android platform behavior is verified by these checks.
