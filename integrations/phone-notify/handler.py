"""Hermes gateway hook: push a "Hermes replied" notification to the phone.

Subscribes to ``agent:end`` and POSTs a short notification to the Hermes Pocket
app's LAN bridge at ``{POCKET_URL}/v1/notify``.

Design rules:
  * stdlib only (json, os, pathlib, urllib, logging, threading, datetime)
  * importing this module performs no I/O at all — config is read inside handle()
  * the POST runs on a short-lived daemon thread, so a slow or unreachable phone
    never stalls the gateway event loop
  * never raise and never retry: one attempt per event, failures are one log line
"""

from __future__ import annotations

import json
import logging
import os
import threading
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

logger = logging.getLogger("hooks.phone-notify")
logger.addHandler(logging.NullHandler())

# Overridable with PHONE_NOTIFY_CONFIG; install.py writes this file.
def _default_config_path() -> str:
    """Per-user Hermes home, without baking a username into the repo."""
    local = os.environ.get("LOCALAPPDATA")
    base = Path(local) / "hermes" if local else Path.home() / ".hermes"
    return str(base / "phone-notify.json")


DEFAULT_CONFIG_PATH = _default_config_path()

EVENT = "agent:end"
ENDPOINT = "/v1/notify"
TITLE_BASE = "Hermes"
TITLE_SEP = " \u00b7 "  # "Hermes · webapp"
MAX_BODY = 240
ELLIPSIS = "\u2026"
TIMEOUT = 3.0
USER_AGENT = "hermes-phone-notify"

_missing_config_logged = False
_missing_lock = threading.Lock()


# --------------------------------------------------------------------------- #
# config
# --------------------------------------------------------------------------- #
def _config_path() -> Path:
    raw = os.environ.get("PHONE_NOTIFY_CONFIG") or DEFAULT_CONFIG_PATH
    return Path(raw).expanduser()


def _truthy(value, default: bool = True) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        text = value.strip().lower()
        if text in ("1", "true", "yes", "on"):
            return True
        if text in ("0", "false", "no", "off"):
            return False
    return default


def _as_int(value, default: int = 0) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return number if number > 0 else default


def _read_config_file() -> dict:
    path = _config_path()
    try:
        with path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as exc:
        logger.warning("phone-notify: cannot read %s (%s)", path, exc)
        return {}
    return data if isinstance(data, dict) else {}


def _load_config() -> dict | None:
    """Env first for url/token, file for the rest. None when not configured."""
    global _missing_config_logged

    file_cfg = _read_config_file()
    url = (os.environ.get("POCKET_URL") or file_cfg.get("url") or "").strip()
    token = (os.environ.get("POCKET_TOKEN") or file_cfg.get("token") or "").strip()

    if not url or not token:
        with _missing_lock:
            first_time = not _missing_config_logged
            _missing_config_logged = True
        if first_time:
            logger.info(
                "phone-notify: not configured (set POCKET_URL/POCKET_TOKEN or write %s); idle",
                _config_path(),
            )
        return None

    return {
        "url": url.rstrip("/"),
        "token": token,
        "enabled": _truthy(file_cfg.get("enabled"), default=True),
        "min_chars": _as_int(file_cfg.get("min_chars"), default=0),
        "quiet_hours": file_cfg.get("quiet_hours") or "",
    }


# --------------------------------------------------------------------------- #
# quiet hours
# --------------------------------------------------------------------------- #
def _parse_hhmm(text: str) -> int | None:
    hours_s, minutes_s = text.split(":", 1)
    hours, minutes = int(hours_s), int(minutes_s)
    if not (0 <= hours <= 24 and 0 <= minutes <= 59):
        return None
    return (hours % 24) * 60 + minutes


def _in_quiet_hours(window, now: datetime | None = None) -> bool:
    """'23:00-07:00' style window in local time; wraps midnight. Bad input = off."""
    if not isinstance(window, str):
        return False
    spec = window.strip()
    if "-" not in spec:
        return False
    start_s, end_s = spec.split("-", 1)
    try:
        start, end = _parse_hhmm(start_s), _parse_hhmm(end_s)
    except (TypeError, ValueError):
        return False
    if start is None or end is None or start == end:
        return False

    now = now or datetime.now()
    current = now.hour * 60 + now.minute
    if start < end:
        return start <= current < end
    return current >= start or current < end


# --------------------------------------------------------------------------- #
# payload
# --------------------------------------------------------------------------- #
def _collapse(text: str) -> str:
    return " ".join(str(text).split())


def _build_title(context: dict) -> str:
    platform = context.get("platform")
    platform = platform.strip() if isinstance(platform, str) else ""
    if platform and platform.lower() not in ("unknown", "none"):
        return TITLE_BASE + TITLE_SEP + platform
    return TITLE_BASE


def _build_body(context: dict) -> str:
    text = _collapse(context.get("response") or "")
    if len(text) > MAX_BODY:
        return text[:MAX_BODY] + ELLIPSIS
    return text


# --------------------------------------------------------------------------- #
# transport
# --------------------------------------------------------------------------- #
def _post(url: str, token: str, payload: dict) -> bool:
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url + ENDPOINT,
        data=data,
        method="POST",
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
            "User-Agent": USER_AGENT,
        },
    )

    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            raw = response.read(4096)
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            logger.warning(
                "phone-notify: bridge rejected the token (401) — check POCKET_TOKEN / %s",
                _config_path(),
            )
        else:
            logger.warning("phone-notify: bridge answered HTTP %s", exc.code)
        return False
    except ValueError as exc:  # bad scheme, e.g. "192.168.1.20:8737"
        logger.warning("phone-notify: bad POCKET_URL %r (%s)", url, exc)
        return False
    except Exception as exc:  # URLError, socket timeouts, refused connections
        logger.warning("phone-notify: cannot reach %s (%s)", url, exc)
        return False

    try:
        result = json.loads(raw.decode("utf-8") or "{}")
    except (UnicodeDecodeError, ValueError):
        result = {}
    if isinstance(result, dict) and result.get("posted") is False:
        logger.info(
            "phone-notify: app suppressed the notification (%s)",
            result.get("reason") or "unknown",
        )
    return True


# --------------------------------------------------------------------------- #
# hook entry point
# --------------------------------------------------------------------------- #
def _notify(context: dict) -> None:
    config = _load_config()
    if config is None:
        return

    if not config["enabled"]:
        logger.info("phone-notify: disabled in config, skipping")
        return

    if _in_quiet_hours(config["quiet_hours"]):
        logger.info("phone-notify: quiet hours %r, skipping", config["quiet_hours"])
        return

    body = _build_body(context)
    if not body:
        logger.info("phone-notify: empty response, nothing to show")
        return

    if config["min_chars"] and len(body) < config["min_chars"]:
        logger.info(
            "phone-notify: response shorter than min_chars (%d), skipping",
            config["min_chars"],
        )
        return

    payload = {"title": _build_title(context)}
    payload["body"] = body
    tag = context.get("session_id")
    if isinstance(tag, str) and tag.strip():
        payload["tag"] = tag.strip()

    _post(config["url"], config["token"], payload)


def _worker(context: dict) -> None:
    try:
        _notify(context)
    except Exception as exc:  # a hook must never break the agent
        logger.warning("phone-notify: unexpected failure (%s)", exc)


def handle(event_type: str, context: dict) -> None:
    """Called by the gateway for each subscribed event. Must be named 'handle'.

    Returns immediately: the gateway awaits this inline on its event loop, so the
    blocking HTTP call lives on a daemon thread of its own.
    """
    if event_type != EVENT:
        return
    try:
        threading.Thread(
            target=_worker,
            args=(context or {},),
            name="phone-notify",
            daemon=True,
        ).start()
    except Exception as exc:
        logger.warning("phone-notify: cannot start worker (%s)", exc)
