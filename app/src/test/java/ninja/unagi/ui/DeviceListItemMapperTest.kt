package ninja.unagi.ui

import ninja.unagi.data.DeviceEntity
import ninja.unagi.util.BluetoothAssignedNumbersRegistry
import ninja.unagi.util.VendorPrefixRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DeviceListItemMapperTest {
  private val mapper = DeviceListItemMapper(
    vendorRegistry = VendorPrefixRegistry.fromLines(emptySequence()),
    assignedNumbers = BluetoothAssignedNumbersRegistry.fromLines(
      companyLines = emptySequence(),
      serviceLines = emptySequence()
    )
  )

  @Test
  fun `volatile signal updates reuse cached presentation strings`() {
    val firstEntity = device()
    val first = mapper.map(listOf(firstEntity), emptyList()).single()

    val updated = mapper.map(
      listOf(
        firstEntity.copy(
          lastSeen = firstEntity.lastSeen + 500L,
          lastRssi = -42,
          observationCount = firstEntity.observationCount + 5,
          rssiMin = -90,
          rssiMax = -42,
          rssiAvg = -55.0
        )
      ),
      emptyList()
    ).single()

    assertEquals(firstEntity.lastSeen + 500L, updated.lastSeen)
    assertEquals(-42, updated.lastRssi)
    assertSame(first.displayTitle, updated.displayTitle)
    assertSame(first.metaLine, updated.metaLine)
    assertSame(first.searchText, updated.searchText)
  }

  private fun device(): DeviceEntity {
    return DeviceEntity(
      deviceKey = "device-1",
      displayName = "Beacon",
      lastAddress = "AA:BB:CC:DD:EE:FF",
      firstSeen = 1_000L,
      lastSeen = 2_000L,
      lastSightingAt = 1_000L,
      sightingsCount = 1,
      observationCount = 10,
      lastRssi = -60,
      rssiMin = -80,
      rssiMax = -50,
      rssiAvg = -62.0,
      lastMetadataJson = """{"transport":"BLE","serviceUuids":[],"manufacturerData":{}}""",
      starred = false
    )
  }
}
