# Run the first backend slice locally

Build and test authenticated alert acceptance, original retrieval, duplicate recovery, and restart-safe text fallback. This implements the foundation in [issue #9](https://github.com/tomstiani/varde/issues/9), not the full Android release.

**Current limits:** plain-text alerts only; no typed measurements, rich generation, paid model requests, push, or change-sync requests. [Public history pagination](read-history.md) and a [single-connection Android inbox](../../android/README.md) are implemented; multiple connections and Android older-history browsing remain later slices. The executable accepts only literal loopback listen addresses. It is a development increment, not supported non-loopback deployment packaging.

## Prerequisites

- Go 1.26 or later, with permission to download module dependencies during setup.
- A private, writable directory on a local filesystem with POSIX file permissions for SQLite (macOS/Linux). Do not place it on a network share.
- A supported Go race-detector platform and C toolchain if running `go test -race`. The normal executable uses a pure-Go SQLite driver and does not require CGO.

Run the commands below from `backend/`, the Go module in this monorepo:

```sh
cd backend
```

Tests and the demonstration use synthetic alerts and local temporary databases, not your homelab records. Build outputs and the default data directory are relative to `backend/`.

## Verify the slice

```sh
go mod download
go test ./...
go vet ./...
go test -race ./...
go run ./cmd/varde-demo
```

After dependencies are downloaded, ordinary tests and the demonstration need no external network access or provider credentials. The demonstration builds a temporary executable, issues credentials into memory, starts the backend on an ephemeral loopback port, submits an alert, restarts the process, retrieves the original, and resolves a duplicate. It verifies terminal text fallback and removes its temporary database afterward.

Expected final line:

```text
Verified: credential issuance, durable acceptance, restart, original retrieval, duplicate recovery, and text fallback.
```

The tests also kill a subprocess immediately before and after the acceptance commit, reject a real failed SQLite commit, and exercise concurrent duplicate/capacity checks across independent database connections. These checks prove process-crash recovery with intact storage, not protection against disk loss or every power-failure scenario.

## Create local identities and credentials

```sh
go build -o bin/varde ./cmd/varde
./bin/varde -db data/varde.sqlite identity create sender n8n
./bin/varde -db data/varde.sqlite identity create installation phone
```

Each command returns JSON containing a stable identity `id`, `role`, and `name`. Names are labels, not identity keys; creating another sender with the same name creates a different duplicate namespace.

Replace `OWNER_ID` with a returned identity ID:

```sh
./bin/varde -db data/varde.sqlite credential issue OWNER_ID
```

This returns the credential ID, identity ID, and **secret shown only on issuance**. Store the secret in the sender/device's secure configuration or a password manager. Retain the credential ID so you can revoke it later. Do not put secrets in shell command arguments, shell tracing, ordinary logs, or committed files. Protect terminal capture and any redirected output.

Only a SHA-256 verification hash of each random 256-bit credential is stored. The backend cannot display the secret again. Issue a replacement for the **existing owner ID** to rotate access without changing sender identity.

Replace `CREDENTIAL_ID` with the issuance record's credential ID:

```sh
./bin/varde -db data/varde.sqlite credential revoke CREDENTIAL_ID
```

Revocation is idempotent for an existing credential. An unknown credential ID returns `not_found`. Revocation does not delete originals, sender identity, or accepted processing. Other credentials for that identity remain usable.

## Start and stop the backend

```sh
./bin/varde -db data/varde.sqlite serve -listen 127.0.0.1:8080
```

Global `-db` must precede the command. Unknown commands, wrong argument counts, and invalid flags return `invalid_arguments` before opening SQLite or creating a data directory, even if the database path is unusable. Credential and alert rules remain lifecycle validation, not command grammar.

`serve` accepts `-listen`; use `127.0.0.1` or `::1`, not a hostname or external address. Port `0` asks the OS to assign an available port.

The process emits one readiness record to standard output, for example:

```json
{"event":"listening","address":"127.0.0.1:8080"}
```

Keep one backend process per database. Local credential commands can run while it serves requests. Stop the backend with Ctrl-C or SIGTERM; it stops the worker and allows up to five seconds for HTTP shutdown.

HTTP bearer credentials are appropriate here only because the executable binds to loopback for development. Any later non-loopback deployment must provide an operator-controlled HTTPS path. A tunnel or network-access product does not replace credential authentication.

## Submit and read an alert

`POST /v1/alerts` requires a **sender** bearer credential and `Content-Type: application/json`. The body must be uncompressed UTF-8 and at most 65,536 bytes, including formatting.

```json
{
  "schemaVersion": 1,
  "eventId": "nas01-disk-pressure-2026-01-01T12:00:00Z",
  "title": "NAS disk pressure",
  "summary": "The backup filesystem is approaching its configured limit.",
  "priority": "high",
  "observedAt": "2026-01-01T12:00:00Z",
  "data": []
}
```

| Field | Accepted value |
| --- | --- |
| `schemaVersion` | Integer `1` |
| `eventId` | Nonblank string, at most 256 Unicode code points |
| `title` | Nonblank string, at most 256 Unicode code points |
| `summary` | Required string, at most 8,192 Unicode code points; may be empty |
| `priority` | `low`, `normal`, `high`, or `critical` |
| `observedAt` | RFC 3339 timestamp with explicit timezone; submitted spelling is preserved |
| `data` | Omitted or `[]` only in this increment |

Reject unknown fields, duplicate JSON object keys, invalid encoding, and incorrect types. Do not trim or rewrite submitted strings. Nonempty `data` is explicitly unsupported until the typed-data increment; the full release's eight-entry allowance is not yet implemented.

A new alert returns `201`. The response contains `id`, `acceptedAt`, `original`, and `presentation`. Presentation initially has `state: "pending"` and `revision: 0`; the worker completes it as `state: "fallback"` and `revision: 1`. The fallback reason is stored internally as `model_not_configured`, not exposed as a private diagnostic record to installations.

`GET /v1/alerts/{alertId}` requires an **installation** bearer credential. It returns the same representation and remains usable while processing is pending. An installation reads all alerts in this homelab; a sender cannot read them. This increment has no push destination management. Installations can also [page history summaries](read-history.md); full originals remain loaded on demand.

Identical submissions under the same sender/event identity return `200` and the existing ID. Changed content returns `409`, even when capacity is full. Formatting and object-key ordering do not affect comparison; omitted `data` equals `[]`. Revoking or replacing credentials does not reset the sender's event-ID history.

The backend retains at most 1,000 unfinished alerts and rejects new identities retryably when full. Completed fallback alerts remain retained indefinitely without consuming unfinished capacity. Accepted work does not expire after restart. Terminal fallback does not regenerate when a later version adds model configuration.

## Errors and diagnostics

Errors contain a safe `error` category and `message`, plus a known `field` where useful. They do not echo submitted values or database errors.

| Status | Meaning |
| --- | --- |
| `400` | Invalid JSON/encoding, duplicate keys, or incomplete body |
| `401` | Missing, invalid, or revoked credential |
| `403` | Credential has the wrong role |
| `404` | Unknown alert, after authenticating an installation |
| `409` | Sender event ID already has different content |
| `413` | Body over 65,536 bytes |
| `415` | Unsupported content type, charset, or compression |
| `422` | Invalid schema fields or typed data not yet supported |
| `503` | Capacity reached or storage unavailable; retry using the same event ID |

A `503` includes `Retry-After: 5`; this is not a guarantee that capacity or storage will recover in five seconds. Never interpret a timeout or missing response as proof that acceptance did not commit. Reuse the same sender event ID on retry.

The executable emits no access log or raw HTTP panic trace. Its readiness record contains only event/address metadata; command failures use sanitized categories. Do not enable a proxy or debugger that records authorization headers or request bodies.

## Storage handling

New data directories use mode `0700`; new database files use `0600`. Existing database files readable by other users are rejected. Existing directory permissions are not automatically changed: the operator must keep the containing directory private. SQLite uses WAL journaling, `synchronous=FULL`, foreign keys, and a five-second lock wait.

The new binary transactionally upgrades version-one stores for history ordering and durable cursor metadata. Stop older backend processes before upgrading, and do not use old binaries to write upgraded stores. Migration preserves originals, credentials, pending work and change records. Failure rolls back the upgrade rather than starting with partial schema changes. Take a consistent backup before upgrading important data.

Do not copy a live `.sqlite` file alone: committed records can still reside in its WAL. Supported backup/restore commands and sync-cursor invalidation are later work; this development increment is not an archival backup procedure. The acceptance guarantee assumes intact storage and working filesystem durability semantics.
