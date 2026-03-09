package ninja.unagi.sdr

/**
 * Maps rtl_433 TPMS protocol/model names to sensor manufacturer information.
 *
 * Vehicle-make identification is NOT included — a single sensor manufacturer
 * supplies dozens of car brands, so mapping sensor → vehicle is unreliable.
 * The table maps to sensor manufacturers, which IS definitive.
 *
 * Reference: https://github.com/merbanan/rtl_433/tree/master/src/devices
 */
data class TpmsSensorVendor(
  val manufacturer: String,
  val protocol: String
)

object TpmsSensorVendorLookup {
  private val vendors = listOf(
    TpmsSensorVendor("Sensata Technologies (Schrader)", "Schrader"),
    TpmsSensorVendor("Pacific Industrial Co.", "PMV-107J"),
    TpmsSensorVendor("Pacific Industrial Co.", "Toyota"),
    TpmsSensorVendor("Continental AG (VDO)", "Hyundai-VDO"),
    TpmsSensorVendor("Continental AG (VDO)", "Ford"),
    TpmsSensorVendor("Renault (protocol-specific)", "Renault"),
    TpmsSensorVendor("Jansite", "Jansite"),
    TpmsSensorVendor("Jansite", "Jansite-Solar"),
    TpmsSensorVendor("Continental AG (VDO)", "Abarth-124Spider"),
    TpmsSensorVendor("Continental AG (VDO)", "Citroen"),
    TpmsSensorVendor("Continental AG (VDO)", "Peugeot"),
  )

  private val byProtocol: Map<String, TpmsSensorVendor> =
    vendors.associateBy { it.protocol.lowercase() }

  fun lookup(model: String): TpmsSensorVendor? =
    byProtocol[model.lowercase()]
}
