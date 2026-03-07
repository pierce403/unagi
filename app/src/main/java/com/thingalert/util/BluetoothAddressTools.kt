package com.thingalert.util

object BluetoothAddressTools {
  fun normalizeAddress(address: String?): String? {
    return VendorPrefixRegistry.normalizeAddress(address)
  }

  fun normalizeFilterFragment(query: String?): String? {
    val trimmed = query?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
    val looksLikeAddress = trimmed.contains(':') || trimmed.contains('-') || trimmed.any(Char::isDigit)
    if (!looksLikeAddress) {
      return null
    }
    val normalized = trimmed.filter { it in '0'..'9' || it in 'A'..'F' }
    return normalized.takeIf { it.isNotEmpty() }
  }

  fun formatAddress(normalizedAddress: String?): String? {
    return normalizeAddress(normalizedAddress)
      ?.chunked(2)
      ?.joinToString(":")
      ?.takeIf { it.isNotEmpty() }
  }
}
