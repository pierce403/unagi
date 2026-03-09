package ninja.unagi.ui

data class DeviceListItem(
  val deviceKey: String,
  val displayName: String?,
  val displayTitle: String,
  val metaLine: String,
  val searchText: String,
  val sortTimestamp: Long,
  val lastSeen: Long,
  val lastRssi: Int,
  val sightingsCount: Int,
  val starred: Boolean,
  val lastAddress: String?,
  val vendorName: String?,
  val sharedFromGroupIds: String? = null
) {
  val isShared: Boolean get() = sharedFromGroupIds != null
}
