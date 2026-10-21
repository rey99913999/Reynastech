#!/bin/sh
set -eu

app_id='io.github.nastechresearch.nastech.debug'
service_class='io.github.nastechresearch.nastech.service.RikkaAccessibilityService'
test_class='io.github.nastechresearch.nastech.service.AccessibilityServiceConfigTest'

# Install both APKs before enabling the service so Android can resolve and bind it.
./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon --stacktrace

echo "Accessibility settings before instrumentation:"
echo "Enabled services: $(adb shell settings get secure enabled_accessibility_services)"
echo "Global accessibility flag: $(adb shell settings get secure accessibility_enabled)"
echo "--- Accessibility manager state before instrumentation ---"
adb shell dumpsys accessibility | grep -i -C 2 -E 'RikkaAccessibilityService|nastech|enabled services|binding services|bound services|isConnected|connected' || true
echo "--- Installed service declaration ---"
adb shell dumpsys package "${app_id}" | grep -i -C 2 -E 'RikkaAccessibilityService|android.accessibilityservice.AccessibilityService' || true

# Find the installed instrumentation runner for the debug application ID.
runner=$(adb shell pm list instrumentation \
  | tr -d '\r' \
  | grep -F "(target=${app_id})" \
  | grep -F "AndroidJUnitRunner" \
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
  echo "--- Accessibility manager state after instrumentation failure ---"
  adb shell dumpsys accessibility | grep -i -C 3 -E 'RikkaAccessibilityService|nastech|enabled services|binding services|bound services|crashed services' || true
  echo "--- Relevant Android service/application logs ---"
  adb logcat -d -t 5000 -v brief | grep -i -E 'RikkaAccessibilityService|AccessibilityManagerService|FATAL EXCEPTION|AndroidRuntime|nastech.debug' | tail -n 200 || true
  exit 1
fi
cat instrumentation-output.txt
if ! grep -Eq 'OK \([0-9]+ tests?\)' instrumentation-output.txt; then
  echo "Instrumentation test did not report success" >&2
  exit 1
fi
