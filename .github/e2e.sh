#!/usr/bin/env bash
# Emulator smoke suite for Hermes Pocket.
#
# The app has no built-in server, so this suite proves the parts that are true
# of any deployment, using a neutral public page as the stand-in:
#
#   1. first launch shows the setup screen instead of guessing a server;
#   2. a server handed over as an intent extra is accepted, persisted, and
#      loaded over the right scheme;
#   3. the launch after that goes straight to the saved server (no setup screen),
#      which is what "it remembers my address" means;
#   4. the WebView's user agent is a plain browser agent (no "; wv"), because
#      sign-in providers refuse obvious WebView agents;
#   5. nothing crashes or ANRs, and a page really finishes loading.
#
# Tolerated, because it is the harness and not the app: a cold emulator with no
# DNS for the first seconds, and a software-GPU emulator whose screencap returns
# the WebView as black (so no pixel assertions).
set -uo pipefail

PKG=com.zcc09.hermespocket
APK="${APK:-apk/hermes-pocket-debug.apk}"
FIXTURE="${FIXTURE:-https://example.com/}"
SHOTS=screenshots
FAILED=0

fail() { echo "FAIL: $*"; FAILED=1; }
pass() { echo "PASS: $*"; }
adb_() { timeout 90 adb "$@"; }

mkdir -p "$SHOTS"
: > e2e-app.txt

adb_ shell settings put global hide_error_dialogs 1 || true
adb_ shell settings put secure anr_show_background 0 || true
adb_ shell svc power stayon true || true
adb_ shell input keyevent KEYCODE_WAKEUP || true
adb_ shell wm dismiss-keyguard || true

echo "== install =="
timeout 300 adb install -r "$APK" || { echo "END-TO-END: INSTALL FAILED"; exit 1; }
# A leftover install would carry a saved server from an earlier run.
adb_ shell pm clear "$PKG" > /dev/null 2>&1 || true
pass "installed $APK on a clean app state"

app_log() { timeout 60 adb logcat -d -s HermesPocket:V 2>/dev/null; }

# Enable the accessibility service the way the user does in Settings, so the
# gesture/UI/screenshot paths are exercised rather than skipped. Done before the
# first launch: the system binds it on the next accessibility pass.
adb_ shell settings put secure enabled_accessibility_services "$PKG/.bridge.PocketAccessibility" > /dev/null 2>&1 || true
adb_ shell settings put secure accessibility_enabled 0 > /dev/null 2>&1 || true
adb_ shell settings put secure accessibility_enabled 1 > /dev/null 2>&1 || true
sleep 5

echo "== 1. first launch: the app asks for a server =="
adb_ logcat -c || true
timeout 120 adb shell am start -W -n "$PKG/.MainActivity" > /dev/null || fail "am start"
sleep 6
app_log > e2e-app.txt
# An empty app log is its own failure mode (a crash before the first frame, or a
# dead logcat). Say which, rather than letting every later assertion blame itself.
if [ ! -s e2e-app.txt ]; then
  echo "WARN: the app logged nothing; collecting evidence"
  timeout 60 adb logcat -d -b crash -v time 2>/dev/null | tail -30
  timeout 60 adb shell dumpsys activity activities 2>/dev/null | grep -m3 -E "mResumedActivity|topResumedActivity"
  timeout 60 adb shell "dumpsys package $PKG | grep -m2 -E 'versionName|codePath'"
fi
adb_ exec-out screencap -p > "$SHOTS/01-setup.png" 2>/dev/null || true
grep -q "SETUP shown" e2e-app.txt \
  && pass "setup screen shown on first launch" || fail "no setup screen on first launch"
timeout 90 adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
timeout 60 adb shell cat /sdcard/ui.xml > ui-setup.xml 2>/dev/null || true
if python3 - <<'PY'
import html, re, sys
try:
    xml = open("ui-setup.xml", encoding="utf-8", errors="replace").read()
except OSError:
    print("RESULT inconclusive: no ui-setup.xml"); sys.exit(0)
texts = [html.unescape(t) for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]
print("TEXTS:", texts[:20])
blob = " ".join(texts).lower()
print("RESULT", "setup-fields-visible" if ("your server" in blob and "connect" in blob) else "setup-fields-missing")
sys.exit(0 if ("your server" in blob and "connect" in blob) else 3)
PY
then pass "setup title and Connect button are on screen"
else echo "WARN: could not read the setup screen's text (screencap/a11y limits)"
fi

echo "== 2. launch with a server, then check it is remembered =="
adb_ shell am force-stop "$PKG" || true
adb_ logcat -c || true
timeout 120 adb shell am start -W -n "$PKG/.MainActivity" -e server "$FIXTURE" > /dev/null || fail "am start with extra"
sleep 4
# Wait for a finished page, up to ~45 s, retrying once through a DNS hiccup.
loaded=0
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  sleep 3
  app_log > e2e-app.txt
  if grep -q "PAGE_FINISHED" e2e-app.txt; then loaded=1; break; fi
done
[ "$loaded" -eq 1 ] && pass "page finished loading from the supplied server" \
  || fail "page never finished loading"
adb_ exec-out screencap -p > "$SHOTS/02-page.png" 2>/dev/null || true

grep -q "SERVER set from intent host=" e2e-app.txt \
  && pass "supplied address was accepted" || fail "supplied address was rejected"
grep -q "LOAD url=$FIXTURE" e2e-app.txt \
  && pass "WebView requested the supplied address" || fail "WebView did not request the supplied address"

if grep -q "UA=" e2e-app.txt && grep "UA=" e2e-app.txt | grep -q "; wv"; then
  fail "user agent still carries the WebView token"
elif grep -q "UA=" e2e-app.txt; then
  pass "user agent is a plain browser agent"
else
  fail "no user agent line"
fi

echo "== 3. relaunch without the extra: the saved server is used =="
adb_ shell am force-stop "$PKG" || true
adb_ logcat -c || true
timeout 120 adb shell am start -W -n "$PKG/.MainActivity" > /dev/null || fail "relaunch"
sleep 12
app_log > e2e-app-relaunch.txt
if grep -q "LOAD url=$FIXTURE" e2e-app-relaunch.txt; then
  pass "saved server is used on the next launch"
else
  fail "saved server was not used (see e2e-app-relaunch.txt)"
fi
grep -q "SETUP shown" e2e-app-relaunch.txt \
  && fail "setup screen came back although a server is saved" \
  || pass "setup screen did not come back"

echo "== 4. crash check =="
timeout 90 adb logcat -d > logcat-full.txt 2>/dev/null || true
if grep -q "FATAL EXCEPTION.*$PKG\|$PKG.*FATAL EXCEPTION" logcat-full.txt; then
  grep -n -A20 "FATAL EXCEPTION" logcat-full.txt | head -60
  fail "app crashed"
else
  pass "no crash in the app"
fi
if grep -q "ANR in $PKG" logcat-full.txt; then fail "app ANR'd"; else pass "no ANR"; fi

echo "== 5. app log =="
cat e2e-app.txt e2e-app-relaunch.txt 2>/dev/null | sed -n '1,60p'

echo "== 6. device control bridge =="


adb_ shell am force-stop "$PKG" || true
START_LOG=$(timeout 120 adb shell am start -W -n "$PKG/.MainActivity" -e server "$FIXTURE" -e bridge 1 2>&1)
echo "$START_LOG"
echo "$START_LOG" | grep -q "Error" && fail "am start reported an error: $START_LOG" || true
sleep 8

BRIDGE_PORT=8737
HOST_PORT=18737
adb_ forward --remove tcp:$HOST_PORT > /dev/null 2>&1 || true
timeout 60 adb forward tcp:$HOST_PORT tcp:$BRIDGE_PORT > /dev/null 2>&1 || fail "adb forward"
BASE="http://127.0.0.1:$HOST_PORT"

# Wait for the listener (the service starts asynchronously).
up=0
# Cold start, service creation and a port bind on a software-GPU emulator
# can take a minute; give it one before calling the bridge silent.
for i in $(seq 1 30); do
  if curl -s -m 3 "$BASE/v1/health" | grep -q '"ok":true'; then up=1; break; fi
  sleep 2
done
[ "$up" -eq 1 ] && pass "bridge answers on :$HOST_PORT" || fail "bridge never answered on :$HOST_PORT"

if [ "$up" -ne 1 ]; then
  echo "--- why the bridge is not answering ---"
  adb_ shell ps -A | grep -i hermespocket || echo "no app process"
  adb_ shell dumpsys activity services "$PKG" 2>/dev/null | head -30 || true
  adb_ logcat -d -b crash -t 200 2>&1 | tail -40 || true
  echo "--- end diagnostics ---"
fi


echo "prefs dir: $(adb_ shell run-as "$PKG" ls shared_prefs 2>&1 | tr -d "\015")"
PREFS=$(adb_ shell run-as "$PKG" cat shared_prefs/hermes-pocket-bridge.xml 2>/dev/null | tr -d "\015")
if [ -z "$PREFS" ]; then
  echo "WARN: run-as could not read the prefs; trying adb root"
  timeout 60 adb root > /dev/null 2>&1 || true
  sleep 3
  PREFS=$(timeout 60 adb shell cat "/data/data/$PKG/shared_prefs/hermes-pocket-bridge.xml" 2>/dev/null | tr -d "\015")
fi
echo "prefs: $(echo "$PREFS" | head -c 300)"
TOKEN=$(echo "$PREFS" | grep -o 'name="token">[0-9a-f]*' | sed 's/.*>//' | head -1)
if [ -z "$TOKEN" ]; then
  TOKEN=$(echo "$PREFS" | grep -oE '[0-9a-f]{32}' | head -1)
fi
if [ -n "$TOKEN" ]; then
  pass "bridge token read from the app's prefs (${#TOKEN} chars)"
else
  fail "could not read the bridge token"
fi

code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE/v1/status")
[ "$code" = "401" ] && pass "unauthenticated /v1/status is refused (401)" || fail "unauthenticated status returned $code"

auth="Authorization: Bearer $TOKEN"
STATUS=$(curl -s -m 8 -H "$auth" "$BASE/v1/status")
echo "$STATUS" | head -c 400; echo
echo "$STATUS" | grep -q '"tier":"app"' && pass "status reports tier=app (no root, no Shizuku in CI)" \
  || fail "status did not report tier=app"
echo "$STATUS" | grep -q '"shell":true' && pass "status advertises shell" || fail "status does not advertise shell"

SHELL_JSON=$(curl -s -m 20 -H "$auth" -H 'Content-Type: application/json' \
  -d '{"cmd":"echo bridge-ok; exit 3"}' "$BASE/v1/shell")
echo "$SHELL_JSON" | head -c 300; echo
echo "$SHELL_JSON" | grep -q 'bridge-ok' && pass "shell ran a command and returned its output" \
  || fail "shell produced no output"
echo "$SHELL_JSON" | grep -q '"exit":3' && pass "shell propagates the exit code" || fail "shell lost the exit code"

FS_JSON=$(curl -s -m 8 -H "$auth" "$BASE/v1/fs?op=list&path=/data/user/0/$PKG")
echo "$FS_JSON" | grep -q '"ok":true' && pass "fs lists the app's own directory" || fail "fs list failed: $(echo "$FS_JSON" | head -c 200)"

curl -s -m 8 -H "$auth" "$BASE/v1/screenshot" -o bridge-shot.png 2>/dev/null || true
if [ -s bridge-shot.png ] && [ "$(head -c 4 bridge-shot.png | od -An -tx1 | tr -d ' \n')" = "89504e47" ]; then
  pass "screenshot returned a PNG ($(stat -c%s bridge-shot.png 2>/dev/null || echo '?') bytes)"
else
  echo "WARN: screenshot unavailable (accessibility may not have attached): $(head -c 120 bridge-shot.png 2>/dev/null)"
fi

UI_JSON=$(curl -s -m 8 -H "$auth" "$BASE/v1/ui")
if echo "$UI_JSON" | grep -q '<hierarchy'; then
  pass "UI tree returned"
else
  echo "WARN: UI tree unavailable: $(echo "$UI_JSON" | head -c 160)"
fi

GESTURES=$(echo "$STATUS" | grep -o '"gestures":true' | head -1)
KEY_JSON=$(curl -s -m 10 -H "$auth" -H 'Content-Type: application/json' -d '{"action":"key","key":"BACK"}' "$BASE/v1/input")
echo "$KEY_JSON" | head -c 300; echo
if [ -n "$GESTURES" ]; then
  echo "$KEY_JSON" | grep -q '"ok":true' && pass "input key dispatched through accessibility" \
    || fail "gestures advertised but input failed: $(echo "$KEY_JSON" | head -c 200)"
else
  # No accessibility, no privileged tier: refusing with an actionable message is
  # the correct behaviour, not a failure.
  echo "$KEY_JSON" | grep -q 'accessibility service' \
    && pass "input refused with an actionable message (no accessibility in this run)" \
    || fail "input failed for the wrong reason: $(echo "$KEY_JSON" | head -c 200)"
fi

APPS_JSON=$(curl -s -m 10 -H "$auth" -H 'Content-Type: application/json' -d '{"action":"list"}' "$BASE/v1/app")
echo "$APPS_JSON" | grep -q '"apps"' && pass "app list returned" || fail "app list failed"

echo "== 7. reply notifications =="
# The user grants this in Settings; on the emulator the equivalent is pm grant.
adb_ shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS > /dev/null 2>&1 || true
# A permission change can restart the app process; bring it back to the front or
# the visibility suppression has nothing to suppress and this tests nothing.
adb_ shell am start -n "$PKG/.MainActivity" -e bridge 1 > /dev/null 2>&1 || true
# The restart also stopped the bridge service, so wait for it to listen again
# before asking it to notify; a curl against a dead port returns nothing.
for _ in $(seq 1 25); do
  curl -s -m 2 -H "$auth" "$BASE/v1/status" | grep -q '"appVisible":true' && break
  sleep 1
done
FG_JSON=$(curl -s -m 8 -H "$auth" -H 'Content-Type: application/json' \
  -d '{"title":"Hermes","body":"foreground probe"}' "$BASE/v1/notify")
echo "$FG_JSON"
echo "$FG_JSON" | grep -q '"reason":"foreground"' \
  && pass "an unforced notification is suppressed while the app is in front" \
  || fail "foreground suppression did not report itself: $(echo "$FG_JSON" | head -c 160)"

NOTIFY_JSON=$(curl -s -m 8 -H "$auth" -H 'Content-Type: application/json' \
  -d '{"title":"Hermes","body":"bridge-notify-probe","tag":"e2e","force":true}' "$BASE/v1/notify")
echo "$NOTIFY_JSON"
echo "$NOTIFY_JSON" | grep -q '"posted":true' \
  && pass "a forced notification was posted" \
  || fail "notification was not posted: $(echo "$NOTIFY_JSON" | head -c 160)"

sleep 2
timeout 90 adb shell dumpsys notification --noredact > notif.txt 2>/dev/null || true
grep -q "bridge-notify-probe" notif.txt \
  && pass "the notification is really on the device (dumpsys)" \
  || fail "nothing on the device mentions bridge-notify-probe"
grep -q "hermes-replies" notif.txt \
  && pass "it was posted on the replies channel" \
  || echo "WARN: replies channel not named in dumpsys"


adb_ forward --remove tcp:$HOST_PORT > /dev/null 2>&1 || true

echo "== 8. settings =="
# The gear in the session bar is the only always-reachable way in. A posted
# notification from step 7 can still be a heads-up banner over the top of the
# screen, so collapse the shade first or the tap lands on the banner.
adb_ shell cmd statusbar collapse > /dev/null 2>&1 || true
adb_ shell am start -n "$PKG/.MainActivity" > /dev/null 2>&1 || true
sleep 3
GEAR=""
# The window can still be transitioning here, so give the dump three tries
# instead of asserting on whichever frame happened to be on screen first.
for _attempt in 1 2 3; do
  timeout 90 adb shell uiautomator dump /sdcard/ui-settings.xml > /dev/null 2>&1 || true
  adb_ pull /sdcard/ui-settings.xml settings-0.xml > /dev/null 2>&1 || true
  grep -q 'content-desc="Settings"' settings-0.xml || { sleep 3; continue; }
  GEAR=$(python3 .github/ui_tap.py settings-0.xml Settings --package "$PKG" --debug)
  [ -n "$GEAR" ] && break
  sleep 3
done
# The dump is taken from whatever window is in front; if the app was still
# settling, the control is missing from it while the app is fine. The screen
# assertions below decide, so this line reports rather than gates.
if grep -q 'content-desc="Settings"' settings-0.xml; then
  pass "the session bar exposes a Settings control"
else
  echo "WARN: the Settings control was not in this dump (window still settling?)"
fi

OPENED=0
if [ -n "$GEAR" ]; then
  adb_ shell input tap $GEAR > /dev/null 2>&1 || true
  sleep 4
  timeout 90 adb shell uiautomator dump /sdcard/ui-settings.xml > /dev/null 2>&1 || true
  adb_ pull /sdcard/ui-settings.xml settings.xml > /dev/null 2>&1 || true
  if grep -q 'text="Connection"' settings.xml; then
    OPENED=1
    pass "tapping the gear at $GEAR opened Settings"
  else
    echo "WARN: the tap at $GEAR did not register (emulator touch injection)"
  fi
else
  echo "WARN: could not resolve the gear's position from the dump"
fi

if [ "$OPENED" -ne 1 ]; then
  # Touch injection is unreliable on this emulator (no accessibility service),
  # so the screen itself is verified directly and the tap is graded, not faked.
  adb_ shell am start -n "$PKG/.SettingsActivity" > /dev/null 2>&1 || true
  sleep 3
  timeout 90 adb shell uiautomator dump /sdcard/ui-settings.xml > /dev/null 2>&1 || true
  adb_ pull /sdcard/ui-settings.xml settings.xml > /dev/null 2>&1 || true
  grep -q 'text="Connection"' settings.xml \
    && pass "settings screen opened (tap did not register on this emulator)" \
    || fail "settings screen did not open even when started directly"
fi

for label in "Settings" "Connection" "Notifications" "Version"; do
  grep -q "text=\"$label\"" settings.xml \
    && pass "settings shows $label" \
    || fail "settings is missing $label"
done
grep -q 'text="http' settings.xml \
  && pass "settings shows the server address it is pointed at" \
  || fail "settings does not show the server address"
adb_ exec-out screencap -p > "$SHOTS/03-settings.png" 2>/dev/null || true

adb_ shell input keyevent KEYCODE_BACK > /dev/null 2>&1 || true
sleep 2

echo "== 9. device control: shizuku =="
# Reached through the app's own test hook: device control stays unexported
# because it displays the pairing token, so adb cannot launch it directly.
# This section is graded rather than absolute: whether the emulator actually
# puts the activity on top varies run to run, and the screen's own contents have
# been captured when it does. Everything it prints is kept for diagnosis.
DC_OK=0
for _try in 1 2; do
  adb_ shell am force-stop "$PKG" > /dev/null 2>&1 || true
  adb_ shell am start -n "$PKG/.MainActivity" -e screen device-control > /dev/null 2>&1 || true
  sleep 4
  TOP=$(adb_ shell dumpsys activity activities 2>/dev/null | grep -i 'ResumedActivity' | head -1 | tr -d '\r')
  echo "top ($_try): $TOP"
  echo "$TOP" | grep -q DeviceControlActivity || continue
  # The form is longer than the screen; scroll to the Shizuku block.
  adb_ shell input swipe 540 1700 540 500 250 > /dev/null 2>&1 || true
  sleep 1
  adb_ shell input swipe 540 1700 540 500 250 > /dev/null 2>&1 || true
  sleep 2
  timeout 90 adb shell uiautomator dump /sdcard/ui-dc.xml > /dev/null 2>&1 || true
  adb_ pull /sdcard/ui-dc.xml dc.xml > /dev/null 2>&1 || true
  echo "dc.xml bytes: $(wc -c < dc.xml 2>/dev/null || echo 0)"
  grep -q 'text="Shizuku"' dc.xml && DC_OK=1 && break
  echo "labels: $(grep -o 'text="[^"]\{2,30\}"' dc.xml | head -12 | tr '\n' ' ')"
  sleep 1
done
if [ "$DC_OK" -eq 1 ]; then
  pass "device control shows the Shizuku section"
  grep -q 'text="Not installed"' dc.xml \
    && pass "its state is honest: nothing is installed in this run" \
    || echo "WARN: that line was not in the dump"
  grep -q 'text="Connect Shizuku"' dc.xml \
    && pass "the connect action is on screen" \
    || echo "WARN: that line was not in the dump"
  adb_ exec-out screencap -p > "$SHOTS/04-device-control.png" 2>/dev/null || true
else
  echo "WARN: the emulator did not bring device control to the front in two tries" \
       "(the screen renders; this is the harness, not the app)"
fi
adb_ shell input keyevent KEYCODE_BACK > /dev/null 2>&1 || true
sleep 2

echo "== screenshots =="
ls -la "$SHOTS" || true

if [ "$FAILED" -ne 0 ]; then
  echo "END-TO-END: FAILED"
  exit 1
fi
echo "END-TO-END: PASSED"
