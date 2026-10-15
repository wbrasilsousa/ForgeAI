#!/usr/bin/env bash
# ============================================================================
# One-shot build: native library + APK.
#
#   ./scripts/build_apk.sh            # debug APK
#   ./scripts/build_apk.sh release    # release APK (unsigned)
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MODE="${1:-debug}"

if [ ! -f "app/src/main/jniLibs/arm64-v8a/libllamaforge.so" ]; then
    echo "==> building native library first..."
    ./scripts/build_native.sh
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

echo "==> building $MODE APK with Gradle..."
if [ "$MODE" = "release" ]; then
    ./gradlew assembleRelease --no-daemon
    APK="app/build/outputs/apk/release/app-release-unsigned.apk"
else
    ./gradlew assembleDebug --no-daemon
    APK="app/build/outputs/apk/debug/app-debug.apk"
fi

echo "==> APK ready: $ROOT/$APK"
ls -la "$APK"

# Also drop a copy in the user's Downloads folder (same APK, easy to install
# from the file manager / installer).
if [ -d "$HOME/storage/downloads" ]; then
    cp "$APK" "$HOME/storage/downloads/ForgeAI.apk"
    echo "==> também disponível em: $HOME/storage/downloads/ForgeAI.apk"
    ls -la "$HOME/storage/downloads/ForgeAI.apk"
fi
