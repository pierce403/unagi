package ninja.unagi.util

import java.util.Locale

/**
 * Passive vendor decoder that identifies BLE TPMS (tire pressure monitoring)
 * sensors from their advertisement data. Aftermarket BLE TPMS sensors broadcast
 * pressure/temperature in proprietary manufacturer data formats — no standard
 * BLE TPMS profile exists.
 *
 * Detection strategy (layered by confidence):
 * 1. Known manufacturer data company IDs with TPMS-specific payload patterns
 * 2. Device name heuristics ("TPMS", "TPS_", "BLE_TPMS", etc.)
 */
object BleTpmsDecoder : PassiveVendorDecoder {
  // Known BLE company IDs used by aftermarket TPMS sensor chipsets.
  // 0xFFFF is the "unassigned" company ID used by many generic TPMS sensors.
  private val TPMS_COMPANY_IDS = setOf(0xFFFF)

  // Minimum manufacturer data payload length (bytes, hex-encoded = 2 chars per byte)
  // for a plausible TPMS reading (pressure + temperature + sensor ID).
  private const val MIN_TPMS_PAYLOAD_HEX_LEN = 8

  // Name patterns that strongly suggest a BLE TPMS sensor.
  private val TPMS_NAME_PATTERNS = listOf(
    "tpms", "tps_", "ble_tpms", "tire", "bletire"
  )

  override fun decode(context: PassiveDecoderContext): List<String> {
    val hints = mutableListOf<String>()

    // Check manufacturer data for known TPMS company IDs with plausible payload size
    for (companyId in TPMS_COMPANY_IDS) {
      val data = context.manufacturerData[companyId]
      if (data != null && data.length >= MIN_TPMS_PAYLOAD_HEX_LEN) {
        // Generic 0xFFFF company ID is very common — only flag as TPMS if name also matches
        if (companyId == 0xFFFF) {
          if (matchesName(context.displayName)) {
            hints += "BLE TPMS sensor (manufacturer data + name match)"
          }
        } else {
          hints += "BLE TPMS sensor (known company ID)"
        }
      }
    }

    // Name-based fallback (lower confidence, but catches most aftermarket sensors)
    if (hints.isEmpty() && matchesName(context.displayName)) {
      hints += "BLE TPMS sensor (name pattern)"
    }

    return hints
  }

  private fun matchesName(displayName: String?): Boolean {
    val lower = displayName?.trim()?.lowercase(Locale.US) ?: return false
    return TPMS_NAME_PATTERNS.any(lower::contains)
  }
}
