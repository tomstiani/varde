#!/usr/bin/env python3
"""Check local contract copies and report GitHub delivery drift without changing it."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
REFERENCE = re.compile(
    r"\b(?:close[sd]?|fix(?:es|ed)?|resolve[sd]?|implement(?:s|ed)?)\s+"
    r"(?P<reference>(?:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)?#[1-9][0-9]*"
    r"|https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/issues/[1-9][0-9]*)",
    re.IGNORECASE,
)


def local_findings(root):
    specs = root / "docs/specs"
    if not specs.is_dir():
        raise RuntimeError("No docs/specs directory found; local check coverage is unknown.")
    findings = []
    for path in sorted(specs.glob("*-tickets/*.md")):
        for number, line in enumerate(path.read_text().splitlines(), 1):
            if re.match(r"\s*[-*]\s+\[[ xX]\]", line):
                findings.append({
                    "kind": "local_progress_copy",
                    "path": str(path.relative_to(root)),
                    "line": number,
                    "detail": "Keep requirements as plain bullets; GitHub owns completion checkboxes.",
                })
    return findings


def prose(body):
    body = re.sub(r"<!--.*?-->", "", body, flags=re.DOTALL)
    lines = []
    fence = None
    for line in body.splitlines():
        stripped = line.lstrip()
        if stripped.startswith(("```", "~~~")):
            marker = stripped[:3]
            if fence is None:
                fence = marker
            elif marker == fence:
                fence = None
            continue
        if fence is None:
            lines.append(re.sub(r"`[^`]*`", "", line))
    return "\n".join(lines)


def delivery_references(body, repository):
    numbers = set()
    for match in REFERENCE.finditer(prose(body or "")):
        reference = match.group("reference")
        if reference.lower().startswith("https://github.com/"):
            owner, number = reference[len("https://github.com/"):].rsplit("/issues/", 1)
        else:
            owner, number = reference.rsplit("#", 1)
        if not owner or owner.lower() == repository.lower():
            numbers.add(int(number))
    return numbers


def github_json(arguments):
    try:
        result = subprocess.run(["gh", *arguments], check=True, capture_output=True, text=True, timeout=120)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as failure:
        raise RuntimeError("GitHub read failed; check gh authentication, connectivity and read permissions.") from failure
    try:
        return json.loads(result.stdout)
    except ValueError as failure:
        raise RuntimeError("GitHub read returned invalid JSON; audit coverage is unknown.") from failure


def github_pages(endpoint):
    pages = github_json(["api", "--method", "GET", "--paginate", "--slurp", endpoint])
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise RuntimeError("GitHub read returned an unsupported paginated response.")
    records = [item for page in pages for item in page]
    if any(not isinstance(item, dict) for item in records):
        raise RuntimeError("GitHub read returned unsupported records.")
    return records


def audit(repository, base, limit):
    issues = {
        item["number"]: item
        for item in github_pages(f"repos/{repository}/issues?state=open&per_page=100")
        if "pull_request" not in item and item.get("state") == "open"
    }
    pulls = github_json(["pr", "list", "--repo", repository, "--state", "merged", "--base", base,
                         "--limit", str(limit), "--json", "number,body,mergedAt"])
    if not isinstance(pulls, list) or any(not isinstance(pull, dict) for pull in pulls):
        raise RuntimeError("GitHub read returned an unsupported pull-request response.")
    deliveries = {}
    for pull in pulls:
        if not pull.get("mergedAt"):
            continue
        for number in delivery_references(pull.get("body"), repository):
            if number in issues:
                deliveries.setdefault(number, set()).add(pull["number"])
    findings = [{
        "kind": "unclosed_delivery", "issue": number, "pulls": sorted(pulls),
        "detail": "Merged PR declares delivery of an issue that is still open. Review completion before closing it.",
    } for number, pulls in sorted(deliveries.items())]
    for number, issue in sorted(issues.items()):
        summary = issue.get("issue_dependencies_summary")
        if not isinstance(summary, dict) or "blocked_by" not in summary:
            raise RuntimeError(f"GitHub read omitted dependency coverage for issue #{number}.")
        if not summary["blocked_by"]:
            continue
        dependencies = github_pages(f"repos/{repository}/issues/{number}/dependencies/blocked_by")
        blockers = sorted({
            item["number"] for item in dependencies
            if item.get("state") == "open" and item["number"] in deliveries
            and item.get("html_url", f"https://github.com/{repository}/issues/{item['number']}")
                .lower().startswith(f"https://github.com/{repository}/issues/".lower())
        })
        if blockers:
            findings.append({
                "kind": "blocked_by_unclosed_delivery", "issue": number, "blockers": blockers,
                "detail": "An open blocker is referenced as delivered by a merged PR. Retain dependency history; reconcile its issue state.",
            })
    return {"repository": repository, "base": base, "openIssuesChecked": len(issues),
            "mergedPullRequestsChecked": len(pulls), "mergedPullRequestLimit": limit, "findings": findings}


def write_summary(path, report):
    repository = report["repository"]
    lines = ["## Tracker audit (read-only)", "",
             f"Checked {report['openIssuesChecked']} open issues and {report['mergedPullRequestsChecked']} recent merged PRs to `{report['base']}`.",
             "Completion intent is inferred only from explicit closing/implements references; findings require human review.", ""]
    for finding in report["findings"]:
        number = finding["issue"]
        issue = f"[#{number}](https://github.com/{repository}/issues/{number})"
        related = finding.get("pulls", finding.get("blockers", []))
        kind = "merged PRs" if "pulls" in finding else "open blockers"
        lines.append(f"- {issue}: `{finding['kind']}` ({kind}: {', '.join('#' + str(item) for item in related)})")
    if not report["findings"]:
        lines.append("No delivery drift found within this scan's coverage.")
    lines.extend(["", "No issues, comments, labels, dependencies, repository settings or commits were changed.", ""])
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as output:
        output.write("\n".join(lines))


def repository_name(value):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*", value):
        raise argparse.ArgumentTypeError("Use an OWNER/REPO repository name.")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check", help="Reject progress checkboxes in retained local ticket contracts")
    check.add_argument("--root", type=Path, default=ROOT)
    scan = commands.add_parser("audit", help="Read GitHub delivery and blockers; never change them")
    scan.add_argument("--repo", required=True, type=repository_name)
    scan.add_argument("--base", default="main")
    scan.add_argument("--limit", type=int, default=200)
    scan.add_argument("--output", type=Path)
    scan.add_argument("--summary", type=Path)
    scan.add_argument("--fail-on-findings", action="store_true")
    options = parser.parse_args()
    if options.command == "audit" and not 1 <= options.limit <= 1000:
        parser.error("--limit must be between 1 and 1000")
    try:
        if options.command == "check":
            report = {"findings": local_findings(options.root)}
            fail = True
        else:
            report = audit(options.repo, options.base, options.limit)
            fail = options.fail_on_findings
            if options.output:
                options.output.parent.mkdir(parents=True, exist_ok=True)
                options.output.write_text(json.dumps(report, indent=2) + "\n")
            if options.summary:
                write_summary(options.summary, report)
        print(json.dumps(report, indent=2))
        return int(fail and bool(report["findings"]))
    except (RuntimeError, OSError, KeyError, TypeError, ValueError) as failure:
        print(failure, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
