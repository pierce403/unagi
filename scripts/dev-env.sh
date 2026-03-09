#!/usr/bin/env bash
# Source this file to set up the development environment:
#   source scripts/dev-env.sh
#
# Then use:
#   ./gradlew assembleDebug
#   ./gradlew installDebug
#   start-emulator        (function defined below)

export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
export ANDROID_HOME="/c/Users/Ingmar/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

start-emulator() {
  "$ANDROID_HOME/emulator/emulator.exe" -avd unagi_test -no-audio -gpu auto &
  echo "Waiting for emulator to boot..."
  adb wait-for-device
  while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    sleep 3
  done
  echo "Emulator ready."
}

echo "Dev environment loaded. JAVA_HOME=$JAVA_HOME"
echo "Run 'start-emulator' to launch the emulator."
