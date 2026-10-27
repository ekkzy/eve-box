#!/usr/bin/env bash
set -euo pipefail
serial="${1:?Explicit owned emulator serial required}"
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || exit 2
emulator_flag="$(adb -s "$serial" shell getprop ro.kernel.qemu)" || exit 2
[[ "${emulator_flag//$'\r'/}" == 1 ]] || exit 2
package='io.github.ekkzy.softlumi.debug'
test_package="$package.test"
permission='android.permission.READ_TV_LISTINGS'
mkdir -p ci-evidence

# This job owns a fresh test installation. Refuse existing debug data instead of
# changing an unknown permission/flag snapshot on a reused emulator.
for target in "$package" "$test_package"; do
  if ! installed="$(adb -s "$serial" shell pm list packages "$target")"; then
    echo "Cannot inspect initial package state: $target" >&2; exit 2;
  fi
  [[ -z "${installed//$'\r'/}" ]] || {
    echo 'Permission job requires a fresh emulator with no softlumi debug package'; exit 2;
  }
done
permission_inventory="$(adb -s "$serial" shell pm list permissions)"
grep -Fq "$permission" <<< "$permission_inventory"
owned_packages=()
cleanup() {
  local exit_status=$? cleanup_failed=0 index target remaining
  trap - EXIT
  for ((index=${#owned_packages[@]}-1;index>=0;index--)); do
    target="${owned_packages[index]}"
    printf 'package=%s\n' "$target" >> ci-evidence/permission-cleanup.txt
    if ! adb -s "$serial" uninstall "$target" >> ci-evidence/permission-cleanup.txt 2>&1; then
      echo "Cannot remove owned test installation: $target" >&2; cleanup_failed=1
    fi
    if ! remaining="$(adb -s "$serial" shell pm list packages "$target" 2>> ci-evidence/permission-cleanup.txt)"; then
      echo "Cannot verify cleanup: $target" >&2; cleanup_failed=1
    elif [[ -n "${remaining//$'\r'/}" ]]; then
      echo "Owned test installation remains after cleanup: $target" >&2; cleanup_failed=1
    fi
  done
  if ((cleanup_failed!=0 && exit_status==0)); then exit_status=1; fi
  exit "$exit_status"
}
trap cleanup EXIT
adb -s "$serial" shell settings put system font_scale 1.0
adb -s "$serial" install app/build/outputs/apk/debug/app-debug.apk
owned_packages+=("$package")
adb -s "$serial" install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
owned_packages+=("$test_package")
adb -s "$serial" shell dumpsys package "$package" > ci-evidence/permission-before.txt
adb -s "$serial" shell pm revoke "$package" "$permission"
adb -s "$serial" shell pm clear-permission-flags "$package" "$permission" user-set user-fixed
adb -s "$serial" shell am instrument -w -r \
  -e class io.github.ekkzy.softlumi.WatchNextPermissionTest \
  "$test_package/androidx.test.runner.AndroidJUnitRunner" | tee ci-evidence/permission-instrumentation.txt
if ! grep -qE '^OK \([1-9][0-9]* tests?\)[[:space:]]*$' ci-evidence/permission-instrumentation.txt; then
  echo 'Permission instrumentation has no positive terminal OK summary' >&2; exit 1
fi
if grep -qE 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' ci-evidence/permission-instrumentation.txt; then
  echo 'Permission instrumentation reported a failure' >&2; exit 1
fi
adb -s "$serial" pull "/sdcard/Android/data/$package/files/watch-permission-test" ci-evidence/screenshots
# Successful EXIT cleanup verifies the initial package state: neither debug APK installed.
