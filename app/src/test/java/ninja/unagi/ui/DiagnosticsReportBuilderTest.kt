package ninja.unagi.ui

import ninja.unagi.data.DeviceEntity
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
          sightingsCount = 3,
          lastRssi = -55,
          rssiMin = -70,
          rssiMax = -40,
          rssiAvg = -52.0,
          lastMetadataJson = null
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
    assertTrue(report.contains("name=Unknown device"))
    assertTrue(report.contains("GrapheneOS likely (best effort): true"))
    assertTrue(report.contains("try Compatibility mode"))
    assertTrue(report.contains("Recent events:"))
  }
}
