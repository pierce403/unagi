package com.thingalert.scan

data class ObservationInput(
  val name: String?,
  val address: String?,
  val rssi: Int,
  val timestamp: Long,
  val serviceUuids: List<String>,
  val manufacturerData: Map<Int, String>,
  val source: String,
  val advertisedName: String? = null,
  val systemName: String? = null,
  val nameSource: String? = null,
  val vendorName: String? = null,
  val vendorSource: String? = null,
  val locallyAdministeredAddress: Boolean? = null
)
