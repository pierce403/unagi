package ninja.unagi.group

import ninja.unagi.data.AlertRuleEntity
import ninja.unagi.data.DeviceEntity
import ninja.unagi.data.DeviceEnrichmentEntity
import ninja.unagi.data.SightingEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes and deserializes Room entities to/from JSON for affinity group bundles.
 * Fix #11: uses isNull() checks consistently for nullable field deserialization.
 */
object BundleSerializer {

  fun serializePayload(
    devices: List<DeviceEntity>,
    sightings: List<SightingEntity>,
    alertRules: List<AlertRuleEntity>,
    enrichments: List<DeviceEnrichmentEntity>,
    starredDeviceKeys: List<String>
  ): String {
    val root = JSONObject()
    root.put("devices", JSONArray().apply { devices.forEach { put(deviceToJson(it)) } })
    root.put("sightings", JSONArray().apply { sightings.forEach { put(sightingToJson(it)) } })
    root.put("alertRules", JSONArray().apply { alertRules.forEach { put(alertRuleToJson(it)) } })
    root.put("enrichments", JSONArray().apply { enrichments.forEach { put(enrichmentToJson(it)) } })
    root.put("starredDeviceKeys", JSONArray().apply { starredDeviceKeys.forEach { put(it) } })
    return root.toString()
  }

  fun deserializePayload(json: String): BundlePayload {
    val root = JSONObject(json)
    return BundlePayload(
      devices = parseArray(root.optJSONArray("devices")) { deviceFromJson(it) },
      sightings = parseArray(root.optJSONArray("sightings")) { sightingFromJson(it) },
      alertRules = parseArray(root.optJSONArray("alertRules")) { alertRuleFromJson(it) },
      enrichments = parseArray(root.optJSONArray("enrichments")) { enrichmentFromJson(it) },
      starredDeviceKeys = parseStringArray(root.optJSONArray("starredDeviceKeys"))
    )
  }

  // --- Serialization helpers ---

  private fun deviceToJson(d: DeviceEntity) = JSONObject().apply {
    put("deviceKey", d.deviceKey)
    put("displayName", d.displayName ?: JSONObject.NULL)
    put("lastAddress", d.lastAddress ?: JSONObject.NULL)
    put("firstSeen", d.firstSeen)
    put("lastSeen", d.lastSeen)
    put("lastSightingAt", d.lastSightingAt)
    put("sightingsCount", d.sightingsCount)
    put("observationCount", d.observationCount)
    put("lastRssi", d.lastRssi)
    put("rssiMin", d.rssiMin)
    put("rssiMax", d.rssiMax)
    put("rssiAvg", d.rssiAvg)
    put("lastMetadataJson", d.lastMetadataJson ?: JSONObject.NULL)
    put("starred", d.starred)
    put("userCustomName", d.userCustomName ?: JSONObject.NULL)
  }

  private fun sightingToJson(s: SightingEntity) = JSONObject().apply {
    put("deviceKey", s.deviceKey)
    put("timestamp", s.timestamp)
    put("rssi", s.rssi)
    put("name", s.name ?: JSONObject.NULL)
    put("address", s.address ?: JSONObject.NULL)
    put("metadataJson", s.metadataJson ?: JSONObject.NULL)
  }

  private fun alertRuleToJson(r: AlertRuleEntity) = JSONObject().apply {
    put("matchType", r.matchType)
    put("matchPattern", r.matchPattern)
    put("displayValue", r.displayValue)
    put("emoji", r.emoji)
    put("soundPreset", r.soundPreset)
    put("enabled", r.enabled)
    put("createdAt", r.createdAt)
  }

  private fun enrichmentToJson(e: DeviceEnrichmentEntity) = JSONObject().apply {
    put("deviceKey", e.deviceKey)
    put("lastQueryTimestamp", e.lastQueryTimestamp)
    put("queryMethod", e.queryMethod)
    put("servicesPresentJson", e.servicesPresentJson ?: JSONObject.NULL)
    put("disAvailable", e.disAvailable)
    put("disReadStatus", e.disReadStatus)
    put("manufacturerName", e.manufacturerName ?: JSONObject.NULL)
    put("modelNumber", e.modelNumber ?: JSONObject.NULL)
    put("serialNumber", e.serialNumber ?: JSONObject.NULL)
    put("hardwareRevision", e.hardwareRevision ?: JSONObject.NULL)
    put("firmwareRevision", e.firmwareRevision ?: JSONObject.NULL)
    put("softwareRevision", e.softwareRevision ?: JSONObject.NULL)
    put("systemId", e.systemId ?: JSONObject.NULL)
    put("pnpVendorIdSource", e.pnpVendorIdSource ?: JSONObject.NULL)
    put("pnpVendorId", e.pnpVendorId ?: JSONObject.NULL)
    put("pnpProductId", e.pnpProductId ?: JSONObject.NULL)
    put("pnpProductVersion", e.pnpProductVersion ?: JSONObject.NULL)
    put("errorCode", e.errorCode ?: JSONObject.NULL)
    put("errorMessage", e.errorMessage ?: JSONObject.NULL)
    put("connectDurationMs", e.connectDurationMs ?: JSONObject.NULL)
    put("servicesDiscovered", e.servicesDiscovered)
    put("characteristicReadSuccessCount", e.characteristicReadSuccessCount)
    put("characteristicReadFailureCount", e.characteristicReadFailureCount)
    put("finalGattStatus", e.finalGattStatus ?: JSONObject.NULL)
  }

  // --- Deserialization helpers (fix #11: consistent isNull checks) ---

  private fun optNullableString(o: JSONObject, key: String): String? =
    if (o.isNull(key)) null else o.optString(key).ifEmpty { null }

  private fun optNullableInt(o: JSONObject, key: String): Int? =
    if (o.isNull(key)) null else o.optInt(key)

  private fun optNullableLong(o: JSONObject, key: String): Long? =
    if (o.isNull(key)) null else o.optLong(key)

  private fun deviceFromJson(o: JSONObject) = DeviceEntity(
    deviceKey = o.getString("deviceKey"),
    displayName = optNullableString(o, "displayName"),
    lastAddress = optNullableString(o, "lastAddress"),
    firstSeen = o.getLong("firstSeen"),
    lastSeen = o.getLong("lastSeen"),
    lastSightingAt = o.optLong("lastSightingAt", 0),
    sightingsCount = o.optInt("sightingsCount", 0),
    observationCount = o.optInt("observationCount", 0),
    lastRssi = o.optInt("lastRssi", 0),
    rssiMin = o.optInt("rssiMin", 0),
    rssiMax = o.optInt("rssiMax", 0),
    rssiAvg = o.optDouble("rssiAvg", 0.0),
    lastMetadataJson = optNullableString(o, "lastMetadataJson"),
    starred = o.optBoolean("starred", false),
    userCustomName = optNullableString(o, "userCustomName")
  )

  private fun sightingFromJson(o: JSONObject) = SightingEntity(
    deviceKey = o.getString("deviceKey"),
    timestamp = o.getLong("timestamp"),
    rssi = o.optInt("rssi", 0),
    name = optNullableString(o, "name"),
    address = optNullableString(o, "address"),
    metadataJson = optNullableString(o, "metadataJson")
  )

  private fun alertRuleFromJson(o: JSONObject) = AlertRuleEntity(
    matchType = o.getString("matchType"),
    matchPattern = o.getString("matchPattern"),
    displayValue = o.optString("displayValue", ""),
    emoji = o.optString("emoji", ""),
    soundPreset = o.optString("soundPreset", "DEFAULT"),
    enabled = o.optBoolean("enabled", true),
    createdAt = o.optLong("createdAt", System.currentTimeMillis())
  )

  private fun enrichmentFromJson(o: JSONObject) = DeviceEnrichmentEntity(
    deviceKey = o.getString("deviceKey"),
    lastQueryTimestamp = o.getLong("lastQueryTimestamp"),
    queryMethod = o.optString("queryMethod", "UNKNOWN"),
    servicesPresentJson = optNullableString(o, "servicesPresentJson"),
    disAvailable = o.optBoolean("disAvailable", false),
    disReadStatus = o.optString("disReadStatus", "UNKNOWN"),
    manufacturerName = optNullableString(o, "manufacturerName"),
    modelNumber = optNullableString(o, "modelNumber"),
    serialNumber = optNullableString(o, "serialNumber"),
    hardwareRevision = optNullableString(o, "hardwareRevision"),
    firmwareRevision = optNullableString(o, "firmwareRevision"),
    softwareRevision = optNullableString(o, "softwareRevision"),
    systemId = optNullableString(o, "systemId"),
    pnpVendorIdSource = optNullableInt(o, "pnpVendorIdSource"),
    pnpVendorId = optNullableInt(o, "pnpVendorId"),
    pnpProductId = optNullableInt(o, "pnpProductId"),
    pnpProductVersion = optNullableInt(o, "pnpProductVersion"),
    errorCode = optNullableInt(o, "errorCode"),
    errorMessage = optNullableString(o, "errorMessage"),
    connectDurationMs = optNullableLong(o, "connectDurationMs"),
    servicesDiscovered = o.optInt("servicesDiscovered", 0),
    characteristicReadSuccessCount = o.optInt("characteristicReadSuccessCount", 0),
    characteristicReadFailureCount = o.optInt("characteristicReadFailureCount", 0),
    finalGattStatus = optNullableInt(o, "finalGattStatus")
  )

  private fun <T> parseArray(arr: JSONArray?, mapper: (JSONObject) -> T): List<T> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { mapper(arr.getJSONObject(it)) }
  }

  private fun parseStringArray(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { arr.getString(it) }
  }
}

data class BundlePayload(
  val devices: List<DeviceEntity>,
  val sightings: List<SightingEntity>,
  val alertRules: List<AlertRuleEntity>,
  val enrichments: List<DeviceEnrichmentEntity>,
  val starredDeviceKeys: List<String>
)
