import json
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
from urllib.parse import urlsplit

SCRIPT = Path(__file__).with_name("local-acceptance.py")


class LocalAcceptanceTest(unittest.TestCase):
    def test_sender_restart_outage_and_rotation_use_real_isolated_backends(self):
        with tempfile.TemporaryDirectory(prefix="varde-acceptance-", dir="/tmp") as temporary:
            state = Path(temporary) / "run"
            process = subprocess.Popen([
                sys.executable, str(SCRIPT), "serve", "--state-dir", str(state),
                "--port-a", "0", "--port-b", "0",
            ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                ready, _, _ = select.select([process.stdout], [], [], 120)
                self.assertTrue(ready, "Sandbox did not report readiness")
                self.assertTrue(json.loads(process.stdout.readline())["ready"])

                def command(action, *extra):
                    result = subprocess.run([sys.executable, str(SCRIPT), action, "--state-dir", str(state), *extra], capture_output=True, timeout=60)
                    self.assertEqual(0, result.returncode, "Acceptance command failed")
                    return json.loads(result.stdout)

                self.assertEqual(0, state.stat().st_mode & 0o077)
                private = state / "connections.json"
                self.assertEqual(0, private.stat().st_mode & 0o077)
                configuration = json.loads(private.read_text())
                self.assertEqual(["Mac A", "Mac B"], [item["name"] for item in configuration["connections"]])
                self.assertEqual("usb-loopback", configuration["transport"])
                self.assertTrue(all(urlsplit(item["url"]).hostname == "127.0.0.1" and urlsplit(item["url"]).port > 0 for item in configuration["connections"]))
                secrets = [item["installation"]["secret"] for item in configuration["connections"]]
                seed = command("seed", "--count", "3")
                self.assertEqual({"created": 6, "duplicates": 0}, seed)
                self.assertEqual({"created": 0, "duplicates": 6}, command("seed", "--count", "3"))
                self.assertTrue(command("duplicate")["sameAlert"])
                self.assertEqual(201, command("late")["status"])
                before = command("status")
                self.assertEqual({"a": 4, "b": 3}, before["accepted"])
                self.assertTrue(all(secret not in json.dumps(before) for secret in secrets))
                command("restart-a")
                self.assertTrue(command("duplicate")["sameAlert"])
                command("pause-a")
                self.assertEqual({"a": False, "b": True}, command("status")["running"])
                command("resume-a")
                current = command("status")
                old_secret = configuration["connections"][0]["installation"]["secret"]
                reader = urllib.request.build_opener(urllib.request.ProxyHandler({}))

                def history(secret):
                    request = urllib.request.Request(current["addresses"]["a"] + "v1/alerts?limit=100", headers={"Authorization": "Bearer " + secret})
                    with reader.open(request, timeout=10) as response:
                        return json.loads(response.read())

                self.assertEqual(4, len(history(old_secret)["items"]))
                command("rotate-a")
                with self.assertRaises(urllib.error.HTTPError) as denied:
                    history(old_secret)
                self.assertEqual(401, denied.exception.code)
                denied.exception.close()
                replacement = json.loads(private.read_text())["connections"][0]["installation"]
                self.assertEqual(4, len(history(replacement["secret"])["items"]))
                self.assertTrue(all(secret.encode() not in (state / "A.sqlite").read_bytes() for secret in secrets))
                command("stop")
                self.assertEqual(0, process.wait(timeout=15))
                self.assertFalse(state.exists())
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=15)
                process.stdout.close()
                process.stderr.close()

    def test_rejects_non_loopback_http_before_creating_state(self):
        with tempfile.TemporaryDirectory(prefix="varde-acceptance-", dir="/tmp") as temporary:
            state = Path(temporary) / "not-created"
            result = subprocess.run([sys.executable, str(SCRIPT), "serve", "--state-dir", str(state), "--url-a", "http://192.168.1.10/", "--url-b", "http://127.0.0.1:18082/"], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(state.exists())


if __name__ == "__main__":
    unittest.main()
