## Parent

Part of #11: First Android slice: named connections and a combined plain-text inbox. The approved parent specification is authoritative. This slice extends the first working single-connection inbox.

## What to build

A person can manage multiple named connections and read their newest alerts together, with an unambiguous source label and source-correct details. Connection changes must never misattribute old content or resurrect results from obsolete requests.

## Acceptance criteria

- [ ] Add, rename, edit and remove multiple saved connections through settings. Require trimmed nonblank names of at most 80 Unicode code points, rejecting duplicates under locale-independent case folding. Reject normalized duplicate URLs according to #11, including host/scheme casing, default port and trailing base-path slash; preserve meaningful path casing.
- [ ] Keep a stable local ID for each connection independent of its name, URL and credential. Identify alerts by connection ID plus server alert ID; equal server IDs on distinct connections remain separate rows.
- [ ] Initial load and refresh combine up to 50 newest rows using acceptance instant descending, connection ID ascending, then alert ID descending. Do not use original event time or friendly name as the merge key.
- [ ] Keep bounded page buffers and obtain a candidate or exhaustion result from every healthy source before emitting the next globally newest row. This applies even to the first combined batch; simply sorting one concatenated page per backend is insufficient when an empty source buffer has a continuation.
- [ ] Fetch backend pages of 50, at most four concurrent requests, with a 15-second end-to-end deadline per request and no hidden retries. Offline or invalid configurations remain editable and do not block saving settings. Preserve the existing explicit error states; the complete stale-pagination recovery policy follows in the outage slice.
- [ ] Label every inbox row and detail screen with its source, even with one connection. Detail navigation and reads use stable connection and alert identity, not list position or the currently edited connection.
- [ ] Renaming preserves loaded content and identity, updates labels everywhere and does not reorder equal-time results. Editing a label does not expose or require re-entering the credential.
- [ ] URL or credential changes clear that source's loaded rows, details, buffers, cursors and errors and start a new combined session. Cancel or ignore previous-configuration responses.
- [ ] Removal deletes local settings, credential and source content only, preserving the backend and unrelated displayed content. Add/remove membership changes establish a fresh combined session; late responses cannot restore removed content.
- [ ] Repeated refresh gestures and configuration changes cannot interleave obsolete results. Initial entry loads automatically; returning from details does not refresh. Process death keeps secure settings but not alerts.
- [ ] Test through the inbox Interface and screens: equal instants, varied timezones/fractions, empty sources, first-batch buffer replenishment, duplicate server IDs, name/URL normalization, secret handling, source-correct details, and delayed responses across edits/removal. Keep backend and single-source tests green.

## Scope boundary

Deliver the combined newest-results batch and complete connection management. The user-facing load-older workflow follows in the next slice. Preserve all parent TLS/security requirements; do not add discovery, aliases detection, background fetching or a federation gateway.
