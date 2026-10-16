# Read retained alert history

Installations can page bounded alert summaries using `GET /v1/alerts`. Send the same installation bearer credential required for original reads. Sender credentials cannot list history. Use the operator-managed HTTPS path for non-local requests; see [backend setup](run-backend.md).

## Request

| Parameter | Contract |
| --- | --- |
| `limit` | Decimal integer 1–100; defaults to 50 |
| `order` | `acceptance` (default) or `acceptedAt` |
| `cursor` | Opaque continuation token from a previous response; omit for a fresh snapshot |

An example path is `/v1/alerts?order=acceptedAt&limit=50`. Do not put credentials in the URL.

`acceptance` returns newest backend acceptance sequence first. `acceptedAt` returns descending acceptance instant, then descending server alert ID for ties. These orders may differ if the backend clock moves backwards. Neither order uses original event time or changes when a sender resubmits an identical original.

## Response

```json
{
  "items": [
    {
      "id": "alert_example",
      "acceptedAt": "2026-01-01T12:00:00Z",
      "title": "NAS disk pressure",
      "priority": "high",
      "observedAt": "2026-01-01T11:59:00Z",
      "presentation": {"state": "fallback", "revision": 1}
    }
  ],
  "nextCursor": null
}
```

An empty inbox returns `items: []` and `nextCursor: null`. Summary pages exclude the original summary, sender event ID and private diagnostics. Fetch the full original on demand with `GET /v1/alerts/{alertId}`.

## Continue without gaps

1. Start without a cursor. The backend captures a committed acceptance watermark.
2. When `nextCursor` is non-null, request it using the same `limit` and `order`. URL-encode the query value normally; do not parse or edit the token.
3. Stop when `nextCursor` is null. This means that snapshot has ended, not that no new alerts have arrived.
4. Omit the cursor to refresh. New acceptances are included only in the fresh snapshot.

The watermark fixes membership while presentation revisions are current at each read. A detail request can show a newer fallback revision than its row. Pagination does not hold a transaction open across requests or implement change sync.

Cursor tokens are integrity-protected and bound to a store, order and limit. They survive ordinary backend restarts, but every continuation still requires a current credential. Revocation blocks continuation. Tokens do not work against a separately initialized database. There is no guarantee across restoring an older backup; supported restore/cursor invalidation remains unimplemented.

## Errors

- `400 invalid_arguments`: malformed, empty, duplicate or unknown parameters, or invalid order/limit.
- `400 invalid_cursor`: malformed, oversized, modified, foreign-store or option-mismatched token. Start a fresh snapshot rather than guessing its position.
- `401 unauthorized`: missing, invalid or revoked credential.
- `403 forbidden`: credential has the wrong role.
- `503 storage_unavailable`: storage unavailable or request timed out; `Retry-After: 5` is a hint, not a recovery guarantee.

History requests share the backend's five-second processing/response budget after headers, including authentication and database-connection queueing. Temporary SQLite writer contention can produce `503` while the worker recovers automatically. Uninterrupted reads under a writer lock are not promised. See [worker recovery and timeout behavior](../specs/backend-worker-recovery.md).

Responses use `Cache-Control: no-store`. Do not configure a proxy to cache authenticated content or log authorization headers, request bodies or cursor values.
