# Coding standards

Optimize this repository for a human reading and changing it. Follow the [Go project-structure guide](https://www.glukhov.org/app-architecture/code-architecture/go-project-structure/), especially its simplicity-first guidance. Its larger architecture examples are options, not a required directory template or an official Go standard.

## Files and functions

- Give each file one cohesive responsibility and a descriptive name: `submit.go`, `credentials.go`, or `timestamp.go`.
- Put substantial operations in their own files. Keep their small, closely related helpers in the same file so readers can follow the operation locally.
- Write functions as readable sequences of steps. Extract a named helper when a step contains a distinct task, nested branching, or a substantial algorithm. Prefer named functions over long anonymous callbacks.
- Split by responsibility, not an arbitrary line limit. A short linear function is better than a chain of one-line forwarding helpers.
- Use descriptive local names where the meaning is not immediate. Expand dense struct literals and SQL definitions so fields and constraints can be scanned.
- Use comments for non-obvious contracts, security rules, and workarounds. Keep descriptions of behavior in names and tests rather than narrating each line.

## Packages and dependencies

- Keep executable entry points under `backend/cmd/` minimal. Put substantive application logic in named packages under `backend/internal/`.
- Organize private logic around a feature or cohesive responsibility. Keep package trees shallow; avoid generic `utils`, `common`, or parallel handler/service/repository layers for each operation.
- Preserve deep Modules: small Interfaces hide transaction, validation, and recovery details. File extraction does not require another package, public Interface, or storage abstraction.
- Keep HTTP translation separate from lifecycle rules. Keep validation facts and safe rule explanations in the lifecycle Module; the HTTP Adapter owns status codes, headers, and JSON formatting.
- Validate complete command grammar before acquiring storage. Keep credential and alert policy in the lifecycle Module rather than duplicating it in command parsing.
- Keep SQLite persistence inside the lifecycle Implementation until a real need justifies another Seam or Adapter.
- Introduce `pkg/` only for code intentionally consumed by another module. Introduce `go.work` only when multiple Go modules actually need it. Android is not a Go module.
- Use the vocabulary in [GLOSSARY.md](GLOSSARY.md).

## Tests and refactoring

- Keep `_test.go` files beside the package they exercise. Name tests and files after behavior, not private helper names.
- Prefer the same Interface that callers use. Keep narrow storage/fault-injection checks only for guarantees that are not yet observable through a public Interface.
- Preserve behavior while reorganizing files: transaction scope, credential checks, limits, and crash recovery must not change for readability alone.
- Verify backend changes from `backend/` with `go test ./...`, `go test -race ./...`, and `go vet ./...`. Run `go run ./cmd/varde-demo` when startup, operator commands, or the HTTP workflow changes.
