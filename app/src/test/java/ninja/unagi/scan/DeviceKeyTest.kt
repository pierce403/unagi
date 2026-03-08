package ninja.unagi.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceKeyTest {
  @Test
  fun `prefers normalized address over shared manufacturer payload`() {
    val first = ObservationInput(
      name = null,
      address = "DA:A1:19:00:00:01",
      rssi = -55,
      timestamp = 1L,
      serviceUuids = listOf("0000FEAA-0000-1000-8000-00805F9B34FB"),
      manufacturerData = mapOf(76 to "0215AABBCCDD"),
      source = "BLE",
      normalizedAddress = "DAA119000001"
    )
    val second = first.copy(
      address = "DA:A1:19:00:00:02",
      normalizedAddress = "DAA119000002",
      timestamp = 2L
    )

    assertNotEquals(DeviceKey.from(first), DeviceKey.from(second))
  }

  @Test
  fun `falls back to name when no address exists`() {
    val first = ObservationInput(
      name = "Tracker",
      address = null,
      rssi = -70,
      timestamp = 1L,
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "BLE"
    )
    val second = first.copy(timestamp = 2L, rssi = -71)

    assertEquals(DeviceKey.from(first), DeviceKey.from(second))
  }
}
