package ninja.unagi.ui

enum class DeviceListGroup {
  ALL,
  STARRED,
  ACTIVE,
  ALERTS
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
}
