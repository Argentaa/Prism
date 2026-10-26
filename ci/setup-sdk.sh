#!/usr/bin/env bash
# Installs the Android SDK (API 37) on a bare GitHub runner.
# Uses the new `android` CLI (classic sdkmanager is deprecated and can't see
# API 37). The platform is still a canary package: exact spec
# `platforms;android-37.0` with --canary. Licenses are accepted AFTER
# installing (license acceptance only covers packages already on disk).
set -euo pipefail

command -v curl >/dev/null || { echo "curl missing"; exit 1; }
command -v unzip >/dev/null || sudo apt-get install -y -q unzip

SDK_ROOT="$HOME/android-sdk"
mkdir -p "$SDK_ROOT/cmdline-tools"
curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" -o /tmp/cmdtools.zip
unzip -q /tmp/cmdtools.zip -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"

A="$SDK_ROOT/cmdline-tools/latest/bin/android --sdk=$SDK_ROOT --no-metrics"
$A sdk install "platform-tools" "build-tools;36.0.0"
$A sdk install --canary "platforms;android-37.0"

# NB: `yes` dies with SIGPIPE once sdkmanager exits; with pipefail that would
# fail the step, so the pipeline's status is ignored (a real license problem
# surfaces loudly in the Gradle build instead).
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true

# Belt and braces: the standard license hashes (verified by the Gradle build).
mkdir -p "$SDK_ROOT/licenses"
printf '\n8933bad161af4178b1185d1a37fbf41ea5269c\nd56f5187479451eabfb336dd92f2e7eb30dd\n24333f8a63b6825a95850c49c4d784a51f6c\n' > "$SDK_ROOT/licenses/android-sdk-license"
printf '\n84831b9409646a918e6343bd356bdd2fdecb7a2a\n' > "$SDK_ROOT/licenses/android-sdk-preview-license"
