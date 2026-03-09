#!/usr/bin/env python3
"""
TPMS Simulator — sends rtl_433-format JSON lines over TCP.

Simulates multiple TPMS sensors broadcasting on 433.92 MHz with realistic
pressure, temperature, and battery data. Designed for testing the UNAGI SDR
pipeline without real RF hardware.

Usage:
  python3 scripts/tpms-simulator.py                     # 4 sensors, 2s interval
  python3 scripts/tpms-simulator.py --port 5555          # custom port
  python3 scripts/tpms-simulator.py --burst              # 100ms interval
  python3 scripts/tpms-simulator.py --sensors 8          # 8 distinct sensors
  python3 scripts/tpms-simulator.py --interval 0.5       # 500ms between readings

Connect from UNAGI using SdrPreferences: source=NETWORK, host=<this-IP>, port=1234
Or use adb forward: adb forward tcp:1234 tcp:1234, then host=127.0.0.1
"""

import argparse
import json
import random
import socket
import sys
import threading
import time

SENSOR_PROFILES = [
    {"model": "Toyota", "id": "0x00ABCDEF", "freq": 433.92},
    {"model": "Toyota", "id": "0x00ABCDE0", "freq": 433.92},
    {"model": "Schrader", "id": "0x12345678", "freq": 433.92},
    {"model": "Schrader", "id": "0x12345679", "freq": 433.92},
    {"model": "Ford", "id": "0xAABBCCDD", "freq": 433.92},
    {"model": "Ford", "id": "0xAABBCCDE", "freq": 433.92},
    {"model": "Continental", "id": "0x55667788", "freq": 433.92},
    {"model": "Continental", "id": "0x55667789", "freq": 433.92},
    {"model": "Renault", "id": "0xDEADBEEF", "freq": 433.92},
    {"model": "Renault", "id": "0xDEADBEF0", "freq": 433.92},
    {"model": "Hyundai", "id": "0x11223344", "freq": 315.00},
    {"model": "Hyundai", "id": "0x11223345", "freq": 315.00},
]


def generate_reading(profile):
    """Generate a single rtl_433-compatible TPMS JSON reading."""
    return {
        "time": time.strftime("%Y-%m-%d %H:%M:%S"),
        "model": profile["model"],
        "type": "TPMS",
        "id": profile["id"],
        "status": random.choice([0, 0, 0, 1]),
        "battery_ok": random.choices([1, 0], weights=[95, 5])[0],
        "pressure_kPa": round(random.uniform(200.0, 250.0), 1),
        "temperature_C": round(random.uniform(15.0, 40.0), 1),
        "rssi": round(random.uniform(-20.0, -5.0), 1),
        "snr": round(random.uniform(8.0, 25.0), 1),
        "freq": profile["freq"],
    }


def handle_client(conn, addr, profiles, interval):
    """Send TPMS readings to a connected client."""
    print(f"Client connected: {addr}")
    try:
        while True:
            profile = random.choice(profiles)
            reading = generate_reading(profile)
            line = json.dumps(reading) + "\n"
            conn.sendall(line.encode("utf-8"))
            time.sleep(interval)
    except (BrokenPipeError, ConnectionResetError, OSError):
        print(f"Client disconnected: {addr}")
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(
        description="TPMS sensor simulator for UNAGI SDR testing"
    )
    parser.add_argument(
        "--port", type=int, default=1234, help="TCP port to listen on (default: 1234)"
    )
    parser.add_argument(
        "--sensors",
        type=int,
        default=4,
        help="Number of distinct sensors to simulate (default: 4, max: 12)",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=2.0,
        help="Seconds between readings (default: 2.0)",
    )
    parser.add_argument(
        "--burst",
        action="store_true",
        help="Burst mode: 100ms interval for stress testing",
    )
    args = parser.parse_args()

    interval = 0.1 if args.burst else args.interval
    num_sensors = min(args.sensors, len(SENSOR_PROFILES))
    profiles = SENSOR_PROFILES[:num_sensors]

    print(f"TPMS Simulator")
    print(f"  Port:     {args.port}")
    print(f"  Sensors:  {num_sensors} ({', '.join(p['model'] + ' ' + p['id'] for p in profiles)})")
    print(f"  Interval: {interval}s {'(burst mode)' if args.burst else ''}")
    print(f"  Waiting for connections...")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", args.port))
    server.listen(5)

    try:
        while True:
            conn, addr = server.accept()
            thread = threading.Thread(
                target=handle_client,
                args=(conn, addr, profiles, interval),
                daemon=True,
            )
            thread.start()
    except KeyboardInterrupt:
        print("\nShutting down.")
    finally:
        server.close()


if __name__ == "__main__":
    main()
