# Submit a supplied metric

The backend accepts up to eight standalone measurements with optional units, measurement times and provenance. Plain-text alerts remain valid. Production generation is not implemented: accepted alerts still finish as original-text fallback without model calls.

## Current rollout

The user approved this staged scope for [#40](https://github.com/tomstiani/varde/issues/40):

- Accept standalone `measurement` entries and optional `dataDefaults` under the [approved exact-decimal, timestamp and metadata rules](../specs/rich-alert-contracts.md).
- Reject any `samples` field, including empty or null samples, before acceptance. Reject records and sequences. History/charts, records/tables and sequences/flows belong to #43–#45.
- Reject the entire invalid submission with `422`, `invalid_field`, a safe field path and local rule guidance. Do not discard unsupported data or accept a text-only substitute.
- Support only metric references to standalone `value` and exact, complete summary-text references. Unknown or malformed rich content rejects the whole view.

This rollout decision does not change the final grammar in #39. The complete contract fixtures include later slices; they are not all runnable submissions in this increment.

## Example original

Submit this invented payload to `POST /v1/alerts` using your existing sender authentication workflow:

```json
{
  "schemaVersion": 1,
  "eventId": "synthetic-cpu-1",
  "title": "Synthetic CPU alert",
  "summary": "The supplied reading belongs to synthetic-host-a.",
  "priority": "high",
  "observedAt": "2026-10-19T12:03:00Z",
  "dataDefaults": {
    "source": "synthetic-monitor",
    "resource": "synthetic-host-a"
  },
  "data": [{
    "type": "measurement",
    "label": "CPU usage",
    "value": 83.00,
    "unit": "%",
    "collector": "synthetic-agent",
    "measuredAt": "2026-10-19T12:00:00.123456789Z"
  }]
}
```

A standalone value is required and may be an exact decimal number or explicit null. Null is missing, not zero, and offers no metric candidate. Missing unit, time or provenance stays unknown. An explicit entry null clears its inherited provenance field independently. Event time never supplies measurement time.

The first accepted original retains number spelling, strings and array order. Retrying `83.00` as `8.3e1` under the same sender/event identity returns the existing alert. Changed values, provenance or other content remain conflicts. Absent and explicit-null metadata are different inputs.

## Read and render

Backend and Android upgrades are coordinated. There is one current JSON contract, not version negotiation or downgrade support.

Terminal rich detail reads carry `richView` beside unchanged `original` and `presentation: {"state":"rich","revision":1}`. History carries state/revision, never rich bodies. Pending remains revision 0; a terminal outcome publishes exactly one revision/change entry.

The Android detail screen resolves every component against validated supplied originals before rendering anything rich. It displays exact numeric tokens, native text and provenance/time labels, with unknown metadata identified explicitly. Original title, summary, priority and event time remain separately readable. Invalid version, type, field, reference, duplicate or overflow causes whole-view original-text fallback.

Rich publication is currently exercised only by deterministic selection fixtures in test binaries. There is no sender field, operator flag, endpoint or fake production generation mode for supplying selections. BYOK configuration and native Jev execution remain separate work. No live provider or notification behavior is introduced.

## Storage integration

Schema version 5 transactionally rebuilds the alert table to allow terminal `rich` state and a reference-only `rich_view` blob. It retains originals, IDs, acceptance ordering, credentials, change history, pending work and push references. Foreign keys are checked before commit and re-enabled before startup returns.

Stop older backend processes before upgrading and retain a consistent private backup. Old binaries must not write the upgraded database. #41 must integrate its configuration manifest/schema work with this migration and preserve the pending-to-terminal publication boundary. Durable paid attempts and native transport belong to the execution slice, not this one.
