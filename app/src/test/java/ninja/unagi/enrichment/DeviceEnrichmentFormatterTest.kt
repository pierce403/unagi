package ninja.unagi.enrichment

import ninja.unagi.data.DeviceEnrichmentEntity
import ninja.unagi.util.BluetoothAssignedNumbersRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEnrichmentFormatterTest {
  private val assignedNumbers = BluetoothAssignedNumbersRegistry.fromLines(
    companyLines = sequenceOf("004C|Apple"),
    serviceLines = sequenceOf(
      "180A|Device Information",
      "180F|Battery Service"
    )
  )

  @Test
  fun `formats stored enrichment details`() {
    val enrichment = DeviceEnrichmentEntity(
      deviceKey = "abc",
      lastQueryTimestamp = 0L,
      queryMethod = BleDeviceInfoQueryClient.QUERY_METHOD_BLE_GATT_DIS,
      servicesPresentJson = "[\"0000180A-0000-1000-8000-00805F9B34FB\",\"0000180F-0000-1000-8000-00805F9B34FB\"]",
      disAvailable = true,
      disReadStatus = "success",
      manufacturerName = "Apple",
      modelNumber = "AirPods",
      serialNumber = null,
      hardwareRevision = "A1",
      firmwareRevision = "1.2.3",
      softwareRevision = null,
      systemId = "01:02:03:04:05:06:07:08",
      pnpVendorIdSource = 1,
      pnpVendorId = 0x004C,
      pnpProductId = 0x1234,
      pnpProductVersion = 0x0100,
      errorCode = null,
      errorMessage = null,
      connectDurationMs = 250L,
      servicesDiscovered = 2,
      characteristicReadSuccessCount = 4,
      characteristicReadFailureCount = 1,
      finalGattStatus = 0
    )

    val formatted = DeviceEnrichmentFormatter.formatForDetail(enrichment, assignedNumbers)

    assertTrue(formatted.contains("DIS available: true"))
    assertTrue(formatted.contains("Manufacturer name: Apple"))
    assertTrue(formatted.contains("Device Information (0x180A)"))
    assertTrue(formatted.contains("PnP ID: source=Bluetooth SIG vendor=0x004C"))
  }

  @Test
  fun `parses stored service list`() {
    assertEquals(
      listOf("0000180A-0000-1000-8000-00805F9B34FB"),
      DeviceEnrichmentFormatter.parseServices("[\"0000180A-0000-1000-8000-00805F9B34FB\"]")
    )
  }
}
