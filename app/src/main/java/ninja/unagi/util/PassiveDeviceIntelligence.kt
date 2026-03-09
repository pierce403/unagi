package ninja.unagi.util

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import java.security.MessageDigest
import java.util.Locale

enum class ObservedTransport(val metadataValue: String, val label: String) {
  BLE("ble", "BLE"),
  CLASSIC("classic", "Classic"),
  DUAL("dual", "Dual"),
  SDR("sdr", "SDR"),
  UNKNOWN("unknown", "Unknown");

  companion object {
    fun fromMetadataValue(value: String?): ObservedTransport {
      return entries.firstOrNull { it.metadataValue == value } ?: UNKNOWN
    }
  }
}

enum class VendorConfidence(val metadataValue: String, val label: String) {
  HIGH("high", "high"),
  MEDIUM("medium", "medium"),
  WEAK("weak", "weak"),
  NONE("none", "none");

  companion object {
    fun fromMetadataValue(value: String?): VendorConfidence {
      return entries.firstOrNull { it.metadataValue == value } ?: NONE
    }
  }
}

enum class ClassificationConfidence(
  val metadataValue: String,
  val label: String
) {
  HIGH("high", "high confidence"),
  MEDIUM("medium", "medium confidence"),
  LOW("low", "low confidence"),
  UNKNOWN("unknown", "unknown confidence");

  companion object {
    fun fromMetadataValue(value: String?): ClassificationConfidence {
      return entries.firstOrNull { it.metadataValue == value } ?: UNKNOWN
    }
  }
}

enum class DeviceCategory(
  val metadataValue: String,
  val label: String
) {
  PHONE("phone", "phone"),
  LAPTOP("laptop", "laptop"),
  AUDIO("audio", "audio accessory"),
  SPEAKER("speaker", "speaker"),
  TRACKER("tracker", "tracker / tag"),
  WEARABLE("wearable", "wearable"),
  DEV_BOARD("dev_board", "dev board"),
  HID("hid", "HID"),
  BEACON("beacon", "beacon"),
  AUTOMOTIVE("automotive", "automotive"),
  MEDICAL_FITNESS("medical_fitness", "medical / fitness"),
  TPMS_SENSOR("tpms_sensor", "TPMS sensor"),
  UNKNOWN("unknown", "unknown");

  companion object {
    fun fromMetadataValue(value: String?): DeviceCategory {
      return entries.firstOrNull { it.metadataValue == value } ?: UNKNOWN
    }

    fun fromLabel(value: String?): DeviceCategory {
      return entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
  }
}

enum class PassiveAddressType(
  val metadataValue: String,
  val label: String,
  val trustsOui: Boolean
) {
  PUBLIC("public", "Public address", true),
  RANDOM_STATIC("random_static", "Random static address", false),
  RESOLVABLE_PRIVATE("resolvable_private", "Resolvable private address", false),
  NON_RESOLVABLE_PRIVATE("non_resolvable_private", "Non-resolvable private address", false),
  ANONYMOUS("anonymous", "Anonymous address", false),
  LOCALLY_ADMINISTERED_OR_RANDOMIZED(
    "locally_administered_or_randomized",
    "Locally administered / randomized / unknown",
    false
  ),
  UNKNOWN("unknown", "Unknown address type", false);

  companion object {
    fun fromMetadataValue(value: String?): PassiveAddressType {
      return entries.firstOrNull { it.metadataValue == value } ?: UNKNOWN
    }
  }
}

data class PassiveAddressInsight(
  val normalizedAddress: String?,
  val addressType: PassiveAddressType,
  val rawAndroidAddressType: Int?,
  val locallyAdministered: Boolean
)

data class PassiveVendorHint(
  val vendorName: String? = null,
  val vendorSource: String? = null,
  val confidence: VendorConfidence = VendorConfidence.NONE
)

data class DeviceClassification(
  val category: DeviceCategory = DeviceCategory.UNKNOWN,
  val confidence: ClassificationConfidence = ClassificationConfidence.UNKNOWN,
  val evidence: List<String> = emptyList(),
  val score: Int = 0
)

object PassiveAddressResolver {
  fun resolve(address: String?, rawAndroidAddressType: Int?): PassiveAddressInsight {
    val normalizedAddress = VendorPrefixRegistry.normalizeAddress(address)
    val locallyAdministered = VendorPrefixRegistry.isLocallyAdministered(normalizedAddress)
    val addressType = when (rawAndroidAddressType) {
      BluetoothDevice.ADDRESS_TYPE_PUBLIC -> PassiveAddressType.PUBLIC
      BluetoothDevice.ADDRESS_TYPE_RANDOM -> classifyRandomAddress(normalizedAddress)
      BluetoothDevice.ADDRESS_TYPE_ANONYMOUS -> PassiveAddressType.ANONYMOUS
      BluetoothDevice.ADDRESS_TYPE_UNKNOWN, null -> {
        if (locallyAdministered) {
          PassiveAddressType.LOCALLY_ADMINISTERED_OR_RANDOMIZED
        } else {
          PassiveAddressType.UNKNOWN
        }
      }

      else -> PassiveAddressType.UNKNOWN
    }

    return PassiveAddressInsight(
      normalizedAddress = normalizedAddress,
      addressType = addressType,
      rawAndroidAddressType = rawAndroidAddressType,
      locallyAdministered = locallyAdministered
    )
  }

  private fun classifyRandomAddress(normalizedAddress: String?): PassiveAddressType {
    val firstOctet = normalizedAddress?.take(2)?.toIntOrNull(16) ?: return PassiveAddressType.LOCALLY_ADMINISTERED_OR_RANDOMIZED
    return when ((firstOctet shr 6) and 0x03) {
      0b11 -> PassiveAddressType.RANDOM_STATIC
      0b01 -> PassiveAddressType.RESOLVABLE_PRIVATE
      0b00 -> PassiveAddressType.NON_RESOLVABLE_PRIVATE
      else -> PassiveAddressType.LOCALLY_ADMINISTERED_OR_RANDOMIZED
    }
  }
}

object PassiveVendorResolver {
  fun resolve(
    addressInsight: PassiveAddressInsight,
    assignedNumbers: BluetoothAssignedNumbersRegistry,
    vendorRegistry: VendorPrefixRegistry,
    manufacturerData: Map<Int, String>,
    serviceUuids: List<String>,
    displayName: String?
  ): PassiveVendorHint {
    if (addressInsight.addressType.trustsOui) {
      val resolution = vendorRegistry.resolve(addressInsight.normalizedAddress)
      if (!resolution?.vendorName.isNullOrBlank()) {
        return PassiveVendorHint(
          vendorName = resolution?.vendorName,
          vendorSource = resolution?.vendorSource ?: "IEEE OUI",
          confidence = VendorConfidence.HIGH
        )
      }
    }

    val companyId = manufacturerData.keys.sorted().firstOrNull()
    if (companyId != null) {
      val companyName = assignedNumbers.companyName(companyId)
      return PassiveVendorHint(
        vendorName = companyName ?: assignedNumbers.companyCode(companyId),
        vendorSource = "Manufacturer company ID",
        confidence = if (companyName != null) VendorConfidence.MEDIUM else VendorConfidence.WEAK
      )
    }

    val memberServiceVendor = serviceUuids
      .asSequence()
      .mapNotNull { assignedNumbers.serviceName(it) }
      .mapNotNull(::serviceBasedVendorGuess)
      .firstOrNull()
    if (memberServiceVendor != null) {
      return PassiveVendorHint(
        vendorName = memberServiceVendor,
        vendorSource = "Advertised service",
        confidence = VendorConfidence.WEAK
      )
    }

    val nameGuess = nameBasedVendorGuess(displayName)
    return if (nameGuess != null) {
      PassiveVendorHint(
        vendorName = nameGuess,
        vendorSource = "Advertised name",
        confidence = VendorConfidence.WEAK
      )
    } else {
      PassiveVendorHint()
    }
  }

  private fun nameBasedVendorGuess(displayName: String?): String? {
    val lower = displayName?.trim()?.lowercase(Locale.US) ?: return null
    return when {
      lower.contains("airtag") || lower.contains("airpods") -> "Apple"
      lower.contains("galaxy") -> "Samsung"
      lower.contains("pixel") -> "Google"
      lower.contains("tile") -> "Tile"
      else -> null
    }
  }

  private fun serviceBasedVendorGuess(serviceName: String): String? {
    return when {
      serviceName.contains("Apple", ignoreCase = true) -> "Apple"
      serviceName.contains("Google", ignoreCase = true) -> "Google"
      serviceName.contains("Samsung", ignoreCase = true) -> "Samsung"
      serviceName.contains("Microsoft", ignoreCase = true) -> "Microsoft"
      serviceName.contains("Tile", ignoreCase = true) -> "Tile"
      serviceName.contains("Nordic", ignoreCase = true) -> "Nordic"
      else -> null
    }
  }
}

object ClassificationFingerprint {
  fun from(
    addressInsight: PassiveAddressInsight,
    manufacturerData: Map<Int, String>,
    serviceUuids: List<String>,
    serviceData: Map<String, String>,
    appearance: Int?,
    classicMajorClass: Int?,
    classicDeviceClass: Int?,
    displayName: String?
  ): String {
    val token = buildList {
      add("addrType:${addressInsight.addressType.metadataValue}")
      manufacturerData.keys.sorted().forEach { add("company:$it") }
      serviceUuids.mapNotNull(BluetoothAssignedNumbersRegistry::normalizeServiceKey).sorted()
        .forEach { add("svc:$it") }
      serviceData.keys.mapNotNull(BluetoothAssignedNumbersRegistry::normalizeServiceKey).sorted()
        .forEach { add("svcData:$it") }
      appearance?.let { add("appearance:$it") }
      classicMajorClass?.let { add("major:$it") }
      classicDeviceClass?.let { add("device:$it") }
      normalizeNameToken(displayName)?.let { add("name:$it") }
    }.joinToString("|")

    return sha256(token)
  }

  private fun normalizeNameToken(value: String?): String? {
    val cleaned = value
      ?.lowercase(Locale.US)
      ?.replace(Regex("[^a-z0-9]+"), " ")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: return null
    return cleaned.split(' ')
      .filter { it.length >= 3 }
      .take(3)
      .joinToString("_")
      .takeIf { it.isNotEmpty() }
  }

  private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.toByteArray())
    return bytes.joinToString("") { b -> "%02x".format(b) }
  }
}

object DeviceClassificationEngine {
  fun classify(
    metadata: ClassificationMetadata,
    assignedNumbers: BluetoothAssignedNumbersRegistry
  ): DeviceClassification {
    val scorer = ClassificationScorer()

    scoreByTransport(metadata, scorer)
    scoreByAddressType(metadata, scorer)
    scoreByClassicClass(metadata, scorer)
    scoreByAppearance(metadata, scorer)
    scoreByServices(metadata, assignedNumbers, scorer)
    scoreByManufacturer(metadata, assignedNumbers, scorer)
    scoreByName(metadata.displayName, scorer)

    val best = scorer.best() ?: return DeviceClassification()
    return DeviceClassification(
      category = best.category,
      confidence = confidenceForScore(best.score),
      evidence = best.evidence.take(MAX_EVIDENCE),
      score = best.score
    )
  }

  private fun scoreByTransport(metadata: ClassificationMetadata, scorer: ClassificationScorer) {
    when (metadata.transport) {
      ObservedTransport.CLASSIC -> scorer.add(DeviceCategory.AUDIO, 5, "transport:classic")
      ObservedTransport.DUAL -> scorer.add(DeviceCategory.AUDIO, 10, "transport:dual")
      ObservedTransport.SDR -> scorer.add(DeviceCategory.TPMS_SENSOR, 100, "transport:sdr")
      else -> Unit
    }
  }

  private fun scoreByAddressType(metadata: ClassificationMetadata, scorer: ClassificationScorer) {
    when (metadata.addressType) {
      PassiveAddressType.RESOLVABLE_PRIVATE,
      PassiveAddressType.NON_RESOLVABLE_PRIVATE -> scorer.add(DeviceCategory.TRACKER, 20, "address:${metadata.addressType.metadataValue}")
      PassiveAddressType.RANDOM_STATIC -> scorer.add(DeviceCategory.WEARABLE, 10, "address:${metadata.addressType.metadataValue}")
      else -> Unit
    }
  }

  private fun scoreByClassicClass(metadata: ClassificationMetadata, scorer: ClassificationScorer) {
    when (metadata.classicMajorClass) {
      BluetoothClass.Device.Major.COMPUTER -> scorer.add(DeviceCategory.LAPTOP, 80, "classic:computer")
      BluetoothClass.Device.Major.PHONE -> scorer.add(DeviceCategory.PHONE, 90, "classic:phone")
      BluetoothClass.Device.Major.PERIPHERAL -> scorer.add(DeviceCategory.HID, 90, "classic:peripheral")
      BluetoothClass.Device.Major.WEARABLE -> scorer.add(DeviceCategory.WEARABLE, 80, "classic:wearable")
      BluetoothClass.Device.Major.HEALTH -> scorer.add(DeviceCategory.MEDICAL_FITNESS, 85, "classic:health")
      BluetoothClass.Device.Major.AUDIO_VIDEO -> scorer.add(DeviceCategory.AUDIO, 45, "classic:audio_video")
      else -> Unit
    }

    when (metadata.classicDeviceClass) {
      BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
      BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
      BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
      BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
      BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> scorer.add(DeviceCategory.AUDIO, 90, "classic:audio")

      BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> scorer.add(DeviceCategory.SPEAKER, 95, "classic:loudspeaker")
      BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> scorer.add(DeviceCategory.AUTOMOTIVE, 95, "classic:car_audio")
      BluetoothClass.Device.PERIPHERAL_KEYBOARD,
      BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING,
      BluetoothClass.Device.PERIPHERAL_POINTING,
      BluetoothClass.Device.PERIPHERAL_NON_KEYBOARD_NON_POINTING,
      BluetoothClass.Device.TOY_CONTROLLER -> scorer.add(DeviceCategory.HID, 95, "classic:hid")

      BluetoothClass.Device.COMPUTER_LAPTOP -> scorer.add(DeviceCategory.LAPTOP, 95, "classic:laptop")
      BluetoothClass.Device.PHONE_SMART,
      BluetoothClass.Device.PHONE_CELLULAR -> scorer.add(DeviceCategory.PHONE, 95, "classic:smartphone")

      BluetoothClass.Device.HEALTH_GLUCOSE,
      BluetoothClass.Device.HEALTH_PULSE_RATE,
      BluetoothClass.Device.HEALTH_PULSE_OXIMETER,
      BluetoothClass.Device.HEALTH_THERMOMETER,
      BluetoothClass.Device.HEALTH_WEIGHING -> scorer.add(DeviceCategory.MEDICAL_FITNESS, 95, "classic:health_device")

      else -> Unit
    }
  }

  private fun scoreByAppearance(metadata: ClassificationMetadata, scorer: ClassificationScorer) {
    val appearance = metadata.appearance ?: return
    when {
      appearance in 0x03C0..0x03FF -> scorer.add(DeviceCategory.HID, 70, "appearance:hid")
      appearance in 0x0340..0x037F -> scorer.add(DeviceCategory.WEARABLE, 70, "appearance:watch")
      appearance in 0x0380..0x03BF -> scorer.add(DeviceCategory.MEDICAL_FITNESS, 70, "appearance:heart_rate")
      appearance in 0x0940..0x097F -> scorer.add(DeviceCategory.AUDIO, 70, "appearance:audio")
    }
  }

  private fun scoreByServices(
    metadata: ClassificationMetadata,
    assignedNumbers: BluetoothAssignedNumbersRegistry,
    scorer: ClassificationScorer
  ) {
    val normalizedServices = metadata.serviceUuids
      .mapNotNull(BluetoothAssignedNumbersRegistry::normalizeServiceKey)
      .toSet()
    val normalizedServiceData = metadata.serviceData.keys
      .mapNotNull(BluetoothAssignedNumbersRegistry::normalizeServiceKey)
      .toSet()
    val allServices = normalizedServices + normalizedServiceData

    if ("1812" in allServices || "185C" in allServices) {
      scorer.add(DeviceCategory.HID, 90, "service:hid")
    }
    if ("180D" in allServices || "1818" in allServices || "1816" in allServices) {
      scorer.add(DeviceCategory.MEDICAL_FITNESS, 85, "service:fitness")
    }
    if ("FEAA" in allServices) {
      scorer.add(DeviceCategory.BEACON, 95, "service:eddystone")
    }
    if ("FEED" in allServices || "FEEC" in allServices || "FD44" in allServices) {
      scorer.add(DeviceCategory.TRACKER, 95, "service:tracker")
    }
    if ("FE2C" in allServices) {
      scorer.add(DeviceCategory.AUDIO, 40, "service:google_fast_pair")
      scorer.add(DeviceCategory.WEARABLE, 30, "service:google_fast_pair")
    }
    if ("180F" in allServices) {
      scorer.add(DeviceCategory.WEARABLE, 15, "service:battery")
    }

    metadata.serviceUuids.forEach { rawUuid ->
      val serviceName = assignedNumbers.serviceName(rawUuid) ?: return@forEach
      when {
        serviceName.contains("Tile", ignoreCase = true) -> scorer.add(DeviceCategory.TRACKER, 85, "service:${serviceName}")
        serviceName.contains("Google", ignoreCase = true) -> scorer.add(DeviceCategory.AUDIO, 25, "service:${serviceName}")
        serviceName.contains("Apple", ignoreCase = true) -> scorer.add(DeviceCategory.TRACKER, 35, "service:${serviceName}")
      }
    }
  }

  private fun scoreByManufacturer(
    metadata: ClassificationMetadata,
    assignedNumbers: BluetoothAssignedNumbersRegistry,
    scorer: ClassificationScorer
  ) {
    metadata.manufacturerData.keys.forEach { companyId ->
      val companyName = assignedNumbers.companyName(companyId)?.lowercase(Locale.US) ?: return@forEach
      when {
        "tile" in companyName -> scorer.add(DeviceCategory.TRACKER, 90, "company:Tile")
        "nordic" in companyName -> scorer.add(DeviceCategory.DEV_BOARD, 80, "company:Nordic")
        "apple" in companyName -> {
          scorer.add(DeviceCategory.AUDIO, 20, "company:Apple")
          scorer.add(DeviceCategory.TRACKER, 20, "company:Apple")
          scorer.add(DeviceCategory.WEARABLE, 20, "company:Apple")
        }
        "microsoft" in companyName -> scorer.add(DeviceCategory.HID, 25, "company:Microsoft")
        "google" in companyName -> scorer.add(DeviceCategory.WEARABLE, 20, "company:Google")
        "samsung" in companyName -> scorer.add(DeviceCategory.WEARABLE, 20, "company:Samsung")
      }
    }
  }

  private fun scoreByName(displayName: String?, scorer: ClassificationScorer) {
    val lower = displayName?.lowercase(Locale.US)?.trim() ?: return
    when {
      Regex("airpods|earbuds|headphones|headset|buds").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.AUDIO, 95, "name:audio")
      Regex("speaker|boom|soundcore").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.SPEAKER, 95, "name:speaker")
      Regex("airtag|tile|tracker|tag").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.TRACKER, 95, "name:tracker")
      Regex("watch|band|fit|garmin|polar").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.WEARABLE, 85, "name:wearable")
      Regex("keyboard|mouse|trackpad|gamepad|controller").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.HID, 95, "name:hid")
      Regex("esp32|nrf|arduino|devkit|raspberry").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.DEV_BOARD, 95, "name:dev_board")
      Regex("iphone|pixel|galaxy phone|phone").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.PHONE, 80, "name:phone")
      Regex("macbook|laptop|notebook|thinkpad").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.LAPTOP, 85, "name:laptop")
      Regex("tesla|car|auto").containsMatchIn(lower) ->
        scorer.add(DeviceCategory.AUTOMOTIVE, 80, "name:automotive")
    }
  }

  private fun confidenceForScore(score: Int): ClassificationConfidence {
    return when {
      score >= 85 -> ClassificationConfidence.HIGH
      score >= 55 -> ClassificationConfidence.MEDIUM
      score >= 30 -> ClassificationConfidence.LOW
      else -> ClassificationConfidence.UNKNOWN
    }
  }

  private data class CategoryScore(
    val category: DeviceCategory,
    val score: Int,
    val evidence: List<String>
  )

  private class ClassificationScorer {
    private val scores = linkedMapOf<DeviceCategory, Int>()
    private val evidence = linkedMapOf<DeviceCategory, MutableList<String>>()

    fun add(category: DeviceCategory, points: Int, reason: String) {
      scores[category] = (scores[category] ?: 0) + points
      evidence.getOrPut(category) { mutableListOf() }.add(reason)
    }

    fun best(): CategoryScore? {
      val entry = scores.maxByOrNull { it.value } ?: return null
      return CategoryScore(
        category = entry.key,
        score = entry.value,
        evidence = evidence[entry.key].orEmpty()
      )
    }
  }

  private const val MAX_EVIDENCE = 6
}

data class ClassificationMetadata(
  val transport: ObservedTransport,
  val addressType: PassiveAddressType,
  val manufacturerData: Map<Int, String>,
  val serviceUuids: List<String>,
  val serviceData: Map<String, String>,
  val appearance: Int?,
  val classicMajorClass: Int?,
  val classicDeviceClass: Int?,
  val displayName: String?
)
