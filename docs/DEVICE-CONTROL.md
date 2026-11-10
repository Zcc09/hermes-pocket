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

1. Install Shizuku from the Play Store, or from
   `github.com/RikkaApps/Shizuku/releases`.
2. On Android 11 or newer, enable **Developer options -> Wireless debugging**.
   In Shizuku choose **Start by connecting to a computer** (labelled "Start
   via adb" on some versions) and run the two commands it shows, which look
   like:

   ```bash
   adb pair 192.168.1.20:37123 482913
   adb connect 192.168.1.20:5555
   ```

   The pairing port, code and connect port are different each time; copy them
   from the Shizuku screen, do not reuse the numbers above.

3. If wireless debugging is not available, connect the phone over USB once and
   run:

   ```bash
   adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```

4. Open Hermes Pocket, enable device control and press **Test**. Shizuku shows
   an authorization prompt for Hermes Pocket; allow it. Press **Test** again if
   the first run fails.

Limit: this is shell, not root. Most of `/sdcard` and the system binaries are
reachable, but other apps' private data under `/data/data` is not, and neither
is anything that requires root to mount or modify.

Shizuku stops when the phone reboots. After each reboot you must start it
again the same way, unless it was started by a computer or by root (root can
keep it running on boot).

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
or the authorization was granted to an older install. Open Shizuku ->
**Authorized applications**, revoke Hermes Pocket, then press **Test** in
Hermes Pocket and allow the prompt again.

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
python tools/pocket.py status              # reachable? which tier?
python tools/pocket.py shell id            # uid=0(root) means root tier
python tools/pocket.py shot -o screen.png  # screenshot to a file
python tools/pocket.py tap 540 1200        # tap that point on the screen
```

Run `python tools/pocket.py --help` for the full command list.

The agent on your computer has to be on the same network as the phone. A
Hermes instance running on a server elsewhere, or on a VPN that does not route
to the phone's subnet, cannot reach `192.168.x.x`.
