package com.thingalert.util

import java.text.DateFormat
import java.util.Date

object Formatters {
  private val dateTimeFormatter: DateFormat = DateFormat.getDateTimeInstance(
    DateFormat.MEDIUM,
    DateFormat.MEDIUM
  )

  fun formatTimestamp(timestamp: Long): String {
    return dateTimeFormatter.format(Date(timestamp))
  }

  fun formatRssi(rssi: Int): String {
    return "RSSI: $rssi dBm"
  }

  fun formatName(name: String?, vendorName: String? = null): String {
    if (!name.isNullOrBlank()) {
      return name
    }
    return if (vendorName.isNullOrBlank()) {
      "Unknown device"
    } else {
      "$vendorName device"
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
