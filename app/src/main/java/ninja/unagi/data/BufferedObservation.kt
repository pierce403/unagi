package ninja.unagi.data

data class BufferedObservation(
  val deviceKey: String,
  val name: String?,
  val address: String?,
  val firstTimestamp: Long,
  val lastTimestamp: Long,
  val observationCount: Int,
  val lastRssi: Int,
  val rssiMin: Int,
  val rssiMax: Int,
  val rssiSum: Long,
  val metadataJson: String?
) {
  fun merge(observation: DeviceObservation): BufferedObservation {
    require(deviceKey == observation.deviceKey) {
      "Cannot merge observation for ${observation.deviceKey} into $deviceKey"
    }

    return BufferedObservation(
      deviceKey = deviceKey,
      name = observation.name ?: name,
      address = observation.address ?: address,
      firstTimestamp = minOf(firstTimestamp, observation.timestamp),
      lastTimestamp = maxOf(lastTimestamp, observation.timestamp),
      observationCount = observationCount + 1,
      lastRssi = observation.rssi,
      rssiMin = minOf(rssiMin, observation.rssi),
      rssiMax = maxOf(rssiMax, observation.rssi),
      rssiSum = rssiSum + observation.rssi,
      metadataJson = observation.metadataJson ?: metadataJson
    )
  }

  companion object {
    fun from(observation: DeviceObservation): BufferedObservation {
      return BufferedObservation(
        deviceKey = observation.deviceKey,
        name = observation.name,
        address = observation.address,
        firstTimestamp = observation.timestamp,
        lastTimestamp = observation.timestamp,
        observationCount = 1,
        lastRssi = observation.rssi,
        rssiMin = observation.rssi,
        rssiMax = observation.rssi,
        rssiSum = observation.rssi.toLong(),
        metadataJson = observation.metadataJson
      )
    }
  }
}
