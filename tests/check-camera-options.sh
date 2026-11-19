#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT
if command -v javac >/dev/null; then compiler=(javac); else compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -d "$temp" \
  "$root/app/src/main/java/com/yuanyang/camera/CameraOptions.java" "$root/tests/CameraOptionsTest.java"
java -cp "$temp" com.yuanyang.camera.CameraOptionsTest
