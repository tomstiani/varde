# Varde backend

Go + standard `net/http` + SQLite. The implemented slices support authenticated plain-text submission, retained originals, duplicate recovery, restart-safe text fallback, and snapshot-paginated history reads.

From this directory:

```sh
go mod download
go test ./...
go vet ./...
go test -race ./...
go run ./cmd/varde-demo
```

See [local setup and HTTP reference](../docs/how-to/run-backend.md) for prerequisites, credential commands, and current limits. See [backend architecture](../docs/architecture/backend.md) for the wider design.

## Code map

- `cmd/varde/`: minimal backend entry point.
- `cmd/varde-demo/`: minimal local verification entry point.
- `internal/app/`: complete command grammar validation before opening SQLite, followed by operator dispatch, startup, and shutdown.
- `internal/lifecycle/`: alert acceptance, credentials, validation and safe rule explanations, SQLite transactions, and fallback processing. `schema.sql` is embedded in the executable.
- `internal/httpapi/`: routing and HTTP-only request/response translation.
- `internal/demo/`: temporary-process and HTTP verification used by the demo.

Within a package, files follow responsibilities such as `submit.go`, `credentials.go`, and `timestamp.go`; related helpers stay beside the operation they support. See the [coding standards](../CODING_STANDARDS.md).

The named-connection Android inbox lives separately in [`android/`](../android/). This module does not build or package it. [History request and cursor contracts](../docs/how-to/read-history.md) support its timestamp-ordered newest-results view and later pagination.
