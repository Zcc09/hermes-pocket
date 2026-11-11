#!/usr/bin/env python3
"""Stdlib test suite for the phone-notify gateway hook.

Runs a fake Hermes Pocket bridge on 127.0.0.1:0 and asserts the payload the
handler produces, plus every suppression / failure path.

    python integrations/phone-notify/test_handler.py
"""

from __future__ import annotations

import importlib.util
import json
import logging
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent


def load_handler(name: str):
    """Import handler.py from this directory under a unique module name."""
    spec = importlib.util.spec_from_file_location(name, HERE / "handler.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


HANDLER = load_handler("phone_notify_handler_under_test")


def wait_until(predicate, timeout: float = 3.0, interval: float = 0.02) -> bool:
    """handle() dispatches on a worker thread, so wait for the side effect."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


class LogCollector(logging.Handler):
    """Captures the hook's records (they are emitted on a worker thread)."""

    def __init__(self) -> None:
        super().__init__(level=logging.DEBUG)
        self.records: list[logging.LogRecord] = []
        self._lock = threading.Lock()

    def emit(self, record: logging.LogRecord) -> None:
        with self._lock:
            self.records.append(record)

    def messages(self) -> list[str]:
        with self._lock:
            return [r.getMessage() for r in self.records]

    def warnings(self) -> list[str]:
        with self._lock:
            return [r.getMessage() for r in self.records if r.levelno >= logging.WARNING]


# --------------------------------------------------------------------------- #
# fake bridge
# --------------------------------------------------------------------------- #
class FakeBridge:
    """Minimal stand-in for the app's /v1/notify endpoint."""

    def __init__(self) -> None:
        self.requests: list[dict] = []
        self._lock = threading.Lock()
        self.status = 200
        self.reply = {"ok": True, "posted": True}
        bridge = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):  # noqa: N802 - stdlib naming
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                with bridge._lock:
                    bridge.requests.append(
                        {
                            "path": self.path,
                            "headers": {k.lower(): v for k, v in self.headers.items()},
                            "body": raw.decode("utf-8", "replace"),
                            "json": json.loads(raw.decode("utf-8") or "{}"),
                        }
                    )
                payload = json.dumps(bridge.reply).encode("utf-8")
                self.send_response(bridge.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):  # silence test output
                pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def count(self) -> int:
        with self._lock:
            return len(self.requests)

    def last(self) -> dict:
        with self._lock:
            return self.requests[-1]

    def reset(self) -> None:
        with self._lock:
            self.requests.clear()

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)


def dead_port_url() -> str:
    """A URL nothing is listening on (bind, note the port, close)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    finally:
        sock.close()
    return "http://127.0.0.1:%d" % port


def hhmm(minutes: int) -> str:
    minutes %= 1440
    return "%02d:%02d" % (minutes // 60, minutes % 60)


def now_minutes() -> int:
    now = datetime.now()
    return now.hour * 60 + now.minute


LONG_RESPONSE = (
    "  Hermes   finished\n\n the   task, and this tail is deliberately long.  "
    + ("y" * 400)
)


class HookTests(unittest.TestCase):
    def setUp(self) -> None:
        self.bridge = FakeBridge()
        self.tmp = tempfile.TemporaryDirectory()
        self.cfg_path = Path(self.tmp.name) / "phone-notify.json"
        os.environ["POCKET_URL"] = self.bridge.url
        os.environ["POCKET_TOKEN"] = "s3cret-token"
        os.environ["PHONE_NOTIFY_CONFIG"] = str(self.cfg_path)
        HANDLER._missing_config_logged = False
        self.logs = LogCollector()
        HANDLER.logger.addHandler(self.logs)
        HANDLER.logger.setLevel(logging.DEBUG)

    def tearDown(self) -> None:
        HANDLER.logger.removeHandler(self.logs)
        for key in ("POCKET_URL", "POCKET_TOKEN", "PHONE_NOTIFY_CONFIG"):
            os.environ.pop(key, None)
        self.bridge.stop()
        self.tmp.cleanup()

    # helpers ---------------------------------------------------------------- #
    def write_config(self, **overrides) -> None:
        data = {
            "url": self.bridge.url,
            "token": "s3cret-token",
            "enabled": True,
            "min_chars": 0,
            "quiet_hours": "",
        }
        data.update(overrides)
        self.cfg_path.write_text(json.dumps(data), encoding="utf-8")
        os.environ.pop("POCKET_URL", None)
        os.environ.pop("POCKET_TOKEN", None)

    def context(self, **overrides) -> dict:
        ctx = {
            "platform": "webapp",
            "user_id": "u1",
            "chat_id": "c1",
            "session_id": "sess-42",
            "message": "status?",
            "response": "Done — the deploy finished and all checks are green.",
        }
        ctx.update(overrides)
        return ctx

    def settle(self, seconds: float = 0.25) -> None:
        """Give a stray worker time to do something it should not have done."""
        time.sleep(seconds)

    # (a) happy path --------------------------------------------------------- #
    def test_a_agent_end_posts_expected_payload(self):
        HANDLER.handle("agent:end", self.context(response=LONG_RESPONSE))
        self.assertTrue(wait_until(lambda: self.bridge.count() == 1), "no POST arrived")
        self.settle()
        self.assertEqual(self.bridge.count(), 1, "expected exactly one POST")

        req = self.bridge.last()
        self.assertEqual(req["path"], "/v1/notify")
        self.assertEqual(req["headers"].get("authorization"), "Bearer s3cret-token")
        self.assertEqual(req["headers"].get("content-type"), "application/json")
        self.assertEqual(req["headers"].get("user-agent"), "hermes-phone-notify")

        payload = req["json"]
        self.assertEqual(payload["title"], "Hermes \u00b7 webapp")

        collapsed = " ".join(LONG_RESPONSE.split())
        expected_body = collapsed[:240] + "\u2026"
        self.assertEqual(payload["body"], expected_body)
        self.assertEqual(len(payload["body"]), 241)
        self.assertTrue(payload["body"].endswith("\u2026"))
        self.assertNotIn("\n", payload["body"])

        self.assertEqual(payload["tag"], "sess-42")
        self.assertNotIn("force", payload)
        print("PASS a) agent:end posts one /v1/notify with bearer auth, 241-char body, tag=session_id")

    # (b) other events ------------------------------------------------------- #
    def test_b_other_events_post_nothing(self):
        for event in ("agent:start", "agent:step", "session:end", "command:reset", ""):
            HANDLER.handle(event, self.context())
        self.settle()
        self.assertEqual(self.bridge.count(), 0)
        print("PASS b) non-agent:end events post nothing")

    # (c) 401 ---------------------------------------------------------------- #
    def test_c_unauthorized_logs_warning_without_raising(self):
        self.bridge.status = 401
        self.bridge.reply = {"ok": False, "error": "bad or missing token"}

        HANDLER.handle("agent:end", self.context())  # must not raise
        self.assertTrue(wait_until(lambda: self.logs.warnings()), "no warning logged")
        self.settle()

        warnings = self.logs.warnings()
        self.assertEqual(len(warnings), 1, "expected a single warning line")
        self.assertIn("401", warnings[0])
        self.assertNotIn("Traceback", warnings[0])
        self.assertEqual(self.bridge.count(), 1, "must not retry")
        print("PASS c) 401 logs one warning, no traceback, no retry, no raise")

    # (d) connection refused ------------------------------------------------- #
    def test_d_connection_refused_does_not_raise(self):
        os.environ["POCKET_URL"] = dead_port_url()

        HANDLER.handle("agent:end", self.context())
        self.assertTrue(wait_until(lambda: self.logs.warnings()), "no warning logged")
        self.settle()

        warnings = self.logs.warnings()
        self.assertEqual(len(warnings), 1)
        self.assertIn("cannot reach", warnings[0])
        self.assertEqual(self.bridge.count(), 0)
        print("PASS d) connection refused logs one warning and raises nothing")

    # (e) enabled=false ------------------------------------------------------ #
    def test_e_disabled_config_posts_nothing(self):
        self.write_config(enabled=False)

        HANDLER.handle("agent:end", self.context())
        self.assertTrue(wait_until(lambda: self.logs.messages()), "nothing logged")
        self.settle()

        self.assertTrue(any("disabled" in m for m in self.logs.messages()))
        self.assertEqual(self.bridge.count(), 0)
        print("PASS e) enabled=false posts nothing (one INFO line)")

    # (f) quiet hours -------------------------------------------------------- #
    def test_f_quiet_hours_window(self):
        current = now_minutes()
        inside = "%s-%s" % (hhmm(current - 30), hhmm(current + 30))
        self.write_config(quiet_hours=inside)

        HANDLER.handle("agent:end", self.context())
        self.assertTrue(wait_until(lambda: self.logs.messages()), "nothing logged")
        self.settle()
        self.assertTrue(any("quiet hours" in m for m in self.logs.messages()))
        self.assertEqual(self.bridge.count(), 0, "must stay quiet inside the window")

        outside = "%s-%s" % (hhmm(current + 120), hhmm(current + 180))
        self.write_config(quiet_hours=outside)
        HANDLER.handle("agent:end", self.context())
        self.assertTrue(wait_until(lambda: self.bridge.count() == 1), "must post outside the window")

        # midnight wrap is deterministic when checked against a fixed clock
        self.assertTrue(HANDLER._in_quiet_hours("23:00-07:00", datetime(2026, 1, 1, 23, 30)))
        self.assertTrue(HANDLER._in_quiet_hours("23:00-07:00", datetime(2026, 1, 1, 6, 59)))
        self.assertFalse(HANDLER._in_quiet_hours("23:00-07:00", datetime(2026, 1, 1, 7, 0)))
        self.assertFalse(HANDLER._in_quiet_hours("23:00-07:00", datetime(2026, 1, 1, 12, 0)))
        self.assertFalse(HANDLER._in_quiet_hours("not-a-window", datetime(2026, 1, 1, 12, 0)))

        # invalid values in the config file are ignored, not raised
        self.bridge.reset()
        self.write_config(quiet_hours="not-a-window")
        HANDLER.handle("agent:end", self.context())
        self.assertTrue(wait_until(lambda: self.bridge.count() == 1), "invalid window is ignored")
        print("PASS f) quiet_hours suppress inside the window, post outside, bad values ignored")

    # (g) import isolation --------------------------------------------------- #
    def test_g_import_performs_no_io(self):
        def forbidden(*args, **kwargs):
            raise AssertionError("importing handler.py performed network I/O")

        with mock.patch("socket.socket.connect", side_effect=forbidden), mock.patch(
            "socket.create_connection", side_effect=forbidden
        ), mock.patch("urllib.request.urlopen", side_effect=forbidden):
            module = load_handler("phone_notify_import_probe")

        self.assertTrue(callable(module.handle))
        self.assertEqual(self.bridge.count(), 0)
        print("PASS g) importing handler.py performs no I/O and exposes handle()")

    # (h) missing config ----------------------------------------------------- #
    def test_h_missing_config_is_a_single_info_line(self):
        os.environ.pop("POCKET_URL", None)
        os.environ.pop("POCKET_TOKEN", None)
        HANDLER._missing_config_logged = False

        HANDLER.handle("agent:end", self.context())
        HANDLER.handle("agent:end", self.context())
        self.assertTrue(wait_until(lambda: self.logs.records), "nothing logged")
        self.settle()

        notices = [m for m in self.logs.messages() if "not configured" in m]
        self.assertEqual(len(notices), 1, "should log once, not per event")
        self.assertEqual(self.bridge.count(), 0)
        print("PASS h) unconfigured install logs once at INFO and posts nothing")


def main() -> int:
    print("phone-notify: %s" % (HERE / "handler.py"))
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(HookTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if result.wasSuccessful():
        print("\nOK — %d cases passed" % result.testsRun)
        return 0
    print("\nFAILED — %d failure(s), %d error(s)" % (len(result.failures), len(result.errors)))
    return 1


if __name__ == "__main__":
    sys.exit(main())
