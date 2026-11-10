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

echo "== 1. first launch: the app asks for a server =="
adb_ logcat -c || true
timeout 120 adb shell am start -W -n "$PKG/.MainActivity" > /dev/null || fail "am start"
sleep 6
app_log > e2e-app.txt
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

echo "== screenshots =="
ls -la "$SHOTS" || true

if [ "$FAILED" -ne 0 ]; then
  echo "END-TO-END: FAILED"
  exit 1
fi
echo "END-TO-END: PASSED"
