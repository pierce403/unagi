package com.thingalert.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStateDeciderTest {
  @Test
  fun `does not enter scanning when both startup paths fail`() {
    val state = ScanStateDecider.stateAfterStartup(
      listOf(
        ScanStartupResult(
          path = ScanPath.BLE,
          started = false,
          reason = ScanStateDecider.BLE_SCANNER_UNAVAILABLE
        ),
        ScanStartupResult(
          path = ScanPath.CLASSIC,
          started = false,
          reason = "Bluetooth classic discovery failed to start"
        )
      )
    )

    assertTrue(state is ScanState.Error)
    val message = (state as ScanState.Error).message
    assertTrue(message.contains("Scan failed to start"))
    assertTrue(message.contains("Bluetooth LE scanner unavailable"))
  }

  @Test
  fun `classic success keeps scanning active when ble fails`() {
    val state = ScanStateDecider.stateAfterStartup(
      listOf(
        ScanStartupResult(path = ScanPath.BLE, started = false, reason = "Missing permission"),
        ScanStartupResult(path = ScanPath.CLASSIC, started = true)
      )
    )

    assertEquals(ScanState.Scanning, state)
  }

  @Test
  fun `ble success keeps scanning active when classic fails`() {
    val state = ScanStateDecider.stateAfterStartup(
      listOf(
        ScanStartupResult(path = ScanPath.BLE, started = true),
        ScanStartupResult(path = ScanPath.CLASSIC, started = false, reason = "Bluetooth classic discovery failed to start")
      )
    )

    assertEquals(ScanState.Scanning, state)
  }

  @Test
  fun `both successful startup paths enter scanning`() {
    val state = ScanStateDecider.stateAfterStartup(
      listOf(
        ScanStartupResult(path = ScanPath.BLE, started = true),
        ScanStartupResult(path = ScanPath.CLASSIC, started = true)
      )
    )

    assertEquals(ScanState.Scanning, state)
  }

  @Test
  fun `timeout with zero results produces complete zero state`() {
    val state = ScanStateDecider.stateAfterTimeout(
      ScanDiagnosticsSnapshot(
        startTimeMs = 1L,
        bleStartup = ScanStartupResult(path = ScanPath.BLE, started = true)
      )
    )

    assertEquals(ScanState.Complete(0), state)
  }

  @Test
  fun `timeout with results produces complete state with device count`() {
    val state = ScanStateDecider.stateAfterTimeout(
      ScanDiagnosticsSnapshot(
        startTimeMs = 1L,
        bleStartup = ScanStartupResult(path = ScanPath.BLE, started = true),
        classicStartup = ScanStartupResult(path = ScanPath.CLASSIC, started = false),
        deviceKeys = setOf("a", "b")
      )
    )

    assertEquals(ScanState.Complete(2), state)
  }
}
