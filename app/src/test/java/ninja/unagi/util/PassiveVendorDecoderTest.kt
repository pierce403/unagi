package ninja.unagi.util

import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveVendorDecoderTest {
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
}
