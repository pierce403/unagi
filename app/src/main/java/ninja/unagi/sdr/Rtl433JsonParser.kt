package ninja.unagi.sdr

import org.json.JSONObject

object Rtl433JsonParser {
  fun parse(jsonLine: String): TpmsReading? {
    return try {
      val json = JSONObject(jsonLine)
      val type = json.optString("type", "")
      if (!type.equals("TPMS", ignoreCase = true)) return null

      TpmsReading(
        model = json.optString("model", "Unknown"),
        sensorId = json.optString("id", "0x00000000"),
        pressureKpa = json.optDoubleOrNull("pressure_kPa")
          ?: json.optDoubleOrNull("pressure_PSI")?.let { it * 6.89476 }
          ?: json.optDoubleOrNull("pressure_bar")?.let { it * 100.0 },
        temperatureC = json.optDoubleOrNull("temperature_C"),
        batteryOk = json.optBooleanOrNull("battery_ok"),
        status = json.optIntOrNull("status"),
        rssi = json.optDoubleOrNull("rssi"),
        snr = json.optDoubleOrNull("snr"),
        frequencyMhz = json.optDoubleOrNull("freq"),
        rawJson = jsonLine
      )
    } catch (_: Exception) {
      null
    }
  }

  private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() }
  }

  private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when {
      optInt(key, -1) == 1 -> true
      optInt(key, -1) == 0 -> false
      else -> optBoolean(key)
    }
  }

  private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
  }
}
