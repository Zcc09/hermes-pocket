#!/usr/bin/env python3
"""End-to-end self-test for tools/pocket.py against a local fake bridge.

Spins up a stdlib ``http.server`` on 127.0.0.1 with a free port that imitates
the Hermes Pocket bridge (same routes, same JSON shapes, same bearer check),
then drives the real CLI through ``subprocess`` exactly as a user would.
Nothing under app/ is touched and no network access is needed.

Run from the repo root (or anywhere):

    python tools/pocket_selftest.py

Every case prints PASS or FAIL; the exit status is non-zero if any case
fails.  Temp files live in one directory that is removed on the way out.
"""
from __future__ import annotations

import base64
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, List, Optional, Tuple

TOKEN = "selftest-token"
WRONG_TOKEN = "not-the-token"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"  # 8 bytes: what every PNG starts with
FAKE_PNG = PNG_MAGIC + b"\x00\x00\x00\rIHDR"
HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(HERE, "pocket.py")


# ---------------------------------------------------------------------------
# the fake bridge
# ---------------------------------------------------------------------------
class FakeBridge:
    """In-memory stand-in for the on-phone HTTP/1.1 JSON bridge."""

    def __init__(self) -> None:
        self.files: Dict[str, bytes] = {"/sdcard/demo.txt": b"hello"}
        self.requests: List[Dict[str, Any]] = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._make_handler())
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.url = f"http://127.0.0.1:{self.port}"
        self._thread = threading.Thread(target=self.server.serve_forever, name="fake-bridge", daemon=True)

    # -- lifecycle ------------------------------------------------------
    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self._thread.join(timeout=5)

    # -- routing helpers -------------------------------------------------
    @staticmethod
    def _json(handler: BaseHTTPRequestHandler, status: int, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        handler.send_response(status)
        handler.send_header("Content-Type", "application/json; charset=utf-8")
        handler.send_header("Content-Length", str(len(body)))
        handler.end_headers()
        handler.wfile.write(body)

    def _make_handler(self) -> type:
        bridge = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            server_version = "FakePocketBridge/1.3.0"

            def log_message(self, fmt: str, *args: Any) -> None:  # keep test output clean
                pass

            # -- plumbing --------------------------------------------------
            def _route(self) -> Tuple[str, Dict[str, List[str]]]:
                parsed = urllib.parse.urlsplit(self.path)
                path = parsed.path
                # the CLI may be given a base URL with a path prefix
                if path.startswith("/api"):
                    path = path[len("/api"):] or "/"
                return path, urllib.parse.parse_qs(parsed.query)

            def _body(self) -> Dict[str, Any]:
                length = int(self.headers.get("Content-Length") or 0)
                if not length:
                    return {}
                raw = self.rfile.read(length)
                try:
                    parsed = json.loads(raw.decode("utf-8"))
                except ValueError:
                    return {}
                return parsed if isinstance(parsed, dict) else {}

            def _authorized(self) -> bool:
                return self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

            def _record(self, method: str, body: Optional[Dict[str, Any]] = None) -> None:
                bridge.requests.append(
                    {"method": method, "path": self.path, "body": body or {},
                     "auth": self.headers.get("Authorization", "")}
                )

            def _json(self, status: int, payload: Dict[str, Any]) -> None:
                FakeBridge._json(self, status, payload)

            # -- dispatch --------------------------------------------------
            def do_GET(self) -> None:
                path, query = self._route()
                self._record("GET")
                if not self._authorized():
                    self._json(401, {"ok": False, "error": "bad or missing token"})
                    return
                if path == "/v1/status":
                    self._json(200, STATUS)
                elif path == "/v1/fs":
                    self._fs(query)
                elif path == "/v1/screenshot":
                    self.send_response(200)
                    self.send_header("Content-Type", "image/png")
                    self.send_header("Content-Length", str(len(FAKE_PNG)))
                    self.end_headers()
                    self.wfile.write(FAKE_PNG)
                elif path == "/v1/ui":
                    self._json(200, {"ok": True, "xml": UI_XML})
                else:
                    self._json(404, {"ok": False, "error": f"no route {path}"})

            def do_POST(self) -> None:
                path, _query = self._route()
                body = self._body()
                self._record("POST", body)
                if not self._authorized():
                    self._json(401, {"ok": False, "error": "bad or missing token"})
                    return
                if path == "/v1/shell":
                    cmd = str(body.get("cmd") or "")
                    exit_code = 7 if cmd.startswith("exit7") else 0
                    self._json(200, {
                        "ok": True,
                        "exit": exit_code,
                        "stdout": f"echo:{cmd}\n",
                        "stderr": "boom\n" if exit_code else "",
                        "truncated": False,
                    })
                elif path == "/v1/input":
                    action = body.get("action")
                    if action not in ("tap", "swipe", "text", "key"):
                        self._json(400, {"ok": False, "error": f"bad action {action!r}"})
                    else:
                        self._json(200, {"ok": True})
                elif path == "/v1/app":
                    action = body.get("action")
                    if action == "list":
                        self._json(200, {"ok": True, "apps": APP_LIST})
                    elif action in ("launch", "stop") and body.get("package"):
                        self._json(200, {"ok": True})
                    else:
                        self._json(400, {"ok": False, "error": f"bad app action {action!r}"})
                elif path == "/v1/intent":
                    if body.get("action") in ("view", "send"):
                        self._json(200, {"ok": True})
                    else:
                        self._json(400, {"ok": False, "error": "bad intent action"})
                else:
                    self._json(404, {"ok": False, "error": f"no route {path}"})

            def do_PUT(self) -> None:
                path, _query = self._route()
                body = self._body()
                self._record("PUT", body)
                if not self._authorized():
                    self._json(401, {"ok": False, "error": "bad or missing token"})
                    return
                if path != "/v1/fs" or body.get("op") != "write":
                    self._json(400, {"ok": False, "error": "bad write request"})
                    return
                target = str(body.get("path") or "")
                if not target:
                    self._json(400, {"ok": False, "error": "write needs a path"})
                    return
                try:
                    data = base64.b64decode(str(body.get("content_b64") or ""))
                except (ValueError, TypeError):
                    self._json(400, {"ok": False, "error": "bad content_b64"})
                    return
                bridge.files[target] = data
                self._json(200, {"ok": True, "bytes": len(data)})

            # -- /v1/fs ------------------------------------------------
            def _fs(self, query: Dict[str, List[str]]) -> None:
                op = (query.get("op") or ["list"])[0]
                target = (query.get("path") or ["/sdcard"])[0]
                b64 = (query.get("b64") or ["0"])[0] not in ("0", "", "false")
                if op == "list":
                    self._json(200, {"ok": True, "entries": [
                        {"name": "demo.txt", "dir": False, "size": 5, "mtime": 1700000000},
                        {"name": "sub", "dir": True, "size": 0, "mtime": 1700000000},
                    ]})
                elif op == "read":
                    if target not in bridge.files:
                        self._json(404, {"ok": False, "error": f"no such file: {target}"})
                        return
                    data = bridge.files[target]
                    if b64:
                        self._json(200, {"ok": True,
                                               "content_b64": base64.b64encode(data).decode("ascii")})
                    else:
                        self._json(200, {"ok": True, "content": data.decode("utf-8", "replace")})
                elif op == "stat":
                    exists = target in bridge.files
                    self._json(200, {"ok": True, "exists": exists,
                                           "dir": target.rstrip("/") in ("/sdcard",) if exists else False,
                                           "size": len(bridge.files.get(target, b"")),
                                           "mtime": 1700000000 if exists else 0})
                else:
                    self._json(400, {"ok": False, "error": f"bad fs op {op!r}"})

        return Handler


STATUS: Dict[str, Any] = {
    "ok": True,
    "app": "hermes-pocket",
    "version": "1.3.0",
    "tier": "shizuku",
    "tiers": {"root": False, "shizuku": True, "shell": True},
    "caps": {"shell": True, "files": True, "screenshot": True, "gestures": True, "ui": True},
    "device": {"model": "Pixel 8", "android": "14", "sdk": 34},
    "bridge": {"port": 8737, "since": int(time.time()) - 5400},
}

UI_XML = '<hierarchy rotation="0"><node text="Hermes" bounds="[0,0][1080,2400]"/></hierarchy>'

APP_LIST = [
    {"package": "com.zcc09.hermespocket", "label": "Hermes Pocket"},
    {"package": "com.android.settings", "label": "Settings"},
]


# ---------------------------------------------------------------------------
# CLI driver
# ---------------------------------------------------------------------------
class Cli:
    """Runs tools/pocket.py the way a user would, in its own temp cwd."""

    def __init__(self, bridge: FakeBridge, workdir: str) -> None:
        self.bridge = bridge
        self.workdir = workdir
        self.python = sys.executable or "python3"

    def run(self, *argv: str, env: Optional[Dict[str, str]] = None,
            token: Optional[str] = TOKEN, url: Optional[str] = None) -> subprocess.CompletedProcess:
        environ = dict(os.environ)
        for key in ("POCKET_URL", "POCKET_TOKEN"):
            environ.pop(key, None)
        environ["PYTHONIOENCODING"] = "utf-8"
        if token is not None:
            environ["POCKET_TOKEN"] = token
        environ["POCKET_URL"] = url if url is not None else self.bridge.url
        if env:
            environ.update(env)
        return subprocess.run(
            [self.python, CLI, *argv],
            cwd=self.workdir,
            env=environ,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )

    # -- assertions ------------------------------------------------------
    @staticmethod
    def ok(proc: subprocess.CompletedProcess) -> str:
        if proc.returncode != 0:
            raise AssertionError(
                f"exit {proc.returncode}; stdout={proc.stdout.strip()!r} stderr={proc.stderr.strip()!r}"
            )
        return proc.stdout


class Runner:
    def __init__(self) -> None:
        self.total = 0
        self.failed = 0

    def case(self, name: str, fn: Callable[[], None]) -> None:
        self.total += 1
        try:
            fn()
        except AssertionError as exc:
            self.failed += 1
            print(f"FAIL {name}: {exc}")
        except Exception as exc:  # noqa: BLE001 - a crash is a failed case, not a dead suite
            self.failed += 1
            print(f"FAIL {name}: {type(exc).__name__}: {exc}")
        else:
            print(f"PASS {name}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


# ---------------------------------------------------------------------------
# the cases
# ---------------------------------------------------------------------------
def main() -> int:
    for required in ("pocket.py",):
        if not os.path.isfile(os.path.join(HERE, required)):
            print(f"FAIL setup: {required} not found next to this script ({HERE})")
            return 1

    bridge = FakeBridge()
    bridge.start()
    workdir = tempfile.mkdtemp(prefix="pocket-selftest-")
    cli = Cli(bridge, workdir)
    runner = Runner()
    print(f"fake bridge on {bridge.url}  cli={CLI}  python={sys.executable}")
    print(f"workdir {workdir}\n")

    try:
        # 1. status -----------------------------------------------------
        def case_status() -> None:
            out = cli.ok(cli.run("status"))
            require("hermes-pocket 1.3.0" in out, f"version missing: {out!r}")
            require("tier shizuku" in out, f"tier missing: {out!r}")
            require("Pixel 8" in out, f"device missing: {out!r}")
            require(":8737" in out, f"port missing: {out!r}")
            require("screenshot" in out, f"caps missing: {out!r}")

        def case_status_json() -> None:
            # also proves a path prefix and doubled slashes are joined correctly
            proc = cli.run("status", "--json", url=f"http://127.0.0.1:{bridge.port}/api//")
            out = cli.ok(proc)
            data = json.loads(out)
            require(data["ok"] is True, "ok flag not true")
            require(data["app"] == "hermes-pocket", f"wrong app: {data.get('app')!r}")
            require(data["device"]["sdk"] == 34, "sdk missing from json")

        # 2. shell ------------------------------------------------------
        def case_shell_echo() -> None:
            proc = cli.run("shell", "echo", "hi", "there")
            out = cli.ok(proc)
            require(out.strip() == "echo:echo hi there", f"unexpected stdout: {out!r}")
            require(proc.returncode == 0, f"exit {proc.returncode}")

        def case_shell_exit_code() -> None:
            proc = cli.run("shell", "--timeout", "5000", "exit7", "now")
            require(proc.returncode == 7, f"expected remote exit 7, got {proc.returncode}")
            require("echo:exit7 now" in proc.stdout, f"stdout lost: {proc.stdout!r}")
            require("boom" in proc.stderr, f"stderr not forwarded: {proc.stderr!r}")

        def case_shell_json() -> None:
            proc = cli.run("--json", "shell", "ls", "-l")
            data = json.loads(cli.ok(proc))
            require(data["exit"] == 0 and data["ok"] is True, f"bad json shell: {data}")

        # 3. ls ---------------------------------------------------------
        def case_ls() -> None:
            out = cli.ok(cli.run("ls", "/sdcard"))
            require("demo.txt" in out, f"file entry missing: {out!r}")
            require("sub/" in out, f"dir entry missing trailing slash: {out!r}")
            require("5 B" in out, f"size missing: {out!r}")

        # 4. cat --------------------------------------------------------
        def case_cat() -> None:
            out = cli.ok(cli.run("cat", "/sdcard/demo.txt"))
            require(out.strip() == "hello", f"unexpected content: {out!r}")

        def case_cat_out() -> None:
            target = os.path.join(workdir, "cat-out.bin")
            cli.ok(cli.run("cat", "/sdcard/demo.txt", "--out", target))
            with open(target, "rb") as handle:
                require(handle.read() == b"hello", "cat --out wrote the wrong bytes")

        def case_cat_json() -> None:
            data = json.loads(cli.ok(cli.run("cat", "/sdcard/demo.txt", "--json")))
            require(data.get("ok") is True, f"ok flag missing: {data}")
            require(base64.b64decode(data.get("content_b64", "")) == b"hello",
                    f"content_b64 missing: {data}")

        # 5. put --------------------------------------------------------
        payload = b"hello from selftest\n"
        local = os.path.join(workdir, "upload.txt")
        with open(local, "wb") as handle:
            handle.write(payload)

        def case_put() -> None:
            out = cli.ok(cli.run("put", local, "/sdcard/upload.txt"))
            require("wrote 20 bytes" in out, f"unexpected put output: {out!r}")
            require(bridge.files.get("/sdcard/upload.txt") == payload, "bridge did not receive the bytes")
            require(cli.ok(cli.run("ls", "/sdcard")).find("demo.txt") >= 0, "ls broke after put")

        # 6. get --------------------------------------------------------
        def case_get() -> None:
            target = os.path.join(workdir, "download.txt")
            out = cli.ok(cli.run("get", "/sdcard/upload.txt", target))
            with open(target, "rb") as handle:
                require(handle.read() == payload, "downloaded bytes differ from uploaded bytes")
            require("wrote 20 bytes" in out, f"unexpected get output: {out!r}")

        # 7. shot -------------------------------------------------------
        def case_shot() -> None:
            out = cli.ok(cli.run("shot", "--max", "1280"))
            require("screenshot.png" in out, f"default path not reported: {out!r}")
            saved = os.path.join(workdir, "screenshot.png")
            require(os.path.isfile(saved), "screenshot.png not written to the cwd")
            with open(saved, "rb") as handle:
                head = handle.read(8)
            require(head == PNG_MAGIC, f"PNG magic missing, got {head!r}")
            require(str(len(FAKE_PNG)) in out, f"byte size not reported: {out!r}")

        def case_shot_out() -> None:
            target = os.path.join(workdir, "shot2.png")
            cli.ok(cli.run("shot", "--out", target))
            with open(target, "rb") as handle:
                require(handle.read(8) == PNG_MAGIC, "PNG magic missing in --out file")

        # 8. ui ---------------------------------------------------------
        def case_ui() -> None:
            out = cli.ok(cli.run("ui"))
            require("<hierarchy" in out, f"xml not printed: {out!r}")
            require("Hermes" in out, f"xml content missing: {out!r}")

        # 9. input / intents / apps ------------------------------------
        def case_tap() -> None:
            out = cli.ok(cli.run("tap", "540", "1200"))
            require(out.strip() == "ok", f"unexpected output: {out!r}")
            last = bridge.requests[-1]
            require(last["path"] == "/v1/input", f"hit {last['path']}")
            require(last["body"] == {"action": "tap", "x": 540, "y": 1200},
                    f"wrong body: {last['body']}")

        def case_swipe() -> None:
            out = cli.ok(cli.run("swipe", "1", "2", "3", "4", "--ms", "250"))
            require(out.strip() == "ok", f"unexpected output: {out!r}")
            require(bridge.requests[-1]["body"] == {
                "action": "swipe", "x1": 1, "y1": 2, "x2": 3, "y2": 4, "ms": 250,
            }, f"wrong body: {bridge.requests[-1]['body']}")

        def case_text() -> None:
            cli.ok(cli.run("text", "hello", "world"))
            require(bridge.requests[-1]["body"] == {"action": "text", "text": "hello world"},
                    f"wrong body: {bridge.requests[-1]['body']}")

        def case_key() -> None:
            cli.ok(cli.run("key", "back"))
            require(bridge.requests[-1]["body"] == {"action": "key", "key": "BACK"},
                    f"wrong body: {bridge.requests[-1]['body']}")

        def case_app_list() -> None:
            out = cli.ok(cli.run("app", "list"))
            require("com.android.settings" in out and "Settings" in out, f"apps missing: {out!r}")

        def case_app_launch() -> None:
            cli.ok(cli.run("app", "launch", "com.android.settings"))
            require(bridge.requests[-1]["body"] == {"action": "launch", "package": "com.android.settings"},
                    f"wrong body: {bridge.requests[-1]['body']}")

        def case_open() -> None:
            cli.ok(cli.run("open", "https://example.com/x"))
            require(bridge.requests[-1]["body"] == {"action": "view", "url": "https://example.com/x"},
                    f"wrong body: {bridge.requests[-1]['body']}")

        def case_share() -> None:
            cli.ok(cli.run("share", "look", "at", "this"))
            require(bridge.requests[-1]["body"] == {"action": "send", "text": "look at this"},
                    f"wrong body: {bridge.requests[-1]['body']}")

        # 10. auth + error handling ------------------------------------
        def case_auth_failure() -> None:
            proc = cli.run("status", token=WRONG_TOKEN)
            require(proc.returncode == 2, f"expected exit 2, got {proc.returncode}")
            require("error" in proc.stderr.lower() or "token" in proc.stderr.lower(),
                    f"no error line: {proc.stderr!r}")
            require("bad or missing token" in proc.stderr, f"server error string missing: {proc.stderr!r}")

        def case_missing_token() -> None:
            proc = cli.run("status", token=None)
            require(proc.returncode == 2, f"expected exit 2, got {proc.returncode}")
            require("token" in proc.stderr.lower(), f"no token hint: {proc.stderr!r}")

        def case_http_error() -> None:
            proc = cli.run("cat", "/sdcard/nope.txt")
            require(proc.returncode == 2, f"expected exit 2, got {proc.returncode}")
            require("no such file" in proc.stderr, f"server error string missing: {proc.stderr!r}")

        def case_connection_refused() -> None:
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                dead_port = probe.getsockname()[1]
            proc = cli.run("status", url=f"http://127.0.0.1:{dead_port}", token=TOKEN)
            require(proc.returncode == 2, f"expected exit 2, got {proc.returncode}")
            require("pocket:" in proc.stderr, f"no error line: {proc.stderr!r}")

        def case_env_url() -> None:
            out = cli.ok(cli.run("ls", env={"POCKET_URL": bridge.url}))
            require("demo.txt" in out, f"env URL ignored: {out!r}")

        runner.case("status", case_status)
        runner.case("status --json (path prefix + // url)", case_status_json)
        runner.case("shell echo", case_shell_echo)
        runner.case("shell exit code 7 + stderr", case_shell_exit_code)
        runner.case("shell --json", case_shell_json)
        runner.case("ls", case_ls)
        runner.case("cat", case_cat)
        runner.case("cat --out", case_cat_out)
        runner.case("cat --json (raw server JSON)", case_cat_json)
        runner.case("put", case_put)
        runner.case("get", case_get)
        runner.case("shot (PNG magic on disk)", case_shot)
        runner.case("shot --out", case_shot_out)
        runner.case("ui", case_ui)
        runner.case("tap", case_tap)
        runner.case("swipe --ms", case_swipe)
        runner.case("text", case_text)
        runner.case("key", case_key)
        runner.case("app list", case_app_list)
        runner.case("app launch", case_app_launch)
        runner.case("open", case_open)
        runner.case("share", case_share)
        runner.case("auth failure (401 -> exit 2)", case_auth_failure)
        runner.case("missing token", case_missing_token)
        runner.case("http error carries server message", case_http_error)
        runner.case("connection refused", case_connection_refused)
        runner.case("POCKET_URL env", case_env_url)
    finally:
        bridge.stop()
        shutil.rmtree(workdir, ignore_errors=True)

    print()
    passed = runner.total - runner.failed
    print(f"{passed}/{runner.total} cases passed")
    if runner.failed:
        print(f"{runner.failed} FAILED")
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
