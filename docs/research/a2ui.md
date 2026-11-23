# A2UI for rich views

A2UI is a plausible format and Android renderer for rich views, provided Varde uses a custom read-only catalog and keeps measurements in a backend-owned data model. Adoption remains an open decision until a rendering spike confirms the Compose renderer's fitness.

Findings reflect public documentation as of October 2026. Recheck versions before implementation.

## What A2UI is

A2UI (Agent-to-User Interface) is an Apache-2.0 protocol for agent-generated native UI, maintained at [a2ui-project/a2ui](https://github.com/a2ui-project/a2ui). A server sends JSON messages; the client renders them using only components declared in a registered **catalog**. No executable code is transmitted. See the [protocol overview](https://a2ui.org/concepts/messages/).

Each surface has two independent parts:

- **Components** (`updateComponents`): a flat list of typed components, each referencing children by ID.
- **Data model** (`updateDataModel`): a JSON document. Component properties bind to it with JSON Pointer paths, such as `{"path": "/cpu/value"}` in v0.9.1.

The [data-binding documentation](https://a2ui.org/concepts/data-binding/) explicitly allows deterministic server code, rather than the model, to populate the data model.

## Android support

The official [Jetpack Compose renderer](https://developer.android.com/develop/ui/compose/agentic) consists of:

- `androidx.a2ui.compose:compose-runtime` and `compose-ui`, at **1.0.0-alpha02** (21 October 2026). See [release notes](https://developer.android.com/jetpack/androidx/releases/a2ui-compose).
- `androidx.a2ui:a2ui-model` and `a2ui-engine` for parsing, schema validation and message processing.
- `androidx.compose.material3:material3-a2ui` for a Material 3 implementation of the Basic Catalog.

Custom components implement `A2uiComponent` and are grouped with `A2uiCatalog(catalogId, components, functions)`. An `A2uiMessageProcessor` can register several catalog versions simultaneously, so stored surfaces built against an older catalog keep rendering. See [managing catalogs](https://developer.android.com/develop/ui/compose/agentic/manage-catalogs).

The renderer supports protocol **v0.9.1** only. The current specification is v1.0, which renames envelopes and changes binding syntax (`@path`).

## Basic Catalog gap

The [v0.9.1 Basic Catalog](https://a2ui.org/specification/v0.9.1-basic-catalog-implementation-guide/) contains Text, Image, Icon, Video, AudioPlayer, Row, Column, List, Card, Tabs, Modal, Divider, Button, TextField, CheckBox, ChoicePicker, Slider and DateTimeInput.

It has no metric, chart, table or flow. Varde's approved components must therefore be custom catalog entries.

## Backend SDKs

Official agent SDKs exist for TypeScript, Python and Dart. Go has only community libraries, such as [`tmc/a2ui`](https://pkg.go.dev/github.com/tmc/a2ui) and [`satorunooshie/a2ui-go`](https://github.com/satorunooshie/a2ui-go). See [SDKs](https://a2ui.org/concepts/sdks/). A Go backend could instead validate surfaces with JSON Schema against Varde's own catalog.

## Fit with Varde

### Grounding through the data model

Deterministic backend code writes the data model from the alert's supplied data. Layout generation produces only components. Custom catalog schemas make measurement properties **binding-only**: for example, `Metric.value` accepts a data binding and rejects a literal number. A layout that invents a measurement then fails schema validation and falls back to the original alert.

### Read-only catalog v1

A proposed Varde catalog v1 contains:

| Component | Origin | Notes |
| --- | --- | --- |
| Text, Column, Row, Card, Divider | Basic Catalog | Text Markdown limited to the Basic Catalog's simple subset |
| Metric | Custom | Value, unit, source and measurement time are required bindings |
| LineChart | Custom | Samples bound from the data model; missing samples stay missing |
| Table | Custom | Rows bound from the data model |
| Flow | Custom | Ordered stages with status |
| SourceStamp | Custom | Visible source and time identity for adjacent content |

Catalog v1 excludes interactive components (Button, inputs, `openUrl`) and media (Image, Video, AudioPlayer). Excluding media also prevents the phone from fetching URLs supplied by an alert source.

Interactive components belong to a later catalog version; see [alert responses](../specs/alert-responses.md).

### Stored surfaces, not streaming

A2UI is transport-agnostic. The homelab backend validates and stores each alert's completed message list, then serves it through the history API. The Android app replays it into a message processor. The original alert remains a native screen outside the surface.

## Layout generation options

Both options use the same catalog, data model and validation.

1. **Candidate selection.** The backend prepares component subtrees; a decision model selects and orders them. This matches the design partially exercised in earlier offline experiments (preserved in Git history at `docs/research/alert-view-experiments.md`). It is predictable, but it does not use A2UI's generative capability.
2. **Model-authored layout.** A model writes `updateComponents` freely within the catalog while deterministic code owns the data model. Binding-only schemas prevent invented measurements. This removes candidate preparation and may produce better layouts, but relevance, generated text and fallback rates are untested.

The spike should try model-authored layout first, keeping candidate selection as the fallback.

## Risks

- The Compose renderer is alpha; alpha02 already changed public API.
- Android trails the specification (v0.9.1 versus v1.0). Catalog versioning reduces, but does not remove, migration cost.
- Server-side validation is still required so fallback is decided before notification. Go lacks an official SDK.
- A2UI is designed for streaming conversation; Varde uses a narrow, store-and-forward subset.

## Alternatives

- **A2UI wire format with an in-house Compose renderer.** Avoids the alpha dependency and preserves a migration path.
- **No A2UI.** Return to an in-house versioned layout list rendered natively.

## Proposed spike

1. Implement custom `Metric` and `LineChart` components with binding-only values against alpha02.
2. Hand-write stored message lists for a high-CPU alert and a partial-backup alert.
3. Confirm that both render, a literal measurement is rejected, and an invalid surface falls back to the original alert.
4. Record findings and decide between A2UI's renderer, an in-house renderer, or no A2UI.
