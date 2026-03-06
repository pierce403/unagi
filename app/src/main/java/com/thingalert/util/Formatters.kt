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

  fun formatName(name: String?): String {
    return if (name.isNullOrBlank()) "Unknown device" else name
  }
}
