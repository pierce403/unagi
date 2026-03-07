package ninja.unagi.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceKeyTest {
  @Test
  fun `manufacturer data takes precedence`() {
    val input = ObservationInput(
      name = "Device",
      address = "AA:BB:CC:DD:EE:FF",
      rssi = -42,
      timestamp = 0L,
      serviceUuids = listOf("1234"),
      manufacturerData = mapOf(1 to "abcd"),
      source = "BLE"
    )

    val key = DeviceKey.from(input)
    val mutated = input.copy(
      name = "Other",
      address = "11:22:33:44:55:66",
      serviceUuids = listOf("9999")
    )

    assertEquals(key, DeviceKey.from(mutated))
  }

  @Test
  fun `service uuids used when no manufacturer data`() {
    val inputA = ObservationInput(
      name = "Device",
      address = "AA:BB:CC:DD:EE:FF",
      rssi = -42,
      timestamp = 0L,
      serviceUuids = listOf("1234"),
      manufacturerData = emptyMap(),
      source = "BLE"
    )

    val inputB = inputA.copy(serviceUuids = listOf("5678"))

    assertNotEquals(DeviceKey.from(inputA), DeviceKey.from(inputB))
  }

  @Test
  fun `address fallback when identifiers missing`() {
    val inputA = ObservationInput(
      name = null,
      address = "AA:BB:CC:DD:EE:FF",
      rssi = -42,
      timestamp = 0L,
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "Classic"
    )

    val inputB = inputA.copy(address = "11:22:33:44:55:66")

    assertNotEquals(DeviceKey.from(inputA), DeviceKey.from(inputB))
  }
}
