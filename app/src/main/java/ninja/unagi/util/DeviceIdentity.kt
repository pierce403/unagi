package ninja.unagi.util

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
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
  val nameSource: DeviceNameSource
)

data class ObservationMetadata(
  val source: String? = null,
  val transport: ObservedTransport = ObservedTransport.UNKNOWN,
  val advertisedName: String? = null,
  val systemName: String? = null,
  val nameSource: DeviceNameSource? = null,
  val vendorName: String? = null,
  val vendorSource: String? = null,
  val vendorConfidence: VendorConfidence = VendorConfidence.NONE,
  val locallyAdministeredAddress: Boolean? = null,
  val normalizedAddress: String? = null,
  val addressType: PassiveAddressType = PassiveAddressType.UNKNOWN,
  val rawAndroidAddressType: Int? = null,
  val deviceType: Int? = null,
  val deviceTypeLabel: String? = null,
  val bondState: Int? = null,
  val bondStateLabel: String? = null,
  val serviceUuids: List<String> = emptyList(),
  val serviceData: Map<String, String> = emptyMap(),
  val manufacturerData: Map<Int, String> = emptyMap(),
  val advertiseFlags: Int? = null,
  val txPowerLevel: Int? = null,
  val resultTxPower: Int? = null,
  val connectable: Boolean? = null,
  val legacy: Boolean? = null,
  val dataStatus: Int? = null,
  val primaryPhy: Int? = null,
  val secondaryPhy: Int? = null,
  val advertisingSid: Int? = null,
  val periodicAdvertisingInterval: Int? = null,
  val appearance: Int? = null,
  val appearanceLabel: String? = null,
  val classicMajorClass: Int? = null,
  val classicMajorClassLabel: String? = null,
  val classicDeviceClass: Int? = null,
  val classicDeviceClassLabel: String? = null,
  val passiveDecoderHints: List<String> = emptyList(),
  val classificationFingerprint: String? = null,
  val classificationCategory: String? = null,
  val classificationLabel: String? = null,
  val classificationConfidence: ClassificationConfidence = ClassificationConfidence.UNKNOWN,
  val classificationEvidence: List<String> = emptyList(),
  val tpmsModel: String? = null,
  val tpmsSensorId: String? = null,
  val tpmsPressureKpa: Double? = null,
  val tpmsTemperatureC: Double? = null,
  val tpmsBatteryOk: Boolean? = null,
  val tpmsFrequencyMhz: Double? = null,
  val tpmsSnr: Double? = null
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
  val vendorConfidenceLabel: String?,
  val nameSourceLabel: String?,
  val addressLabel: String?,
  val addressTypeLabel: String?,
  val advertisedName: String?,
  val systemName: String?,
  val classificationLabel: String?,
  val classificationConfidenceLabel: String?,
  val classificationEvidence: List<String>,
  val classificationFingerprint: String?,
  val metadataSummary: MetadataSummary
)

object ObservedIdentityResolver {
  fun forBle(
    advertisedName: String?,
    systemName: String?
  ): ObservedIdentity {
    val cleanAdvertisedName = cleanName(advertisedName)
    val cleanSystemName = cleanName(systemName)
    return ObservedIdentity(
      displayName = cleanAdvertisedName ?: cleanSystemName,
      advertisedName = cleanAdvertisedName,
      systemName = cleanSystemName,
      nameSource = when {
        cleanAdvertisedName != null -> DeviceNameSource.BLE_ADVERTISED
        cleanSystemName != null -> DeviceNameSource.BLUETOOTH_DEVICE
        else -> DeviceNameSource.UNKNOWN
      }
    )
  }

  fun forClassic(
    systemName: String?
  ): ObservedIdentity {
    val cleanSystemName = cleanName(systemName)
    return ObservedIdentity(
      displayName = cleanSystemName,
      advertisedName = null,
      systemName = cleanSystemName,
      nameSource = if (cleanSystemName != null) {
        DeviceNameSource.BLUETOOTH_DEVICE
      } else {
        DeviceNameSource.UNKNOWN
      }
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
        transport = ObservedTransport.fromMetadataValue(
          json.optStringOrNull("transport") ?: json.optStringOrNull("source")?.lowercase()
        ),
        advertisedName = json.optStringOrNull("advertisedName"),
        systemName = json.optStringOrNull("systemName"),
        nameSource = DeviceNameSource.fromMetadataValue(json.optStringOrNull("nameSource")),
        vendorName = json.optStringOrNull("vendorName"),
        vendorSource = json.optStringOrNull("vendorSource"),
        vendorConfidence = VendorConfidence.fromMetadataValue(json.optStringOrNull("vendorConfidence")),
        locallyAdministeredAddress = json.optBooleanOrNull("locallyAdministeredAddress"),
        normalizedAddress = json.optStringOrNull("normalizedAddress"),
        addressType = PassiveAddressType.fromMetadataValue(json.optStringOrNull("addressType")),
        rawAndroidAddressType = json.optIntOrNull("rawAndroidAddressType"),
        deviceType = json.optIntOrNull("deviceType"),
        deviceTypeLabel = json.optStringOrNull("deviceTypeLabel"),
        bondState = json.optIntOrNull("bondState"),
        bondStateLabel = json.optStringOrNull("bondStateLabel"),
        serviceUuids = json.optStringList("serviceUuids"),
        serviceData = json.optStringMap("serviceData"),
        manufacturerData = json.optManufacturerData("manufacturerData"),
        advertiseFlags = json.optIntOrNull("advertiseFlags"),
        txPowerLevel = json.optIntOrNull("txPowerLevel"),
        resultTxPower = json.optIntOrNull("resultTxPower"),
        connectable = json.optBooleanOrNull("connectable"),
        legacy = json.optBooleanOrNull("legacy"),
        dataStatus = json.optIntOrNull("dataStatus"),
        primaryPhy = json.optIntOrNull("primaryPhy"),
        secondaryPhy = json.optIntOrNull("secondaryPhy"),
        advertisingSid = json.optIntOrNull("advertisingSid"),
        periodicAdvertisingInterval = json.optIntOrNull("periodicAdvertisingInterval"),
        appearance = json.optIntOrNull("appearance"),
        appearanceLabel = json.optStringOrNull("appearanceLabel"),
        classicMajorClass = json.optIntOrNull("classicMajorClass"),
        classicMajorClassLabel = json.optStringOrNull("classicMajorClassLabel"),
        classicDeviceClass = json.optIntOrNull("classicDeviceClass"),
        classicDeviceClassLabel = json.optStringOrNull("classicDeviceClassLabel"),
        passiveDecoderHints = json.optStringList("passiveDecoderHints"),
        classificationFingerprint = json.optStringOrNull("classificationFingerprint"),
        classificationCategory = json.optStringOrNull("classificationCategory"),
        classificationLabel = json.optStringOrNull("classificationLabel"),
        classificationConfidence = ClassificationConfidence.fromMetadataValue(
          json.optStringOrNull("classificationConfidence")
        ),
        classificationEvidence = json.optStringList("classificationEvidence"),
        tpmsModel = json.optStringOrNull("tpmsModel"),
        tpmsSensorId = json.optStringOrNull("tpmsSensorId"),
        tpmsPressureKpa = json.optDoubleOrNull("tpmsPressureKpa"),
        tpmsTemperatureC = json.optDoubleOrNull("tpmsTemperatureC"),
        tpmsBatteryOk = json.optBooleanOrNull("tpmsBatteryOk"),
        tpmsFrequencyMhz = json.optDoubleOrNull("tpmsFrequencyMhz"),
        tpmsSnr = json.optDoubleOrNull("tpmsSnr")
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

  private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) {
      return null
    }
    return optInt(key)
  }

  private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) {
      return null
    }
    return optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() }
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

  private fun JSONObject.optStringMap(key: String): Map<String, String> {
    val jsonObject = optJSONObject(key) ?: return emptyMap()
    val map = mutableMapOf<String, String>()
    val keys = jsonObject.keys()
    while (keys.hasNext()) {
      val rawKey = keys.next()
      val value = jsonObject.optString(rawKey)
        .uppercase()
        .filter { it in '0'..'9' || it in 'A'..'F' }
        .takeIf { it.isNotEmpty() }
        ?: continue
      map[rawKey] = value
    }
    return map.toMap()
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

object PassiveMetadataInterpreter {
  fun summarize(
    metadata: ObservationMetadata,
    assignedNumbers: BluetoothAssignedNumbersRegistry
  ): MetadataSummary {
    val manufacturerEntries = metadata.manufacturerData.toSortedMap().map { (companyId, payloadHex) ->
      val companyName = assignedNumbers.companyName(companyId)
      val companyCode = assignedNumbers.companyCode(companyId)
      ManufacturerEntry(
        companyName = companyName,
        companyCode = companyCode,
        payloadHex = payloadHex,
        payloadBytes = max(1, payloadHex.length / 2)
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

    val serviceDataEntries = metadata.serviceData.entries
      .mapNotNull { (uuid, payloadHex) ->
        val serviceCode = assignedNumbers.serviceCode(uuid) ?: return@mapNotNull null
        ServiceDataEntry(
          serviceName = assignedNumbers.serviceName(uuid),
          serviceCode = serviceCode,
          payloadBytes = max(1, payloadHex.length / 2)
        )
      }

    val listLabels = buildList {
      metadata.classificationLabel?.takeIf { it.isNotBlank() }?.let { label ->
        val confidence = metadata.classificationConfidence
          .takeIf { it != ClassificationConfidence.UNKNOWN }
          ?.label
        add(if (confidence != null) "Likely: $label ($confidence)" else "Likely: $label")
      }
      if (metadata.tpmsPressureKpa != null || metadata.tpmsTemperatureC != null) {
        val parts = buildList {
          metadata.tpmsPressureKpa?.let { add("%.1f kPa (%.1f PSI)".format(it, it * 0.145038)) }
          metadata.tpmsTemperatureC?.let { add("%.0f°C".format(it)) }
        }
        if (parts.isNotEmpty()) add(parts.joinToString(" / "))
      }
      manufacturerEntries.firstOrNull()?.let { first ->
        val label = first.companyName ?: first.companyCode
        add(if (manufacturerEntries.size == 1) "Mfr: $label" else "Mfr: $label +${manufacturerEntries.size - 1}")
      }
      metadata.passiveDecoderHints.firstOrNull()?.let { hint ->
        add("Hint: $hint")
      }
      serviceEntries.firstOrNull()?.let { first ->
        val label = first.serviceName ?: first.serviceCode
        add(if (serviceEntries.size == 1) "Svc: $label" else "Svc: $label +${serviceEntries.size - 1}")
      }
    }

    val detailLines = buildList {
      metadata.vendorSource?.let { source ->
        val confidence = metadata.vendorConfidence
          .takeIf { it != VendorConfidence.NONE }
          ?.label
          ?.let { " ($it)" }
          .orEmpty()
        add("Vendor source: $source$confidence")
      }
      metadata.deviceTypeLabel?.let { add("Device type: $it") }
      metadata.bondStateLabel?.let { add("Bond state: $it") }
      metadata.appearanceLabel?.let { label ->
        val raw = metadata.appearance?.let { " (0x${it.toString(16).uppercase().padStart(4, '0')})" }.orEmpty()
        add("Appearance: $label$raw")
      }
      metadata.classificationLabel?.let { label ->
        val confidence = metadata.classificationConfidence
          .takeIf { it != ClassificationConfidence.UNKNOWN }
          ?.label
          ?.let { " ($it)" }
          .orEmpty()
        add("Likely classification: $label$confidence")
      }
      if (metadata.passiveDecoderHints.isNotEmpty()) {
        add("Passive hints: ${metadata.passiveDecoderHints.joinToString("; ")}")
      }
      if (metadata.classificationEvidence.isNotEmpty()) {
        add("Classification evidence: ${metadata.classificationEvidence.joinToString(", ")}")
      }
      if (manufacturerEntries.isNotEmpty()) {
        add(
          "BLE manufacturer data: " + manufacturerEntries.joinToString("; ") { entry ->
            val label = entry.companyName?.let { "$it (${entry.companyCode})" } ?: entry.companyCode
            "$label, ${entry.payloadBytes} B, payload=${entry.payloadHex}"
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
      if (serviceDataEntries.isNotEmpty()) {
        add(
          "BLE service data: " + serviceDataEntries.joinToString(", ") { entry ->
            val label = entry.serviceName?.let { "$it (${entry.serviceCode})" } ?: entry.serviceCode
            "$label, ${entry.payloadBytes} B"
          }
        )
      }
      metadata.tpmsModel?.let { add("TPMS protocol: $it") }
      metadata.tpmsSensorId?.let { add("Sensor ID: $it") }
      metadata.tpmsPressureKpa?.let { kpa ->
        add("Pressure: %.1f kPa (%.1f PSI)".format(kpa, kpa * 0.145038))
      }
      metadata.tpmsTemperatureC?.let { c ->
        add("Temperature: %.1f°C (%.1f°F)".format(c, c * 9.0 / 5.0 + 32.0))
      }
      metadata.tpmsBatteryOk?.let { add("Battery: ${if (it) "OK" else "Low"}") }
      metadata.tpmsFrequencyMhz?.let { add("Frequency: %.2f MHz".format(it)) }
      metadata.tpmsSnr?.let { add("SNR: %.1f dB".format(it)) }
      metadata.classicMajorClassLabel?.let { add("Classic major class: $it") }
      metadata.classicDeviceClassLabel?.let { add("Classic device class: $it") }
      if (metadata.connectable != null || metadata.legacy != null) {
        add(
          buildString {
            append("Advertising:")
            metadata.connectable?.let { append(" connectable=$it") }
            metadata.legacy?.let { append(" legacy=$it") }
            metadata.advertiseFlags?.let { append(" flags=0x${it.toString(16).uppercase()}") }
          }.trim()
        )
      }
      if (metadata.primaryPhy != null || metadata.secondaryPhy != null || metadata.advertisingSid != null) {
        add(
          buildString {
            append("PHY:")
            metadata.primaryPhy?.let { append(" primary=${formatPhy(it)}") }
            metadata.secondaryPhy?.let { append(" secondary=${formatPhy(it)}") }
            metadata.advertisingSid?.let { append(" sid=${if (it >= 0) it else "none"}") }
            metadata.periodicAdvertisingInterval?.let { append(" periodic=$it") }
          }.trim()
        )
      }
    }

    val searchTerms = buildSet {
      metadata.vendorName?.let(::add)
      metadata.vendorSource?.let(::add)
      metadata.classificationLabel?.let(::add)
      metadata.classificationEvidence.forEach(::add)
      metadata.passiveDecoderHints.forEach(::add)
      manufacturerEntries.forEach { entry ->
        add(entry.companyCode)
        entry.companyName?.let(::add)
      }
      serviceEntries.forEach { entry ->
        add(entry.serviceCode)
        entry.serviceName?.let(::add)
      }
      serviceDataEntries.forEach { entry ->
        add(entry.serviceCode)
        entry.serviceName?.let(::add)
      }
      metadata.deviceTypeLabel?.let(::add)
      metadata.addressType.label.let(::add)
      metadata.tpmsModel?.let(::add)
      metadata.tpmsSensorId?.let(::add)
      if (metadata.tpmsModel != null) add("TPMS")
    }

    val titleFallback = when {
      metadata.classificationLabel != null -> "Likely ${metadata.classificationLabel}"
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

  private fun formatPhy(value: Int): String {
    return when (value) {
      BluetoothDevice.PHY_LE_1M -> "LE 1M"
      BluetoothDevice.PHY_LE_2M -> "LE 2M"
      BluetoothDevice.PHY_LE_CODED -> "LE Coded"
      else -> value.toString()
    }
  }

  private data class ManufacturerEntry(
    val companyName: String?,
    val companyCode: String,
    val payloadHex: String,
    val payloadBytes: Int
  )

  private data class ServiceEntry(
    val serviceName: String?,
    val serviceCode: String
  )

  private data class ServiceDataEntry(
    val serviceName: String?,
    val serviceCode: String,
    val payloadBytes: Int
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
    return present(
      displayName = displayName,
      address = address,
      metadata = ObservationMetadataParser.parse(metadataJson),
      vendorRegistry = vendorRegistry,
      assignedNumbers = assignedNumbers
    )
  }

  fun present(
    displayName: String?,
    address: String?,
    metadata: ObservationMetadata,
    vendorRegistry: VendorPrefixRegistry,
    assignedNumbers: BluetoothAssignedNumbersRegistry
  ): DevicePresentation {
    val addressInsight = PassiveAddressResolver.resolve(
      address = metadata.normalizedAddress ?: address,
      rawAndroidAddressType = metadata.rawAndroidAddressType
    )
    val vendorHint = if (!metadata.vendorName.isNullOrBlank()) {
      PassiveVendorHint(
        vendorName = metadata.vendorName,
        vendorSource = metadata.vendorSource,
        confidence = metadata.vendorConfidence
      )
    } else {
      PassiveVendorResolver.resolve(
        addressInsight = addressInsight,
        assignedNumbers = assignedNumbers,
        vendorRegistry = vendorRegistry,
        manufacturerData = metadata.manufacturerData,
        serviceUuids = metadata.serviceUuids,
        displayName = displayName ?: metadata.advertisedName ?: metadata.systemName
      )
    }

    val classificationCategory = when {
      !metadata.classificationCategory.isNullOrBlank() ->
        DeviceCategory.fromMetadataValue(metadata.classificationCategory)
      !metadata.classificationLabel.isNullOrBlank() ->
        DeviceCategory.fromLabel(metadata.classificationLabel)
      else -> DeviceCategory.UNKNOWN
    }

    val classification = if (
      classificationCategory != DeviceCategory.UNKNOWN ||
      metadata.classificationEvidence.isNotEmpty() ||
      metadata.classificationConfidence != ClassificationConfidence.UNKNOWN
    ) {
      DeviceClassification(
        category = classificationCategory,
        confidence = metadata.classificationConfidence,
        evidence = metadata.classificationEvidence
      )
    } else {
      DeviceClassificationEngine.classify(
        metadata = ClassificationMetadata(
          transport = metadata.transport,
          addressType = metadata.addressType,
          manufacturerData = metadata.manufacturerData,
          serviceUuids = metadata.serviceUuids,
          serviceData = metadata.serviceData,
          appearance = metadata.appearance,
          classicMajorClass = metadata.classicMajorClass,
          classicDeviceClass = metadata.classicDeviceClass,
          displayName = displayName ?: metadata.advertisedName ?: metadata.systemName
        ),
        assignedNumbers = assignedNumbers
      )
    }
    val passiveDecoderHints = if (metadata.passiveDecoderHints.isNotEmpty()) {
      metadata.passiveDecoderHints
    } else {
      PassiveVendorDecoderRegistry.decode(
        PassiveDecoderContext(
          displayName = displayName ?: metadata.advertisedName ?: metadata.systemName,
          vendorName = vendorHint.vendorName ?: metadata.vendorName,
          manufacturerData = metadata.manufacturerData,
          serviceUuids = metadata.serviceUuids,
          serviceData = metadata.serviceData,
          addressType = metadata.addressType.takeIf { it != PassiveAddressType.UNKNOWN }
            ?: addressInsight.addressType
        )
      )
    }
    val metadataSummary = PassiveMetadataInterpreter.summarize(
      metadata = metadata.copy(
        vendorName = vendorHint.vendorName ?: metadata.vendorName,
        vendorSource = vendorHint.vendorSource ?: metadata.vendorSource,
        vendorConfidence = if (metadata.vendorConfidence == VendorConfidence.NONE) {
          vendorHint.confidence
        } else {
          metadata.vendorConfidence
        },
        passiveDecoderHints = passiveDecoderHints,
        classificationCategory = metadata.classificationCategory
          ?: classification.category.metadataValue.takeIf { classification.category != DeviceCategory.UNKNOWN },
        classificationLabel = metadata.classificationLabel
          ?: classification.category.label.takeIf { classification.category != DeviceCategory.UNKNOWN },
        classificationConfidence = if (metadata.classificationConfidence == ClassificationConfidence.UNKNOWN) {
          classification.confidence
        } else {
          metadata.classificationConfidence
        },
        classificationEvidence = if (metadata.classificationEvidence.isEmpty()) classification.evidence else metadata.classificationEvidence
      ),
      assignedNumbers = assignedNumbers
    )

    return DevicePresentation(
      title = Formatters.formatName(
        name = displayName,
        vendorName = vendorHint.vendorName,
        fallbackName = metadataSummary.titleFallback
      ),
      vendorName = vendorHint.vendorName,
      vendorSource = vendorHint.vendorSource,
      vendorConfidenceLabel = vendorHint.confidence
        .takeIf { it != VendorConfidence.NONE }
        ?.label,
      nameSourceLabel = metadata.nameSource?.label?.takeIf { !displayName.isNullOrBlank() },
      addressLabel = formatAddress(addressInsight.normalizedAddress ?: metadata.normalizedAddress ?: VendorPrefixRegistry.normalizeAddress(address)),
      addressTypeLabel = metadata.addressType
        .takeIf { it != PassiveAddressType.UNKNOWN }
        ?.label
        ?: addressInsight.addressType.label.takeIf { it != PassiveAddressType.UNKNOWN.label },
      advertisedName = metadata.advertisedName,
      systemName = metadata.systemName,
      classificationLabel = metadata.classificationLabel
        ?: classification.category.label.takeIf { classification.category != DeviceCategory.UNKNOWN },
      classificationConfidenceLabel = metadata.classificationConfidence
        .takeIf { it != ClassificationConfidence.UNKNOWN }
        ?.label
        ?: classification.confidence.takeIf { it != ClassificationConfidence.UNKNOWN }?.label,
      classificationEvidence = if (metadata.classificationEvidence.isNotEmpty()) {
        metadata.classificationEvidence
      } else {
        classification.evidence
      },
      classificationFingerprint = metadata.classificationFingerprint,
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
