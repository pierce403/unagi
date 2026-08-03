package ninja.unagi.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveVendorDecoderTest {
  @Test
  fun `KARR decoder requires QT prefix and a nonblank suffix`() {
    assertTrue(
      decoderHints("QT 123456").contains(BluetoothNameSignatures.KARR_HINT)
    )
    assertTrue(
      decoderHints("qt serial-7").contains(BluetoothNameSignatures.KARR_HINT)
    )

    listOf("QT", "QT ", "QT123456", "XQT 123456", "Device QT 123456", " QT 123456").forEach { name ->
      assertFalse(name, decoderHints(name).contains(BluetoothNameSignatures.KARR_HINT))
    }
  }

  @Test
  fun `KARR decoder inspects alternate bluetooth names`() {
    val hints = PassiveVendorDecoderRegistry.decode(
      PassiveDecoderContext(
        displayName = "Other device",
        vendorName = null,
        manufacturerData = emptyMap(),
        serviceUuids = emptyList(),
        serviceData = emptyMap(),
        addressType = PassiveAddressType.UNKNOWN,
        alternateNames = listOf("QT serial-7")
      )
    )

    assertTrue(hints.contains(BluetoothNameSignatures.KARR_HINT))
  }

  @Test
  fun `Meta decoder requires company and service in the same observation`() {
    val pairedHints = PassiveVendorDecoderRegistry.decode(
      passiveContext(
        manufacturerData = mapOf(0x01AB to "1234"),
        serviceUuids = listOf("0000FD5F-0000-1000-8000-00805F9B34FB")
      )
    )
    val companyOnlyHints = PassiveVendorDecoderRegistry.decode(
      passiveContext(manufacturerData = mapOf(0x01AB to "1234"))
    )
    val serviceOnlyHints = PassiveVendorDecoderRegistry.decode(
      passiveContext(serviceUuids = listOf("FD5F"))
    )
    val wrongPairHints = PassiveVendorDecoderRegistry.decode(
      passiveContext(
        manufacturerData = mapOf(0x0D53 to "1234"),
        serviceUuids = listOf("FD5F")
      )
    )
    val serviceDataOnlyHints = PassiveVendorDecoderRegistry.decode(
      passiveContext(
        manufacturerData = mapOf(0x01AB to "1234"),
        serviceData = mapOf("FD5F" to "0102")
      )
    )

    assertTrue(pairedHints.contains(BluetoothSignalSignatures.META_SMART_GLASSES_HINT))
    assertFalse(companyOnlyHints.contains(BluetoothSignalSignatures.META_SMART_GLASSES_HINT))
    assertFalse(serviceOnlyHints.contains(BluetoothSignalSignatures.META_SMART_GLASSES_HINT))
    assertFalse(wrongPairHints.contains(BluetoothSignalSignatures.META_SMART_GLASSES_HINT))
    assertFalse(serviceDataOnlyHints.contains(BluetoothSignalSignatures.META_SMART_GLASSES_HINT))
  }

  @Test
  fun `apple decoder surfaces ibeacon and find my hints`() {
    val hints = PassiveVendorDecoderRegistry.decode(
      PassiveDecoderContext(
        displayName = "AirTag",
        vendorName = "Apple",
        manufacturerData = mapOf(0x004C to "0215AABBCCDD"),
        serviceUuids = listOf("0000FD44-0000-1000-8000-00805F9B34FB"),
        serviceData = emptyMap(),
        addressType = PassiveAddressType.RESOLVABLE_PRIVATE
      )
    )

    assertTrue(hints.contains("Apple ecosystem payload"))
    assertTrue(hints.contains("iBeacon-format manufacturer data"))
    assertTrue(hints.contains("Find My / tracker-style service"))
  }

  @Test
  fun `google decoder surfaces fast pair hints`() {
    val hints = PassiveVendorDecoderRegistry.decode(
      PassiveDecoderContext(
        displayName = "Pixel Buds",
        vendorName = "Google",
        manufacturerData = emptyMap(),
        serviceUuids = listOf("0000FE2C-0000-1000-8000-00805F9B34FB"),
        serviceData = emptyMap(),
        addressType = PassiveAddressType.PUBLIC
      )
    )

    assertTrue(hints.contains("Google ecosystem payload"))
    assertTrue(hints.contains("Fast Pair advertiser"))
  }

  @Test
  fun `tile decoder surfaces tracker style hints`() {
    val hints = PassiveVendorDecoderRegistry.decode(
      PassiveDecoderContext(
        displayName = null,
        vendorName = "Tile",
        manufacturerData = mapOf(0x067C to "01020304"),
        serviceUuids = listOf("0000FEED-0000-1000-8000-00805F9B34FB"),
        serviceData = emptyMap(),
        addressType = PassiveAddressType.RESOLVABLE_PRIVATE
      )
    )

    assertTrue(hints.contains("Tile tracker-style payload"))
    assertTrue(hints.contains("Tile service UUID"))
    assertTrue(hints.contains("Tracker-style randomized address"))
  }

  private fun decoderHints(displayName: String): List<String> {
    return PassiveVendorDecoderRegistry.decode(
      PassiveDecoderContext(
        displayName = displayName,
        vendorName = null,
        manufacturerData = emptyMap(),
        serviceUuids = emptyList(),
        serviceData = emptyMap(),
        addressType = PassiveAddressType.UNKNOWN
      )
    )
  }

  private fun passiveContext(
    manufacturerData: Map<Int, String> = emptyMap(),
    serviceUuids: List<String> = emptyList(),
    serviceData: Map<String, String> = emptyMap()
  ) = PassiveDecoderContext(
    displayName = null,
    vendorName = null,
    manufacturerData = manufacturerData,
    serviceUuids = serviceUuids,
    serviceData = serviceData,
    addressType = PassiveAddressType.RANDOM_STATIC
  )
}
