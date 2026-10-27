#!/usr/bin/env bash
set -euo pipefail
serial="${1:?Explicit emulator serial required}"
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Emulator-only test script'; exit 2; }
emulator_flag="$(adb -s "$serial" shell getprop ro.kernel.qemu)" || exit 2
[[ "${emulator_flag//$'\r'/}" == 1 ]] || exit 2
# The suite rewrites launcher preferences. Only a fresh CI installation is owned.
for target in io.github.ekkzy.softlumi.debug io.github.ekkzy.softlumi.debug.test \
              io.github.ekkzy.softlumi.fixture.alpha io.github.ekkzy.softlumi.fixture.beta; do
  if ! installed="$(adb -s "$serial" shell pm list packages "$target")"; then
    echo "Cannot inspect initial package state: $target" >&2; exit 2;
  fi
  [[ -z "${installed//$'\r'/}" ]] || {
    echo 'Smoke job requires a fresh emulator with no softlumi debug or fixture packages'; exit 2;
  }
done
mkdir -p ci-evidence
adb -s "$serial" shell wm size 3840x2160
adb -s "$serial" shell wm density 640
adb -s "$serial" shell settings put system font_scale 1.0
adb -s "$serial" shell settings put global animator_duration_scale 1.0
adb -s "$serial" shell settings put global transition_animation_scale 1.0
adb -s "$serial" shell settings put global window_animation_scale 1.0
{
  adb -s "$serial" shell getprop ro.kernel.qemu
  adb -s "$serial" shell getprop ro.build.version.sdk
  adb -s "$serial" shell getprop ro.product.cpu.abi
  adb -s "$serial" shell wm size
  adb -s "$serial" shell wm density
  adb -s "$serial" shell settings get system font_scale
} > ci-evidence/environment.txt
# Instrumentation also asserts the measured Activity window is truly 3840x2160.
# wm output alone cannot establish a compositor's rendering size.
adb -s "$serial" install -r fixtures/build/outputs/apk/alpha/debug/fixtures-alpha-debug.apk
adb -s "$serial" install -r fixtures/build/outputs/apk/beta/debug/fixtures-beta-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell am instrument -w -r -e notClass io.github.ekkzy.softlumi.WatchNextPermissionTest io.github.ekkzy.softlumi.debug.test/androidx.test.runner.AndroidJUnitRunner | tee ci-evidence/instrumentation.txt
if ! grep -qE '^OK \([1-9][0-9]* tests?\)[[:space:]]*$' ci-evidence/instrumentation.txt; then
  echo 'Smoke instrumentation has no positive terminal OK summary' >&2; exit 1
fi
if grep -qE 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' ci-evidence/instrumentation.txt; then
  echo 'Smoke instrumentation reported a failure' >&2; exit 1
fi
adb -s "$serial" pull /sdcard/Android/data/io.github.ekkzy.softlumi.debug/files/screenshots ci-evidence/screenshots
