#!/usr/bin/env bash
# Füstteszt emulátoron: telepítés, a szimulációs végponttól végpontig tartó teszt futtatása, képernyőképek letöltése.
set -u
PKG=hu.obddiag.app.debug
OUT=smoke-output
mkdir -p "$OUT/screens"
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r -e class hu.obddiag.app.SimulationSmokeTest \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"
adb logcat -d > "$OUT/logcat.txt"
for f in $(adb shell run-as $PKG ls files/screens 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as $PKG cat "files/screens/$f" > "$OUT/screens/$f"
done
ls -la "$OUT/screens"
# A képernyőképek közzététele egy külön kiadásban (egyszerű letölthetőség)
if [ -n "${GH_TOKEN:-}" ]; then
  (cd "$OUT" && zip -qr ../ui-screens.zip .)
  gh release delete ui-screens --yes --cleanup-tag >/dev/null 2>&1 || true
  gh release create ui-screens ui-screens.zip --title "UI füstteszt képernyőképei" --notes "Automatikus emulátoros füstteszt (szimulációs mód)." --prerelease --target "${GITHUB_SHA}" || true
fi
if grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" "$OUT/instrument.txt"; then
  echo "A füstteszt SIKERTELEN"; exit 1
fi
grep -q "OK (1 test)" "$OUT/instrument.txt" || { echo "A füstteszt nem futott le"; exit 1; }
echo "A füstteszt SIKERES"
