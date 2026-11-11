# phone-notify — "Hermes replied" on your phone

A Hermes **gateway hook** that fires on `agent:end` and POSTs a short
notification to the Hermes Pocket app's bridge, so the phone buzzes when the
agent finishes. The POST is fire-and-forget on a daemon thread (one attempt,
3 s timeout), and a failure logs one line instead of a traceback or a retry.

## Install

```bash
python integrations/phone-notify/install.py --url http://192.168.1.20:8737 --token <token>
```

This copies `HOOK.yaml` + `handler.py` to `<HERMES_HOME>/hooks/phone-notify/` and
writes `<HERMES_HOME>/phone-notify.json` (`url`, `token`, `enabled`, `min_chars`,
`quiet_hours`, 0600); `--remove` uninstalls. Restart: `hermes gateway restart`.

**Where the URL and token come from:** Hermes Pocket → **Device control** →
enable it. That screen shows the phone's LAN address (e.g.
`http://192.168.1.20:8737`) and a bearer token; copy both. The bridge listens
only while that foreground service runs, on the same Wi-Fi.

## Test

```bash
python integrations/phone-notify/install.py --dry-run   # show, change nothing
hermes hooks doctor                                     # sanity-check hook config
python integrations/phone-notify/test_handler.py         # 8 stdlib cases vs a fake bridge
```

## Suppressed on purpose

The app answers `200 {"ok": true, "posted": false}` plus a `reason` when it skips
on purpose: `disabled` (reply notifications off in the app) or `foreground` (app
open in front of the user; `force` overrides). Both log at INFO, no retry.
