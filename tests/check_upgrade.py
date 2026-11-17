#!/usr/bin/env python3
"""Verify two APKs have a compatible package identity, version and signer."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def run(argv):
    return subprocess.check_output(argv, text=True, stderr=subprocess.STDOUT)

def tools():
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    sdk_tools = Path(sdk)/"build-tools"/"34.0.0" if sdk else None
    if sdk_tools and (sdk_tools/"aapt").is_file() and (sdk_tools/"apksigner").is_file():
        return [str(sdk_tools/"aapt")], [str(sdk_tools/"apksigner")]
    fallback = Path(os.environ.get("ANDROID_BUILD_TOOLS_DIR", ROOT.parent/"android-build-tools"))/"root/usr"
    libs = [str(fallback/p) for p in ("lib/x86_64-linux-gnu/android", "lib/x86_64-linux-gnu", "lib", "lib/7zip")]
    if os.environ.get("LD_LIBRARY_PATH"):
        libs.append(os.environ["LD_LIBRARY_PATH"])
    os.environ["LD_LIBRARY_PATH"] = ":".join(libs)
    return [str(fallback/"bin/aapt")], ["java", "-jar", str(fallback/"share/java/apksigner.jar")]

def inspect(path, aapt, signer):
    if not path.is_file():
        raise ValueError(f"APK not found: {path}")
    manifest = run([*aapt, "dump", "badging", str(path)])
    metadata = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", manifest, re.M)
    if not metadata:
        raise ValueError(f"No package identity: {path}")
    signed = run([*signer, "verify", "--verbose", "--print-certs", str(path)])
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)$", signed, re.M | re.I)
    if not certs:
        raise ValueError(f"No verified signing certificates: {path}")
    return metadata[1], int(metadata[2]), metadata[3], tuple(sorted(certs))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("old_apk", type=Path)
    parser.add_argument("new_apk", type=Path)
    args = parser.parse_args()
    try:
        aapt, signer = tools()
        old, new = [inspect(path, aapt, signer) for path in (args.old_apk, args.new_apk)]
        if old[0] != new[0]:
            raise ValueError(f"Different package names: {old[0]} and {new[0]}")
        if old[3] != new[3]:
            raise ValueError("Signing certificates differ; in-place Android upgrade would fail")
        if new[1] <= old[1]:
            raise ValueError(f"New versionCode {new[1]} must be higher than {old[1]}")
        print(f"Verified upgrade: {new[0]} {old[2]} ({old[1]}) -> {new[2]} ({new[1]})")
        print(f"Identical signer SHA-256: {', '.join(new[3])}")
    except (ValueError, OSError, subprocess.CalledProcessError) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
