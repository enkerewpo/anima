#!/usr/bin/env bash
# Stage 0 in one go: boot the emulator (silent), build and install Anima, open the shell, forward the API.
# First run: creates an AVD "anima" from the stock AOSP API 35 arm64 image.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
AVD="${ANIMA_AVD:-anima}"
IMG="system-images;android-35;default;arm64-v8a"   # use x86_64 on Intel/Linux hosts
export JAVA_HOME="${ANIMA_JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo "$JAVA_HOME")}"
export PATH="$JAVA_HOME/bin:$PATH"

if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" "$IMG" >/dev/null
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMG" -d pixel_7 >/dev/null
fi
if ! "$ADB" get-state >/dev/null 2>&1; then
  nohup "$SDK/emulator/emulator" -avd "$AVD" -no-audio -no-boot-anim >/dev/null 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
fi

"$ROOT/scripts/build-device.sh" >/dev/null
"$ADB" install -r "$ROOT/build/device/anima.apk" >/dev/null
"$ADB" shell am start -n dev.anima/.MainActivity >/dev/null
"$ADB" forward tcp:7878 tcp:7878 >/dev/null
echo "Anima is up. API: http://127.0.0.1:7878  Agent: python3 host/agent.py"
