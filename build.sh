#!/usr/bin/env bash
#
# NEON FORGE — build script (no Gradle / no Android Studio required).
# Produces a signed, installable APK at neonforge/build/NEON-FORGE.apk
#
# Toolchain (aapt2 + android.jar + ecj + d8 + apksigner) is expected in one of:
#   $NEONFORGE_TOOLS            (env var)
#   ./tools                     (project-local)
#   /home/user/toolchain/bin    (default workspace location)
#
set -e
cd "$(dirname "$0")"

TOOLS="${NEONFORGE_TOOLS:-}"
if [ -z "$TOOLS" ]; then
  if [ -x "$PWD/tools/aapt2" ]; then TOOLS="$PWD/tools"; else TOOLS="/home/user/toolchain/bin"; fi
fi

JAVA="${JAVA:-}"
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  if [ -x /home/user/toolchain/venv/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java ]; then
    JAVA=/home/user/toolchain/venv/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java
  else
    JAVA="$(command -v java || true)"
  fi
fi

echo "Tools: $TOOLS"
echo "Java : $JAVA"

python3 build.py --java "$JAVA" --tools "$TOOLS" \
  --src neonforge/src \
  --res neonforge/res \
  --manifest neonforge/AndroidManifest.xml \
  --out neonforge/build/NEON-FORGE.apk \
  --keystore neonforge/release.keystore \
  --ks-alias neonforge \
  --ks-pass neonforge \
  --ks-dname "CN=NEON FORGE, O=NeonForge, C=BR" \
  --sign \
  --min-sdk 26 \
  --target-sdk 34 \
  --version-code 1 \
  --version-name 1.0 \
  "$@"
