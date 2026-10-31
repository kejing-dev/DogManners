#!/usr/bin/env bash
# Dog Manners developer helper (macOS, kj-mbp). Every device command is pinned to emulator-5556 (AVD PICO_6_1_kj-mbp_2)
# and refuses to run when that serial is another AVD. emulator-5554 belongs to another app: never touch it.
#
#   tools/dev.sh build | test | install | launch | stop | relaunch
#   tools/dev.sh cmd 'NAME ARGS'      debug broadcast (app/src/debug/.../DebugCommands.kt)
#   tools/dev.sh log [REGEX] [LINES]  logcat, tag DogManners
#   tools/dev.sh crash                last crash lines
#   tools/dev.sh shot NAME [DIR]      pico-cli screenshot -> DIR/NAME.png (default .scratch/shots)
DEV=${DEV:-emulator-5556}
AVD=${AVD:-PICO_6_1_kj-mbp_2}
PKG=io.embodit.dogmanners
TAG=DogManners
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.pico/primer-cli/jdk/jdk-21.0.12.1+1/Contents/Home}"
mkdir -p "$ROOT/.scratch"

check() {
  avd="$(adb -s $DEV emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  if [ "$avd" != "$AVD" ]; then echo "WRONG AVD on $DEV: '$avd' (expected $AVD)" >&2; exit 2; fi
}

case "${1:-}" in
  build)
    cd "$ROOT" && ./gradlew assembleDebug --console=plain > .scratch/build.log 2>&1
    rc=$?; echo "exit=$rc"; grep -E '^e: |error:|BUILD|FAILED' .scratch/build.log | head -40; exit $rc ;;
  test)
    cd "$ROOT" && ./gradlew testDebugUnitTest --console=plain > .scratch/test.log 2>&1; rc=$?; echo "exit=$rc"
    grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml; exit $rc ;;
  install)
    check; adb -s $DEV install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" 2>&1 | tail -1 ;;
  launch)
    check; adb -s $DEV logcat -c; adb -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo launched ;;
  stop)
    check; adb -s $DEV shell am force-stop $PKG; echo stopped ;;
  relaunch)
    check; adb -s $DEV shell am force-stop $PKG; sleep 1; adb -s $DEV logcat -c; adb -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo relaunched ;;
  cmd)
    check; adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '${2:?cmd NAME}'" | tail -1 | tr -d '\r'; sleep "${CMD_WAIT:-0.5}"
    adb -s $DEV logcat -d -v brief -s $TAG:V 2>/dev/null | grep "CMD " | tail -1 ;;
  log)
    check; adb -s $DEV logcat -d -v time -s $TAG:V 2>/dev/null | grep -E -- "${2:-.}" | tail -n "${3:-200}" | cut -c1-700 ;;
  crash)
    check; adb -s $DEV logcat -d -v time 2>/dev/null | grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $PKG" | tail -n 40 | cut -c1-400 ;;
  shot)
    check; dir="${3:-$ROOT/.scratch/shots}"; mkdir -p "$dir"
    pico-cli capture screenshot --device $DEV --out "$dir/${2:-shot}.png" >/dev/null 2>&1 || adb -s $DEV exec-out screencap -p > "$dir/${2:-shot}.png"
    echo "$dir/${2:-shot}.png" ;;
  *) echo "usage: dev.sh build|test|install|launch|stop|relaunch|cmd|log|crash|shot" ;;
esac
