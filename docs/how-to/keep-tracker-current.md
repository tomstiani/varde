# Keep delivery and tracker status aligned

Use GitHub issues for live completion, acceptance checkboxes and dependency readiness. Local ticket contracts retain plain requirement bullets and links to those issues. Verification records describe observations at a particular change/run; they are not live status dashboards.

## Deliver a ticket

1. Draft the PR with the delivery checklist in `.github/pull_request_template.md`, even when supplying a custom body file.
2. Put one explicit `Closes #N` line per fully delivered implementation ticket. Show this closing intent when requesting publication approval. Use `Part of #N` for parent specs or incomplete work; state when maintenance has no linked implementation ticket.
3. Update docs for changed behaviour and limitations. Distinguish implemented features from approved plans and unobserved acceptance. Link recorded evidence rather than copying task progress into local files.
4. Wait for the current head's CI before an approved merge.
5. After merging, verify the intended issues closed and the native dependency frontier is correct. Closed blocking links remain useful history; do not delete them merely because the blocker completed.

A parent spec or manual acceptance gate is not complete just because a feature PR or automated test passed. If only part is delivered, leave it open and keep the unchecked criteria in GitHub. Prepare publication checkboxes in ignored scratch files; retain only plain requirement bullets under `docs/specs/*-tickets/`.

## Check local contracts

From any working directory:

```sh
python3 /path/to/varde/scripts/tracker-status.py check
```

From the repository root, test the checker and the existing fixture helpers:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The backend CI job runs the local contract check and these tests. Local checking needs no GitHub token, network, Android device or live service. It rejects both checked and unchecked progress markers in retained ticket contracts; ordinary requirement bullets, evidence records and the PR delivery checklist remain allowed.

## Read the reconciliation report

The separate `Tracker audit` workflow runs on Mondays at 07:23 UTC and supports manual dispatch after it is merged to the default branch. It reads issues/PRs with read-only permissions, writes a job summary and retains a JSON report artifact for seven days. It never changes issues, labels, comments, dependencies, repository settings or commits.

For an immediate local read-only scan:

```sh
python3 scripts/tracker-status.py audit --repo tomstiani/varde \
  --output .pi/tracker-audit/report.json --fail-on-findings
```

The audit reports:

- Open issues explicitly referenced as delivered by a merged PR through `Closes`, `Fixes`, `Resolves` or `Implements` wording.
- Open work still blocked by those potentially unclosed deliveries.

It ignores `Part of` references, fenced/inline code and HTML comment examples, and retained closed dependency history. References must be explicit, such as `Closes #22`, `Implements tomstiani/varde#22` or the issue URL; it does not infer completion from general prose. The default coverage is all open issues and the 200 most recent PRs merged to `main`; `--base` and `--limit` (1–1000) can change that bounded PR window. Reports identify their coverage and contain issue/PR identifiers, not copied bodies or credentials.

Findings are review candidates, not proof that a ticket should close. Recheck its requirements and unverified acceptance, then draft any correction for approval. No automatic issue closure/body overwrite is authorized by the audit.

Exit codes: `0` means the scan completed (or findings were reported without `--fail-on-findings`), `1` means drift/progress-copy findings need attention, and `2` means the scan could not establish coverage. Authentication, missing dependency metadata and read failures are not reported as a clean audit.

This audit is deliberately separate from required PR checks: a PR's closing target should still be open before merge. Weekly runs can be delayed or disabled by GitHub scheduling/inactivity policies; use manual dispatch or the local command when needed.

## Require successful CI on main

The repository's `main-required-ci` ruleset (ID `25381768`) is active for `main`, verified through the read-only GitHub ruleset API. It requires PRs and all four existing GitHub Actions checks, with an up-to-date branch and no bypass actors. It requires no extra human approval count, so a solo maintainer can still merge after the checks pass. The check source is the observed GitHub Actions app (integration ID `15368`).

`.github/main-ci-ruleset.json` is the reviewed policy template, not a mechanism that activates protection by being committed. Recheck live rules before any settings operation; do not create another copy of the active ruleset. Applying this policy elsewhere is a separate approved repository-settings operation:

```sh
gh api repos/tomstiani/varde/rulesets
# After reviewing existing rules and explicitly approving this change:
gh api --method POST repos/tomstiani/varde/rulesets \
  --input .github/main-ci-ruleset.json
```

If a matching ruleset already exists, inspect and review an update instead of creating a duplicate. Do not overwrite unrelated branch policies. Verify the returned enforcement, branch condition and required checks afterward. An active strict policy blocks direct pushes and requires updating an out-of-date PR branch before merge; preserve the four stable job names or coordinate an approved rule update when renaming them.

The repository administrator can change or disable the rules through an approved settings operation. Committing the JSON or merging this tooling does not apply it automatically. The read-only audit does not provision rules or live services.
