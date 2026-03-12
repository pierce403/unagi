package ninja.unagi.group

import ninja.unagi.data.DeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BundleSerializerTest {
  @Test
  fun `device notes round-trip through bundle JSON with quotes intact`() {
    val original = DeviceEntity(
      deviceKey = "device-key-1",
      displayName = "Beacon",
      lastAddress = "AA:BB:CC:DD:EE:FF",
      firstSeen = 1_000L,
      lastSeen = 2_000L,
      lastSightingAt = 1_900L,
      sightingsCount = 4,
      observationCount = 8,
      lastRssi = -62,
      rssiMin = -80,
      rssiMax = -55,
      rssiAvg = -66.5,
      lastMetadataJson = """{"transport":"BLE"}""",
      starred = true,
      userCustomName = """Dean's "Flipper""""
    )

    val payload = BundleSerializer.serializePayload(
      devices = listOf(original),
      sightings = emptyList(),
      alertRules = emptyList(),
      enrichments = emptyList(),
      starredDeviceKeys = emptyList()
    )

    val restored = BundleSerializer.deserializePayload(payload).devices.single()

    assertEquals(original.userCustomName, restored.userCustomName)
  }
}
