package ninja.unagi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTpmsDecoderTest {
  @Test
  fun `returns empty for non-TPMS BLE device`() {
    val context = PassiveDecoderContext(
      displayName = "JBL Flip 5",
      vendorName = "Harman",
      manufacturerData = mapOf(0x0057 to "0102030405"),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.PUBLIC
    )
    val hints = BleTpmsDecoder.decode(context)
    assertTrue(hints.isEmpty())
  }

  @Test
  fun `detects TPMS from name pattern`() {
    val context = PassiveDecoderContext(
      displayName = "TPMS_FL",
      vendorName = null,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.PUBLIC
    )
    val hints = BleTpmsDecoder.decode(context)
    assertEquals(1, hints.size)
    assertTrue(hints[0].contains("name pattern"))
  }

  @Test
  fun `detects TPMS with TPS_ prefix in name`() {
    val context = PassiveDecoderContext(
      displayName = "TPS_AABBCCDD",
      vendorName = null,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.PUBLIC
    )
    val hints = BleTpmsDecoder.decode(context)
    assertTrue(hints.isNotEmpty())
  }

  @Test
  fun `detects TPMS with BLE_TPMS name`() {
    val context = PassiveDecoderContext(
      displayName = "BLE_TPMS_01",
      vendorName = null,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.PUBLIC
    )
    val hints = BleTpmsDecoder.decode(context)
    assertTrue(hints.isNotEmpty())
  }

  @Test
  fun `detects TPMS with 0xFFFF company ID and matching name`() {
    val context = PassiveDecoderContext(
      displayName = "TPMS_Sensor",
      vendorName = null,
      manufacturerData = mapOf(0xFFFF to "0102030405060708"),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.PUBLIC
    )
    val hints = BleTpmsDecoder.decode(context)
    assertEquals(1, hints.size)
    assertTrue(hints[0].contains("manufacturer data"))
  }

  @Test
  fun `ignores 0xFFFF company ID without TPMS name`() {
    val context = PassiveDecoderContext(
      displayName = "Unknown Device",
      vendorName = null,
      manufacturerData = mapOf(0xFFFF to "0102030405060708"),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.PUBLIC
    )
    val hints = BleTpmsDecoder.decode(context)
    assertTrue(hints.isEmpty())
  }

  @Test
  fun `name matching is case insensitive`() {
    val context = PassiveDecoderContext(
      displayName = "My_Tpms_Monitor",
      vendorName = null,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.PUBLIC
    )
    val hints = BleTpmsDecoder.decode(context)
    assertTrue(hints.isNotEmpty())
  }

  @Test
  fun `returns empty for null name and no manufacturer data`() {
    val context = PassiveDecoderContext(
      displayName = null,
      vendorName = null,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.UNKNOWN
    )
    val hints = BleTpmsDecoder.decode(context)
    assertTrue(hints.isEmpty())
  }

  @Test
  fun `detects tire keyword in name`() {
    val context = PassiveDecoderContext(
      displayName = "TireMoni TM-100",
      vendorName = null,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      addressType = PassiveAddressType.PUBLIC
    )
    val hints = BleTpmsDecoder.decode(context)
    assertTrue(hints.isNotEmpty())
  }
}
