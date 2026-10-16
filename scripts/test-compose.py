#!/usr/bin/env python3
"""Exercise the Compose deployment on Linux using disposable synthetic data."""

import json
import os
from pathlib import Path
import platform
import subprocess
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]


def main():
    if platform.system() != "Linux":
        raise SystemExit("Run this test on Linux: Compose uses host networking.")
    port = int(os.environ.get("VARDE_TEST_PORT", "18080"))
    if not 1 <= port <= 65535:
        raise SystemExit("VARDE_TEST_PORT must be between 1 and 65535.")
    project = "varde-smoke-" + uuid.uuid4().hex[:12]
    environment = {**os.environ, "VARDE_PORT": str(port)}
    command = ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "-p", project]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def compose(*args, capture=False):
        result = subprocess.run(
            [*command, *args], cwd=ROOT, env=environment,
            check=True, capture_output=capture, text=True,
        )
        return result.stdout

    def operator(*args):
        return json.loads(compose(
            "exec", "-T", "varde", "varde", "-db", "/data/varde.sqlite",
            *args, capture=True,
        ))

    def request(path, secret=None, body=None):
        headers = {}
        if secret:
            headers["Authorization"] = "Bearer " + secret
        if body is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}", headers=headers,
            data=None if body is None else json.dumps(body).encode(),
        )
        try:
            with opener.open(req, timeout=3) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, None

    def wait_ready():
        for _ in range(60):
            try:
                logs = compose("logs", "--no-log-prefix", "varde", capture=True)
                if '"event":"listening"' in logs and request("/v1/alerts")[0] == 401:
                    return
            except (OSError, TimeoutError):
                pass
            time.sleep(1)
        raise RuntimeError("Backend did not become ready.")

    try:
        compose("up", "-d", "--build")
        wait_ready()
        sender = operator("identity", "create", "sender", "smoke-sender")
        phone = operator("identity", "create", "installation", "smoke-phone")
        sender_secret = operator("credential", "issue", sender["id"])["secret"]
        phone_secret = operator("credential", "issue", phone["id"])["secret"]
        original = {
            "schemaVersion": 1, "eventId": project,
            "title": "Compose smoke test", "summary": "Synthetic data only.",
            "priority": "high", "observedAt": "2026-01-01T12:00:00Z", "data": [],
        }
        status, accepted = request("/v1/alerts", sender_secret, original)
        assert status == 201, "Submission failed."
        alert_id = accepted["id"]
        # Recreate the container to test volume persistence, not just process restart.
        compose("up", "-d", "--force-recreate")
        wait_ready()
        status, retained = request("/v1/alerts/" + alert_id, phone_secret)
        assert status == 200 and retained["original"] == original, "Original was not retained."
        status, duplicate = request("/v1/alerts", sender_secret, original)
        assert status == 200 and duplicate["id"] == alert_id, "Duplicate recovery failed."
        status, history = request("/v1/alerts", phone_secret)
        assert status == 200 and len(history["items"]) == 1, "Unexpected history."
        print("Verified: Compose startup, credentials, retained original and duplicate recovery.")
    finally:
        compose("down", "--volumes")


if __name__ == "__main__":
    main()
