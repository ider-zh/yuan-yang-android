#!/usr/bin/env bash
# Pure JVM only: no Android SDK or model needed.
set -euo pipefail
APP=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf -- "$TMP"' EXIT
if command -v javac >/dev/null; then
    JAVAC=(javac)
else
    JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${JAVAC[@]}" -source 8 -target 8 -Xlint:all,-options -encoding UTF-8 -d "$TMP" \
    "$APP/app/src/main/java/com/yuanyang/camera/PoseAlignment.java" "$APP/tests/PoseAlignmentTest.java"
java -cp "$TMP" com.yuanyang.camera.PoseAlignmentTest
