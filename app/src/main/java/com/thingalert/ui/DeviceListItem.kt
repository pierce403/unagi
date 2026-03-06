package com.thingalert.ui

data class DeviceListItem(
  val deviceKey: String,
  val displayName: String?,
  val lastSeen: Long,
  val lastRssi: Int,
  val sightingsCount: Int,
  val lastAddress: String?
)
