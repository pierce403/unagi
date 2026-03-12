package ninja.unagi.ui

import ninja.unagi.data.DeviceEntity
import ninja.unagi.data.DeviceEnrichmentEntity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class DeviceJsonExport(
  val fileName: String,
  val json: String
)

object DeviceDetailExport {
  fun build(
    device: DeviceEntity,
    enrichment: DeviceEnrichmentEntity?
  ): DeviceJsonExport {
    return DeviceJsonExport(
      fileName = defaultFileName(device.lastAddress),
      json = buildJson(device, enrichment)
    )
  }

  fun buildJson(
    device: DeviceEntity,
    enrichment: DeviceEnrichmentEntity?
  ): String {
    val root = JSONObject()
    root.put("device", deviceToJson(device))
    root.put("lastMetadata", parseJsonValue(device.lastMetadataJson) ?: JSONObject.NULL)
    root.put("activeBleEnrichment", enrichment?.let(::enrichmentToJson) ?: JSONObject.NULL)
    return root.toString(2)
  }

  fun defaultFileName(address: String?): String {
    val suffix = address
      ?.trim()
      ?.uppercase()
      ?.replace(Regex("[^A-Z0-9:_-]"), "_")
      ?.takeUnless { it.isBlank() }
      ?: "UNKNOWN-DEVICE"
    return "unagi-$suffix.txt"
  }

  private fun deviceToJson(device: DeviceEntity): JSONObject {
    return JSONObject().apply {
      put("deviceKey", device.deviceKey)
      put("displayName", device.displayName ?: JSONObject.NULL)
      put("lastAddress", device.lastAddress ?: JSONObject.NULL)
      put("firstSeen", device.firstSeen)
      put("lastSeen", device.lastSeen)
      put("lastSightingAt", device.lastSightingAt)
      put("sightingsCount", device.sightingsCount)
      put("observationCount", device.observationCount)
      put("lastRssi", device.lastRssi)
      put("rssiMin", device.rssiMin)
      put("rssiMax", device.rssiMax)
      put("rssiAvg", device.rssiAvg)
      put("lastMetadataJson", device.lastMetadataJson ?: JSONObject.NULL)
      put("starred", device.starred)
      put("userCustomName", device.userCustomName ?: JSONObject.NULL)
    }
  }

  private fun enrichmentToJson(enrichment: DeviceEnrichmentEntity): JSONObject {
    return JSONObject().apply {
      put("deviceKey", enrichment.deviceKey)
      put("lastQueryTimestamp", enrichment.lastQueryTimestamp)
      put("queryMethod", enrichment.queryMethod)
      put("servicesPresentJson", enrichment.servicesPresentJson ?: JSONObject.NULL)
      put("servicesPresent", parseServices(enrichment.servicesPresentJson))
      put("disAvailable", enrichment.disAvailable)
      put("disReadStatus", enrichment.disReadStatus)
      put("manufacturerName", enrichment.manufacturerName ?: JSONObject.NULL)
      put("modelNumber", enrichment.modelNumber ?: JSONObject.NULL)
      put("serialNumber", enrichment.serialNumber ?: JSONObject.NULL)
      put("hardwareRevision", enrichment.hardwareRevision ?: JSONObject.NULL)
      put("firmwareRevision", enrichment.firmwareRevision ?: JSONObject.NULL)
      put("softwareRevision", enrichment.softwareRevision ?: JSONObject.NULL)
      put("systemId", enrichment.systemId ?: JSONObject.NULL)
      put("pnpVendorIdSource", enrichment.pnpVendorIdSource ?: JSONObject.NULL)
      put("pnpVendorId", enrichment.pnpVendorId ?: JSONObject.NULL)
      put("pnpProductId", enrichment.pnpProductId ?: JSONObject.NULL)
      put("pnpProductVersion", enrichment.pnpProductVersion ?: JSONObject.NULL)
      put("errorCode", enrichment.errorCode ?: JSONObject.NULL)
      put("errorMessage", enrichment.errorMessage ?: JSONObject.NULL)
      put("connectDurationMs", enrichment.connectDurationMs ?: JSONObject.NULL)
      put("servicesDiscovered", enrichment.servicesDiscovered)
      put("characteristicReadSuccessCount", enrichment.characteristicReadSuccessCount)
      put("characteristicReadFailureCount", enrichment.characteristicReadFailureCount)
      put("finalGattStatus", enrichment.finalGattStatus ?: JSONObject.NULL)
    }
  }

  private fun parseJsonValue(rawJson: String?): Any? {
    if (rawJson.isNullOrBlank()) {
      return null
    }
    return runCatching { JSONTokener(rawJson).nextValue() }.getOrNull() ?: rawJson
  }

  private fun parseServices(rawJson: String?): JSONArray {
    if (rawJson.isNullOrBlank()) {
      return JSONArray()
    }
    return runCatching { JSONArray(rawJson) }.getOrElse { JSONArray() }
  }
}
