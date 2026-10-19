# Varde backend

Go + standard `net/http` + SQLite. The implemented slices support authenticated plain-text submission, retained originals, duplicate recovery, restart-safe text fallback, and snapshot-paginated history reads. The fallback worker recovers from temporary SQLite contention without restarting the backend; HTTP requests remain bounded and diagnostics cannot block processing.

From this directory:

```sh
go mod download
go test ./...
go vet ./...
go test -race ./...
go run ./cmd/varde-demo
```

See [local setup and HTTP reference](../docs/how-to/run-backend.md) for prerequisites, credential commands, and current limits. [Local Jev configuration](../docs/how-to/configure-generation.md) supports offline checks and restart activation only; provider execution is not implemented and all alerts still use original-text fallback. See [backend architecture](../docs/architecture/backend.md) for the wider design.

## Code map

- `cmd/varde/`: minimal backend entry point.
- `cmd/varde-demo/`: minimal local verification entry point.
- `internal/app/`: complete command grammar validation before opening SQLite, followed by operator dispatch, startup, shutdown, and bounded diagnostic output.
- `internal/lifecycle/`: alert acceptance, credentials, validation and safe rule explanations, SQLite transactions, and fallback processing. `schema.sql` is embedded in the executable.
- `internal/httpapi/`: routing, request deadlines, and HTTP-only request/response translation.
- `internal/demo/`: temporary-process and HTTP verification used by the demo.

The [worker recovery contract](../docs/specs/backend-worker-recovery.md) records retry classification, timing, shutdown and diagnostic guarantees, with tests at the lifecycle, executable HTTP and application-output interfaces.

Within a package, files follow responsibilities such as `submit.go`, `credentials.go`, and `timestamp.go`; related helpers stay beside the operation they support. See the [coding standards](../CODING_STANDARDS.md).

The named-connection Android inbox lives separately in [`android/`](../android/). This module does not build or package it. [History request and cursor contracts](../docs/how-to/read-history.md) support its timestamp-ordered history browsing. The Android `goHistoryTest` Gradle task verifies that transport/inbox path against an actual temporary Go/SQLite process.
