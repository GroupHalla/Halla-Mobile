#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
mkdir -p "$LIBS"
name="halla-webrtc-android-144.7559.09-p1.aar"
expected="456f5c7a30c2047e01608df52bcbb76a5bdfff2cb14401961c3b4d15fd01e162"
path="$LIBS/$name"
if [[ ! -f "$path" || "$(sha256sum "$path" | awk '{print $1}')" != "$expected" ]]; then
  tmp="$path.tmp"
  rm -f "$tmp"
  curl -fsSL -o "$tmp" \
    "https://github.com/GroupHalla/Halla-WebRTC-Builds/releases/download/android-v0.1.1/$name"
  actual="$(sha256sum "$tmp" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || { echo "WebRTC Android checksum mismatch: $actual" >&2; rm -f "$tmp"; exit 1; }
  mv "$tmp" "$path"
fi
echo "Halla WebRTC Android SDK OK: $expected"
