#!/usr/bin/env bash
# Independent decode tests (Java desktop/ImageIO). Requires Android API 34 stubs for compilation only.
set -euo pipefail
APP=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
JAR=${ANDROID_PLATFORM_JAR:-${SDK:+$SDK/platforms/android-34/android.jar}}
JAR=${JAR:-$APP/.toolchain/android-34.jar}
if [[ ! -s "$JAR" ]]; then
    printf 'Set ANDROID_PLATFORM_JAR to an Android API 34 android.jar or configure ANDROID_SDK_ROOT.\n' >&2
    exit 1
fi
TMP=$(mktemp -d)
trap 'rm -rf -- "$TMP"' EXIT
SOURCE="$APP/app/src/main/java/com/yuanyang/camera"
if command -v javac >/dev/null; then
    JAVAC=(javac)
else
    JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${JAVAC[@]}" -source 8 -target 8 -Xlint:all,-options -encoding UTF-8 -cp "$JAR" -d "$TMP" \
    "$SOURCE"/{Timelapse,PhotoTools,ShareProvider}.java "$APP/tests/TimelapseMediaJvmTest.java"
java -cp "$JAR:$TMP" com.yuanyang.camera.TimelapseMediaJvmTest
