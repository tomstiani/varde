import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("tracker-status.py")


class TrackerStatusTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_local_contracts_reject_duplicate_progress_but_allow_requirement_lists(self):
        folder = self.root / "docs/specs/example-tickets"
        folder.mkdir(parents=True)
        ticket = folder / "01-example.md"
        ticket.write_text("# Contract\n- [x] Implement the behaviour\n")
        result = subprocess.run([sys.executable, str(SCRIPT), "check", "--root", str(self.root)], text=True, capture_output=True)
        self.assertEqual(result.returncode, 1, result.stderr)
        finding = json.loads(result.stdout)["findings"][0]
        self.assertEqual(finding["path"], "docs/specs/example-tickets/01-example.md")
        self.assertEqual(finding["line"], 2)
        ticket.write_text("# Contract\n- Implement the behaviour\n")
        (self.root / "README.md").write_text("- [ ] An unrelated example\n")
        result = subprocess.run([sys.executable, str(SCRIPT), "check", "--root", str(self.root)], text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["findings"], [])

    def test_local_check_with_the_wrong_root_does_not_claim_clean_coverage(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "check", "--root", str(self.root)], text=True, capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("coverage is unknown", result.stderr)
        self.assertEqual(result.stdout, "")

    def audit(self, fixture, *arguments):
        directory = self.root / "bin"
        directory.mkdir(exist_ok=True)
        data = self.root / "github.json"
        data.write_text(json.dumps(fixture))
        gh = directory / "gh"
        gh.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
fixture = json.loads(Path(os.environ["TRACKER_FIXTURE"]).read_text())
args = sys.argv[1:]
if fixture.get("fail"):
    raise SystemExit(88)
if args[:2] == ["pr", "list"]:
    print(json.dumps(fixture["pulls"]))
elif args and args[0] == "api" and "--method" in args and args[args.index("--method") + 1] == "GET":
    endpoint = next(item for item in args if item.startswith("repos/"))
    if "/issues?" in endpoint:
        if "--paginate" not in args or "--slurp" not in args:
            raise SystemExit(90)
        print(json.dumps([fixture["issues"]]))
    elif endpoint.endswith("/dependencies/blocked_by"):
        number = endpoint.split("/")[-3]
        print(json.dumps([fixture.get("dependencies", {}).get(number, [])]))
    else:
        raise SystemExit(91)
else:
    raise SystemExit(92)
''')
        gh.chmod(0o700)
        environment = {**os.environ, "PATH": str(directory) + os.pathsep + os.environ["PATH"], "TRACKER_FIXTURE": str(data), "GH_TOKEN": "synthetic-only"}
        return subprocess.run([sys.executable, str(SCRIPT), "audit", "--repo", "owner/repo", *arguments], env=environment, text=True, capture_output=True)

    @staticmethod
    def issue(number, blocked=0, state="open"):
        return {"number": number, "state": state, "issue_dependencies_summary": {"blocked_by": blocked}}

    def test_audit_reports_unclosed_delivery_and_its_live_blocking_effect(self):
        fixture = {
            "issues": [self.issue(19), self.issue(21), self.issue(22, blocked=1)],
            "pulls": [{"number": 28, "body": "Implements #21, part of #19.", "mergedAt": "2026-01-01T00:00:00Z"}],
            "dependencies": {"22": [self.issue(21)]},
        }
        result = self.audit(fixture, "--fail-on-findings")
        self.assertEqual(result.returncode, 1, result.stderr)
        findings = json.loads(result.stdout)["findings"]
        self.assertEqual({item["kind"] for item in findings}, {"unclosed_delivery", "blocked_by_unclosed_delivery"})
        self.assertEqual(next(item for item in findings if item["kind"] == "unclosed_delivery")["issue"], 21)
        blocked = next(item for item in findings if item["kind"] == "blocked_by_unclosed_delivery")
        self.assertEqual((blocked["issue"], blocked["blockers"]), (22, [21]))

    def test_audit_ignores_parent_references_examples_and_closed_dependency_history(self):
        fixture = {
            "issues": [self.issue(19), self.issue(22, blocked=1)],
            "pulls": [{"number": 28, "body": "Part of #19.\n`Implements #19` is an example.\n```md\nCloses #19\n```\n<!-- Closes #19 -->\nCloses #21", "mergedAt": "2026-01-01T00:00:00Z"}],
            "dependencies": {"22": [self.issue(21, state="closed")]},
        }
        result = self.audit(fixture, "--fail-on-findings")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["findings"], [])

    def test_audit_scopes_references_and_reports_no_raw_issue_or_pr_content(self):
        fixture = {
            "issues": [self.issue(21)],
            "pulls": [{"number": 28, "body": "Closes other/repo#21.\nImplements https://github.com/owner/repo/issues/21\nSYNTHETIC_PRIVATE_BODY", "mergedAt": "2026-01-01T00:00:00Z"}],
        }
        report = self.root / "reports/result.json"
        summary = self.root / "summary.md"
        result = self.audit(fixture, "--output", str(report), "--summary", str(summary))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(json.loads(result.stdout)["findings"]), 1)
        self.assertEqual(json.loads(report.read_text()), json.loads(result.stdout))
        self.assertNotIn("SYNTHETIC_PRIVATE_BODY", result.stdout + report.read_text() + summary.read_text())
        self.assertIn("#21", summary.read_text())

    def test_missing_dependency_metadata_is_not_reported_as_a_clean_audit(self):
        result = self.audit({"issues": [{"number": 21, "state": "open"}], "pulls": []})
        self.assertEqual(result.returncode, 2)
        self.assertIn("dependency coverage", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_unexpected_pull_response_does_not_look_like_a_completed_audit(self):
        result = self.audit({"issues": [], "pulls": {"unexpected": True}})
        self.assertEqual(result.returncode, 2)
        self.assertIn("unsupported", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_unavailable_github_is_not_reported_as_a_clean_audit(self):
        result = self.audit({"fail": True})
        self.assertEqual(result.returncode, 2)
        self.assertIn("GitHub read failed", result.stderr)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
