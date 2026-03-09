package ninja.unagi.sdr

data class TpmsReading(
  val model: String,
  val sensorId: String,
  val pressureKpa: Double?,
  val temperatureC: Double?,
  val batteryOk: Boolean?,
  val status: Int?,
  val rssi: Double?,
  val snr: Double?,
  val frequencyMhz: Double?,
  val rawJson: String
)
