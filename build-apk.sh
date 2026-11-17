#!/usr/bin/env bash
# Offline APK assembly with preloaded Android and MediaPipe build artifacts.
set -euo pipefail
APP=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
LEGACY=${ANDROID_BUILD_TOOLS_DIR:-$APP/../android-build-tools}
MAIN="$APP/app/src/main"
BUILD="$APP/build"
DIST="$APP/dist"
VERSION=0.6.0
MEDIAPIPE=${YUANYANG_MEDIAPIPE_DIR:-$APP/.toolchain/mediapipe}
QA_DEBUGGABLE=${YUANYANG_QA_DEBUGGABLE:-0}
ABIS=${YUANYANG_ABIS:-universal}
ORIGINAL_CERT=94c0f46dafdd9d4f903aa360b0b9c61161f0326b97ee21dc2dd7b6a150b8439e
if [[ ${1:-} == --preview ]]; then
    PREVIEW=1
elif [[ $# == 0 ]]; then
    PREVIEW=0
else
    echo "Usage: ./build-apk.sh [--preview]" >&2; exit 2
fi
if [[ "$QA_DEBUGGABLE" != 0 && "$QA_DEBUGGABLE" != 1 ]] || (( QA_DEBUGGABLE && ! PREVIEW )); then
    echo "YUANYANG_QA_DEBUGGABLE=1 is allowed only with --preview." >&2; exit 2
fi
if [[ "$ABIS" != universal && "$ABIS" != arm64-v8a,armeabi-v7a ]] ||
   { [[ "$ABIS" != universal ]] && (( ! PREVIEW || QA_DEBUGGABLE )); }; then
    echo 'YUANYANG_ABIS=arm64-v8a,armeabi-v7a requires non-QA --preview.' >&2; exit 2
fi
if [[ -n "$SDK" && -f "$SDK/platforms/android-34/android.jar" && -x "$SDK/build-tools/34.0.0/aapt2" ]]; then
    TOOLS="$SDK/build-tools/34.0.0"
    AAPT="$TOOLS/aapt"
    AAPT2="$TOOLS/aapt2"
    ZIPALIGN="$TOOLS/zipalign"
    ANDROID_JAR="$SDK/platforms/android-34/android.jar"
    D8=("$TOOLS/d8")
    SIGNER=("$TOOLS/apksigner")
else
    ANDROID_JAR="${ANDROID_PLATFORM_JAR:-$APP/.toolchain/android-34.jar}"
    AAPT="$LEGACY/root/usr/bin/aapt"
    AAPT2="$LEGACY/root/usr/bin/aapt2"
    ZIPALIGN="$LEGACY/root/usr/bin/zipalign"
    export LD_LIBRARY_PATH="$LEGACY/root/usr/lib/x86_64-linux-gnu/android:$LEGACY/root/usr/lib/x86_64-linux-gnu:$LEGACY/root/usr/lib:$LEGACY/root/usr/lib/7zip${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    D8=(java -cp "$LEGACY/jars/r8.jar" com.android.tools.r8.D8)
    SIGNER=(java -jar "$LEGACY/root/usr/share/java/apksigner.jar")
fi
[[ -s "$ANDROID_JAR" && -x "$AAPT2" && -x "$ZIPALIGN" ]] || {
    printf '%s\n' 'Set ANDROID_SDK_ROOT to an SDK with platform 34/build-tools 34.0.0, or configure the local AOSP toolchain.' >&2
    exit 1
}
if command -v javac >/dev/null; then
    JAVAC=(javac)
else
    JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
mkdir -p "$BUILD" "$DIST"
# Files are regenerated from scratch on each build. Keep the signing key so
# subsequent builds can update an already installed copy.
TMP=$(mktemp -d "$BUILD/work.XXXXXXXX")
trap 'rm -rf -- "$TMP"' EXIT
mkdir -p "$TMP/classes" "$TMP/dex" "$TMP/generated" "$TMP/aar" "$TMP/package"
artifact_files=()
while read -r sha name url; do
    if [[ -z "$sha" || "$sha" == \#* ]]; then continue; fi
    if ! sha256sum --check --status <(printf '%s  %s\n' "$sha" "$MEDIAPIPE/$name"); then
        echo "Missing or unexpected MediaPipe artifact $name. Run fetch-mediapipe.sh first." >&2; exit 1
    fi
    artifact_files+=("$MEDIAPIPE/$name")
done < "$APP/mediapipe-runtime.lock"
MODEL="$MAIN/assets/pose_landmarker_lite.task"
if ! sha256sum --check --status <(printf '%s  %s\n' \
    59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a "$MODEL"); then
    echo "Missing or unexpected Pose Landmarker Lite model asset." >&2; exit 1
fi
dependency_jars=()
for artifact in "${artifact_files[@]}"; do
    if [[ "$artifact" == *.jar ]]; then
        dependency_jars+=("$artifact")
    else
        module="$TMP/aar/$(basename -- "$artifact" .aar)"
        mkdir -p "$module"
        unzip -qo "$artifact" -d "$module"
        if [[ -s "$module/classes.jar" ]]; then dependency_jars+=("$module/classes.jar"); fi
        if [[ -d "$module/jni" ]]; then
            while IFS= read -r -d '' so; do
                relative="${so#"$module/jni/"}"
                if [[ "$ABIS" != universal && ",$ABIS," != *",${relative%%/*},"* ]]; then continue; fi
                install -D -m 0644 "$so" "$TMP/package/lib/$relative"
            done < <(find "$module/jni" -type f -name '*.so' -print0)
        fi
    fi
done
[[ -s "$TMP/package/lib/arm64-v8a/libmediapipe_tasks_jni.so" &&
   -s "$TMP/package/lib/armeabi-v7a/libmediapipe_tasks_jni.so" &&
   ( "$ABIS" != universal || -s "$TMP/package/lib/x86_64/libmediapipe_tasks_jni.so" ) ]] || {
    echo "Missing phone or emulator MediaPipe JNI library." >&2; exit 1
}
# Flogger discovers its backend factory using JVM service-loader resources.
for dep_jar in "${dependency_jars[@]}"; do
    while IFS= read -r service; do
        if [[ "$service" =~ ^META-INF/services/[a-zA-Z0-9._-]+$ ]]; then
            mkdir -p "$TMP/package/META-INF/services"
            unzip -p "$dep_jar" "$service" >> "$TMP/package/$service"
            printf '\n' >> "$TMP/package/$service"
        fi
    done < <(unzip -Z -1 "$dep_jar" | grep '^META-INF/services/' || true)
done
res=()
if [[ -d "$MAIN/res" ]] && find "$MAIN/res" -type f -print -quit | grep -q .; then
    "$AAPT2" compile --dir "$MAIN/res" -o "$TMP/res.zip"
    res+=("$TMP/res.zip")
fi
assets=()
[[ ! -d "$MAIN/assets" ]] || assets+=(-A "$MAIN/assets")

# Keep the package attribute for the offline linker; AGP also accepts it with
# a warning if it matches the explicit Gradle namespace.
# Gradle merges and applies the Internet-permission removal marker. The direct
# linker does not merge library manifests, so it must omit the marker entirely.
sed '/android:name="android.permission.INTERNET".*tools:node="remove"/d' \
    "$MAIN/AndroidManifest.xml" > "$TMP/ManifestPrivacy.xml"
MANIFEST="$TMP/ManifestPrivacy.xml"
if (( PREVIEW )); then
    sed -e 's/package="com.yuanyang.camera"/package="com.yuanyang.camera.preview"/' \
        -e 's/android:name="\.\(MainActivity\|CaptureActivity\|ExportPreviewActivity\|ShareProvider\)"/android:name="com.yuanyang.camera.\1"/g' \
        -e 's/android:authorities="com.yuanyang.camera.share"/android:authorities="com.yuanyang.camera.preview.share"/' \
        -e 's/android:label="原样"/android:label="原样测试版"/' "$MANIFEST" > "$TMP/AndroidManifest.xml"
    MANIFEST="$TMP/AndroidManifest.xml"
fi
qa_args=()
if (( QA_DEBUGGABLE )); then qa_args+=(--debug-mode); fi
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$MANIFEST" -0 task "${qa_args[@]}" \
  --java "$TMP/generated" -o "$TMP/unsigned.apk" "${assets[@]}" "${res[@]}"
mapfile -d '' sources < <(find "$MAIN/java" "$TMP/generated" -type f -name '*.java' -print0 | sort -z)
[[ "${#sources[@]}" -gt 0 ]]
compile_cp=$(IFS=:; printf '%s' "$ANDROID_JAR:${dependency_jars[*]}")
"${JAVAC[@]}" -source 8 -target 8 -Xlint:-options -encoding UTF-8 -classpath "$compile_cp" -d "$TMP/classes" "${sources[@]}"
mapfile -d '' classes < <(find "$TMP/classes" -name '*.class' -type f -print0 | sort -z)
MIN_API=$(sed -nE 's/.*android:minSdkVersion="([0-9]+)".*/\1/p' "$MAIN/AndroidManifest.xml" | head -1)
"${D8[@]}" --min-api "${MIN_API:-23}" --lib "$ANDROID_JAR" --output "$TMP/dex" "${classes[@]}" "${dependency_jars[@]}"
(cd "$TMP/dex" && "$AAPT" add "$TMP/unsigned.apk" classes*.dex >/dev/null)
(cd "$TMP/package" && mapfile -d '' files < <(find . -type f -printf '%P\0' | sort -z)
 "$AAPT" add "$TMP/unsigned.apk" "${files[@]}" >/dev/null)
"$ZIPALIGN" -f 4 "$TMP/unsigned.apk" "$TMP/aligned.apk"

KEY="${YUANYANG_KEYSTORE:-$BUILD/debug.keystore}"
SUFFIX=debug
if (( PREVIEW )); then
    KEY="$BUILD/preview.keystore"
    SUFFIX=preview
    if (( QA_DEBUGGABLE )); then SUFFIX=qa; fi
    if [[ "$ABIS" != universal ]]; then SUFFIX=phone; fi
elif [[ ! -f "$KEY" ]]; then
    echo "Original signing keystore required for an in-place upgrade. Set YUANYANG_KEYSTORE or use --preview." >&2
    exit 1
fi
if [[ ! -e "$KEY" ]]; then
    umask 077
    keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey \
      -dname 'CN=Android Debug,O=YuanYang Development' -keyalg RSA -keysize 2048 -validity 10000
    umask 022
fi
APK="$DIST/yuan-yang-$VERSION-$SUFFIX.apk"
"${SIGNER[@]}" sign --v4-signing-enabled false --ks "$KEY" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$TMP/signed.apk" "$TMP/aligned.apk"
"${SIGNER[@]}" verify --verbose --print-certs "$TMP/signed.apk" > "$TMP/apk-signature-report.txt"
if (( ! PREVIEW )) && ! grep -q "certificate SHA-256 digest: $ORIGINAL_CERT" "$TMP/apk-signature-report.txt"; then
    echo "Refusing an incompatible in-place upgrade: signer does not match the original APK." >&2; exit 1
fi
REPORT_SUFFIX=
if (( QA_DEBUGGABLE )); then REPORT_SUFFIX=-qa; fi
if [[ "$ABIS" != universal ]]; then REPORT_SUFFIX=-phone; fi
cp "$TMP/apk-signature-report.txt" "$DIST/apk-signature-report$REPORT_SUFFIX.txt"
"$ZIPALIGN" -c -v 4 "$TMP/signed.apk" > "$DIST/apk-alignment-report$REPORT_SUFFIX.txt"
"$AAPT" dump badging "$TMP/signed.apk" > "$DIST/apk-manifest-report$REPORT_SUFFIX.txt"
if grep -q 'android.permission.INTERNET' "$DIST/apk-manifest-report$REPORT_SUFFIX.txt"; then
    echo "Refusing an APK with network permission." >&2; exit 1
fi
if grep -q '^application-debuggable' "$DIST/apk-manifest-report$REPORT_SUFFIX.txt"; then
    if (( ! QA_DEBUGGABLE )); then echo "Refusing an accidentally debuggable final APK." >&2; exit 1; fi
elif (( QA_DEBUGGABLE )); then
    echo "QA APK did not become debuggable." >&2; exit 1
fi
unzip -tq "$TMP/signed.apk"
python3 - "$TMP/signed.apk" "$ABIS" <<'PYZIP'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    assert apk.getinfo('assets/pose_landmarker_lite.task').compress_type == zipfile.ZIP_STORED
    assert apk.getinfo('lib/arm64-v8a/libmediapipe_tasks_jni.so').file_size > 0
    all_abis = {f.split('/')[1] for f in apk.namelist() if f.startswith('lib/') and f.endswith('.so')}
    expected = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'} if sys.argv[2] == 'universal' else set(sys.argv[2].split(','))
    assert all_abis == expected
    for abi in expected:
        assert apk.getinfo(f'lib/{abi}/libmediapipe_tasks_jni.so').file_size > 0
PYZIP
mv -- "$TMP/signed.apk" "$APK"
(cd "$DIST" && sha256sum "$(basename -- "$APK")" > "SHA256SUMS$REPORT_SUFFIX")
printf 'Built: %s\n' "$APK"
