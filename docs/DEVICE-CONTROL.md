# Device control

Hermes Pocket can act as a small server for the Hermes Agent running on your
computer. Turn it on in the app and the phone listens on your local network
(default port `8737`) with an HTTP/JSON API. The agent can then run shell
commands on the phone, read and write files, take screenshots, read the UI
hierarchy, tap and swipe and type, launch and stop apps, and fire intents.

Access is gated by one bearer token. The app generates it on the phone and
shows it to you; nothing works without it.

What you can do depends on how much privilege you grant the app. There are
three tiers. Pick the highest one your device allows.

## Turning it on

1. Open Hermes Pocket and enable device control.
2. Read the address and port the app shows (for example
   `http://192.168.1.20:8737`) and copy the token.
3. Press **Test**. The app runs a probe and reports the tier it reached.

The address is the phone's own LAN address, so your computer must be on the
same Wi-Fi. The service runs in the foreground with a notification; if you
swipe the app away or stop the service, the port closes.

Regenerate the token from the same screen at any time. Regenerating it
immediately invalidates the old one.

## Tier 1: root (full)

Use this if the phone is rooted. Any rooted Android device works; the
reference device is an AYN Thor handheld on Android 13, rooted with Magisk 31.

1. Open **Magisk** and go to **Superuser**. It should be empty on a fresh
   setup.
2. In Hermes Pocket, enable device control and press **Test**.
3. The app's first root command triggers Magisk's superuser prompt. Grant it
   and choose **Remember**, so later commands do not prompt again.

That is the whole setup. Nothing else to install.

What it enables: everything. `input`, `pm`, `settings`, `screencap`, any path
on the filesystem, other apps' data.

Verify it took:

```bash
python tools/pocket.py status      # tier=root
python tools/pocket.py shell id    # uid=0(root) ...
```

If `status` says a lower tier, the prompt was denied or the grant was not
remembered. Open Magisk -> Superuser, find Hermes Pocket, set it to allowed,
then press **Test** again.

## Tier 2: Shizuku (adb level, no root)

Use this if the phone is not rooted but you can reach a computer or use
wireless debugging. Shizuku is a separate helper app that starts a service
running with shell privileges (`uid=2000`). You start that service once, and
apps you authorize can borrow it.

The **Shizuku** section of device control connects and checks, so you never have
to guess which step is missing. Its status line is three facts, each from its
own call:

- `Installed · running · allowed` — ready. Choose **Shizuku (adb level)** under
  Access level, press **Test**, and commands run as the shell user.
- `Installed · running · not allowed` — the service is there and Hermes Pocket
  has no permission yet. Press **Connect Shizuku**.
- `Installed · not running` — the app is there, its service is not. Open Shizuku
  and start it.
- `Not installed` — press **Connect Shizuku**; it opens
  <https://shizuku.rikka.app/download/> in your browser.

The three facts are the package check (`moe.shizuku.privileged.api`), the
service binder, and Shizuku's own permission check. Nothing is inferred from the
access level you picked, so the radio group never claims a tier the phone cannot
deliver.

Steps, on a phone without root:

1. **Developer options -> Wireless debugging.** The **Wireless debugging
   settings** row opens that screen. If it lands on the general settings screen,
   Developer options are still off: open **About phone** and tap **Build number**
   seven times first.
2. **Install Shizuku and open it.** The **Open Shizuku** row starts it. Install
   from the Play Store or from <https://shizuku.rikka.app/download/>.
3. **Start the service.** In Shizuku, tap **Start via Wireless debugging** and
   pair with the pairing code and port Android shows. Or start it once from a
   computer over USB:

   ```bash
   adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```

   A computer can also do it over the network; Shizuku shows those two commands,
   and the ports differ every time, so copy them from that screen rather than
   reusing older ones.

Then press **Connect Shizuku** and allow the prompt Shizuku shows. A refusal is
reported plainly and changes nothing: the tier stays where it was.

Limit: this is shell, not root. Most of `/sdcard` and the system binaries are
reachable, but other apps' private data under `/data/data` is not, and neither
is anything that requires root to mount or modify.

Shizuku stops when the phone reboots. After each reboot you must start it
again the same way, unless it was started by a computer or by root (root can
keep it running on boot). The **Test** button and the status line tell you which
of those two states you are in.

Taps and screenshots are not part of this tier's gift: they still come from the
Hermes Pocket accessibility service, which is why the section above it matters on
a phone without root.

## Tier 3: none (app only)

No root, no Shizuku. The bridge still works, with the app's own privileges.

Enable the accessibility service to get gestures and screenshots: open Hermes
Pocket and press the button that opens **Settings -> Accessibility ->
Hermes Pocket**, then turn it on. Android shows a warning about full control of
the screen; that warning is accurate, and it is the same permission every
automation app asks for.

What works:

- Shell commands as the app's own user id. You get the app's sandbox and paths
  that are world-readable, which includes most of `/sdcard` on many devices.
- Launching apps and firing intents.
- Screenshots, reading the UI hierarchy, and tap/swipe/type through the
  accessibility service. Screenshots need Android 11 (API 30) or newer.
- Reading your own app's files and the shared storage the app can see.

What does not work:

- `input`, `pm`, `settings` — shell built-ins that need a different uid.
- Other apps' private data, and system paths the app cannot read.
- Force-stopping other apps.

## Capability matrix

| Capability | Root | Shizuku | None |
|---|---|---|---|
| Shell | full, uid 0 | shell uid 2000 | app uid only |
| Arbitrary file paths | any path | most of `/sdcard` and system paths, not `/data/data/*` | app sandbox and readable paths |
| Screenshot | yes | yes | accessibility service, Android 11+ |
| UI hierarchy | yes | yes | accessibility service |
| Tap / swipe / type | yes, `input` | yes, `input` | accessibility service |
| Launch / stop apps | yes | yes | launch only |
| System settings | read and write | read, write most keys | no |

"Accessibility service" means the Hermes Pocket accessibility service is
enabled in Settings. Without it, gestures and screenshots do nothing in the
none tier.

## Security

Be clear about what this is: while device control is on, anyone on your
network who has the token has remote code execution on your phone. The token
is the only gate.

- Keep it on your own Wi-Fi. Not café Wi-Fi, not a hotel network, not a shared
  flat's network with people you do not trust.
- Turn it off when you are not using it. It is a switch, not a setting to
  leave on.
- Regenerate the token if it leaks — pasted into a chat, committed to a repo,
  shown on a screen share.
- Never port-forward port 8737 from your router. That puts it on the internet.
- The agent's tools inherit the phone's power. It can wipe app data, read your
  photos, and send messages through intents. Grant the tier you are willing to
  live with.

What the app does not do: it is not a VPN, there is no cloud relay, and there
is no telemetry. Traffic goes from your computer to the phone over your LAN,
directly. Nothing is sent anywhere else, and the app phones home to nobody but
the GitHub releases API it already uses for updates.

## Troubleshooting

**Connection refused.** The service is off, or you have the wrong address, or
you are on a different network. Check that device control is on in the app and
that the notification is present. Check the IP in the app against your
computer's subnet — `ipconfig` on Windows, `ip addr` on Linux. A guest Wi-Fi
network usually isolates clients from each other, so the phone and computer
must be on the same main network. If the phone has been asleep a long time the
socket may be gone; wake it and check the notification again.

**401 Unauthorized.** The token does not match. Copy it again from the app and
update `POCKET_TOKEN`. Watch for a trailing newline or a smart quote picked up
by copy-paste; the token is plain ASCII.

**403 from Shizuku.** Shizuku is running but Hermes Pocket is not authorized,
or the authorization was granted to an older install. The status line in device
control says `Installed · running · not allowed` in that case. Open Shizuku ->
**Authorized applications**, revoke Hermes Pocket, then press **Connect Shizuku**
in Hermes Pocket and allow the prompt again.

**Gestures do nothing.** The accessibility service is off. Settings ->
Accessibility -> Hermes Pocket -> On, then retry.

**Screenshot fails in the none tier.** Android 11 (API 30) added the
accessibility screenshot API the app uses. On Android 10 and older, screenshots
in the none tier are not available — use root or Shizuku, or plug the phone in
and use `adb exec-out screencap`.

## Using it from the agent

`tools/pocket.py` is a small stdlib-only CLI in this repo, so no packages to
install. Set the address and token once per shell:

```bash
export POCKET_URL=http://192.168.1.20:8737
export POCKET_TOKEN=the-token-from-the-app
```

Then:

```bash
python tools/pocket.py status                 # reachable? which tier?
python tools/pocket.py shell id               # uid=0(root) means root tier
python tools/pocket.py shot --out screen.png  # screenshot to a file (default ./screenshot.png)
python tools/pocket.py ui                     # the UI tree, with bounds to tap
python tools/pocket.py tap 540 1200           # tap that point on the screen
```

`--url` and `--token` override the environment per call. `--json` prints the bridge's
raw JSON for the read and action commands; `shot` writes bytes, so it takes `--out`
and `--max <width>` instead. Run `python tools/pocket.py --help` for the full list.

The agent on your computer has to be on the same network as the phone. A
Hermes instance running on a server elsewhere, or on a VPN that does not route
to the phone's subnet, cannot reach `192.168.x.x`.

## Notifications

The same bridge can raise a notification, which is how a reply reaches you when
the app is not on screen:

```
POST /v1/notify
{"title": "Hermes", "body": "…", "tag": "<session id>", "force": false}
-> {"ok": true, "posted": true}
-> {"ok": true, "posted": false, "reason": "foreground" | "disabled" | "permission"}
```

Two suppressions are deliberate: nothing is posted while the app is in front
(you are already reading it — pass `"force": true` to override, which is what
the app's test button does), and nothing is posted if you turned **Reply
notifications** off on the Settings screen. A refusal comes back as a
normal 200 with `posted: false`, so the agent can tell "quiet by choice" from
"the bridge is down". Body is capped at 400 characters and title at 64; the tag
makes successive replies replace each other instead of stacking.

To have Hermes do this automatically on every reply, install the gateway hook in
[`integrations/phone-notify`](../integrations/phone-notify/README.md): it
subscribes to `agent:end` and posts the reply through this endpoint. Gateway
hooks load at startup, so the install ends with `hermes gateway restart`.
