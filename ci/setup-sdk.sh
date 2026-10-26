#!/usr/bin/env bash
# Installs the Android SDK (API 37) on a bare GitHub runner.
set -euo pipefail

command -v curl >/dev/null || { echo "curl missing"; exit 1; }
command -v unzip >/dev/null || sudo apt-get install -y -q unzip

SDK_ROOT="$HOME/android-sdk"
mkdir -p "$SDK_ROOT/cmdline-tools"
curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip" -o /tmp/cmdtools.zip
unzip -q /tmp/cmdtools.zip -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
# NB: `yes` dies with SIGPIPE once sdkmanager exits; with pipefail that would
# fail the step, so the pipeline's status is ignored (license problems surface
# loudly at the install below instead).
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
echo "--- available android platforms (probe) ---"
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --list 2>/dev/null | grep -i "platforms;android" | head -n 30 || true
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-37" "build-tools;36.0.0"
