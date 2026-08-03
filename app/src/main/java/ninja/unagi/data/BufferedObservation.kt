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
    return merge(from(observation))
  }

  fun merge(observation: BufferedObservation): BufferedObservation {
    require(deviceKey == observation.deviceKey) {
      "Cannot merge observation for ${observation.deviceKey} into $deviceKey"
    }

    val incomingIsLatest = observation.lastTimestamp >= lastTimestamp

    return BufferedObservation(
      deviceKey = deviceKey,
      name = if (incomingIsLatest) observation.name ?: name else name ?: observation.name,
      address = if (incomingIsLatest) observation.address ?: address else address ?: observation.address,
      firstTimestamp = minOf(firstTimestamp, observation.firstTimestamp),
      lastTimestamp = maxOf(lastTimestamp, observation.lastTimestamp),
      observationCount = observationCount + observation.observationCount,
      lastRssi = if (incomingIsLatest) observation.lastRssi else lastRssi,
      rssiMin = minOf(rssiMin, observation.rssiMin),
      rssiMax = maxOf(rssiMax, observation.rssiMax),
      rssiSum = rssiSum + observation.rssiSum,
      metadataJson = if (incomingIsLatest) {
        observation.metadataJson ?: metadataJson
      } else {
        metadataJson ?: observation.metadataJson
      }
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
