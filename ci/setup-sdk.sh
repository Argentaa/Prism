#!/usr/bin/env bash
# Installs the Android SDK (API 37) on a bare GitHub runner.
# Uses the new `android` CLI: classic sdkmanager is deprecated and can no
# longer see/install platforms;android-37.
set -euo pipefail

command -v curl >/dev/null || { echo "curl missing"; exit 1; }
command -v unzip >/dev/null || sudo apt-get install -y -q unzip

SDK_ROOT="$HOME/android-sdk"
mkdir -p "$SDK_ROOT/cmdline-tools"
curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" -o /tmp/cmdtools.zip
unzip -q /tmp/cmdtools.zip -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"

BIN="$SDK_ROOT/cmdline-tools/latest/bin"
echo "--- full sdk list ---"
"$BIN/android" --sdk="$SDK_ROOT" sdk list 2>&1 | head -n 120 || true
echo "--- install help ---"
"$BIN/android" sdk install --help 2>&1 | head -n 40 || true
