#!/usr/bin/env bash
# smoke-test-sdr.sh — Automated SDR pipeline smoke test using simulated TPMS data.
#
# Starts a local TPMS simulator, forwards the port to a connected Android device/
# emulator via adb, injects SDR network preferences, and watches logcat for
# SDR observations to confirm the full pipeline works end-to-end.
#
# Usage:
#   bash scripts/smoke-test-sdr.sh              # default: port 1234, 10s capture
#   bash scripts/smoke-test-sdr.sh --duration 30  # 30s capture window
#   bash scripts/smoke-test-sdr.sh --port 5555    # custom port

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="ninja.unagi"
DEFAULT_PORT=1234
DEFAULT_DURATION=10
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

  # Remove adb port forward
  adb forward --remove tcp:"$PORT" 2>/dev/null || true
  echo "Removed adb forward for port $PORT"

  # Reset SDR preferences (disable SDR)
  adb shell "run-as $PACKAGE sh -c 'rm -f shared_prefs/unagi_sdr.xml'" 2>/dev/null || true
  echo "Cleared SDR preferences"

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

# 3. Set up adb forward
echo ""
echo "--- Setting up adb forward ---"
adb forward tcp:"$PORT" tcp:"$PORT"
echo "Forwarded tcp:$PORT -> tcp:$PORT"

# 4. Inject SDR preferences
echo ""
echo "--- Injecting SDR preferences ---"
adb shell "run-as $PACKAGE sh -c 'mkdir -p shared_prefs && cat > shared_prefs/unagi_sdr.xml << PREFS_EOF
<?xml version=\"1.0\" encoding=\"utf-8\"?>
<map>
  <boolean name=\"sdr_enabled\" value=\"true\" />
  <string name=\"sdr_source\">NETWORK</string>
  <string name=\"sdr_network_host\">127.0.0.1</string>
  <int name=\"sdr_network_port\" value=\"$PORT\" />
  <int name=\"sdr_frequency_mhz\" value=\"433\" />
</map>
PREFS_EOF'"
echo "SDR preferences written (source=NETWORK, host=127.0.0.1, port=$PORT)"

# 5. Force-stop and relaunch the app so preferences are re-read
echo ""
echo "--- Restarting app ---"
adb shell am force-stop "$PACKAGE"
sleep 1
adb shell am start -n "$PACKAGE/.ui.MainActivity"
sleep 2
echo "App launched"

# 6. Clear logcat and start watching for SDR observations
echo ""
echo "--- Watching logcat for SDR observations (${DURATION}s) ---"
adb logcat -c
adb logcat -v time -s "DebugLog:*" 2>/dev/null | grep --line-buffered -i "sdr\|tpms\|rtl_433\|network bridge" &
LOGCAT_PID=$!

# 7. Wait for the capture duration
sleep "$DURATION"

# 8. Collect results
echo ""
echo "--- Results ---"
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true
LOGCAT_PID=""

# Grab a final snapshot of relevant log lines
SDR_LINES=$(adb logcat -d -v time -s "DebugLog:*" 2>/dev/null | grep -ci "sdr observation" || true)
BRIDGE_LINES=$(adb logcat -d -v time -s "DebugLog:*" 2>/dev/null | grep -ci "network bridge" || true)

echo "SDR observation log lines: $SDR_LINES"
echo "Network bridge log lines:  $BRIDGE_LINES"

if [[ "$SDR_LINES" -gt 0 ]]; then
  echo ""
  echo "PASS: SDR pipeline received simulated TPMS readings"
  exit 0
else
  echo ""
  echo "FAIL: No SDR observations detected in ${DURATION}s"
  echo "  Check that the app has SDR code enabled and the network bridge is connecting."
  exit 1
fi
