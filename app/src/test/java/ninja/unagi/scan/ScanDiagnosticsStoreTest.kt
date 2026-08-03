package ninja.unagi.scan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanDiagnosticsStoreTest {
  @After
  fun resetStore() {
    ScanDiagnosticsStore.reset()
  }

  @Test
  fun `old session observations cannot contaminate the active session`() {
    ScanDiagnosticsStore.startSession(
      sessionId = 10L,
      snapshot = ScanDiagnosticsSnapshot(scanMode = ScanModePreset.NORMAL)
    )
    ScanDiagnosticsStore.recordObservation(
      sessionId = 9L,
      deviceKey = "old-device",
      sample = sample("old")
    )
    ScanDiagnosticsStore.recordObservation(
      sessionId = 10L,
      deviceKey = "current-device",
      sample = sample("current")
    )

    val snapshot = ScanDiagnosticsStore.snapshot.value
    assertEquals(10L, snapshot.sessionId)
    assertEquals(1, snapshot.uniqueDeviceCount)
    assertEquals(listOf("current"), snapshot.callbackSamples.map { it.name })
  }

  @Test
  fun `starting a session atomically resets unique device ownership`() {
    ScanDiagnosticsStore.startSession(1L, ScanDiagnosticsSnapshot())
    ScanDiagnosticsStore.recordObservation(1L, "device-a", sample("a"))

    ScanDiagnosticsStore.startSession(2L, ScanDiagnosticsSnapshot())
    ScanDiagnosticsStore.recordObservation(2L, "device-b", sample("b"))

    val snapshot = ScanDiagnosticsStore.snapshot.value
    assertEquals(2L, snapshot.sessionId)
    assertEquals(1, snapshot.uniqueDeviceCount)
    assertEquals(listOf("b"), snapshot.callbackSamples.map { it.name })
  }

  private fun sample(name: String): CallbackSample {
    return CallbackSample(
      path = ScanPath.BLE,
      timestampMs = 1L,
      address = null,
      name = name,
      rssi = -50,
      serviceUuidCount = 0,
      manufacturerDataKeys = emptyList()
    )
  }
}
