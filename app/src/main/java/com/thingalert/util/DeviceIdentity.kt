package com.thingalert.util

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
  val locallyAdministeredAddress: Boolean? = null
)

data class DevicePresentation(
  val title: String,
  val vendorName: String?,
  val vendorSource: String?,
  val nameSourceLabel: String?,
  val addressLabel: String?,
  val addressTypeLabel: String?,
  val advertisedName: String?,
  val systemName: String?
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
        locallyAdministeredAddress = json.optBooleanOrNull("locallyAdministeredAddress")
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
}

object DeviceIdentityPresenter {
  fun present(
    displayName: String?,
    address: String?,
    metadataJson: String?,
    vendorRegistry: VendorPrefixRegistry
  ): DevicePresentation {
    val metadata = ObservationMetadataParser.parse(metadataJson)
    val resolution = vendorRegistry.resolve(address)
    val vendorName = metadata.vendorName ?: resolution?.vendorName
    val vendorSource = metadata.vendorSource ?: resolution?.vendorSource
    val locallyAdministeredAddress = metadata.locallyAdministeredAddress ?: resolution?.locallyAdministered ?: false
    return DevicePresentation(
      title = Formatters.formatName(displayName, vendorName),
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
      systemName = metadata.systemName
    )
  }

  private fun formatAddress(normalizedAddress: String?): String? {
    return normalizedAddress
      ?.chunked(2)
      ?.joinToString(":")
      ?.takeIf { it.isNotEmpty() }
  }
}
