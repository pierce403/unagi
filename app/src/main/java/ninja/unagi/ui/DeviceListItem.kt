package ninja.unagi.ui

data class DeviceListItem(
  val deviceKey: String,
  val displayName: String?,
  val displayTitle: String,
  val metaLine: String,
  val searchText: String,
  val lastSeen: Long,
  val lastRssi: Int,
  val sightingsCount: Int,
  val lastAddress: String?,
  val vendorName: String?
)
