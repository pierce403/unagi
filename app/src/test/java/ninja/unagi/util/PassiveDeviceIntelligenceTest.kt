package ninja.unagi.util

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveDeviceIntelligenceTest {
  private val vendorRegistry = VendorPrefixRegistry.fromLines(
    sequenceOf("001122|Acme Audio")
  )
  private val assignedNumbers = BluetoothAssignedNumbersRegistry.fromLines(
    companyLines = sequenceOf(
      "004C|Apple",
      "0059|Nordic Semiconductor ASA",
      "067C|Tile, Inc."
    ),
    serviceLines = sequenceOf(
      "180F|Battery Service",
      "1812|Human Interface Device",
      "FEEC|Tile, Inc.",
      "FEED|Tile, Inc."
    )
  )

  @Test
  fun `classifies random address subtypes from raw address`() {
    assertEquals(
      PassiveAddressType.RANDOM_STATIC,
      PassiveAddressResolver.resolve("C2:00:00:00:00:01", BluetoothDevice.ADDRESS_TYPE_RANDOM).addressType
    )
    assertEquals(
      PassiveAddressType.RESOLVABLE_PRIVATE,
      PassiveAddressResolver.resolve("72:00:00:00:00:01", BluetoothDevice.ADDRESS_TYPE_RANDOM).addressType
    )
    assertEquals(
      PassiveAddressType.NON_RESOLVABLE_PRIVATE,
      PassiveAddressResolver.resolve("32:00:00:00:00:01", BluetoothDevice.ADDRESS_TYPE_RANDOM).addressType
    )
  }

  @Test
  fun `downgrades vendor confidence on randomized addresses`() {
    val randomAddressHint = PassiveVendorResolver.resolve(
      addressInsight = PassiveAddressResolver.resolve(
        "DA:A1:19:00:00:01",
        BluetoothDevice.ADDRESS_TYPE_RANDOM
      ),
      assignedNumbers = assignedNumbers,
      vendorRegistry = vendorRegistry,
      manufacturerData = mapOf(76 to "0215AABBCCDD"),
      serviceUuids = emptyList(),
      displayName = null
    )

    assertEquals("Apple", randomAddressHint.vendorName)
    assertEquals("Manufacturer company ID", randomAddressHint.vendorSource)
    assertEquals(VendorConfidence.MEDIUM, randomAddressHint.confidence)
  }

  @Test
  fun `uses OUI with high confidence on public addresses`() {
    val publicHint = PassiveVendorResolver.resolve(
      addressInsight = PassiveAddressResolver.resolve(
        "00:11:22:33:44:55",
        BluetoothDevice.ADDRESS_TYPE_PUBLIC
      ),
      assignedNumbers = assignedNumbers,
      vendorRegistry = vendorRegistry,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      displayName = null
    )

    assertEquals("Acme Audio", publicHint.vendorName)
    assertEquals("IEEE MA-L", publicHint.vendorSource)
    assertEquals(VendorConfidence.HIGH, publicHint.confidence)
  }

  @Test
  fun `classification engine emits tracker evidence for tile-like payloads`() {
    val classification = DeviceClassificationEngine.classify(
      metadata = ClassificationMetadata(
        transport = ObservedTransport.BLE,
        addressType = PassiveAddressType.RESOLVABLE_PRIVATE,
        manufacturerData = mapOf(1660 to "01020304"),
        serviceUuids = listOf("0000FEEC-0000-1000-8000-00805F9B34FB"),
        serviceData = emptyMap(),
        appearance = null,
        classicMajorClass = null,
        classicDeviceClass = null,
        displayName = null
      ),
      assignedNumbers = assignedNumbers
    )

    assertEquals(DeviceCategory.TRACKER, classification.category)
    assertTrue(classification.evidence.any { it.contains("tracker", ignoreCase = true) })
    assertTrue(classification.confidence != ClassificationConfidence.UNKNOWN)
  }

  @Test
  fun `classification engine scores SDR transport as TPMS sensor`() {
    val classification = DeviceClassificationEngine.classify(
      metadata = ClassificationMetadata(
        transport = ObservedTransport.SDR,
        addressType = PassiveAddressType.UNKNOWN,
        manufacturerData = emptyMap(),
        serviceUuids = emptyList(),
        serviceData = emptyMap(),
        appearance = null,
        classicMajorClass = null,
        classicDeviceClass = null,
        displayName = "TPMS Toyota 0x00ABCDEF"
      ),
      assignedNumbers = assignedNumbers
    )

    assertEquals(DeviceCategory.TPMS_SENSOR, classification.category)
    assertEquals(ClassificationConfidence.HIGH, classification.confidence)
    assertTrue(classification.evidence.any { it.contains("sdr") })
  }
}
