package com.thingalert.util

import android.content.Context
import java.io.InputStream

class BluetoothAssignedNumbersRegistry private constructor(
  private val companyNamesById: Map<Int, String>,
  private val serviceNamesByKey: Map<String, String>
) {
  fun companyName(companyId: Int): String? {
    return companyNamesById[companyId and COMPANY_ID_MASK]
  }

  fun companyCode(companyId: Int): String {
    return "0x${(companyId and COMPANY_ID_MASK).toString(16).uppercase().padStart(4, '0')}"
  }

  fun serviceName(uuid: String?): String? {
    val key = normalizeServiceKey(uuid) ?: return null
    return serviceNamesByKey[key]
  }

  fun serviceCode(uuid: String?): String? {
    val key = normalizeServiceKey(uuid) ?: return null
    return when (key.length) {
      4, 8 -> "0x$key"
      32 -> key.chunked(8).let { chunks ->
        "${chunks[0]}-${chunks[1].take(4)}-${chunks[1].drop(4)}-${chunks[2].take(4)}-${chunks[2].drop(4)}${chunks[3]}"
      }
      else -> key
    }
  }

  companion object {
    private const val COMPANY_ID_MASK = 0xFFFF
    private const val BLUETOOTH_BASE_UUID_SUFFIX = "00001000800000805F9B34FB"

    fun fromInputStreams(
      companyInputStream: InputStream,
      serviceInputStream: InputStream
    ): BluetoothAssignedNumbersRegistry {
      val companyLines = CompressedTextAsset.prepareInputStream(companyInputStream)
        .bufferedReader()
        .use { reader -> reader.readLines().asSequence() }
      val serviceLines = CompressedTextAsset.prepareInputStream(serviceInputStream)
        .bufferedReader()
        .use { reader -> reader.readLines().asSequence() }
      return fromLines(companyLines, serviceLines)
    }

    fun fromLines(
      companyLines: Sequence<String>,
      serviceLines: Sequence<String>
    ): BluetoothAssignedNumbersRegistry {
      val companyNamesById = mutableMapOf<Int, String>()
      companyLines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
          return@forEach
        }
        val parts = trimmed.split('|', limit = 2)
        if (parts.size != 2) {
          return@forEach
        }
        val companyId = normalizeHex(parts[0])?.toIntOrNull(16) ?: return@forEach
        val name = parts[1].trim()
        if (name.isNotEmpty()) {
          companyNamesById[companyId] = name
        }
      }

      val serviceNamesByKey = mutableMapOf<String, String>()
      serviceLines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
          return@forEach
        }
        val parts = trimmed.split('|', limit = 2)
        if (parts.size != 2) {
          return@forEach
        }
        val key = normalizeHex(parts[0]) ?: return@forEach
        val name = parts[1].trim()
        if (name.isNotEmpty()) {
          serviceNamesByKey[key] = name
        }
      }

      return BluetoothAssignedNumbersRegistry(
        companyNamesById = companyNamesById.toMap(),
        serviceNamesByKey = serviceNamesByKey.toMap()
      )
    }

    fun normalizeServiceKey(uuid: String?): String? {
      val normalized = normalizeHex(uuid) ?: return null
      return when (normalized.length) {
        4, 8 -> normalized
        32 -> {
          val prefix = normalized.take(8)
          val suffix = normalized.drop(8)
          if (suffix == BLUETOOTH_BASE_UUID_SUFFIX) {
            if (prefix.startsWith("0000")) {
              prefix.drop(4)
            } else {
              prefix
            }
          } else {
            normalized
          }
        }

        else -> null
      }
    }

    private fun normalizeHex(value: String?): String? {
      return value
        ?.uppercase()
        ?.filter { it in '0'..'9' || it in 'A'..'F' }
        ?.takeIf { it.isNotEmpty() }
    }
  }
}

object BluetoothAssignedNumbersProvider {
  @Volatile
  private var cached: BluetoothAssignedNumbersRegistry? = null

  fun get(context: Context): BluetoothAssignedNumbersRegistry {
    val existing = cached
    if (existing != null) {
      return existing
    }

    return synchronized(this) {
      cached ?: load(context).also { loaded ->
        cached = loaded
      }
    }
  }

  private fun load(context: Context): BluetoothAssignedNumbersRegistry {
    val companyInputStream = CompressedTextAsset.openFirstAvailable(context, COMPANY_ASSET_CANDIDATES)
      ?: error("Missing Bluetooth company identifier asset: ${COMPANY_ASSET_CANDIDATES.joinToString()}")
    val serviceInputStream = CompressedTextAsset.openFirstAvailable(context, SERVICE_ASSET_CANDIDATES)
      ?: error("Missing Bluetooth service UUID asset: ${SERVICE_ASSET_CANDIDATES.joinToString()}")

    companyInputStream.use { companyStream ->
      serviceInputStream.use { serviceStream ->
        return BluetoothAssignedNumbersRegistry.fromInputStreams(companyStream, serviceStream)
      }
    }
  }

  private val COMPANY_ASSET_CANDIDATES = listOf(
    "bluetooth_company_identifiers.txt.gz",
    "bluetooth_company_identifiers.txt"
  )

  private val SERVICE_ASSET_CANDIDATES = listOf(
    "bluetooth_service_uuids.txt.gz",
    "bluetooth_service_uuids.txt"
  )
}
