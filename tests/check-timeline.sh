#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
t=$(mktemp -d)
trap 'rm -rf -- "$t"' EXIT
if command -v javac >/dev/null; then compiler=(javac); else compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -d "$t" app/src/main/java/com/yuanyang/camera/TimelineStore.java tests/TimelineStoreTest.java
java -cp "$t" com.yuanyang.camera.TimelineStoreTest "$t"
