# Android CLI Setup (No Android Studio)

This project is designed to run with the Android SDK Command-line Tools only.

## Quick path (script)

Run:

  scripts/setup-android-sdk

It will download the command-line tools, install required packages, accept licenses, and print the environment variables to export.
If you need a different command-line tools zip, set ANDROID_SDK_TOOLS_URL before running.

## Manual fallback

1) Install JDK 17.

2) Download Android SDK Command-line Tools from Android Developers ("command line tools only").

3) Unzip into your SDK directory using this structure:

  ANDROID_SDK_ROOT/
    cmdline-tools/
      latest/
        bin/
        lib/

4) Install required SDK packages:

  sdkmanager "platform-tools" "build-tools;35.0.0" "platforms;android-35"

5) Accept licenses:

  yes | sdkmanager --licenses

6) Export environment variables (add to shell profile):

  export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
  export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

## Doctor checklist

- java -version shows JDK 17
- sdkmanager --version works
- adb version works
- Required packages installed (platform-tools, build-tools;35.0.0, platforms;android-35)

## Build/run

- ./gradlew assembleDebug
- ./gradlew installDebug
- scripts/stage-apk
- adb devices (to verify device is connected)
