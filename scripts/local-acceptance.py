#!/usr/bin/env python3
"""Own two temporary loopback backends and send synthetic acceptance alerts."""

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import select
import socket
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
ACTIONS = ("serve", "status", "seed", "late", "duplicate", "pause-a", "resume-a", "restart-a", "rotate-a", "stop")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class Backend:
    def __init__(self, directory, executable, source, port, phone_url):
        self.directory = directory
        self.executable = executable
        self.source = source
        self.port = port
        self.phone_url = phone_url
        self.database = directory / f"{source}.sqlite"
        self.process = None
        self.alerts = {}
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.sender = self.issue("sender")
        self.installation = self.issue("installation")
        try:
            self.start()
        except BaseException:
            self.stop()
            raise

    def operator(self, *arguments):
        result = subprocess.run(
            [str(self.executable), "-db", str(self.database), *arguments],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20, check=True,
        )
        return json.loads(result.stdout)

    def issue(self, role):
        owner = self.operator("identity", "create", role, f"acceptance-{self.source}-{role}")
        return self.operator("credential", "issue", owner["id"])

    def start(self):
        if self.process is not None:
            raise ValueError("backend_already_running")
        with (self.directory / f"{self.source}.stderr").open("ab") as errors:
            self.process = subprocess.Popen(
                [str(self.executable), "-db", str(self.database), "serve", "-listen", f"127.0.0.1:{self.port}"],
                stdout=subprocess.PIPE, stderr=errors,
            )
        # Startup output is only the existing executable's readiness record.
        ready, _, _ = select.select([self.process.stdout], [], [], 15)
        if not ready:
            self.stop()
            raise TimeoutError("backend_startup_timeout")
        record = json.loads(self.process.stdout.readline())
        self.base_url = "http://" + record["address"] + "/"

    def stop(self):
        if self.process is None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.process.stdout.close()
        self.process = None

    def rotate(self):
        previous = self.installation
        replacement = self.operator("credential", "issue", previous["identityId"])
        self.operator("credential", "revoke", previous["id"])
        self.installation = replacement

    def send(self, kind, number):
        # Fixed original content makes duplicate replay deterministic; server acceptance time is separate.
        body = {
            "schemaVersion": 1,
            "eventId": f"acceptance-{self.source}-{kind}-{number:03d}",
            "title": f"Mac {self.source} {'scripted alert' if kind == 'seed' else 'late arrival'} {number:03d}",
            "summary": f"Synthetic acceptance data only. Source {self.source}, {kind} event {number:03d}. No homelab data.",
            "priority": "high" if number % 10 == 0 else "normal",
            "observedAt": "2026-01-01T12:00:00Z",
            "data": [],
        }
        request = urllib.request.Request(self.base_url + "v1/alerts", json.dumps(body).encode("utf-8"), {
            "Authorization": "Bearer " + self.sender["secret"], "Content-Type": "application/json",
        })
        with self.http.open(request, timeout=15) as response:
            if response.status not in (200, 201):
                raise ValueError("alert_not_accepted")
            alert = json.loads(response.read(100_000))
            self.alerts[body["eventId"]] = alert["id"]
            return response.status, alert["id"]

    def configuration(self):
        return {"name": f"Mac {self.source}", "url": self.phone_url or self.base_url, "installation": self.installation}


def write_private(path, value):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False, encoding="utf-8") as output:
        temporary = Path(output.name)
        try:
            json.dump(value, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


class Sandbox:
    def __init__(self, arguments):
        self.directory = arguments.state_dir
        self.backends = {}
        self.late_number = 0
        self.running = True
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        try:
            executable = self.directory / "varde"
            subprocess.run(["go", "build", "-o", str(executable), "./cmd/varde"], cwd=ROOT / "backend", check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
            self.backends["a"] = Backend(self.directory, executable, "A", arguments.port_a, arguments.url_a)
            self.backends["b"] = Backend(self.directory, executable, "B", arguments.port_b, arguments.url_b)
            self.persist()
        except BaseException:
            self.close()
            raise

    def persist(self):
        connections = [backend.configuration() for backend in self.backends.values()]
        transport = "usb-loopback" if all(urlsplit(item["url"]).scheme == "http" for item in connections) else "trusted-https"
        write_private(self.directory / "connections.json", {"transport": transport, "connections": connections})

    def act(self, action, count=65):
        if action == "status":
            return {"running": {name: backend.process is not None and backend.process.poll() is None for name, backend in self.backends.items()}, "accepted": {name: len(backend.alerts) for name, backend in self.backends.items()}, "addresses": {name: backend.base_url for name, backend in self.backends.items()}}
        if action == "seed":
            if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= 200:
                raise ValueError("invalid_count")
            created = duplicates = 0
            for number in range(1, count + 1):
                for backend in self.backends.values():
                    status, _ = backend.send("seed", number)
                    created += status == 201
                    duplicates += status == 200
            return {"created": created, "duplicates": duplicates}
        if action == "late":
            self.late_number += 1
            status, alert_id = self.backends["a"].send("late", self.late_number)
            return {"status": status, "source": "Mac A", "alertId": alert_id, "title": f"Mac A late arrival {self.late_number:03d}"}
        if action == "duplicate":
            first = self.backends["a"].send("seed", 1)
            second = self.backends["a"].send("seed", 1)
            if second[0] != 200 or first[1] != second[1]:
                raise ValueError("duplicate_contract_failed")
            return {"sameAlert": True, "status": 200}
        if action == "pause-a":
            self.backends["a"].stop()
        elif action == "resume-a":
            self.backends["a"].start()
        elif action == "restart-a":
            self.backends["a"].stop()
            self.backends["a"].start()
        elif action == "rotate-a":
            self.backends["a"].rotate()
            self.persist()
        elif action == "stop":
            self.running = False
        else:
            raise ValueError("unknown_action")
        return {"ok": True, "action": action}

    def close(self):
        for backend in self.backends.values():
            backend.stop()
        shutil.rmtree(self.directory, ignore_errors=True)


def serve(arguments):
    sandbox = Sandbox(arguments)
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        listener.bind(str(arguments.state_dir / "s"))
        os.chmod(arguments.state_dir / "s", 0o600)
        listener.listen(4)
        listener.settimeout(0.5)
        signal.signal(signal.SIGTERM, lambda *_: setattr(sandbox, "running", False))
        print(json.dumps({"ready": True, "credentialsFile": str(arguments.state_dir / "connections.json")}), flush=True)
        while sandbox.running:
            try:
                connection, _ = listener.accept()
            except socket.timeout:
                continue
            with connection:
                connection.settimeout(5)
                try:
                    with connection.makefile("rb") as incoming:
                        request = json.loads(incoming.readline(1025))
                    result = sandbox.act(request["action"], request.get("count", 65))
                except Exception:
                    result = {"error": "operation_failed"}
                try:
                    connection.sendall(json.dumps(result).encode("utf-8") + b"\n")
                except (BrokenPipeError, ConnectionResetError, socket.timeout):
                    pass
    finally:
        listener.close()
        sandbox.close()


def client(arguments):
    info = arguments.state_dir.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077 or arguments.state_dir.is_symlink():
        raise ValueError("insecure_state_directory")
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as control:
        control.settimeout(60)
        control.connect(str(arguments.state_dir / "s"))
        control.sendall(json.dumps({"action": arguments.action, "count": arguments.count}).encode("utf-8") + b"\n")
        with control.makefile("rb") as incoming:
            result = json.loads(incoming.readline(65_537))
        print(json.dumps(result))
        return 1 if "error" in result else 0


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=ACTIONS)
    parser.add_argument("--state-dir", type=Path, default=ROOT / ".pi/acceptance/live")
    parser.add_argument("--port-a", type=int, default=18081)
    parser.add_argument("--port-b", type=int, default=18082)
    parser.add_argument("--url-a")
    parser.add_argument("--url-b")
    parser.add_argument("--count", type=int, default=65)
    arguments = parser.parse_args()
    arguments.state_dir = arguments.state_dir.absolute()
    if not 0 <= arguments.port_a <= 65535 or not 0 <= arguments.port_b <= 65535:
        parser.error("ports must be in 0..65535")
    schemes = []
    for url in (arguments.url_a, arguments.url_b):
        if url is None:
            schemes.append("http")
            continue
        parsed = urlsplit(url)
        safe_transport = parsed.scheme == "https" or (parsed.scheme == "http" and parsed.hostname == "127.0.0.1")
        if not safe_transport or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            parser.error("use HTTPS or literal 127.0.0.1 HTTP, without embedded credentials")
        schemes.append(parsed.scheme)
    if arguments.action == "serve" and len(set(schemes)) != 1:
        parser.error("both phone connections must use the same transport")
    try:
        if arguments.action == "serve":
            serve(arguments)
            return 0
        return client(arguments)
    except KeyboardInterrupt:
        return 0
    except Exception:
        print(json.dumps({"error": "local_acceptance_failed"}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
