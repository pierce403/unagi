package ninja.unagi.ui

import java.util.Locale
import ninja.unagi.util.BluetoothAddressTools

enum class DeviceListGroup {
  ALL,
  STARRED,
  ACTIVE,
  ALERTS
}

internal data class DeviceListFilterOptions(
  val query: String,
  val group: DeviceListGroup,
  val sortMode: SortMode,
  val liveOnly: Boolean,
  val unknownOnly: Boolean,
  val starredOnly: Boolean
) {
  val requiresLiveClock: Boolean
    get() = liveOnly || group == DeviceListGroup.ACTIVE || group == DeviceListGroup.ALERTS
}

object DeviceListFilters {
  fun matchesGroup(item: DeviceListItem, group: DeviceListGroup, now: Long): Boolean {
    return when (group) {
      DeviceListGroup.ALL -> true
      DeviceListGroup.STARRED -> item.starred
      DeviceListGroup.ACTIVE -> LiveDeviceWindow.isLive(item.lastSeen, now)
      DeviceListGroup.ALERTS -> item.matchesEnabledAlert && LiveDeviceWindow.isLive(item.lastSeen, now)
    }
  }

  internal fun filterAndSort(
    items: List<DeviceListItem>,
    options: DeviceListFilterOptions,
    now: Long
  ): List<DeviceListItem> {
    val query = options.query.trim().lowercase(Locale.ROOT)
    val normalizedAddressFragment = BluetoothAddressTools.normalizeFilterFragment(query)
    val filtered = items.filter { item ->
      val matchesQuery = query.isEmpty() ||
        item.searchText.contains(query) ||
        (
          normalizedAddressFragment != null &&
            item.normalizedAddress?.contains(normalizedAddressFragment) == true
          )
      matchesQuery &&
        matchesGroup(item, options.group, now) &&
        (!options.liveOnly || LiveDeviceWindow.isLive(item.lastSeen, now)) &&
        (!options.unknownOnly || item.displayName.isNullOrBlank()) &&
        (!options.starredOnly || item.starred)
    }

    return when (options.sortMode) {
      SortMode.RECENT -> filtered.sortedWith(
        compareByDescending<DeviceListItem> { it.sortTimestamp }
          .thenBy { it.deviceKey }
      )
      SortMode.STRONGEST -> filtered.sortedWith(
        compareByDescending<DeviceListItem> { it.lastRssi }
          .thenByDescending { it.sortTimestamp }
          .thenBy { it.deviceKey }
      )
      SortMode.NAME -> filtered.sortedWith(
        compareBy<DeviceListItem> { it.sortName }
          .thenBy { it.deviceKey }
      )
    }
  }
}
