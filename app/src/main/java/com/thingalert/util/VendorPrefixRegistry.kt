package com.thingalert.util

import android.content.Context
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class VendorResolution(
  val normalizedAddress: String,
  val vendorName: String?,
  val vendorSource: String?,
  val locallyAdministered: Boolean
)

class VendorPrefixRegistry private constructor(
  private val entriesByLength: Map<Int, Map<String, String>>
) {
  fun resolve(address: String?): VendorResolution? {
    val normalizedAddress = normalizeAddress(address) ?: return null
    val locallyAdministered = isLocallyAdministered(normalizedAddress)
    if (locallyAdministered) {
      return VendorResolution(
        normalizedAddress = normalizedAddress,
        vendorName = null,
        vendorSource = null,
        locallyAdministered = true
      )
    }

    for (prefixLength in SUPPORTED_PREFIX_LENGTHS) {
      val vendorName = entriesByLength[prefixLength]?.get(normalizedAddress.take(prefixLength))
      if (!vendorName.isNullOrBlank()) {
        return VendorResolution(
          normalizedAddress = normalizedAddress,
          vendorName = vendorName,
          vendorSource = registryLabel(prefixLength),
          locallyAdministered = false
        )
      }
    }

    return VendorResolution(
      normalizedAddress = normalizedAddress,
      vendorName = null,
      vendorSource = null,
      locallyAdministered = false
    )
  }

  companion object {
    private const val MA_L_LENGTH = 6
    private const val MA_M_LENGTH = 7
    private const val MA_S_LENGTH = 9
    private val SUPPORTED_PREFIX_LENGTHS = listOf(MA_S_LENGTH, MA_M_LENGTH, MA_L_LENGTH)

    fun fromCompressedInputStream(inputStream: InputStream): VendorPrefixRegistry {
      return GZIPInputStream(inputStream).bufferedReader().use { reader ->
        fromLines(reader.lineSequence())
      }
    }

    fun fromLines(lines: Sequence<String>): VendorPrefixRegistry {
      val grouped = SUPPORTED_PREFIX_LENGTHS.associateWith { mutableMapOf<String, String>() }
      lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
          return@forEach
        }

        val parts = trimmed.split('|', limit = 2)
        if (parts.size != 2) {
          return@forEach
        }

        val prefix = normalizeHex(parts[0]) ?: return@forEach
        val vendorName = parts[1].trim()
        if (vendorName.isEmpty()) {
          return@forEach
        }

        grouped[prefix.length]?.set(prefix, vendorName)
      }

      return VendorPrefixRegistry(grouped.mapValues { it.value.toMap() })
    }

    fun normalizeAddress(address: String?): String? {
      val normalized = normalizeHex(address)
      return normalized?.takeIf { it.length >= 6 }
    }

    fun isLocallyAdministered(normalizedAddress: String?): Boolean {
      if (normalizedAddress.isNullOrBlank() || normalizedAddress.length < 2) {
        return false
      }
      val firstOctet = normalizedAddress.take(2).toIntOrNull(16) ?: return false
      return firstOctet and 0x02 != 0
    }

    private fun normalizeHex(value: String?): String? {
      val cleaned = value
        ?.uppercase()
        ?.filter { it in '0'..'9' || it in 'A'..'F' }
        ?.takeIf { it.isNotEmpty() }
      return cleaned
    }

    private fun registryLabel(prefixLength: Int): String {
      return when (prefixLength) {
        MA_S_LENGTH -> "IEEE MA-S"
        MA_M_LENGTH -> "IEEE MA-M"
        else -> "IEEE MA-L"
      }
    }
  }
}

object VendorPrefixRegistryProvider {
  @Volatile
  private var cached: VendorPrefixRegistry? = null

  fun get(context: Context): VendorPrefixRegistry {
    val existing = cached
    if (existing != null) {
      return existing
    }

    return synchronized(this) {
      cached ?: context.applicationContext.assets.open(ASSET_NAME).use { inputStream ->
        VendorPrefixRegistry.fromCompressedInputStream(inputStream).also { loaded ->
          cached = loaded
        }
      }
    }
  }

  private const val ASSET_NAME = "vendor_prefixes.txt.gz"
}
