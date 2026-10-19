#!/usr/bin/env python3
"""Check non-root Compose key permissions using disposable invented files only."""

import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import uuid


ROOT = Path(__file__).resolve().parents[1]


def main():
    if platform.system() != "Linux":
        raise SystemExit("Run on Linux: the existing Compose service uses host networking.")
    with tempfile.TemporaryDirectory(prefix="varde-generation-") as directory:
        root = Path(directory)
        key = root / "fake-key"
        key.write_text("invented-not-a-provider-key\n")
        key.chmod(0o600)
        config = root / "generation.json"
        config.write_text(json.dumps({
            "schemaVersion": 1, "enabled": True, "configId": "packaging-test",
            "accountRef": "synthetic", "model": "jev-1.13.0",
            "apiKeyFile": "/run/secrets/jev-api-key",
        }))
        config.chmod(0o644)
        environment = {
            **os.environ, "VARDE_GENERATION_CONFIG": str(config),
            "VARDE_GENERATION_KEY_FILE": str(key),
        }
        project = "varde-generation-test-" + uuid.uuid4().hex[:12]
        command = [
            "docker", "compose", "--env-file", "/dev/null", "-p", project,
            "-f", str(ROOT / "compose.yaml"),
            "-f", str(ROOT / "docs/examples/compose.generation.yaml"),
        ]

        def compose(*arguments):
            return subprocess.run(
                [*command, *arguments], cwd=ROOT, env=environment,
                check=True, capture_output=True, text=True,
            ).stdout

        def check(expected):
            output = compose(
                "run", "--rm", "--no-deps", "varde", "generation", "check",
                "-generation-config", "/run/varde-generation.json",
            )
            assert json.loads(output) == {"event": expected}, output
            assert "invented-not-a-provider-key" not in output

        try:
            compose("build", "varde")
            # Only the synthetic host fixture is mounted writable in this helper.
            # Compose keeps the service mount read-only, so use a separate root container.
            image = compose("config", "--images").strip()
            for owner, mode, expected in [
                ("10001:10001", "600", "generation_ready"),
                ("0:10001", "640", "generation_ready"),
                ("0:0", "600", "generation_key_unavailable"),
                ("0:0", "640", "generation_key_unavailable"),
                ("10002:10001", "640", "generation_key_unavailable"),
                ("10001:10001", "644", "generation_key_unavailable"),
                ("10001:10001", "660", "generation_key_unavailable"),
            ]:
                subprocess.run([
                    "docker", "run", "--rm", "--network", "none", "--user", "0",
                    "--entrypoint", "/bin/sh", "-v", str(key) + ":/fixture-key",
                    image, "-c", f"chown {owner} /fixture-key && chmod {mode} /fixture-key",
                ], check=True, capture_output=True)
                check(expected)
            print("Verified: non-root Compose runtime key ownership, readability and safe rejection.")
        finally:
            compose("down", "--volumes", "--remove-orphans")


if __name__ == "__main__":
    main()
