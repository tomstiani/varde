# Optional typed alert input: authoring examples

Design discussion: **2026-10-19**. The [#39 contracts](../specs/rich-alert-contracts.md), published in [GitHub #39](https://github.com/tomstiani/varde/issues/39), are approved and define all fields, bounds and semantics. This page introduces the sender experience; it is not a second schema or a claim of runtime support.

**Implementation warning:** the [current backend](../how-to/run-backend.md#submit-and-read-an-alert) accepts only omitted `data` or `[]`. All nonempty examples below are future extension fragments and are rejected by that implementation. `dataDefaults` and provider configuration are also not implemented. No real alert content, credentials or model calls were used.

## Start with text, add facts when useful

Plain-text input remains valid under the approved current contract. Senders do not need typed data or model credentials to submit an alert. BYOK belongs to the homelab backend operator.

Typed entries describe supplied facts—not UI components, model prompts or preferred layouts. The backend prepares candidates; Jev selects relevance/order. Backend and Android upgrades are coordinated; no backward-version negotiation or downgrade path is required.

## 1. Add one measurement

Extension fragment for an ordinary alert:

```json
{
  "data": [
    {
      "type": "measurement",
      "label": "CPU usage",
      "value": 83,
      "unit": "%"
    }
  ]
}
```

The value and unit are supplied; source and measurement time remain unknown. No ID is required. A metric may show this fact without inventing a source/time. It cannot create a history chart from this value alone.

Values are JSON numbers, not formatted strings such as `"83%"`. The approved contract defines exact decimal bounds/comparison; neither model output nor binary floating-point rounding replaces the submitted value. Original text stays unchanged and readable.

## 2. Add source and history

```json
{
  "dataDefaults": {
    "source": "synthetic-source",
    "resource": "synthetic-host",
    "collector": "synthetic-collector"
  },
  "data": [
    {
      "type": "measurement",
      "label": "CPU usage",
      "unit": "%",
      "value": 83,
      "measuredAt": "2026-10-19T12:02:00Z",
      "samples": [
        { "measuredAt": "2026-10-19T12:00:00Z", "value": 20 },
        { "measuredAt": "2026-10-19T12:01:00Z", "value": 40 },
        { "measuredAt": "2026-10-19T12:02:00Z", "value": 83 }
      ]
    }
  ]
}
```

Grouping establishes that the measurement and samples share quantity/resource/unit context. Every sample supplies its own time; parent `measuredAt` describes the standalone value only. Receipt/event time never becomes measurement time.

Source/resource/collector are independent optional supplied labels, not values inferred from sender identity. Entry fields override defaults; explicit null clears an inherited field. Empty/blank labels are invalid, not a reset convention. Separate collectors use separate entries; matching labels or values never merge them.

You may omit standalone `value` and send history only. The backend can offer a chart plus a latest-supplied-reading metric with its actual sample time. If the newest sample is null, omit that metric rather than showing an older numeric sample as latest. An explicit null standalone value does not silently become a historical metric.

A null timestamped sample is a gap: no zero-filling, interpolation or continuous line across missing observations. Sender sample order is retained in originals; presentation samples are sorted chronologically. Reject duplicate instants, including equivalent offsets, and contradictory standalone/history values at the same instant. A standalone value without time remains independent.

## 3. Supply records or an ordered sequence

```json
{
  "data": [
    {
      "type": "records",
      "label": "Synthetic job results",
      "columns": [
        { "key": "job", "label": "Job" },
        { "key": "duration", "label": "Duration", "unit": "ms" }
      ],
      "rows": [
        { "job": "nightly", "duration": 1200 },
        { "job": "hourly", "duration": null }
      ]
    },
    {
      "type": "sequence",
      "label": "Synthetic backup stages",
      "steps": [
        { "label": "Snapshot", "status": "complete" },
        { "label": "Upload", "status": "failed" },
        { "label": "Verify", "status": "not run" }
      ]
    }
  ]
}
```

Records declare columns and exact row keys, with scalar string/number/boolean/null cells. A known per-column unit permits numeric/null cells only. No nested cells or executable formatting; no mandatory row IDs.

Sequence list order is authoritative. Optional status text is supplied plain text, not an inferred severity or action. Jev can choose the complete flow candidate but cannot invent/reorder its internal steps or add branches/dependencies.

## Confirmed initial limits

| Limit | Value |
| --- | ---: |
| Total submitted body | 65,536 bytes |
| Typed entries | 8 |
| Samples per measurement | 120 |
| Record-set rows / columns | 20 / 6 |
| Steps per sequence | 8 |
| Selected presentation components | 8 |

The complete contract also defines string/decimal/timestamp/depth bounds and a 17-candidate catalog cap. One measurement can offer a metric and a single-series chart. Combined multi-series charts are deferred.

## Failure behavior

- Invalid/excessive typed input is rejected before acceptance with safe field-level guidance. Never silently discard an invalid entry or accept only its text portion.
- Empty records/sequences and missing readings are valid but may offer no useful candidates. Without useful typed candidates, bypass Jev and retain original text.
- Exact original summary text may accompany structured candidates; automatic prose segmentation and prose-to-measurement extraction are deferred.
- After acceptance, invalid selection, component overflow or any invalid assembled component causes whole-view fallback to unchanged original text. Never salvage a valid-looking subset.
- Optional entry IDs are alert-local; no global registry or mandatory IDs for every value/sample. The initial contract does not define cross-entry relationship fields.

Use the [approved feature specification](../specs/typed-rich-alert-views.md) and [#38](https://github.com/tomstiani/varde/issues/38) for implementation scope, and the [concrete contracts](../specs/rich-alert-contracts.md) for exact grammar. GitHub is canonical for current progress; these examples do not imply typed ingestion, rich rendering or live model access is implemented.
