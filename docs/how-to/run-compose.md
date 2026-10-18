# Run Varde with Docker Compose and Pangolin

Run the plain-text backend on a Linux server and reach it through your existing Pangolin site. This is an initial homelab deployment, not a claim that remote HTTPS or n8n acceptance has passed. Push, AI-generated views, background recovery and supported backup tooling are not implemented. [Foreground change recovery](recover-alerts.md) is available when opening Android.

## Prerequisites

- A Linux server with Docker Engine, the Compose plugin and Git.
- An existing Pangolin site with a connected Newt connector on that server.
- A hostname with an Android-trusted HTTPS certificate, managed through Pangolin.
- Local Docker storage. Do not put the SQLite volume on NFS or SMB.

The backend shares the Linux host network but listens only on `127.0.0.1`. There is no Docker port mapping and no need to open an inbound backend port. Local host processes and other host-network containers can reach it; Varde still requires bearer authentication.

Newt must use the same host network namespace. If your existing Newt runs in Docker, its service needs `network_mode: host` rather than bridge networking. Do not add a second connector using the same site credentials. Review any other resources that rely on Newt's current Docker networks before changing it; container-name targets may stop resolving. Newt on another machine cannot reach this loopback listener.

This setup is Linux-specific. Docker Desktop host networking is not the deployment or verification target.

## 1. Build and start

Clone this repository onto the server, then run these commands from its root:

```sh
docker compose -p varde up -d --build
docker compose -p varde logs --tail 20 varde
```

Expect a readiness record containing `"event":"listening"` and `127.0.0.1:8080`. If that port is occupied, set `VARDE_PORT` to an unused port for every Compose command, or export it in your shell before continuing.

Verify that unauthenticated reads are rejected:

```sh
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
  http://127.0.0.1:8080/v1/alerts
```

Expect `401`. This is not a health endpoint; it checks that HTTP and authentication are working. Also check `docker compose -p varde ps` and the readiness log so that an unrelated service on the port is not mistaken for Varde.

The image builds from `backend/`, excluding local binaries, databases and environment files from its build context. It runs as UID/GID `10001`, drops Linux capabilities, and uses a read-only root filesystem. The named volume is writable and initialized with a private `/data` directory. Do not override its user or substitute a bind mount without preparing matching ownership and private permissions.

The Dockerfile uses version-series base-image tags, not immutable digests. Build and verify updates before relying on them.

## 2. Create separate credentials

Run commands from a private terminal. Disable shell tracing and terminal/session recording before issuing secrets. Do not paste secret output into logs, tickets or this repository.

```sh
docker compose -p varde exec -T varde \
  varde -db /data/varde.sqlite identity create sender n8n

docker compose -p varde exec -T varde \
  varde -db /data/varde.sqlite identity create installation phone
```

Each command returns an identity `id`. Issue a credential for each identity, replacing `OWNER_ID`:

```sh
docker compose -p varde exec -T varde \
  varde -db /data/varde.sqlite credential issue OWNER_ID
```

Each issuance returns a credential `id` and a one-time `secret`. Save them in a password manager. Give only the sender secret to n8n and only the installation secret to Android. Credentials are not container environment variables.

To rotate access, issue another credential for the same identity, update the client, then revoke the old credential:

```sh
docker compose -p varde exec -T varde \
  varde -db /data/varde.sqlite credential revoke OLD_CREDENTIAL_ID
```

Keep the sender identity when rotating: duplicate detection is scoped to that identity.

## 3. Route HTTPS through Pangolin

In your existing Pangolin configuration:

1. Create an HTTPS resource for a hostname such as `alerts.example.com`.
2. Select the site whose Newt connector runs on this Linux server.
3. Configure an HTTP target at `127.0.0.1`, port `8080` (or your chosen port).
4. Allow API requests without an interactive Pangolin browser-login challenge. Keep Varde bearer authentication enabled.
5. Preserve the `Authorization` header. Do not configure redirects for API requests or record authorization headers/request bodies in proxy logs.

Pangolin UI labels vary by version. The target is plain HTTP over loopback at the Newt site, not HTTPS to the backend and not `127.0.0.1` on the public Pangolin server. The public URL must use HTTPS with a certificate trusted by Android. Do not bypass TLS verification or weaken the release app's URL policy.

From another machine, check:

```sh
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
  https://alerts.example.com/v1/alerts
```

Expect `401`, not a redirect, HTML login page or proxy error. Do not use `curl -k` or follow redirects to make the check pass. A timeout or `502` usually indicates that Newt cannot reach the loopback target; inspect its network mode and site selection.

In Android, add a named connection with base URL `https://alerts.example.com` and the installation secret. An empty inbox is expected before submission.

## 4. Send a synthetic n8n alert

Use an n8n HTTP Request node:

- Method: `POST`
- URL: `https://alerts.example.com/v1/alerts`
- Authentication: a stored Header Auth credential with header name `Authorization` and value `Bearer YOUR_SENDER_SECRET`.
- Body content type: JSON.
- Redirect following: disabled.

Store the secret in n8n's credential store, not in the node's JSON or an exported workflow. Avoid retaining request headers or private alert content in execution/debug logs.

Use this synthetic body:

```json
{
  "schemaVersion": 1,
  "eventId": "homelab-compose-acceptance-001",
  "title": "Varde homelab test",
  "summary": "Synthetic alert sent through n8n and Pangolin.",
  "priority": "high",
  "observedAt": "2026-01-01T12:00:00Z",
  "data": []
}
```

Expect `201`. Refresh Android and open the unchanged original. Resubmit the identical body using the same sender credential: expect `200` with the same alert ID, not a second inbox item. Use a new `eventId` for each genuinely new test alert.

Restart the backend and confirm the original remains readable:

```sh
docker compose -p varde restart varde
```

## Persistence and updates

The `varde` Compose project keeps SQLite in its `varde-data` named volume. Use the same project name for subsequent commands. Stop containers without deleting data:

```sh
docker compose -p varde down
```

**Do not use `down --volumes` or delete/prune this volume unless you intend to erase alerts and credentials.** Do not copy a live SQLite file alone; committed data can be in its WAL. For a manual filesystem backup, stop Varde and copy the complete volume using your trusted backup process. Verify restoration separately. Built-in backup/restore tooling is not provided.

Before updating, preserve a stopped-volume backup. Pull the intended revision, then rebuild:

```sh
docker compose -p varde up -d --build
```

Schema upgrades may prevent rollback to an older binary. See [backend storage and migration limits](run-backend.md).

## Optional second backend

Use a separate project and port, consistently for all its commands:

```sh
VARDE_PORT=8081 docker compose -p varde-second up -d --build
VARDE_PORT=8081 docker compose -p varde-second exec -T varde \
  varde -db /data/varde.sqlite identity create installation phone
```

The separate project creates a separate volume. Issue fresh sender and installation credentials there. Add a second Pangolin hostname targeting `127.0.0.1:8081`, then save a distinct named connection in Android. Never share a database volume between running backends.

## Automated versus operator verification

On Linux, with Docker, Compose and Python 3 installed, run the disposable container smoke test:

```sh
python3 scripts/test-compose.py
```

It builds and starts a unique Compose project on loopback port `18080`, issues synthetic credentials in memory, submits an alert, recreates the container, checks retained reads and duplicate recovery, then removes only its own containers and volume. Set `VARDE_TEST_PORT` if needed. Do not interrupt cleanup; if it fails, inspect the printed smoke-project name before removing leftover resources.

The smoke test does not provision or verify Pangolin, n8n, trusted HTTPS or the physical phone. Those are operator steps above. It also does not replace the full [two-backend acceptance criteria](../specs/android-inbox-tickets/05-two-backend-acceptance.md), including outage/recovery and connection changes. Record observed results without secrets; keep unobserved criteria open.
