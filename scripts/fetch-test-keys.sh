#!/usr/bin/env bash
# Fetches the public AOSP platform test key (build/make/target/product/security).
# Stock emulator images are signed with it, so an app signed with it is a platform app there.
# Never use these keys on a real device you care about.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)/keys"
mkdir -p "$DIR"
for f in platform.pk8 platform.x509.pem; do
  [ -s "$DIR/$f" ] && continue
  curl -sfL "https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/$f?format=TEXT" | base64 -d > "$DIR/$f"
done
