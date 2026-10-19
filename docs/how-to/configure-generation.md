# Configure backend-owned Jev access locally

Varde loads optional local BYOK configuration, but **does not execute Jev requests yet**. All accepted alerts still reach terminal original-text fallback, including with `generation_ready`. Checks and startup make no provider, account, model or connectivity requests. This guide prepares configuration; it does not enable rich generation or verify a provider account.

Generation is disabled by default. Keep it disabled by omitting `-generation-config`, or select a file containing:

```json
{"schemaVersion":1,"enabled":false}
```

No environment variable, readable key, sender credential or Android installation credential enables generation. Outbound provider keys are separate from inbound Varde credentials.

## Check a native-process configuration

Use a locally built `varde` executable. The paths below are examples, not existing deployment paths. Use invented key material for local checks; never pass a real key in command arguments.

1. Create a private temporary fixture directory:

   ```sh
   fixture=$(mktemp -d)
   printf '%s\n' 'invented-not-a-provider-key' > "$fixture/jev-key"
   chmod 600 "$fixture/jev-key"
   ```

2. Write `$fixture/generation.json` using its absolute key path:

   ```sh
   cat > "$fixture/generation.json" <<EOF
   {"schemaVersion":1,"enabled":true,"configId":"jev-project-1","accountRef":"operator-project","model":"jev-1.13.0","apiKeyFile":"$fixture/jev-key"}
   EOF
   ```

3. Check the selected files as the same user/group that runs Varde:

   ```sh
   varde generation check -generation-config "$fixture/generation.json"
   ```

   Expect `{"event":"generation_ready"}`. This means local validation passed, not that the key works. The check does not open SQLite, compare retained revisions, or activate anything. Unavailable checks also exit successfully; inspect their fixed `event` category.

4. Activate through a controlled process restart, using your selected database:

   ```sh
   varde -db /absolute/path/varde.sqlite serve \
     -generation-config "$fixture/generation.json"
   ```

   Readiness stays on stdout. Stderr reports a structured `generation_configuration` event with a safe category. A revision conflict can appear here even after a successful file-only check. Slow or failed diagnostic writers do not block service or shutdown.

Files are read at startup, not watched. Changing either file requires restart. Do not use this temporary invented key as a deployment credential.

## Configuration reference

The selected configuration path must be absolute. JSON is a closed object, at most 8 KiB and depth 4; nested values are not part of this grammar. Duplicate/unknown fields, nulls and wrong types are rejected.

| Field | Rule |
| --- | --- |
| `schemaVersion` | Required integer `1` |
| `enabled` | Required boolean; only explicit `true` enables file checks |
| `configId` | Required when enabled; nonsecret revision, 1–64 ASCII letters/digits/underscore/hyphen, beginning with a letter |
| `accountRef` | Required when enabled; same syntax, operator-chosen local account/project label |
| `model` | Required when enabled; exactly `jev-1.13.0` |
| `apiKeyFile` | Required when enabled; absolute path, at most 4,096 bytes |

Optional fields may be staged while disabled, but supplied fields must still be valid. Disabled configuration never opens the key file. There is no endpoint/header override, moving model alias, ambient credential discovery, failover, hot reload, remote key API or Android key input.

The native Jev destination for later execution is fixed to `https://api.typesafe.ai/v1/systemone`; there is no production endpoint switch in this slice.

### Runtime key-file rules

- Select one regular, nonsymlink file. Directories, FIFOs and device files are rejected without reading them.
- Limit the complete file to 4 KiB. After removing at most one terminal LF or CRLF, require nonempty printable ASCII with no whitespace or control characters. A lone CR, blank lines, spaces and oversized files are invalid. No provider prefix is guessed.
- On Unix, the file must belong to the service user or root. Require service-readable mode bits, no world access and no group/other write. Group read is allowed only for the service's effective group; supplementary groups are not sufficient.
- For a native service-user-owned file, use `0600`. A root-owned file may use `0640` with the service's effective group.

The enabled startup reads the key once for validation and discards it in this configuration-only slice. SQLite stores only config ID, account label, model, prompt revision `candidate-choice-1`, contract version `1` and candidate version `1`, plus the active nonsecret revision. Neither the key, its path nor a key fingerprint is persisted. Raw file errors and configuration paths are not emitted.

## Mount files in Compose

The existing image runs as **UID/GID 10001:10001**, not root. A host `0600` file owned by your login user is usually unreadable there. Prepare a selected runtime file owned by `10001:10001` with `0600`, or by root with group `10001` and `0640`. Have your host administrator set those permissions; do not change unrelated credential files.

Use the [nonsecret configuration example](../examples/generation.json) and [Compose override](../examples/compose.generation.yaml). Its key path is the container path, not the host path. From the repository root, after creating your own selected files:

```sh
export VARDE_GENERATION_CONFIG=/absolute/host/path/generation.json
export VARDE_GENERATION_KEY_FILE=/absolute/host/path/jev-key

docker compose --env-file /dev/null -f compose.yaml \
  -f docs/examples/compose.generation.yaml run --rm --no-deps varde \
  generation check -generation-config /run/varde-generation.json
```

These environment variables select files only; they never contain key bytes. The override bind-mounts both files read-only at runtime and preserves the existing service hardening and data volume. Do not add keys to build context, images, Compose values or tracked files. Compose bind mounts preserve host ownership/mode; a file mount is not encrypted secret storage. Host/root administrators, backups and the Docker daemon remain trusted.

For an operator-approved activation, recreate the service with the same two Compose files and your selected files. This ticket does not authorize deployment. The disposable Linux packaging test is `python3 scripts/test-generation-compose.py`; it builds locally, uses invented files, tests UID/GID readability and removes only its uniquely named test resources. It does not use your selected files or deployment project.

## Rotation, revision conflicts and unavailable generation

Reuse `configId` for same-account key replacement, including a different key-file path. Key contents/path do not define semantic identity. `accountRef` is an operator assertion, not proof that the replacement key belongs to that account.

Use a new `configId` for a different account/project or changed semantic manifest. A restart records activation. Reusing a retained ID with different account/model/prompt/contract/candidate semantics reports `generation_configuration_invalid` and clears active generation. Previously used IDs retain their manifests even across disabled restarts. An unsupported model is invalid even with a new ID.

Safe categories are:

- `generation_disabled`: no selected configuration, or explicit disabled configuration.
- `generation_configuration_invalid`: missing/unreadable/malformed configuration, unsupported fields/values, or a retained revision conflict.
- `generation_key_unavailable`: missing, unreadable, insecure or malformed selected key file.
- `generation_ready`: locally valid configuration and, at startup, no retained revision conflict. **Execution remains unimplemented.**

These generation failures do not prevent durable acceptance or unchanged original reads. Fatal storage/startup failures retain existing behavior. No provider work is created by this slice: the existing fallback worker completes pending alerts, including after restart. Enabling or correcting configuration never revives terminal alerts. Future execution must reconcile pinned pending-work retirement before any dispatch; this slice does not add paid attempts, scheduling or retries.

## Disclosure, retention and billing

Explicit enabled configuration records operator consent to future hosted disclosure under the approved contract, not a promise of local-only generation. Later Jev execution will disclose allowlisted title/summary and necessary supplied candidate context. Free text may itself contain sensitive data; do not submit secrets.

The [reviewed TypeSafe policy evidence](../research/hosted-model-byok.md) does not establish a fixed standard prompt/response deletion window. Enterprise zero-data-retention terms are not assumed for an ordinary account. BYOK does not mean zero retention or protection from provider access. Review current terms for your account before enabling actual execution.

Provider account/key billing controls belong to you. Varde has no dollar budget, daily model-attempt quota or guaranteed hard billing cap. Provider controls may be soft, account-wide or unavailable, and cancellation cannot guarantee remote billing stopped. No live trial, account verification or paid call is authorized by a local check.
