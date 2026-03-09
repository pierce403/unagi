package ninja.unagi.ui

import ninja.unagi.data.DeviceEntity
import ninja.unagi.data.DeviceEnrichmentEntity
import ninja.unagi.enrichment.BleDeviceInfoQueryClient
import ninja.unagi.scan.CallbackSample
import ninja.unagi.scan.ScanDiagnosticsSnapshot
import ninja.unagi.scan.ScanPath
import ninja.unagi.scan.ScanSessionOutcome
import ninja.unagi.scan.ScanStartupResult
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportBuilderTest {
  @Test
  fun `report includes platform info inventory and suggestions`() {
    val report = DiagnosticsReportBuilder.build(
      entries = listOf("event one", "event two"),
      scanDiagnostics = ScanDiagnosticsSnapshot(
        bleStartup = ScanStartupResult(path = ScanPath.BLE, started = true),
        outcome = ScanSessionOutcome.ZERO_RESULTS,
        bleCallbackCount = 0,
        classicCallbackCount = 0,
        missingPermissions = emptyList(),
        bluetoothEnabled = true,
        locationServicesEnabled = true
      ),
      persistedDevices = listOf(
        DeviceEntity(
          deviceKey = "abcdef1234567890",
          displayName = null,
          lastAddress = "AA:BB:CC:DD:EE:FF",
          firstSeen = 1L,
          lastSeen = 2L,
          lastSightingAt = 2L,
          sightingsCount = 3,
          observationCount = 5,
          lastRssi = -55,
          rssiMin = -70,
          rssiMax = -40,
          rssiAvg = -52.0,
          lastMetadataJson = null,
          starred = false
        )
      ),
      persistedEnrichments = listOf(
        DeviceEnrichmentEntity(
          deviceKey = "abcdef1234567890",
          lastQueryTimestamp = 3L,
          queryMethod = BleDeviceInfoQueryClient.QUERY_METHOD_BLE_GATT_DIS,
          servicesPresentJson = "[\"0000180A-0000-1000-8000-00805F9B34FB\"]",
          disAvailable = true,
          disReadStatus = "success",
          manufacturerName = "Acme",
          modelNumber = "Beacon",
          serialNumber = null,
          hardwareRevision = null,
          firmwareRevision = null,
          softwareRevision = null,
          systemId = null,
          pnpVendorIdSource = null,
          pnpVendorId = null,
          pnpProductId = null,
          pnpProductVersion = null,
          errorCode = null,
          errorMessage = null,
          connectDurationMs = 120L,
          servicesDiscovered = 1,
          characteristicReadSuccessCount = 2,
          characteristicReadFailureCount = 0,
          finalGattStatus = 0
        )
      ),
      platformInfo = DiagnosticsPlatformInfo(
        appVersionName = "0.1.0",
        appVersionCode = 7L,
        packageName = "ninja.unagi",
        manufacturer = "Google",
        model = "Pixel",
        device = "akita",
        product = "akita",
        buildType = "user",
        buildDisplay = "graphene-test",
        fingerprint = "graphene/fingerprint",
        sdkInt = 35,
        release = "15",
        grapheneOsLikely = true
      ),
      permissionInfo = DiagnosticsPermissionInfo(
        missingPermissions = emptyList(),
        permissionLabels = emptyList(),
        permissionsBlocked = false,
        locationServicesRequired = false,
        locationServicesEnabled = true
      ),
      bluetoothSupported = true,
      bluetoothEnabled = true,
      generatedAtMs = 0L
    )

    assertTrue(report.contains("App version: 0.1.0 (7)"))
    assertTrue(report.contains("Persisted devices: 1"))
    assertTrue(report.contains("Active BLE enrichments: 1"))
    assertTrue(report.contains("name=Unknown device"))
    assertTrue(report.contains("GrapheneOS likely (best effort): true"))
    assertTrue(report.contains("try Compatibility mode"))
    assertTrue(report.contains("Recent events:"))
  }

  @Test
  fun `report includes callback samples when present`() {
    val report = DiagnosticsReportBuilder.build(
      entries = emptyList(),
      scanDiagnostics = ScanDiagnosticsSnapshot(
        bleStartup = ScanStartupResult(path = ScanPath.BLE, started = true),
        outcome = ScanSessionOutcome.RESULTS,
        bleCallbackCount = 2,
        callbackSamples = listOf(
          CallbackSample(
            path = ScanPath.BLE,
            timestampMs = 1000L,
            address = "AA:BB:CC:DD:EE:FF",
            name = "TestBeacon",
            rssi = -65,
            serviceUuidCount = 1,
            manufacturerDataKeys = listOf(76)
          ),
          CallbackSample(
            path = ScanPath.CLASSIC,
            timestampMs = 2000L,
            address = "11:22:33:44:55:66",
            name = null,
            rssi = -80,
            serviceUuidCount = 0,
            manufacturerDataKeys = emptyList()
          )
        )
      ),
      persistedDevices = emptyList(),
      persistedEnrichments = emptyList(),
      platformInfo = DiagnosticsPlatformInfo(
        appVersionName = "0.1.0", appVersionCode = 7L, packageName = "ninja.unagi",
        manufacturer = "Google", model = "Pixel", device = "akita", product = "akita",
        buildType = "user", buildDisplay = "test", fingerprint = "test/fp",
        sdkInt = 35, release = "15", grapheneOsLikely = false
      ),
      permissionInfo = DiagnosticsPermissionInfo(
        missingPermissions = emptyList(), permissionLabels = emptyList(),
        permissionsBlocked = false, locationServicesRequired = false, locationServicesEnabled = true
      ),
      bluetoothSupported = true,
      bluetoothEnabled = true,
      generatedAtMs = 0L
    )

    assertTrue(report.contains("Callback samples (first 2):"))
    assertTrue(report.contains("ble addr=AA:BB:CC:DD:EE:FF name=TestBeacon rssi=-65 services=1 mfg=76"))
    assertTrue(report.contains("classic addr=11:22:33:44:55:66 name=unknown rssi=-80 services=0 mfg=none"))
  }
}
