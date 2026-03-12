package ninja.unagi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferedObservationTest {
  @Test
  fun `merge keeps counts rssi stats and latest metadata`() {
    val first = DeviceObservation(
      deviceKey = "device-1",
      name = "Alpha",
      address = "AA:BB:CC:DD:EE:FF",
      rssi = -72,
      timestamp = 1_000L,
      metadataJson = "{\"seq\":1}"
    )
    val second = DeviceObservation(
      deviceKey = "device-1",
      name = null,
      address = null,
      rssi = -48,
      timestamp = 1_250L,
      metadataJson = "{\"seq\":2}"
    )

    val merged = BufferedObservation.from(first).merge(second)

    assertEquals("device-1", merged.deviceKey)
    assertEquals("Alpha", merged.name)
    assertEquals("AA:BB:CC:DD:EE:FF", merged.address)
    assertEquals(1_000L, merged.firstTimestamp)
    assertEquals(1_250L, merged.lastTimestamp)
    assertEquals(2, merged.observationCount)
    assertEquals(-48, merged.lastRssi)
    assertEquals(-72, merged.rssiMin)
    assertEquals(-48, merged.rssiMax)
    assertEquals(-120L, merged.rssiSum)
    assertEquals("{\"seq\":2}", merged.metadataJson)
  }
}
