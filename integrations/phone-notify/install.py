#!/usr/bin/env python3
"""Install the phone-notify gateway hook for the Hermes Pocket app.

Copies HOOK.yaml + handler.py into <HERMES_HOME>/hooks/phone-notify/ and writes
the bridge config (url + token) to <HERMES_HOME>/phone-notify.json with 0600.

    python integrations/phone-notify/install.py --url http://192.168.1.20:8737 --token abc
    python integrations/phone-notify/install.py --dry-run
    python integrations/phone-notify/install.py --remove

Gateway hooks are loaded once at gateway startup, so a restart is required.
Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

HOOK_NAME = "phone-notify"
FILES = ("HOOK.yaml", "handler.py")
def _default_hermes_home() -> str:
    """Per-user Hermes home, without baking a username into the repo."""
    local = os.environ.get("LOCALAPPDATA")
    base = Path(local) / "hermes" if local else Path.home() / ".hermes"
    return str(base)


DEFAULT_HERMES_HOME = _default_hermes_home()
RESTART_COMMAND = "hermes gateway restart"


def hermes_home(arg: str | None) -> Path:
    raw = arg or os.environ.get("HERMES_HOME") or DEFAULT_HERMES_HOME
    return Path(raw).expanduser().resolve()


def config_path(hermes: Path) -> Path:
    raw = os.environ.get("PHONE_NOTIFY_CONFIG")
    return Path(raw).expanduser() if raw else hermes / (HOOK_NAME + ".json")


def hook_dir(hermes: Path) -> Path:
    return hermes / "hooks" / HOOK_NAME


def say(message: str) -> None:
    print(message, flush=True)


def ask(label: str) -> str:
    try:
        return input(label + ": ").strip()
    except EOFError:
        return ""


def write_config(path: Path, url: str, token: str, dry_run: bool) -> None:
    payload = {
        "url": url,
        "token": token,
        "enabled": True,
        "min_chars": 0,
        "quiet_hours": "",
    }
    if dry_run:
        say("  would write %s (0600)" % path)
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")
    try:
        os.chmod(path, 0o600)
    except OSError as exc:  # ACL-only filesystems (Windows) may refuse
        say("  note: could not chmod 0600 on %s (%s)" % (path, exc))
    say("  wrote %s (0600, url=%s)" % (path, url))


def install(args: argparse.Namespace) -> int:
    source = Path(__file__).resolve().parent
    hermes = hermes_home(args.hermes_home)
    target = hook_dir(hermes)
    cfg = config_path(hermes)
    dry = args.dry_run

    url = (args.url or "").strip()
    token = (args.token or "").strip()

    say("%s phone-notify hook" % ("[dry-run] would install" if dry else "Installing"))
    say("  source: %s" % source)
    say("  target: %s" % target)

    for name in FILES:
        if not (source / name).is_file():
            say("error: %s is missing next to install.py" % name)
            return 2

    if not url:
        if dry:
            say("  would prompt for the bridge URL (e.g. http://192.168.1.20:8737)")
            url = "http://example.invalid:8737"
        else:
            url = ask("Bridge URL (shown in the app, e.g. http://192.168.1.20:8737)")
    if not token and not dry:
        token = ask("Bridge token (shown in the app)")

    if not url or not url.lower().startswith(("http://", "https://")):
        say("error: --url must look like http://192.168.1.20:8737")
        return 2
    if not token:
        if dry:
            say("  would prompt for the bridge token")
            token = "dry-run-token"
        else:
            say("error: a token is required (--token or the prompt)")
            return 2

    if dry:
        say("  would copy %s -> %s" % (", ".join(FILES), target))
    else:
        target.mkdir(parents=True, exist_ok=True)
        for name in FILES:
            shutil.copyfile(source / name, target / name)
        say("  copied %s -> %s" % (", ".join(FILES), target))

    write_config(cfg, url.rstrip("/"), token, dry)

    if dry:
        say("  would print: restart the gateway with `%s`" % RESTART_COMMAND)
        say("dry-run: nothing was changed")
        return 0

    say("")
    say("Installed. Gateway hooks load at startup, so restart the gateway:")
    say("")
    say("    %s" % RESTART_COMMAND)
    say("")
    say("Then reply to the bot from another device; the phone should buzz.")
    say("Logs: hermes logs --follow --level INFO | grep phone-notify")
    return 0


def remove(args: argparse.Namespace) -> int:
    hermes = hermes_home(args.hermes_home)
    target = hook_dir(hermes)
    cfg = config_path(hermes)
    dry = args.dry_run

    say("%s phone-notify hook" % ("[dry-run] would remove" if dry else "Removing"))

    if not target.exists() and not cfg.exists():
        say("  nothing installed under %s" % hermes)
        return 0

    if dry:
        if target.exists():
            say("  would delete %s" % target)
        if cfg.exists():
            say("  would delete %s" % cfg)
        say("dry-run: nothing was changed")
        return 0

    if target.exists():
        shutil.rmtree(target)
        say("  deleted %s" % target)
    if cfg.exists():
        cfg.unlink()
        say("  deleted %s" % cfg)

    say("")
    say("Removed. Restart the gateway to unload it:")
    say("")
    say("    %s" % RESTART_COMMAND)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="install.py",
        description="Install the phone-notify gateway hook (Hermes Pocket app).",
    )
    parser.add_argument("--url", help="bridge base URL, e.g. http://192.168.1.20:8737")
    parser.add_argument("--token", help="bridge bearer token shown in the app")
    parser.add_argument(
        "--hermes-home",
        help="Hermes home (default: $HERMES_HOME or %s)" % DEFAULT_HERMES_HOME,
    )
    parser.add_argument("--dry-run", action="store_true", help="show actions, change nothing")
    parser.add_argument("--remove", action="store_true", help="uninstall the hook and config")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return remove(args) if args.remove else install(args)


if __name__ == "__main__":
    sys.exit(main())
