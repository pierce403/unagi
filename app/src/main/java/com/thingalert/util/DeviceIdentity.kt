package com.thingalert.util

import kotlin.math.max
import org.json.JSONObject

enum class DeviceNameSource(val metadataValue: String, val label: String) {
  BLE_ADVERTISED("ble_advertised", "BLE advertised name"),
  BLUETOOTH_DEVICE("bluetooth_device", "Bluetooth device name"),
  UNKNOWN("unknown", "Unknown");

  companion object {
    fun fromMetadataValue(value: String?): DeviceNameSource? {
      return entries.firstOrNull { it.metadataValue == value }
    }
  }
}

data class ObservedIdentity(
  val displayName: String?,
  val advertisedName: String?,
  val systemName: String?,
  val nameSource: DeviceNameSource,
  val vendorName: String?,
  val vendorSource: String?,
  val locallyAdministeredAddress: Boolean
)

data class ObservationMetadata(
  val source: String? = null,
  val advertisedName: String? = null,
  val systemName: String? = null,
  val nameSource: DeviceNameSource? = null,
  val vendorName: String? = null,
  val vendorSource: String? = null,
  val locallyAdministeredAddress: Boolean? = null,
  val serviceUuids: List<String> = emptyList(),
  val manufacturerData: Map<Int, String> = emptyMap()
)

data class MetadataSummary(
  val titleFallback: String? = null,
  val listLabels: List<String> = emptyList(),
  val detailLines: List<String> = emptyList(),
  val searchTerms: Set<String> = emptySet()
)

data class DevicePresentation(
  val title: String,
  val vendorName: String?,
  val vendorSource: String?,
  val nameSourceLabel: String?,
  val addressLabel: String?,
  val addressTypeLabel: String?,
  val advertisedName: String?,
  val systemName: String?,
  val metadataSummary: MetadataSummary
)

object ObservedIdentityResolver {
  fun forBle(
    advertisedName: String?,
    systemName: String?,
    address: String?,
    vendorRegistry: VendorPrefixRegistry
  ): ObservedIdentity {
    val cleanAdvertisedName = cleanName(advertisedName)
    val cleanSystemName = cleanName(systemName)
    val resolution = vendorRegistry.resolve(address)
    return ObservedIdentity(
      displayName = cleanAdvertisedName ?: cleanSystemName,
      advertisedName = cleanAdvertisedName,
      systemName = cleanSystemName,
      nameSource = when {
        cleanAdvertisedName != null -> DeviceNameSource.BLE_ADVERTISED
        cleanSystemName != null -> DeviceNameSource.BLUETOOTH_DEVICE
        else -> DeviceNameSource.UNKNOWN
      },
      vendorName = resolution?.vendorName,
      vendorSource = resolution?.vendorSource,
      locallyAdministeredAddress = resolution?.locallyAdministered ?: false
    )
  }

  fun forClassic(
    systemName: String?,
    address: String?,
    vendorRegistry: VendorPrefixRegistry
  ): ObservedIdentity {
    val cleanSystemName = cleanName(systemName)
    val resolution = vendorRegistry.resolve(address)
    return ObservedIdentity(
      displayName = cleanSystemName,
      advertisedName = null,
      systemName = cleanSystemName,
      nameSource = if (cleanSystemName != null) {
        DeviceNameSource.BLUETOOTH_DEVICE
      } else {
        DeviceNameSource.UNKNOWN
      },
      vendorName = resolution?.vendorName,
      vendorSource = resolution?.vendorSource,
      locallyAdministeredAddress = resolution?.locallyAdministered ?: false
    )
  }

  private fun cleanName(name: String?): String? {
    return name?.trim()?.takeIf { it.isNotEmpty() }
  }
}

object ObservationMetadataParser {
  fun parse(metadataJson: String?): ObservationMetadata {
    if (metadataJson.isNullOrBlank()) {
      return ObservationMetadata()
    }

    return try {
      val json = JSONObject(metadataJson)
      ObservationMetadata(
        source = json.optStringOrNull("source"),
        advertisedName = json.optStringOrNull("advertisedName"),
        systemName = json.optStringOrNull("systemName"),
        nameSource = DeviceNameSource.fromMetadataValue(json.optStringOrNull("nameSource")),
        vendorName = json.optStringOrNull("vendorName"),
        vendorSource = json.optStringOrNull("vendorSource"),
        locallyAdministeredAddress = json.optBooleanOrNull("locallyAdministeredAddress"),
        serviceUuids = json.optStringList("serviceUuids"),
        manufacturerData = json.optManufacturerData("manufacturerData")
      )
    } catch (_: Exception) {
      ObservationMetadata()
    }
  }

  private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) {
      return null
    }
    return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
  }

  private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) {
      return null
    }
    return optBoolean(key)
  }

  private fun JSONObject.optStringList(key: String): List<String> {
    val jsonArray = optJSONArray(key) ?: return emptyList()
    return buildList {
      for (index in 0 until jsonArray.length()) {
        val value = jsonArray.optString(index).trim().takeIf { it.isNotEmpty() && it != "null" }
        if (value != null) {
          add(value)
        }
      }
    }.distinct()
  }

  private fun JSONObject.optManufacturerData(key: String): Map<Int, String> {
    val jsonObject = optJSONObject(key) ?: return emptyMap()
    val manufacturerData = mutableMapOf<Int, String>()
    val keys = jsonObject.keys()
    while (keys.hasNext()) {
      val rawKey = keys.next()
      val companyId = rawKey.toIntOrNull() ?: continue
      val payload = jsonObject.optString(rawKey)
        .uppercase()
        .filter { it in '0'..'9' || it in 'A'..'F' }
        .takeIf { it.isNotEmpty() }
        ?: continue
      manufacturerData[companyId] = payload
    }
    return manufacturerData.toMap()
  }
}

object BleMetadataInterpreter {
  fun summarize(
    metadata: ObservationMetadata,
    assignedNumbers: BluetoothAssignedNumbersRegistry
  ): MetadataSummary {
    val manufacturerEntries = metadata.manufacturerData.toSortedMap().map { (companyId, payloadHex) ->
      val companyName = assignedNumbers.companyName(companyId)
      val companyCode = assignedNumbers.companyCode(companyId)
      val payloadBytes = max(1, payloadHex.length / 2)
      ManufacturerEntry(
        companyName = companyName,
        companyCode = companyCode,
        payloadBytes = payloadBytes
      )
    }

    val serviceEntries = metadata.serviceUuids
      .distinct()
      .mapNotNull { rawUuid ->
        val serviceCode = assignedNumbers.serviceCode(rawUuid) ?: return@mapNotNull null
        ServiceEntry(
          serviceName = assignedNumbers.serviceName(rawUuid),
          serviceCode = serviceCode
        )
      }

    val listLabels = buildList {
      manufacturerEntries.firstOrNull()?.let { first ->
        val label = first.companyName ?: first.companyCode
        add(
          if (manufacturerEntries.size == 1) {
            "Mfr: $label"
          } else {
            "Mfr: $label +${manufacturerEntries.size - 1}"
          }
        )
      }

      serviceEntries.firstOrNull()?.let { first ->
        val label = first.serviceName ?: first.serviceCode
        add(
          if (serviceEntries.size == 1) {
            "Svc: $label"
          } else {
            "Svc: $label +${serviceEntries.size - 1}"
          }
        )
      }
    }

    val detailLines = buildList {
      if (manufacturerEntries.isNotEmpty()) {
        add(
          "BLE manufacturer data: " + manufacturerEntries.joinToString("; ") { entry ->
            val label = entry.companyName?.let { "$it (${entry.companyCode})" } ?: entry.companyCode
            "$label, ${entry.payloadBytes} B"
          }
        )
      }

      if (serviceEntries.isNotEmpty()) {
        add(
          "BLE service UUIDs: " + serviceEntries.joinToString(", ") { entry ->
            entry.serviceName?.let { "${it} (${entry.serviceCode})" } ?: entry.serviceCode
          }
        )
      }
    }

    val searchTerms = buildSet {
      manufacturerEntries.forEach { entry ->
        add(entry.companyCode)
        entry.companyName?.let(::add)
      }
      serviceEntries.forEach { entry ->
        add(entry.serviceCode)
        entry.serviceName?.let(::add)
      }
    }

    val titleFallback = when {
      manufacturerEntries.isNotEmpty() -> {
        val label = manufacturerEntries.first().companyName ?: manufacturerEntries.first().companyCode
        "BLE device: $label"
      }

      serviceEntries.isNotEmpty() -> {
        val label = serviceEntries.first().serviceName ?: serviceEntries.first().serviceCode
        "BLE device: $label"
      }

      else -> null
    }

    return MetadataSummary(
      titleFallback = titleFallback,
      listLabels = listLabels,
      detailLines = detailLines,
      searchTerms = searchTerms
    )
  }

  private data class ManufacturerEntry(
    val companyName: String?,
    val companyCode: String,
    val payloadBytes: Int
  )

  private data class ServiceEntry(
    val serviceName: String?,
    val serviceCode: String
  )
}

object DeviceIdentityPresenter {
  fun present(
    displayName: String?,
    address: String?,
    metadataJson: String?,
    vendorRegistry: VendorPrefixRegistry,
    assignedNumbers: BluetoothAssignedNumbersRegistry
  ): DevicePresentation {
    val metadata = ObservationMetadataParser.parse(metadataJson)
    val resolution = vendorRegistry.resolve(address)
    val vendorName = metadata.vendorName ?: resolution?.vendorName
    val vendorSource = metadata.vendorSource ?: resolution?.vendorSource
    val locallyAdministeredAddress = metadata.locallyAdministeredAddress ?: resolution?.locallyAdministered ?: false
    val metadataSummary = BleMetadataInterpreter.summarize(metadata, assignedNumbers)
    return DevicePresentation(
      title = Formatters.formatName(displayName, vendorName, metadataSummary.titleFallback),
      vendorName = vendorName,
      vendorSource = vendorSource,
      nameSourceLabel = metadata.nameSource?.label?.takeIf { !displayName.isNullOrBlank() },
      addressLabel = formatAddress(resolution?.normalizedAddress ?: VendorPrefixRegistry.normalizeAddress(address)),
      addressTypeLabel = if (locallyAdministeredAddress) {
        "Locally administered / randomized address"
      } else {
        null
      },
      advertisedName = metadata.advertisedName,
      systemName = metadata.systemName,
      metadataSummary = metadataSummary
    )
  }

  private fun formatAddress(normalizedAddress: String?): String? {
    return normalizedAddress
      ?.chunked(2)
      ?.joinToString(":")
      ?.takeIf { it.isNotEmpty() }
  }
}
