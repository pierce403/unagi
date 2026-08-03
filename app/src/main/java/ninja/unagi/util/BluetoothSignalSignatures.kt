package ninja.unagi.util

data class BluetoothCompanyServiceSignature(
  val companyId: Int,
  val serviceUuid16: Int
)

object BluetoothSignalSignatures {
  val META_SMART_GLASSES = BluetoothCompanyServiceSignature(
    companyId = 0x01AB,
    serviceUuid16 = 0xFD5F
  )
  const val META_SMART_GLASSES_HINT =
    "Possible Meta smart-glasses family (spoofable same-report 0x01AB + 0xFD5F)"

  private val inputPattern = Regex(
    """^\s*(?:0[xX])?([0-9A-Fa-f]{1,4})\s*\+\s*(?:0[xX])?([0-9A-Fa-f]{1,4})\s*$"""
  )
  private val storagePattern = Regex("""^([0-9A-Fa-f]{4})\+([0-9A-Fa-f]{4})$""")

  fun parseCompanyServiceInput(rawInput: String): BluetoothCompanyServiceSignature? {
    return parse(rawInput, inputPattern)
  }

  fun parseCompanyServiceStorage(pattern: String): BluetoothCompanyServiceSignature? {
    return parse(pattern, storagePattern)
  }

  fun companyServiceStorageValue(signature: BluetoothCompanyServiceSignature): String {
    return "${hex4(signature.companyId).lowercase()}+${hex4(signature.serviceUuid16).lowercase()}"
  }

  fun companyServiceDisplayValue(signature: BluetoothCompanyServiceSignature): String {
    return "0x${hex4(signature.companyId)} + 0x${hex4(signature.serviceUuid16)}"
  }

  fun matchesCompanyService(
    signature: BluetoothCompanyServiceSignature,
    manufacturerCompanyIds: Set<Int>,
    serviceUuids: Iterable<String>
  ): Boolean {
    if (signature.companyId !in manufacturerCompanyIds) {
      return false
    }

    val expectedService = hex4(signature.serviceUuid16)
    return serviceUuids.any { uuid ->
      BluetoothAssignedNumbersRegistry.normalizeServiceKey(uuid) == expectedService
    }
  }

  private fun parse(
    value: String,
    pattern: Regex
  ): BluetoothCompanyServiceSignature? {
    val match = pattern.matchEntire(value) ?: return null
    val companyId = match.groupValues[1].toIntOrNull(16) ?: return null
    val serviceUuid16 = match.groupValues[2].toIntOrNull(16) ?: return null
    return BluetoothCompanyServiceSignature(companyId, serviceUuid16)
  }

  private fun hex4(value: Int): String {
    return (value and 0xFFFF).toString(16).uppercase().padStart(4, '0')
  }
}
