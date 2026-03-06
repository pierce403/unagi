package com.thingalert.scan

data class ObservationInput(
  val name: String?,
  val address: String?,
  val rssi: Int,
  val timestamp: Long,
  val serviceUuids: List<String>,
  val manufacturerData: Map<Int, String>,
  val source: String
)
