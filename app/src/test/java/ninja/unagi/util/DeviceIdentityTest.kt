package ninja.unagi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
  private val registry = VendorPrefixRegistry.fromLines(
    sequenceOf("001122|Acme Audio")
  )
  private val assignedNumbers = BluetoothAssignedNumbersRegistry.fromLines(
    companyLines = sequenceOf("004C|Apple"),
    serviceLines = sequenceOf("180F|Battery Service", "180A|Device Information")
  )

  @Test
  fun `ble identity prefers advertised name`() {
    val identity = ObservedIdentityResolver.forBle(
      advertisedName = "Earbuds",
      systemName = "Pierce's Earbuds"
    )

    assertEquals("Earbuds", identity.displayName)
    assertEquals(DeviceNameSource.BLE_ADVERTISED, identity.nameSource)
  }

  @Test
  fun `classic identity uses bluetooth device name`() {
    val identity = ObservedIdentityResolver.forClassic(
      systemName = "Gamepad"
    )

    assertEquals("Gamepad", identity.displayName)
    assertEquals(DeviceNameSource.BLUETOOTH_DEVICE, identity.nameSource)
  }

  @Test
  fun `device presentation falls back to vendor title`() {
    val presentation = DeviceIdentityPresenter.present(
      displayName = null,
      address = "00:11:22:33:44:55",
      metadataJson = """
        {
          "transport": "classic",
          "addressType": "public",
          "vendorName": "Acme Audio",
          "vendorSource": "IEEE MA-L",
          "vendorConfidence": "high"
        }
      """.trimIndent(),
      vendorRegistry = registry,
      assignedNumbers = assignedNumbers
    )

    assertEquals("Acme Audio device", presentation.title)
    assertEquals("Acme Audio", presentation.vendorName)
  }

  @Test
  fun `metadata parser reads stored identity fields`() {
    val metadata = ObservationMetadataParser.parse(
      """
      {
        "advertisedName": "Tracker",
        "systemName": "Tracker",
        "nameSource": "ble_advertised",
        "vendorName": "Acme Audio",
        "vendorSource": "IEEE MA-L",
        "vendorConfidence": "high",
        "locallyAdministeredAddress": true,
        "normalizedAddress": "001122334455",
        "addressType": "public",
        "passiveDecoderHints": ["iBeacon-format manufacturer data"],
        "classificationCategory": "tracker",
        "classificationLabel": "tracker / tag",
        "classificationConfidence": "medium",
        "classificationEvidence": ["service:tracker", "company:Apple"],
        "serviceUuids": ["0000180F-0000-1000-8000-00805F9B34FB"],
        "manufacturerData": {
          "76": "0102A0"
        }
      }
      """.trimIndent()
    )

    assertEquals("Tracker", metadata.advertisedName)
    assertEquals(DeviceNameSource.BLE_ADVERTISED, metadata.nameSource)
    assertEquals("Acme Audio", metadata.vendorName)
    assertEquals("IEEE MA-L", metadata.vendorSource)
    assertEquals(VendorConfidence.HIGH, metadata.vendorConfidence)
    assertTrue(metadata.locallyAdministeredAddress == true)
    assertEquals(PassiveAddressType.PUBLIC, metadata.addressType)
    assertEquals(listOf("iBeacon-format manufacturer data"), metadata.passiveDecoderHints)
    assertEquals("tracker", metadata.classificationCategory)
    assertEquals("tracker / tag", metadata.classificationLabel)
    assertEquals(ClassificationConfidence.MEDIUM, metadata.classificationConfidence)
    assertEquals(listOf("service:tracker", "company:Apple"), metadata.classificationEvidence)
    assertEquals(listOf("0000180F-0000-1000-8000-00805F9B34FB"), metadata.serviceUuids)
    assertEquals("0102A0", metadata.manufacturerData[76])
    assertNull(ObservationMetadataParser.parse(null).vendorName)
  }

  @Test
  fun `device presentation falls back to manufacturer and service hints`() {
    val presentation = DeviceIdentityPresenter.present(
      displayName = null,
      address = "DA:A1:19:00:00:01",
      metadataJson = """
        {
          "serviceUuids": ["0000180F-0000-1000-8000-00805F9B34FB"],
          "manufacturerData": {
            "76": "0215AABBCCDD"
          }
        }
      """.trimIndent(),
      vendorRegistry = registry,
      assignedNumbers = assignedNumbers
    )

    assertEquals("Apple device", presentation.title)
    assertTrue(presentation.metadataSummary.listLabels.contains("Mfr: Apple"))
    assertTrue(presentation.metadataSummary.listLabels.any { it.contains("Hint:") })
    assertTrue(presentation.metadataSummary.listLabels.contains("Svc: Battery Service"))
    assertEquals("Manufacturer company ID", presentation.vendorSource)
    assertEquals("medium", presentation.vendorConfidenceLabel)
    assertTrue(
      presentation.metadataSummary.detailLines.any { detail ->
        detail.contains("Apple (0x004C)")
      }
    )
  }
}
