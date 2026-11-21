#!/usr/bin/env bash
# Builds the Anima system app without Gradle: aapt2 + javac + d8 + apksigner.
# Signs with the AOSP platform test key, which the stock emulator images trust.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-35.0.0}"
JAR="$SDK/platforms/android-35/android.jar"
OUT="$ROOT/build/device"

"$ROOT/scripts/fetch-test-keys.sh"

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"
"$BT/aapt2" compile --dir "$ROOT/vendor/anima/Anima/res" -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$ROOT/vendor/anima/Anima/AndroidManifest.xml" \
    -o "$OUT/base.apk" "$OUT/res.zip" --java "$OUT/gen" --auto-add-overlay

javac -nowarn -Xlint:-options --release 8 -classpath "$JAR" -encoding UTF-8 \
    -d "$OUT/classes" $(find "$ROOT/vendor/anima/Anima/src" "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -qj "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --key "$ROOT/keys/platform.pk8" --cert "$ROOT/keys/platform.x509.pem" \
    --out "$OUT/anima.apk" "$OUT/aligned.apk"
echo "$OUT/anima.apk"
