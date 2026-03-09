#!/usr/bin/env bash
# smoke-test-sdr.sh — SDR pipeline smoke test using simulated TPMS data.
#
# Starts a local TPMS simulator, sets up adb reverse port forwarding to make
# the host-side simulator reachable from the device/emulator at 127.0.0.1,
# injects SDR + continuous scan preferences, launches the app, taps "Start Scan",
# and watches logcat for SDR observations to confirm the full pipeline works.
#
# Usage:
#   bash scripts/smoke-test-sdr.sh              # default: port 1234, 15s capture
#   bash scripts/smoke-test-sdr.sh --duration 30  # 30s capture window
#   bash scripts/smoke-test-sdr.sh --port 5555    # custom port

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="ninja.unagi"
DEFAULT_PORT=1234
DEFAULT_DURATION=15
SIMULATOR_PID=""
LOGCAT_PID=""

PORT=$DEFAULT_PORT
DURATION=$DEFAULT_DURATION

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

cleanup() {
  echo ""
  echo "=== Cleaning up ==="

  # Kill logcat watcher
  if [[ -n "$LOGCAT_PID" ]]; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
  fi

  # Kill simulator
  if [[ -n "$SIMULATOR_PID" ]]; then
    kill "$SIMULATOR_PID" 2>/dev/null || true
    wait "$SIMULATOR_PID" 2>/dev/null || true
    echo "Stopped TPMS simulator (PID $SIMULATOR_PID)"
  fi

  # Remove adb reverse
  adb reverse --remove tcp:"$PORT" 2>/dev/null || true
  echo "Removed adb reverse for port $PORT"

  # Reset preferences
  adb shell "run-as $PACKAGE sh -c 'rm -f shared_prefs/unagi_sdr.xml shared_prefs/unagi_scan.xml'" 2>/dev/null || true
  adb shell am force-stop "$PACKAGE" 2>/dev/null || true
  echo "Cleared preferences and stopped app"

  echo "=== Cleanup complete ==="
}

trap cleanup EXIT

echo "=== SDR Smoke Test ==="
echo "  Port:     $PORT"
echo "  Duration: ${DURATION}s"
echo ""

# 1. Verify adb device is connected
echo "--- Checking adb device ---"
if ! adb get-state >/dev/null 2>&1; then
  echo "ERROR: No adb device connected. Start an emulator or connect a device."
  exit 1
fi
DEVICE=$(adb get-serialno 2>/dev/null || echo "unknown")
echo "Device: $DEVICE"

# 2. Start TPMS simulator in background
echo ""
echo "--- Starting TPMS simulator ---"
python3 "$SCRIPT_DIR/tpms-simulator.py" --port "$PORT" --sensors 4 --interval 1.0 &
SIMULATOR_PID=$!
sleep 1

if ! kill -0 "$SIMULATOR_PID" 2>/dev/null; then
  echo "ERROR: TPMS simulator failed to start"
  SIMULATOR_PID=""
  exit 1
fi
echo "Simulator running (PID $SIMULATOR_PID)"

# 3. Set up adb reverse (device connects to 127.0.0.1:PORT -> host PORT)
echo ""
echo "--- Setting up adb reverse ---"
adb reverse tcp:"$PORT" tcp:"$PORT"
echo "Reverse: device 127.0.0.1:$PORT -> host :$PORT"

# 4. Inject SDR + continuous scan preferences
echo ""
echo "--- Injecting preferences ---"
SDR_PREFS="<?xml version=\"1.0\" encoding=\"utf-8\"?>
<map>
  <boolean name=\"sdr_enabled\" value=\"true\" />
  <string name=\"sdr_source\">network</string>
  <string name=\"sdr_network_host\">127.0.0.1</string>
  <int name=\"sdr_network_port\" value=\"$PORT\" />
  <int name=\"sdr_frequency\" value=\"433\" />
</map>"
SCAN_PREFS="<?xml version=\"1.0\" encoding=\"utf-8\"?>
<map>
  <boolean name=\"continuous_scanning_enabled\" value=\"true\" />
</map>"
adb shell "run-as $PACKAGE mkdir -p shared_prefs"
echo "$SDR_PREFS" | adb shell "run-as $PACKAGE sh -c 'cat > shared_prefs/unagi_sdr.xml'"
echo "$SCAN_PREFS" | adb shell "run-as $PACKAGE sh -c 'cat > shared_prefs/unagi_scan.xml'"
echo "SDR: source=network, host=127.0.0.1, port=$PORT"
echo "Continuous scanning: enabled"

# 5. Force-stop, relaunch, and start scanning
echo ""
echo "--- Launching app and starting scan ---"
adb shell am force-stop "$PACKAGE"
sleep 1
adb logcat -c
adb shell am start -n "$PACKAGE/.ui.MainActivity"
sleep 3
# Tap the START SCAN button (bounds [691,138][975,264], center ~833,201)
adb shell input tap 833 201
sleep 2
echo "Scan started"

# 6. Watch for SDR observations
echo ""
echo "--- Watching logcat for SDR observations (${DURATION}s) ---"
adb logcat -v time -s "unagi:*" 2>/dev/null | grep --line-buffered -i "sdr\|tpms\|network bridge" &
LOGCAT_PID=$!

sleep "$DURATION"

# 7. Collect results
echo ""
echo "--- Results ---"
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true
LOGCAT_PID=""

SDR_LINES=$(adb logcat -d -v time -s "unagi:*" 2>/dev/null | grep -ci "sdr observation" || true)
BRIDGE_LINES=$(adb logcat -d -v time -s "unagi:*" 2>/dev/null | grep -ci "network bridge" || true)

echo "SDR observation log lines: $SDR_LINES"
echo "Network bridge log lines:  $BRIDGE_LINES"

if [[ "$SDR_LINES" -gt 0 ]]; then
  echo ""
  echo "PASS: SDR pipeline received $SDR_LINES simulated TPMS readings"
  exit 0
else
  echo ""
  echo "FAIL: No SDR observations detected in ${DURATION}s"
  echo "  - Ensure the app has INTERNET permission in AndroidManifest.xml"
  echo "  - Ensure the scan button was tapped (UI coordinates may differ on your device)"
  exit 1
fi
