package ninja.unagi.util

import java.util.Locale

data class PassiveDecoderContext(
  val displayName: String?,
  val vendorName: String?,
  val manufacturerData: Map<Int, String>,
  val serviceUuids: List<String>,
  val serviceData: Map<String, String>,
  val addressType: PassiveAddressType
)

fun interface PassiveVendorDecoder {
  fun decode(context: PassiveDecoderContext): List<String>
}

object PassiveVendorDecoderRegistry {
  private val decoders: List<PassiveVendorDecoder> = listOf(
    ApplePassiveVendorDecoder,
    GooglePassiveVendorDecoder,
    MicrosoftPassiveVendorDecoder,
    SamsungPassiveVendorDecoder,
    NordicPassiveVendorDecoder,
    TilePassiveVendorDecoder
  )

  fun decode(context: PassiveDecoderContext): List<String> {
    return decoders
      .flatMap { decoder -> decoder.decode(context) }
      .map(String::trim)
      .filter(String::isNotEmpty)
      .distinct()
  }
}

private object ApplePassiveVendorDecoder : PassiveVendorDecoder {
  override fun decode(context: PassiveDecoderContext): List<String> {
    val hints = mutableListOf<String>()
    val serviceKeys = normalizedServices(context)
    val hasAppleCompany = context.manufacturerData.containsKey(0x004C) ||
      context.vendorName?.contains("Apple", ignoreCase = true) == true

    if (hasAppleCompany) {
      hints += "Apple ecosystem payload"
    }
    if (context.manufacturerData[0x004C]?.startsWith("0215") == true) {
      hints += "iBeacon-format manufacturer data"
    }
    if ("FD44" in serviceKeys) {
      hints += "Find My / tracker-style service"
    }
    if (containsName(context.displayName, "airpods", "airtag", "beats")) {
      hints += "Apple accessory naming pattern"
    }
    return hints
  }
}

private object GooglePassiveVendorDecoder : PassiveVendorDecoder {
  override fun decode(context: PassiveDecoderContext): List<String> {
    val hints = mutableListOf<String>()
    val serviceKeys = normalizedServices(context)
    val hasGoogleCompany = context.manufacturerData.keys.any { it == 0x00E0 || it == 0x018E } ||
      context.vendorName?.contains("Google", ignoreCase = true) == true

    if (hasGoogleCompany) {
      hints += "Google ecosystem payload"
    }
    if ("FE2C" in serviceKeys) {
      hints += "Fast Pair advertiser"
    }
    if ("FEAA" in serviceKeys) {
      hints += "Eddystone beacon payload"
    }
    if (containsName(context.displayName, "pixel", "nest")) {
      hints += "Google accessory naming pattern"
    }
    return hints
  }
}

private object MicrosoftPassiveVendorDecoder : PassiveVendorDecoder {
  override fun decode(context: PassiveDecoderContext): List<String> {
    val hints = mutableListOf<String>()
    val serviceKeys = normalizedServices(context)
    val hasMicrosoftCompany = context.manufacturerData.containsKey(0x0006) ||
      context.vendorName?.contains("Microsoft", ignoreCase = true) == true

    if (hasMicrosoftCompany) {
      hints += "Microsoft ecosystem payload"
    }
    if (hasMicrosoftCompany && "1812" in serviceKeys) {
      hints += "Likely HID-style Microsoft accessory"
    }
    if (containsName(context.displayName, "surface", "xbox")) {
      hints += "Microsoft accessory naming pattern"
    }
    return hints
  }
}

private object SamsungPassiveVendorDecoder : PassiveVendorDecoder {
  override fun decode(context: PassiveDecoderContext): List<String> {
    val hints = mutableListOf<String>()
    val hasSamsungCompany = context.manufacturerData.containsKey(0x0075) ||
      context.vendorName?.contains("Samsung", ignoreCase = true) == true

    if (hasSamsungCompany) {
      hints += "Samsung ecosystem payload"
    }
    if (containsName(context.displayName, "galaxy", "buds", "smarttag")) {
      hints += "Samsung accessory naming pattern"
    }
    return hints
  }
}

private object NordicPassiveVendorDecoder : PassiveVendorDecoder {
  override fun decode(context: PassiveDecoderContext): List<String> {
    val hints = mutableListOf<String>()
    val hasNordicCompany = context.manufacturerData.containsKey(0x0059) ||
      context.vendorName?.contains("Nordic", ignoreCase = true) == true

    if (hasNordicCompany) {
      hints += "Nordic SDK / dev-board style payload"
    }
    if (containsName(context.displayName, "nrf", "thingy", "devkit")) {
      hints += "Nordic dev-board naming pattern"
    }
    return hints
  }
}

private object TilePassiveVendorDecoder : PassiveVendorDecoder {
  override fun decode(context: PassiveDecoderContext): List<String> {
    val hints = mutableListOf<String>()
    val serviceKeys = normalizedServices(context)
    val hasTileCompany = context.manufacturerData.containsKey(0x067C) ||
      context.vendorName?.contains("Tile", ignoreCase = true) == true

    if (hasTileCompany) {
      hints += "Tile tracker-style payload"
    }
    if ("FEEC" in serviceKeys || "FEED" in serviceKeys) {
      hints += "Tile service UUID"
    }
    if (containsName(context.displayName, "tile")) {
      hints += "Tile naming pattern"
    }
    if (context.addressType == PassiveAddressType.RESOLVABLE_PRIVATE ||
      context.addressType == PassiveAddressType.NON_RESOLVABLE_PRIVATE
    ) {
      if (hasTileCompany || "FEEC" in serviceKeys || "FEED" in serviceKeys) {
        hints += "Tracker-style randomized address"
      }
    }
    return hints
  }
}

private fun normalizedServices(context: PassiveDecoderContext): Set<String> {
  return buildSet {
    context.serviceUuids
      .mapNotNull(BluetoothAssignedNumbersRegistry::normalizeServiceKey)
      .forEach(::add)
    context.serviceData.keys
      .mapNotNull(BluetoothAssignedNumbersRegistry::normalizeServiceKey)
      .forEach(::add)
  }
}

private fun containsName(displayName: String?, vararg tokens: String): Boolean {
  val lower = displayName?.trim()?.lowercase(Locale.US) ?: return false
  return tokens.any(lower::contains)
}
