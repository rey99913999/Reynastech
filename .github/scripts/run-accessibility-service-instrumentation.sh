#!/bin/sh
set -eu

app_id='io.github.nastechresearch.nastech.debug'
service_class='io.github.nastechresearch.nastech.service.RikkaAccessibilityService'
test_class='io.github.nastechresearch.nastech.service.AccessibilityServiceConfigTest'

# Install both APKs before enabling the service so Android can resolve and bind it.
./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon --stacktrace

component="${app_id}/${service_class}"
enabled="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
case ":${enabled}:" in
  *":${component}:"*) ;;
  *)
    if [ "${enabled}" = "null" ] || [ -z "${enabled}" ]; then
      enabled="${component}"
    else
      enabled="${enabled}:${component}"
    fi
    ;;
esac
adb shell settings put secure enabled_accessibility_services "${enabled}"
adb shell settings put secure accessibility_enabled 1

# Find the installed instrumentation runner for the debug application ID.
runner=$(adb shell pm list instrumentation \
  | tr -d '\r' \
  | grep -F "(target=${app_id})" \
  | grep -F "androidx.test.runner.AndroidJUnitRunner" \
  | cut -d' ' -f1 \
  | cut -d':' -f2)
if [ -z "${runner}" ]; then
  echo "Android instrumentation runner not found for ${app_id}" >&2
  adb shell pm list instrumentation
  exit 1
fi

echo "Running ${test_class} with ${runner}"
if ! adb shell am instrument -w -r -e class "${test_class}" "${runner}" > instrumentation-output.txt 2>&1; then
  cat instrumentation-output.txt
  exit 1
fi
cat instrumentation-output.txt
if ! grep -Eq 'OK \([0-9]+ tests?\)' instrumentation-output.txt; then
  echo "Instrumentation test did not report success" >&2
  exit 1
fi
