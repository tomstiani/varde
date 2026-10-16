import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("check-phone-acceptance.py")
FAKE_ADB = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
state = Path(os.environ["FAKE_ADB_STATE"])
log = Path(os.environ["FAKE_ADB_LOG"])
arguments = sys.argv[1:]
with log.open("a") as output:
    output.write(json.dumps(arguments) + "\\n")
data = json.loads(state.read_text())
command = arguments[1:]
if command == ["get-serialno"]:
    if not data.get("connected", True): sys.exit(1)
    print(data["serial"])
elif command == ["reverse", "--list"]:
    for local, remote in data["mappings"].items(): print("test-usb", local, remote)
elif command[:2] == ["reverse", "--remove"]:
    del data["mappings"][command[2]]
    state.write_text(json.dumps(data))
elif command and command[0] == "shell":
    pass
else:
    sys.exit(2)
'''


class PhoneAcceptanceSafetyTest(unittest.TestCase):
    def fixture(self, directory, data):
        executable = directory / "adb"
        executable.write_text(FAKE_ADB)
        executable.chmod(0o700)
        state = directory / "fake-adb.json"
        state.write_text(json.dumps(data))
        log = directory / "adb-commands.jsonl"
        environment = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"], FAKE_ADB_STATE=str(state), FAKE_ADB_LOG=str(log))
        return environment, state, log

    def test_invalid_previous_connections_are_rejected_before_device_access(self):
        invalid = [
            None,
            {"connections": []},
            ["not-a-connection"],
            [{"name": "Mac A"}],
            [{"name": "Mac A", "url": "https://a.example.invalid/", "secret": "synthetic-private-value"}],
            [{"name": "Mac A", "url": "https://a.example.invalid/", "original": {"summary": "synthetic-private-value"}}],
            [{"name": 12, "url": "https://a.example.invalid/"}],
            [{"name": "Mac A", "url": None}],
            [{"name": " ", "url": "https://a.example.invalid/"}],
            [{"name": "Mac A", "url": "not-a-url"}],
            [{"name": "Mac A", "url": "https://user:synthetic-private-value@a.example.invalid/"}],
            [{"name": "Mac A", "url": "https://a.example.invalid/?secret=synthetic-private-value"}],
            [{"name": "Mac A", "url": "https://a.example.invalid/#synthetic-private-value"}],
            [{"name": "Mac A", "url": "https://a.example.invalid:bad/"}],
            [{"name": "Mac A", "url": "http://192.168.1.1/"}],
            [{"name": "Mac A", "url": "https://a.example.invalid/ synthetic-private-value"}],
        ]
        for index, value in enumerate(invalid):
            with self.subTest(case=index), tempfile.TemporaryDirectory(prefix="varde-phone-", dir="/tmp") as temporary:
                directory = Path(temporary)
                environment, _, log = self.fixture(directory, {"serial": "synthetic-phone", "mappings": {}})
                allowlist = directory / "previous.json"
                allowlist.write_text(json.dumps(value))
                allowlist.chmod(0o600)
                report = directory / "phone-result.json"
                report.write_text(json.dumps({"passed": True}))
                report.chmod(0o600)
                result = subprocess.run([sys.executable, str(SCRIPT), "--state-dir", str(directory / "run"), "--previous-connections", str(allowlist)], env=environment, capture_output=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(log.exists(), "Invalid allowlist reached device access")
                self.assertFalse(report.exists())
                self.assertNotIn(b"synthetic-private-value", result.stdout + result.stderr)

    def test_valid_previous_connections_reach_device_check(self):
        with tempfile.TemporaryDirectory(prefix="varde-phone-", dir="/tmp") as temporary:
            directory = Path(temporary)
            environment, _, log = self.fixture(directory, {"connected": False, "serial": "synthetic-phone", "mappings": {}})
            allowlist = directory / "previous.json"
            allowlist.write_text(json.dumps([
                {"name": "Mac A", "url": "https://a.example.invalid/"},
                {"name": "Mac B", "url": "http://127.0.0.1:18082/"},
            ]))
            allowlist.chmod(0o600)
            result = subprocess.run([sys.executable, str(SCRIPT), "--state-dir", str(directory / "run"), "--previous-connections", str(allowlist)], env=environment, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([["-d", "get-serialno"]], [json.loads(line) for line in log.read_text().splitlines()])

    def test_cleanup_preserves_changed_and_unrelated_mappings(self):
        with tempfile.TemporaryDirectory(prefix="varde-phone-", dir="/tmp") as temporary:
            directory = Path(temporary)
            environment, state, log = self.fixture(directory, {
                "serial": "synthetic-phone", "mappings": {"tcp:18081": "tcp:9999", "tcp:18082": "tcp:18082", "tcp:17777": "tcp:17777"},
            })
            receipt = directory / "usb-owned.json"
            receipt.write_text(json.dumps({"serial": "synthetic-phone", "mappings": {"tcp:18081": "tcp:18081", "tcp:18082": "tcp:18082"}}))
            receipt.chmod(0o600)
            result = subprocess.run([sys.executable, str(SCRIPT), "--cleanup", "--state-dir", str(directory / "run")], env=environment, capture_output=True)
            self.assertEqual(0, result.returncode)
            self.assertEqual({"tcp:18081": "tcp:9999", "tcp:17777": "tcp:17777"}, json.loads(state.read_text())["mappings"])
            self.assertFalse(receipt.exists())
            self.assertNotIn("--remove-all", log.read_text())

    def test_failed_setup_does_not_leave_a_previous_green_result(self):
        with tempfile.TemporaryDirectory(prefix="varde-phone-", dir="/tmp") as temporary:
            directory = Path(temporary)
            environment, _, _ = self.fixture(directory, {"connected": False, "serial": "synthetic-phone", "mappings": {}})
            report = directory / "phone-result.json"
            report.write_text(json.dumps({"passed": True}))
            report.chmod(0o600)
            result = subprocess.run([sys.executable, str(SCRIPT), "--state-dir", str(directory / "run")], env=environment, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(report.exists())

    def test_different_phone_is_rejected_before_cleanup_or_input_transfer(self):
        with tempfile.TemporaryDirectory(prefix="varde-phone-", dir="/tmp") as temporary:
            directory = Path(temporary)
            environment, _, log = self.fixture(directory, {"serial": "other-phone", "mappings": {}})
            receipt = directory / "usb-owned.json"
            receipt.write_text(json.dumps({"serial": "owned-phone", "mappings": {"tcp:18081": "tcp:18081"}}))
            receipt.chmod(0o600)
            result = subprocess.run([sys.executable, str(SCRIPT), "--cleanup", "--state-dir", str(directory / "run")], env=environment, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([["-d", "get-serialno"]], [json.loads(line) for line in log.read_text().splitlines()])
            self.assertTrue(receipt.exists())


if __name__ == "__main__":
    unittest.main()
