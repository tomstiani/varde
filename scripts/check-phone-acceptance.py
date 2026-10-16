#!/usr/bin/env python3
"""Opt-in USB setup/browsing check with private stdin-only credential transfer."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def private_json(path):
    info = path.stat()
    if path.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError("insecure_private_file")
    return json.loads(path.read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=ROOT / ".pi/acceptance/live")
    parser.add_argument("--previous-connections", type=Path)
    parser.add_argument("--cleanup", action="store_true")
    arguments = parser.parse_args()
    state = arguments.state_dir.absolute()
    adb = shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb")
    receipt = state.parent / "usb-owned.json"
    report = state.parent / "phone-result.json"
    if not arguments.cleanup and report.exists():
        private_json(report)
        report.unlink()

    def device(*command, **options):
        return subprocess.run([adb, "-d", *command], check=True, capture_output=True, timeout=options.pop("timeout", 30), **options)

    serial = device("get-serialno").stdout.decode().strip()
    owned = private_json(receipt) if receipt.exists() else {"serial": serial, "mappings": {}}
    if owned["serial"] != serial:
        raise ValueError("different_phone_connected")

    def mappings():
        lines = device("reverse", "--list").stdout.decode().splitlines()
        return {parts[-2]: parts[-1] for line in lines if len(parts := line.split()) >= 3}

    if arguments.cleanup:
        current = mappings()
        for local, remote in owned["mappings"].items():
            if current.get(local) == remote:
                device("reverse", "--remove", local)
        subprocess.run([adb, "-d", "shell", "run-as no.varde.app rm -f no_backup/live-acceptance.json"], capture_output=True, timeout=15)
        receipt.unlink(missing_ok=True)
        print(json.dumps({"usbCleanup": "owned mappings removed; unrelated mappings preserved"}))
        return 0

    configuration = private_json(state / "connections.json")
    if configuration.get("transport") != "usb-loopback" or len(configuration["connections"]) != 2:
        raise ValueError("not_a_usb_acceptance_profile")
    status = json.loads(subprocess.check_output([sys.executable, str(ROOT / "scripts/local-acceptance.py"), "status", "--state-dir", str(state)]))
    if not all(status["running"].values()) or status["accepted"]["b"] == 0:
        raise ValueError("start_and_seed_both_backends_first")
    wanted = {}
    for source, connection in zip(("a", "b"), configuration["connections"]):
        phone = urlsplit(connection["url"])
        backend = urlsplit(status["addresses"][source])
        if phone.scheme != "http" or phone.hostname != "127.0.0.1" or not phone.port:
            raise ValueError("non_loopback_usb_target")
        wanted[f"tcp:{phone.port}"] = f"tcp:{backend.port}"
    if len(wanted) != 2:
        raise ValueError("usb_ports_must_be_distinct")
    current = mappings()
    if any(local in current and current[local] != remote for local, remote in wanted.items()):
        raise ValueError("usb_mapping_conflict")
    for local, remote in wanted.items():
        if local not in current:
            device("reverse", local, remote)
            owned["mappings"][local] = remote
    with os.fdopen(os.open(receipt, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as output:
        json.dump(owned, output)
    number = status["accepted"]["b"]
    configuration.update(
        expectedAlertCount=sum(status["accepted"].values()),
        expectedTitle=f"Mac B scripted alert {number:03d}",
        expectedSummary=f"Synthetic acceptance data only. Source B, seed event {number:03d}. No homelab data.",
    )
    if arguments.previous_connections:
        configuration["previousConnections"] = private_json(arguments.previous_connections)
    remote = "run-as no.varde.app sh -c 'umask 077; mkdir -p no_backup; cat > no_backup/live-acceptance.json'"
    device("shell", "run-as no.varde.app rm -f no_backup/live-result.json")
    launched = False
    try:
        device("shell", remote, input=json.dumps(configuration).encode())
        launched = True
        run = device("shell", "am", "instrument", "--user", "0", "-w", "-e", "class", "no.varde.app.LiveSetupTest", "-e", "varde.acceptance", "true", "no.varde.app.test/androidx.test.runner.AndroidJUnitRunner", timeout=180)
        evidence = json.loads(device("exec-out", "run-as", "no.varde.app", "cat", "no_backup/live-result.json").stdout)
        allowed = ("passed", "model", "sdk", "rows", "sources", "transport", "stage", "categories")
        redacted = {key: value for key, value in evidence.items() if key in allowed}
        with os.fdopen(os.open(report, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as output:
            json.dump(redacted, output, indent=2)
        print(json.dumps(redacted))
        if evidence.get("passed") is not True or b"OK (1 test)" not in run.stdout:
            return 1
        device("shell", "am", "start", "-n", "no.varde.app/.MainActivity")
        return 0
    except BaseException:
        if launched:
            subprocess.run([adb, "-d", "shell", "am force-stop no.varde.app"], capture_output=True, timeout=15)
        raise
    finally:
        subprocess.run([adb, "-d", "shell", "run-as no.varde.app rm -f no_backup/live-acceptance.json"], capture_output=True, timeout=15)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        print(json.dumps({"passed": False, "stage": "usb-or-device-setup", "hint": "Connect one unlocked authorized phone; check APK installation, private profile and port mappings."}), file=sys.stderr)
        sys.exit(1)
