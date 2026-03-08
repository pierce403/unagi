package ninja.unagi.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Formatters {
  fun formatTimestamp(timestamp: Long): String {
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
      .withLocale(Locale.getDefault())
      .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
  }

  fun formatRssi(rssi: Int): String {
    return "RSSI: $rssi dBm"
  }

  fun formatName(
    name: String?,
    vendorName: String? = null,
    fallbackName: String? = null
  ): String {
    if (!name.isNullOrBlank()) {
      return name
    }
    return if (!vendorName.isNullOrBlank()) {
      "$vendorName device"
    } else if (!fallbackName.isNullOrBlank()) {
      fallbackName
    } else {
      "Unknown device"
    }
  }

  fun formatSightingsCount(count: Int): String {
    return if (count == 1) {
      "1 sighting"
    } else {
      "$count sightings"
    }
  }
}
