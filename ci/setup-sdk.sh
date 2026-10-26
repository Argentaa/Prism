#!/usr/bin/env bash
# Installs the Android SDK (API 37) on a bare GitHub runner.
set -euo pipefail

SDK_ROOT="$HOME/android-sdk"
mkdir -p "$SDK_ROOT/cmdline-tools"
curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip" -o /tmp/cmdtools.zip
unzip -q /tmp/cmdtools.zip -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-37" "build-tools;36.0.0"
