#!/usr/bin/env bash
# Provision pinned build dependencies, not a device-side network operation.
set -euo pipefail
APP=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
DEST=${YUANYANG_MEDIAPIPE_DIR:-$APP/.toolchain/mediapipe}
mkdir -p "$DEST"
while read -r sha name url; do
    if [[ -z "$sha" || "$sha" == \#* ]]; then continue; fi
    [[ "$sha" =~ ^[0-9a-f]{64}$ && "$name" =~ ^[a-zA-Z0-9._-]+\.(jar|aar)$ &&
       ( "$url" == https://dl.google.com/dl/android/maven2/* || "$url" == https://repo.maven.apache.org/maven2/* ) ]]
    if sha256sum --check --status <(printf '%s  %s\n' "$sha" "$DEST/$name"); then
        printf 'Verified: %s\n' "$name"
        continue
    fi
    part=$(mktemp --tmpdir="$DEST" .media-fetch.XXXXXX)
    trap 'rm -f -- "$part"' EXIT
    curl -fsSL --retry 2 --connect-timeout 20 "$url" -o "$part"
    sha256sum --check --status <(printf '%s  %s\n' "$sha" "$part")
    mv -- "$part" "$DEST/$name"
    trap - EXIT
    printf 'Installed verified artifact: %s\n' "$name"
done < "$APP/mediapipe-runtime.lock"
