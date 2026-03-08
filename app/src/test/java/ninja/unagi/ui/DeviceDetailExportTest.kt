package ninja.unagi.ui

import ninja.unagi.data.DeviceEntity
import ninja.unagi.data.DeviceEnrichmentEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDetailExportTest {
  @Test
  fun buildJsonIncludesDeviceMetadataAndEnrichmentWithoutSightingsArray() {
    val device = DeviceEntity(
      deviceKey = "device-key-1",
      displayName = "Beacon",
      lastAddress = "AA:BB:CC:DD:EE:FF",
      firstSeen = 1_000L,
      lastSeen = 2_000L,
      lastSightingAt = 1_900L,
      sightingsCount = 4,
      observationCount = 8,
      lastRssi = -62,
      rssiMin = -80,
      rssiMax = -55,
      rssiAvg = -66.5,
      lastMetadataJson = """{"transport":"BLE","serviceUuids":["180A"]}""",
      starred = true
    )
    val enrichment = DeviceEnrichmentEntity(
      deviceKey = device.deviceKey,
      lastQueryTimestamp = 3_000L,
      queryMethod = "ble_gatt_dis",
      servicesPresentJson = """["180A","180F"]""",
      disAvailable = true,
      disReadStatus = "complete",
      manufacturerName = "Acme",
      modelNumber = "Model 1",
      serialNumber = null,
      hardwareRevision = null,
      firmwareRevision = "1.2.3",
      softwareRevision = null,
      systemId = null,
      pnpVendorIdSource = 1,
      pnpVendorId = 0x004C,
      pnpProductId = 0x0001,
      pnpProductVersion = 0x0002,
      errorCode = null,
      errorMessage = null,
      connectDurationMs = 250L,
      servicesDiscovered = 2,
      characteristicReadSuccessCount = 3,
      characteristicReadFailureCount = 0,
      finalGattStatus = 0
    )

    val json = JSONObject(DeviceDetailExport.buildJson(device, enrichment))

    assertTrue(json.has("device"))
    assertTrue(json.has("lastMetadata"))
    assertTrue(json.has("activeBleEnrichment"))
    assertFalse(json.has("sightings"))

    val deviceJson = json.getJSONObject("device")
    assertEquals("device-key-1", deviceJson.getString("deviceKey"))
    assertEquals("AA:BB:CC:DD:EE:FF", deviceJson.getString("lastAddress"))
    assertEquals(4, deviceJson.getInt("sightingsCount"))

    val metadataJson = json.getJSONObject("lastMetadata")
    assertEquals("BLE", metadataJson.getString("transport"))
    assertEquals("180A", metadataJson.getJSONArray("serviceUuids").getString(0))

    val enrichmentJson = json.getJSONObject("activeBleEnrichment")
    assertEquals("ble_gatt_dis", enrichmentJson.getString("queryMethod"))
    assertEquals("180A", enrichmentJson.getJSONArray("servicesPresent").getString(0))
    assertEquals("Acme", enrichmentJson.getString("manufacturerName"))
  }

  @Test
  fun defaultFileNameUsesUppercaseMacAddress() {
    assertEquals(
      "unagi-AA:BB:CC:DD:EE:FF.txt",
      DeviceDetailExport.defaultFileName("aa:bb:cc:dd:ee:ff")
    )
  }
}
