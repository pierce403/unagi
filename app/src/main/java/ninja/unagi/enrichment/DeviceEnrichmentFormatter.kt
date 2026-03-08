package ninja.unagi.enrichment

import ninja.unagi.data.DeviceEnrichmentEntity
import ninja.unagi.util.BluetoothAssignedNumbersRegistry
import ninja.unagi.util.Formatters
import org.json.JSONArray

object DeviceEnrichmentFormatter {
  fun formatForDetail(
    enrichment: DeviceEnrichmentEntity?,
    assignedNumbers: BluetoothAssignedNumbersRegistry
  ): String {
    if (enrichment == null) {
      return "No active BLE query results yet."
    }

    val services = parseServices(enrichment.servicesPresentJson)
    val serviceLabels = services.map { uuid ->
      assignedNumbers.serviceName(uuid)?.let { "$it (${assignedNumbers.serviceCode(uuid)})" }
        ?: assignedNumbers.serviceCode(uuid)
        ?: uuid
    }

    return buildString {
      appendLine("Last queried: ${Formatters.formatTimestamp(enrichment.lastQueryTimestamp)}")
      appendLine("Query method: ${enrichment.queryMethod}")
      appendLine("DIS available: ${enrichment.disAvailable}")
      appendLine("DIS read status: ${enrichment.disReadStatus}")
      enrichment.connectDurationMs?.let { appendLine("Connect duration: ${it} ms") }
      appendLine("Services discovered: ${enrichment.servicesDiscovered}")
      appendLine("Characteristic reads: ${enrichment.characteristicReadSuccessCount} success / ${enrichment.characteristicReadFailureCount} failed")
      enrichment.finalGattStatus?.let { appendLine("Final GATT status: $it") }
      if (serviceLabels.isNotEmpty()) {
        appendLine("Services: ${serviceLabels.joinToString(", ")}")
      }
      enrichment.manufacturerName?.let { appendLine("Manufacturer name: $it") }
      enrichment.modelNumber?.let { appendLine("Model number: $it") }
      enrichment.serialNumber?.let { appendLine("Serial number: $it") }
      enrichment.hardwareRevision?.let { appendLine("Hardware revision: $it") }
      enrichment.firmwareRevision?.let { appendLine("Firmware revision: $it") }
      enrichment.softwareRevision?.let { appendLine("Software revision: $it") }
      enrichment.systemId?.let { appendLine("System ID: $it") }
      if (
        enrichment.pnpVendorIdSource != null ||
        enrichment.pnpVendorId != null ||
        enrichment.pnpProductId != null ||
        enrichment.pnpProductVersion != null
      ) {
        appendLine(
          "PnP ID: source=${formatVendorIdSource(enrichment.pnpVendorIdSource)} " +
            "vendor=${formatHex(enrichment.pnpVendorId)} " +
            "product=${formatHex(enrichment.pnpProductId)} " +
            "version=${formatHex(enrichment.pnpProductVersion)}"
        )
      }
      enrichment.errorMessage?.let { appendLine("Error: $it") }
      enrichment.errorCode?.let { appendLine("Error code: $it") }
    }.trim()
  }

  fun parseServices(servicesPresentJson: String?): List<String> {
    if (servicesPresentJson.isNullOrBlank()) {
      return emptyList()
    }
    return runCatching {
      val array = JSONArray(servicesPresentJson)
      buildList {
        for (index in 0 until array.length()) {
          val value = array.optString(index).trim()
          if (value.isNotEmpty()) {
            add(value)
          }
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun formatVendorIdSource(source: Int?): String {
    return when (source) {
      1 -> "Bluetooth SIG"
      2 -> "USB IF"
      null -> "n/a"
      else -> source.toString()
    }
  }

  private fun formatHex(value: Int?): String {
    return value?.let { "0x${it.toString(16).uppercase().padStart(4, '0')}" } ?: "n/a"
  }
}
