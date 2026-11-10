#!/usr/bin/env bash
# The core loop on a real Android, end to end. Unit tests stop at the engine; this covers what they
# can't: the service, app detection through UsageStats and the overlay on screen. It builds the debug
# APK, installs it on an emulator of its own, blocks three apps, opens them and looks at the screen.
#
#   scripts/smoke.sh              build, test, shut the emulator down
#   scripts/smoke.sh --no-build   test the APK that's already built
#   scripts/smoke.sh --keep       leave the emulator running afterwards
#
# It takes about four minutes, one of them waiting out the grace between two opens (I52).
#
# It only ever talks to the emulator it started: AVD SMOKE_AVD (xrnrule_smoke, made on first use) on
# port SMOKE_PORT (5640). It refuses that port or AVD if they're already in use, and it never touches
# a phone or another emulator, even with both plugged in. The AVD is wiped of xrnrule on every run.
#
# Needs the SDK's emulator, the API 35 google_apis x86_64 image and KVM. A failure leaves a screenshot,
# the window list and the log in build/smoke/.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

build=1 keep=0
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
    --keep) keep=1 ;;
    *) echo "usage: scripts/smoke.sh [--no-build] [--keep]" >&2; exit 2 ;;
  esac
done

die() { echo "smoke: $*" >&2; exit 1; }

sdk="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}"
[ -n "$sdk" ] || die "no Android SDK: set ANDROID_HOME or sdk.dir in local.properties"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
avdmanager="$sdk/cmdline-tools/latest/bin/avdmanager"
image="system-images;android-35;google_apis;x86_64"

avd="${SMOKE_AVD:-xrnrule_smoke}"
port="${SMOKE_PORT:-5640}"
serial="emulator-$port"
[[ "$port" =~ ^[0-9]+$ ]] || die "SMOKE_PORT isn't a port"

pkg=com.xern.xrnrule
apk=app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
out=build/smoke

# The only way this script reaches a device: always this emulator by name.
a() { "$adb" -s "$serial" "$@"; }

# Building and an emulator together can fill memory on a laptop, and systemd-oomd then kills the
# terminal it all runs in, so don't start near the edge and give up before reaching it.
SWAP_START_MAX=75 SWAP_ABORT=85
swap_used() { awk '/^SwapTotal/{t=$2} /^SwapFree/{f=$2} END{print t ? int((t-f)*100/t) : 0}' /proc/meminfo; }

[ "$(swap_used)" -le "$SWAP_START_MAX" ] || die "swap is $(swap_used)% full; not starting above $SWAP_START_MAX%"
[ -w /dev/kvm ] || die "no KVM: /dev/kvm isn't writable"
"$adb" devices | grep -q "^$serial[[:space:]]*device" && die "$serial is already running and isn't ours; pick another SMOKE_PORT"
pgrep -f -- "-avd $avd( |\$)" >/dev/null && die "AVD $avd is already running; it isn't ours to touch"

emu_pid="" watch_pid="" started=0
cleanup() {
  local code=$?
  [ -n "$watch_pid" ] && kill "$watch_pid" 2>/dev/null
  if [ "$started" = 1 ] && [ "$keep" = 0 ]; then
    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 20); do kill -0 "$emu_pid" 2>/dev/null || break; sleep 1; done
    kill "$emu_pid" 2>/dev/null || true
  fi
  exit "$code"
}
trap cleanup EXIT

rm -rf "$out"
mkdir -p "$out"

if [ "$build" = 1 ]; then
  echo "== building"
  ./gradlew -q :app:assembleSideloadDebug
fi
[ -f "$apk" ] || die "no $apk: run without --no-build"

if ! "$emulator" -list-avds | grep -qx "$avd"; then
  echo "== making AVD $avd"
  [ -d "$sdk/system-images/android-35/google_apis/x86_64" ] || die "missing image: sdkmanager '$image'"
  echo no | "$avdmanager" -s create avd -n "$avd" -k "$image" >/dev/null
fi

echo "== starting $avd on $serial"
"$emulator" -avd "$avd" -port "$port" -memory 1536 -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect >"$out/emulator.log" 2>&1 &
emu_pid=$!
started=1

(
  while kill -0 "$emu_pid" 2>/dev/null; do
    if [ "$(swap_used)" -ge "$SWAP_ABORT" ]; then
      echo "smoke: swap reached $SWAP_ABORT%, stopping the emulator" >&2
      "$adb" -s "$serial" emu kill >/dev/null 2>&1 || kill "$emu_pid" 2>/dev/null
      exit
    fi
    sleep 5
  done
) &
watch_pid=$!

timeout 180 "$adb" -s "$serial" wait-for-device || die "the emulator never came up (see $out/emulator.log)"
for _ in $(seq 1 180); do
  [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  sleep 1
done
[ "$(a shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || die "the emulator never finished booting"
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  a shell settings put global "$scale" 0
done

# ---- three apps to block, none of them a browser, which has rules of its own -------------------

launcher() {
  a shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER "$1" 2>/dev/null | tail -1 | tr -d '\r'
}
apps=()
for candidate in com.google.android.deskclock com.android.deskclock com.google.android.calculator \
  com.android.calculator2 com.google.android.contacts com.android.contacts com.google.android.dialer \
  com.android.dialer com.google.android.apps.messaging com.android.messaging com.android.documentsui; do
  activity="$(launcher "$candidate")"
  [[ "$activity" == */* ]] && apps+=("$activity")
done
[ "${#apps[@]}" -ge 3 ] || die "found only ${#apps[@]} usable apps on the emulator"
habit_app="${apps[0]}" pause_app="${apps[1]}" free_app="${apps[2]}"
echo "== blocked ${habit_app%%/*}, paused ${pause_app%%/*}, free ${free_app%%/*}"

# ---- install and seed ---------------------------------------------------------------------------

echo "== installing"
a uninstall "$pkg" >/dev/null 2>&1 || true
a install -g "$apk" >/dev/null

pause_s=12 # long enough to read the screen during it, on a busy machine

# Only what has no default; a block saved this way loads like one saved by the app. If the format
# moves on, the app starts empty, no shield appears and the first check below fails.
cat >"$out/world.json" <<JSON
{
  "blocks": [
    {
      "id": "smoke-habit", "name": "Smoke habit",
      "targetPackages": ["${habit_app%%/*}"],
      "trigger": { "type": "CHECKLIST", "requiredAmount": -1, "unit": "ITEMS" }
    },
    {
      "id": "smoke-pause", "name": "Smoke pause", "mode": "ALLOWANCE",
      "targetPackages": ["${pause_app%%/*}"],
      "trigger": { "type": "TIMER", "requiredAmount": 0, "unit": "MS" },
      "opensPerDay": 1, "openWaitS": $pause_s
    }
  ],
  "checklist": {
    "smoke-habit": [ { "id": "smoke-item", "blockId": "smoke-habit", "text": "Stretch", "order": 0 } ]
  }
}
JSON
a shell "run-as $pkg mkdir -p files"
a exec-in run-as "$pkg" sh -c 'cat > files/world.json' <"$out/world.json"

a shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
a shell appops set "$pkg" GET_USAGE_STATS allow
a shell am start -n "$pkg/.app.ui.MainActivity" >/dev/null

# ---- looking at the screen ----------------------------------------------------------------------

# The whole-screen overlay window of ours, on screen. The corner clock is a small one.
shield_up() {
  a shell dumpsys window windows | awk -v pkg="$pkg" '
    /^  Window #/ { ours = index($0, " " pkg "}") > 0; full = 0 }
    ours && /ty=APPLICATION_OVERLAY/ && /fillxfill/ { full = 1 }
    ours && full && /isOnScreen=true/ { found = 1 }
    END { exit !found }'
}

screen_text() {
  a shell uiautomator dump /sdcard/smoke.xml >/dev/null 2>&1 || return 0
  a exec-out cat /sdcard/smoke.xml
}

failed() {
  echo "FAILED: $*" >&2
  a exec-out screencap -p >"$out/failed.png" 2>/dev/null || true
  a shell dumpsys window windows >"$out/windows.txt" 2>/dev/null || true
  a logcat -d -t 400 >"$out/logcat.txt" 2>/dev/null || true
  echo "smoke: evidence in $out/" >&2
  exit 1
}

ok() { echo "ok - $*"; }

# On a busy machine a cold boot can run System UI late, and its "isn't responding" dialog then sits
# over the screen being read. That one is the emulator's trouble and gets waved away; the same
# dialog about xrnrule is a failure.
clear_dialogs() {
  local focus button
  focus="$(a shell dumpsys window | grep -m1 'mCurrentFocus=' | tr -d '\r')"
  case "$focus" in
    *"Not Responding: $pkg"*) failed "xrnrule stopped responding" ;;
    *"Not Responding"*) ;;
    *) return 0 ;;
  esac
  button="$(screen_text | grep -o 'text="Wait"[^>]*' | grep -o 'bounds="\[[0-9]*,[0-9]*\]' | grep -o '[0-9]*,[0-9]*')" || return 0
  a shell input tap "${button%,*}" "${button#*,}"
  sleep 1
}

wait_shield() { # up|down seconds
  local want="$1" left="$2"
  while [ "$left" -gt 0 ]; do
    if shield_up; then [ "$want" = up ] && return 0; else [ "$want" = down ] && return 0; fi
    sleep 1
    left=$((left - 1))
  done
  return 1
}

stays() { # up|down seconds
  local want="$1" left="$2"
  while [ "$left" -gt 0 ]; do
    if shield_up; then [ "$want" = up ] || return 1; else [ "$want" = down ] || return 1; fi
    sleep 1
    left=$((left - 1))
  done
}

open_app() { clear_dialogs; a shell am start -n "$1" >/dev/null; }
home() { a shell input keyevent KEYCODE_HOME; }

expect_text() { # what, why
  local text
  for _ in 1 2 3 4; do
    clear_dialogs
    text="$(screen_text)"
    grep -qF "$1" <<<"$text" && return 0
    sleep 1
  done
  printf '%s\n' "$text" >"$out/screen.xml"
  failed "$2: no \"$1\" on screen"
}

# ---- the checks ---------------------------------------------------------------------------------

echo "== checking"
a shell am wait-for-broadcast-idle >/dev/null 2>&1 || true
sleep 8 # the service starts and takes in the seed
clear_dialogs

screen="$(screen_text)"
grep -qF "Smoke habit" <<<"$screen" || failed "the blocks aren't on the Blocks tab: the seed didn't load"
grep -qF " is off" <<<"$screen" && failed "the app says something is off with every permission given"
ok "the seeded blocks load, and nothing is said to be off"

open_app "$habit_app"
wait_shield up 30 || failed "a blocked app opened with no shield"
ok "a blocked app gets the block screen"
expect_text "finish your habits first" "the block screen doesn't ask for the habit"
expect_text "Stretch" "the block screen doesn't show the habit"
ok "the block screen asks for its habit"

home
wait_shield down 15 || failed "the shield stayed after going home"
ok "leaving takes the shield down"

open_app "$free_app"
stays down 6 || failed "an app no block covers got a shield"
ok "an app no block covers stays open"
home
wait_shield down 10 || failed "the shield came up at home"

open_app "$pause_app"
wait_shield up 30 || failed "no pause before a limited app"
expect_text "is opening" "the pause doesn't say it's opening"
expect_text "Opens in" "the pause doesn't say when it opens"
ok "a limited app pauses first (I53)"
wait_shield down $((pause_s + 15)) || failed "the pause never ended"
stays down 3 || failed "the app didn't stay open after its pause"
ok "the pause ends by itself and the app stays open"

home
wait_shield down 10 || failed "the shield came up at home"
echo "   (a minute's grace before the next open, I52)"
sleep 62

open_app "$pause_app"
wait_shield up 30 || failed "the day's only open was spent but the app opened again"
stays up 10 || failed "the shield went away with no opens left"
expect_text "used up" "the block screen doesn't say the opens are used up"
ok "with no opens left it stays blocked (I52)"

a shell "run-as $pkg ls files" | grep -q "world.unreadable" && failed "the app couldn't read the seed"
pid="$(a shell pidof "$pkg" | tr -d '\r')"
[ -n "$pid" ] || failed "the app's process is gone"
a logcat -d -b crash | grep -q "$pkg" && failed "the app crashed during the run (see the crash log)"
a logcat -d | grep -q "ANR in $pkg" && failed "the app stopped responding during the run"
ok "no crash during the run"

echo "== passed"
