#!/usr/bin/env python3
"""Command-line client for the Hermes Pocket device-control bridge.

``pocket`` talks to the small HTTP/1.1 JSON bridge that the Hermes Pocket
Android app runs on the phone (default port 8737), so a shell on your machine
can drive the device: run commands, read and write files, grab screenshots,
tap, swipe, type, launch apps and fire intents.

Stdlib only -- no pip install, no requests.  Python 3.9+.

Connection
    --url   / POCKET_URL     bridge base, e.g. ``http://192.168.1.20:8737``.
                             A path prefix (``http://host:8737/hermes``) is
                             kept; a missing scheme means ``http://``.
    --token / POCKET_TOKEN   bridge token, always sent as
                             ``Authorization: Bearer <token>``.

Exit status
    0   success (``shell`` forwards the remote exit code instead)
    2   connection failure, HTTP error, or bad arguments

Examples
    pocket --url 192.168.1.20:8737 --token s3cret status

    pocket shell "ls -l /sdcard/Download" \\
        && pocket shot --out phone.png && pocket tap 540 1600

    POCKET_URL=http://10.0.0.5:8737 POCKET_TOKEN=s3cret \\
        pocket put ./build/app.apk /sdcard/app.apk
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import sys
import textwrap
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable, Dict, List, Optional, Sequence

CLI_NAME = "pocket"
CLI_VERSION = "1.0"
USER_AGENT = f"hermes-pocket-cli/{CLI_VERSION} (+python-stdlib)"

DEFAULT_TIMEOUT = 30.0   # seconds, everything but `shell`
SHELL_TIMEOUT = 120.0    # seconds of HTTP patience for a remote shell command
DEFAULT_SHELL_MS = 30000  # timeoutMs handed to the bridge


class PocketError(Exception):
    """Any failure worth showing the user: connection, HTTP, bad response."""


# ---------------------------------------------------------------------------
# URL handling
# ---------------------------------------------------------------------------
def normalize_base(raw: str) -> str:
    """Turn a user-supplied bridge address into ``scheme://host[:port][/prefix]``.

    Accepts ``host:port``, ``host``, ``http://host:port`` and addresses with a
    path prefix.  Repeated slashes are collapsed and a trailing slash dropped.
    """
    text = (raw or "").strip()
    if not text:
        raise PocketError("empty bridge URL")
    if "://" not in text:
        text = "http://" + text
    parts = urllib.parse.urlsplit(text)
    scheme = parts.scheme.lower()
    if scheme not in ("http", "https"):
        raise PocketError(f"unsupported URL scheme {parts.scheme!r}: use http:// or https://")
    if not parts.netloc:
        raise PocketError(f"no host in bridge URL {raw!r}")
    prefix = "/".join(seg for seg in parts.path.split("/") if seg)
    return f"{scheme}://{parts.netloc}" + (f"/{prefix}" if prefix else "")


class PocketClient:
    """Minimal bearer-auth JSON client for one bridge."""

    def __init__(self, base_url: str, token: str, timeout: float = DEFAULT_TIMEOUT) -> None:
        self.base = normalize_base(base_url)
        self.token = token
        self.timeout = timeout

    # -- urls ---------------------------------------------------------------
    def url_for(self, path: str, params: Optional[Dict[str, Any]] = None) -> str:
        segments = [seg for seg in path.split("/") if seg]
        url = f"{self.base}/{'/'.join(segments)}" if segments else self.base
        if params:
            pairs = [(k, v) for k, v in params.items() if v is not None]
            if pairs:
                url += ("&" if "?" in url else "?") + urllib.parse.urlencode(pairs)
        return url

    # -- one request --------------------------------------------------------
    def request(
        self,
        method: str,
        path: str,
        body: Optional[Dict[str, Any]] = None,
        raw: bool = False,
        params: Optional[Dict[str, Any]] = None,
        timeout: Optional[float] = None,
    ) -> Any:
        """Perform one call.  Returns parsed JSON, or bytes when ``raw``."""
        url = self.url_for(path, params)
        data = None
        headers = {
            "Authorization": f"Bearer {self.token}",
            "User-Agent": USER_AGENT,
            "Accept": "image/png, application/json",
        }
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        wait = self.timeout if timeout is None else timeout
        req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
        try:
            with urllib.request.urlopen(req, timeout=wait) as resp:
                payload = resp.read()
        except urllib.error.HTTPError as exc:
            raise PocketError(self._http_error(exc)) from None
        except urllib.error.URLError as exc:
            raise PocketError(f"cannot reach {self.base}: {exc.reason}") from None
        except (TimeoutError, socket.timeout):
            raise PocketError(f"timed out after {wait:g}s talking to {self.base}") from None
        except OSError as exc:
            raise PocketError(f"cannot reach {self.base}: {exc}") from None
        if raw:
            return payload
        try:
            decoded = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            snippet = payload[:200].decode("utf-8", "replace")
            raise PocketError(f"bridge sent invalid JSON from {url}: {snippet!r}") from None
        return decoded

    @staticmethod
    def _http_error(exc: urllib.error.HTTPError) -> str:
        """Best-effort message from an error response body (JSON {"error": ...})."""
        message = ""
        try:
            body = exc.read()
        except Exception:  # pragma: no cover - body already consumed
            body = b""
        if body:
            try:
                parsed = json.loads(body.decode("utf-8", "replace"))
            except ValueError:
                parsed = None
            if isinstance(parsed, dict):
                candidate = parsed.get("error")
                if isinstance(candidate, str) and candidate:
                    message = candidate
            if not message:
                text = body[:200].decode("utf-8", "replace").strip()
                if text:
                    message = text
        detail = f"HTTP {exc.code}"
        if exc.reason:
            detail += f" {exc.reason}"
        return f"{message} ({detail})" if message else detail


# ---------------------------------------------------------------------------
# small formatting helpers
# ---------------------------------------------------------------------------
def fmt_size(size: Optional[int]) -> str:
    if size is None:
        return "-"
    value = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024.0:
            return f"{int(value)} B" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024.0
    return f"{value:.1f} TB"


def fmt_mtime(epoch: Optional[int]) -> str:
    if not epoch:
        return "-"
    try:
        return time.strftime("%Y-%m-%d %H:%M", time.localtime(int(epoch)))
    except (OSError, ValueError, OverflowError):
        return str(epoch)


def fmt_duration(seconds: float) -> str:
    total = max(0, int(seconds))
    days, rest = divmod(total, 86400)
    hours, rest = divmod(rest, 3600)
    minutes, secs = divmod(rest, 60)
    if days:
        return f"{days}d{hours}h"
    if hours:
        return f"{hours}h{minutes}m"
    if minutes:
        return f"{minutes}m{secs}s"
    return f"{secs}s"


def emit_json(value: Any) -> None:
    print(json.dumps(value, indent=2, ensure_ascii=False))


def emit_text(text: str) -> None:
    """Write already-text payloads verbatim, adding only a missing trailing newline."""
    if not text:
        return
    sys.stdout.write(text)
    if not text.endswith("\n"):
        sys.stdout.write("\n")
    sys.stdout.flush()


def emit_stderr(text: str) -> None:
    if not text:
        return
    sys.stderr.write(text)
    if not text.endswith("\n"):
        sys.stderr.write("\n")
    sys.stderr.flush()


def check_parent(path: str) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    if not os.path.isdir(parent):
        raise PocketError(f"no such directory: {parent}")


def write_bytes(path: str, data: bytes) -> None:
    check_parent(path)
    try:
        with open(path, "wb") as handle:
            handle.write(data)
    except OSError as exc:
        raise PocketError(f"cannot write {path}: {exc}") from None


def read_bytes(path: str) -> bytes:
    if not os.path.isfile(path):
        raise PocketError(f"no such file: {path}")
    try:
        with open(path, "rb") as handle:
            return handle.read()
    except OSError as exc:
        raise PocketError(f"cannot read {path}: {exc}") from None


def decode_payload(res: Dict[str, Any]) -> bytes:
    """Bytes out of an /v1/fs read response, whether base64 or plain."""
    if "content_b64" in res:
        try:
            return base64.b64decode(res.get("content_b64") or "", validate=False)
        except (ValueError, TypeError) as exc:
            raise PocketError(f"bridge sent bad base64: {exc}") from None
    if "content" in res:
        return str(res.get("content") or "").encode("utf-8")
    raise PocketError("read returned no content")


def want_json(args: argparse.Namespace) -> bool:
    return bool(getattr(args, "json", False))


# ---------------------------------------------------------------------------
# subcommands
# ---------------------------------------------------------------------------
def cmd_status(client: PocketClient, args: argparse.Namespace) -> int:
    st = client.request("GET", "/v1/status")
    if want_json(args):
        emit_json(st)
        return 0
    device = st.get("device") or {}
    bridge = st.get("bridge") or {}
    tiers = st.get("tiers") or {}
    caps = st.get("caps") or {}

    headline: List[str] = [
        f"hermes-pocket {st.get('version', '?')}",
        f"tier {st.get('tier', '?')}",
    ]
    model = device.get("model") or "?"
    android = device.get("android") or "?"
    sdk = device.get("sdk")
    headline.append(f"{model} / Android {android}" + (f" (sdk {sdk})" if sdk is not None else ""))
    if bridge.get("port"):
        headline.append(f"bridge :{bridge['port']}")
    since = bridge.get("since")
    if isinstance(since, (int, float)) and since:
        headline.append(f"up {fmt_duration(time.time() - since)}")
    print(" · ".join(headline))
    if tiers:
        print("tiers: " + "  ".join(f"{name}={'yes' if on else 'no'}" for name, on in tiers.items()))
    if caps:
        print("caps:  " + "  ".join(name if on else f"-{name}" for name, on in caps.items()))
    return 0


def cmd_shell(client: PocketClient, args: argparse.Namespace) -> int:
    words = list(getattr(args, "cmd", []) or [])
    if words and words[0] == "--":
        words = words[1:]
    if not words:
        raise PocketError("shell needs a command, e.g. pocket shell \"ls -l /sdcard\"")
    res = client.request(
        "POST",
        "/v1/shell",
        {"cmd": " ".join(words), "timeoutMs": int(getattr(args, "timeout_ms", DEFAULT_SHELL_MS))},
        timeout=SHELL_TIMEOUT,
    )
    if want_json(args):
        emit_json(res)
        return 0
    emit_text(str(res.get("stdout") or ""))
    emit_stderr(str(res.get("stderr") or ""))
    if res.get("truncated"):
        print("pocket: warning: output truncated by the bridge", file=sys.stderr)
    code = res.get("exit")
    return 0 if code is None else int(code)


def cmd_ls(client: PocketClient, args: argparse.Namespace) -> int:
    res = client.request("GET", "/v1/fs", params={"op": "list", "path": args.path})
    if want_json(args):
        emit_json(res)
        return 0
    entries = res.get("entries") or []
    if not entries:
        print(f"{args.path}: empty", file=sys.stderr)
        return 0
    for entry in entries:
        is_dir = bool(entry.get("dir"))
        size = "-" if is_dir else fmt_size(entry.get("size"))
        name = str(entry.get("name", "?")) + ("/" if is_dir else "")
        print(f"{'d' if is_dir else '-'} {size:>10}  {fmt_mtime(entry.get('mtime'))}  {name}")
    return 0


def _read_remote(client: PocketClient, remote: str) -> Dict[str, Any]:
    return client.request("GET", "/v1/fs", params={"op": "read", "path": remote, "b64": 1})


def cmd_cat(client: PocketClient, args: argparse.Namespace) -> int:
    res = _read_remote(client, args.path)
    if args.out:
        write_bytes(args.out, decode_payload(res))
        return 0
    if want_json(args):
        emit_json(res)
        return 0
    data = decode_payload(res)
    if b"\x00" in data:
        print("pocket: warning: binary content; pass --out FILE", file=sys.stderr)
    emit_text(data.decode("utf-8", "replace"))
    return 0


def cmd_put(client: PocketClient, args: argparse.Namespace) -> int:
    data = read_bytes(args.local)
    res = client.request(
        "PUT",
        "/v1/fs",
        {"op": "write", "path": args.remote, "content_b64": base64.b64encode(data).decode("ascii")},
    )
    if want_json(args):
        emit_json(res)
        return 0
    written = res.get("bytes", len(data))
    print(f"{args.remote}: wrote {written} bytes")
    return 0


def cmd_get(client: PocketClient, args: argparse.Namespace) -> int:
    res = _read_remote(client, args.remote)
    data = decode_payload(res)
    write_bytes(args.local, data)
    if want_json(args):
        emit_json(res)
        return 0
    print(f"{args.local}: wrote {len(data)} bytes")
    return 0


def cmd_shot(client: PocketClient, args: argparse.Namespace) -> int:
    out = args.out or "screenshot.png"
    params: Dict[str, Any] = {}
    if args.max is not None:
        params["max"] = args.max
    data = client.request("GET", "/v1/screenshot", params=params, raw=True)
    if not data:
        raise PocketError("bridge returned an empty screenshot")
    write_bytes(out, data)
    print(f"{out} ({len(data)} bytes)")
    return 0


def cmd_ui(client: PocketClient, args: argparse.Namespace) -> int:
    res = client.request("GET", "/v1/ui")
    if want_json(args):
        emit_json(res)
        return 0
    emit_text(str(res.get("xml") or ""))
    return 0


def _action(client: PocketClient, args: argparse.Namespace, path: str, body: Dict[str, Any]) -> int:
    res = client.request("POST", path, body)
    if want_json(args):
        emit_json(res)
        return 0
    print("ok")
    return 0


def cmd_tap(client: PocketClient, args: argparse.Namespace) -> int:
    return _action(client, args, "/v1/input", {"action": "tap", "x": args.x, "y": args.y})


def cmd_swipe(client: PocketClient, args: argparse.Namespace) -> int:
    body = {
        "action": "swipe",
        "x1": args.x1,
        "y1": args.y1,
        "x2": args.x2,
        "y2": args.y2,
        "ms": args.ms,
    }
    return _action(client, args, "/v1/input", body)


def cmd_text(client: PocketClient, args: argparse.Namespace) -> int:
    words = list(args.text or [])
    if not words:
        raise PocketError("text needs something to type")
    return _action(client, args, "/v1/input", {"action": "text", "text": " ".join(words)})


def cmd_key(client: PocketClient, args: argparse.Namespace) -> int:
    return _action(client, args, "/v1/input", {"action": "key", "key": args.key.upper()})


def cmd_app(client: PocketClient, args: argparse.Namespace) -> int:
    if args.action == "list":
        res = client.request("POST", "/v1/app", {"action": "list"})
        if want_json(args):
            emit_json(res)
            return 0
        apps = res.get("apps") or []
        if not apps:
            print("no apps", file=sys.stderr)
            return 0
        for app in apps:
            label = str(app.get("label") or "")
            print(f"{str(app.get('package', '?')):<40}  {label}".rstrip())
        return 0
    if not args.package:
        raise PocketError(f"app {args.action} needs a package name")
    return _action(client, args, "/v1/app", {"action": args.action, "package": args.package})


def cmd_open(client: PocketClient, args: argparse.Namespace) -> int:
    if not args.target:
        raise PocketError("open needs a URL")
    return _action(client, args, "/v1/intent", {"action": "view", "url": args.target})


def cmd_share(client: PocketClient, args: argparse.Namespace) -> int:
    words = list(args.text or [])
    if not words:
        raise PocketError("share needs some text")
    return _action(client, args, "/v1/intent", {"action": "send", "text": " ".join(words)})


HANDLERS: Dict[str, Callable[[PocketClient, argparse.Namespace], int]] = {
    "status": cmd_status,
    "shell": cmd_shell,
    "ls": cmd_ls,
    "cat": cmd_cat,
    "put": cmd_put,
    "get": cmd_get,
    "shot": cmd_shot,
    "ui": cmd_ui,
    "tap": cmd_tap,
    "swipe": cmd_swipe,
    "text": cmd_text,
    "key": cmd_key,
    "app": cmd_app,
    "open": cmd_open,
    "share": cmd_share,
}


# ---------------------------------------------------------------------------
# argument parsing
# ---------------------------------------------------------------------------
def examples_epilog() -> str:
    """The three usage examples from the module docstring, dedented for --help."""
    _head, _, tail = __doc__.partition("Examples\n")
    return "examples:\n" + textwrap.dedent(tail).strip()


def build_parser() -> argparse.ArgumentParser:
    # Connection flags also live on every subcommand, so `pocket status --token x`
    # works as well as the documented `pocket --token x status`.  SUPPRESS keeps
    # the subparser copy from clobbering a value given before the subcommand.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--url", default=argparse.SUPPRESS, metavar="URL",
                        help="bridge base URL (default: $POCKET_URL)")
    common.add_argument("--token", default=argparse.SUPPRESS, metavar="TOKEN",
                        help="bridge bearer token (default: $POCKET_TOKEN)")
    common.add_argument("--json", action="store_true", default=argparse.SUPPRESS,
                        help="print the bridge's raw JSON instead of formatted output")

    parser = argparse.ArgumentParser(
        prog=CLI_NAME,
        description="Drive the Hermes Pocket device-control bridge from a shell.",
        epilog=examples_epilog(),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--url", default=None, metavar="URL",
                        help="bridge base URL, e.g. http://192.168.1.20:8737 (default: $POCKET_URL)")
    parser.add_argument("--token", default=None, metavar="TOKEN",
                        help="bridge bearer token (default: $POCKET_TOKEN)")
    parser.add_argument("--json", action="store_true",
                        help="print the bridge's raw JSON instead of formatted output")
    parser.add_argument("--version", action="version", version=f"{CLI_NAME} {CLI_VERSION}")

    sub = parser.add_subparsers(dest="command", metavar="COMMAND", required=True)

    sub.add_parser("status", parents=[common], help="bridge, tier, device and capability status")

    p = sub.add_parser("shell", parents=[common], help="run a shell command on the phone",
                       usage="pocket shell [--timeout MS] -- CMD...")
    p.add_argument("--timeout", dest="timeout_ms", type=int, default=DEFAULT_SHELL_MS,
                   metavar="MS", help=f"command timeout in ms (default {DEFAULT_SHELL_MS})")
    p.add_argument("cmd", nargs=argparse.REMAINDER,
                   help="the command; flags after the first word are passed through")

    p = sub.add_parser("ls", parents=[common], help="list a directory on the phone")
    p.add_argument("path", nargs="?", default="/sdcard", help="directory (default /sdcard)")

    p = sub.add_parser("cat", parents=[common], help="print or download one file")
    p.add_argument("path", help="remote file")
    p.add_argument("--out", metavar="FILE", help="write bytes to FILE instead of printing text")

    p = sub.add_parser("put", parents=[common], help="upload a local file to the phone")
    p.add_argument("local", help="local file")
    p.add_argument("remote", help="remote path")

    p = sub.add_parser("get", parents=[common], help="download a remote file")
    p.add_argument("remote", help="remote path")
    p.add_argument("local", help="local file")

    p = sub.add_parser("shot", parents=[common], help="take a screenshot (PNG)",
                       description="Download a PNG screenshot (raw bytes, so --json does not apply).")
    p.add_argument("--out", metavar="FILE", default=None, help="output file (default ./screenshot.png)")
    p.add_argument("--max", type=int, default=None, metavar="N", help="longest edge in pixels")

    sub.add_parser("ui", parents=[common], help="dump the current UI hierarchy XML")

    p = sub.add_parser("tap", parents=[common], help="tap a screen coordinate")
    p.add_argument("x", type=int)
    p.add_argument("y", type=int)

    p = sub.add_parser("swipe", parents=[common], help="swipe between two points")
    p.add_argument("x1", type=int)
    p.add_argument("y1", type=int)
    p.add_argument("x2", type=int)
    p.add_argument("y2", type=int)
    p.add_argument("--ms", type=int, default=300, metavar="N", help="gesture duration (default 300)")

    p = sub.add_parser("text", parents=[common], help="type text into the focused field")
    p.add_argument("text", nargs=argparse.REMAINDER, help="text to type")

    p = sub.add_parser("key", parents=[common], help="press a key, e.g. BACK, HOME, ENTER")
    p.add_argument("key", help="key name")

    p = sub.add_parser("app", parents=[common], help="list, launch or stop apps")
    p.add_argument("action", choices=("list", "launch", "stop"))
    p.add_argument("package", nargs="?", help="package name (launch/stop)")

    # dest "target", not "url": the positional would otherwise shadow the --url
    # option of the same name and the CLI would dial the page instead of the bridge.
    p = sub.add_parser("open", parents=[common], help="open a URL on the phone")
    p.add_argument("target", metavar="URL", help="http(s) URL")

    p = sub.add_parser("share", parents=[common], help="send text to the share sheet")
    p.add_argument("text", nargs=argparse.REMAINDER, help="text to share")

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        url = getattr(args, "url", None) or os.environ.get("POCKET_URL", "")
        token = getattr(args, "token", None) or os.environ.get("POCKET_TOKEN", "")
        if not url:
            raise PocketError("no bridge URL: pass --url or set POCKET_URL")
        if not token:
            raise PocketError("no bridge token: pass --token or set POCKET_TOKEN")
        timeout = SHELL_TIMEOUT if args.command == "shell" else DEFAULT_TIMEOUT
        client = PocketClient(url, token, timeout=timeout)
        return HANDLERS[args.command](client, args)
    except PocketError as exc:
        print(f"{CLI_NAME}: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print(f"{CLI_NAME}: interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
