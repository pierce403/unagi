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

  @Test
  fun `TPMS sensor uses name-based key and is stable across observations`() {
    val first = ObservationInput(
      name = "TPMS Toyota 0x00ABCDEF",
      address = null,
      rssi = -45,
      timestamp = 1000L,
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "SDR"
    )
    val second = first.copy(timestamp = 2000L, rssi = -50)

    assertEquals(DeviceKey.from(first), DeviceKey.from(second))
  }

  @Test
  fun `different TPMS sensors produce different keys`() {
    val sensor1 = ObservationInput(
      name = "TPMS Toyota 0x00ABCDEF",
      address = null,
      rssi = -45,
      timestamp = 1000L,
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "SDR"
    )
    val sensor2 = sensor1.copy(name = "TPMS Toyota 0x00AABBCC")

    assertNotEquals(DeviceKey.from(sensor1), DeviceKey.from(sensor2))
  }

  @Test
  fun `unnamed devices with same fingerprint produce same key across observations`() {
    val first = ObservationInput(
      name = null,
      address = null,
      rssi = -60,
      timestamp = 1000L,
      serviceUuids = listOf("0000FEAA-0000-1000-8000-00805F9B34FB"),
      manufacturerData = mapOf(76 to "0215AABB"),
      source = "BLE",
      classificationFingerprint = "fp-beacon-a"
    )
    val second = first.copy(timestamp = 5000L, rssi = -72)

    assertEquals(DeviceKey.from(first), DeviceKey.from(second))
  }

  @Test
  fun `unnamed devices with different fingerprints produce different keys`() {
    val first = ObservationInput(
      name = null,
      address = null,
      rssi = -60,
      timestamp = 1L,
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "BLE",
      classificationFingerprint = "fp-type-a"
    )
    val second = first.copy(classificationFingerprint = "fp-type-b")

    assertNotEquals(DeviceKey.from(first), DeviceKey.from(second))
  }

  @Test
  fun `unnamed devices with different service UUIDs produce different keys`() {
    val first = ObservationInput(
      name = null,
      address = null,
      rssi = -60,
      timestamp = 1L,
      serviceUuids = listOf("0000FEAA-0000-1000-8000-00805F9B34FB"),
      manufacturerData = emptyMap(),
      source = "BLE",
      classificationFingerprint = "fp-same"
    )
    val second = first.copy(serviceUuids = listOf("0000FE9F-0000-1000-8000-00805F9B34FB"))

    assertNotEquals(DeviceKey.from(first), DeviceKey.from(second))
  }
}
