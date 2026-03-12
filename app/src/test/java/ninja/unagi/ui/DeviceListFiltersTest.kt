package ninja.unagi.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceListFiltersTest {
  private val now = 100_000L

  @Test
  fun `active group only includes live devices`() {
    val liveDevice = item(lastSeen = now - 1_000L)
    val staleDevice = item(lastSeen = now - LiveDeviceWindow.WINDOW_MS - 1L)

    assertTrue(DeviceListFilters.matchesGroup(liveDevice, DeviceListGroup.ACTIVE, now))
    assertFalse(DeviceListFilters.matchesGroup(staleDevice, DeviceListGroup.ACTIVE, now))
  }

  @Test
  fun `alerts group requires both live status and enabled alert match`() {
    val liveMatch = item(lastSeen = now - 1_000L, matchesEnabledAlert = true)
    val staleMatch = item(lastSeen = now - LiveDeviceWindow.WINDOW_MS - 1L, matchesEnabledAlert = true)
    val liveNonMatch = item(lastSeen = now - 1_000L, matchesEnabledAlert = false)

    assertTrue(DeviceListFilters.matchesGroup(liveMatch, DeviceListGroup.ALERTS, now))
    assertFalse(DeviceListFilters.matchesGroup(staleMatch, DeviceListGroup.ALERTS, now))
    assertFalse(DeviceListFilters.matchesGroup(liveNonMatch, DeviceListGroup.ALERTS, now))
  }

  private fun item(
    lastSeen: Long,
    matchesEnabledAlert: Boolean = false,
    starred: Boolean = false
  ): DeviceListItem {
    return DeviceListItem(
      deviceKey = "device-key",
      displayName = "Beacon",
      displayTitle = "Beacon",
      deviceNote = null,
      metaLine = "",
      searchText = "Beacon",
      sortTimestamp = lastSeen,
      lastSeen = lastSeen,
      lastRssi = -60,
      sightingsCount = 1,
      starred = starred,
      matchesEnabledAlert = matchesEnabledAlert,
      lastAddress = "AA:BB:CC:DD:EE:FF",
      vendorName = "Acme"
    )
  }
}
