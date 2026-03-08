package ninja.unagi.ui

import ninja.unagi.data.DeviceEntity
import ninja.unagi.data.DeviceEnrichmentEntity
import ninja.unagi.scan.ScanDiagnosticsSnapshot
import ninja.unagi.scan.ScanSessionOutcome
import ninja.unagi.scan.ScanStateDecider
import ninja.unagi.enrichment.DeviceEnrichmentFormatter
import ninja.unagi.util.Formatters

data class DiagnosticsPlatformInfo(
  val appVersionName: String,
  val appVersionCode: Long,
  val packageName: String,
  val manufacturer: String,
  val model: String,
  val device: String,
  val product: String,
  val buildType: String,
  val buildDisplay: String,
  val fingerprint: String,
  val sdkInt: Int,
  val release: String,
  val grapheneOsLikely: Boolean
)

data class DiagnosticsPermissionInfo(
  val missingPermissions: List<String>,
  val permissionLabels: List<String>,
  val permissionsBlocked: Boolean,
  val locationServicesRequired: Boolean,
  val locationServicesEnabled: Boolean
)

object DiagnosticsReportBuilder {
  fun build(
    entries: List<String>,
    scanDiagnostics: ScanDiagnosticsSnapshot,
    persistedDevices: List<DeviceEntity>,
    persistedEnrichments: List<DeviceEnrichmentEntity>,
    platformInfo: DiagnosticsPlatformInfo,
    permissionInfo: DiagnosticsPermissionInfo,
    bluetoothSupported: Boolean,
    bluetoothEnabled: Boolean,
    generatedAtMs: Long = System.currentTimeMillis()
  ): String {
    val builder = StringBuilder()
    builder.appendLine("unagi scan debug report")
    builder.appendLine()
    builder.appendLine("Generated: ${Formatters.formatTimestamp(generatedAtMs)}")
    builder.appendLine("App version: ${platformInfo.appVersionName} (${platformInfo.appVersionCode})")
    builder.appendLine("Package: ${platformInfo.packageName}")
    builder.appendLine("SDK: ${platformInfo.sdkInt}")
    builder.appendLine("Release: ${platformInfo.release}")
    builder.appendLine("Manufacturer: ${platformInfo.manufacturer}")
    builder.appendLine("Model: ${platformInfo.model}")
    builder.appendLine("Device: ${platformInfo.device}")
    builder.appendLine("Product: ${platformInfo.product}")
    builder.appendLine("Build type: ${platformInfo.buildType}")
    builder.appendLine("Build display: ${platformInfo.buildDisplay}")
    builder.appendLine("Fingerprint: ${platformInfo.fingerprint}")
    builder.appendLine("GrapheneOS likely (best effort): ${platformInfo.grapheneOsLikely}")
    builder.appendLine()
    builder.appendLine("Environment:")
    builder.appendLine("Bluetooth supported: $bluetoothSupported")
    builder.appendLine("Bluetooth enabled: $bluetoothEnabled")
    builder.appendLine(
      "Missing permissions: ${permissionInfo.missingPermissions.ifEmpty { listOf("none") }.joinToString()}"
    )
    builder.appendLine(
      "Permission labels: ${permissionInfo.permissionLabels.ifEmpty { listOf("none") }.joinToString()}"
    )
    builder.appendLine("Permissions blocked by policy or 'don't ask again': ${permissionInfo.permissionsBlocked}")
    if (permissionInfo.locationServicesRequired) {
      builder.appendLine("Location services enabled: ${permissionInfo.locationServicesEnabled}")
    }
    builder.appendLine()
    builder.appendLine("Latest scan session:")
    builder.appendLine("Scan mode: ${scanDiagnostics.scanMode.label}")
    builder.appendLine("Classic discovery enabled: ${scanDiagnostics.scanMode.startsClassicDiscovery}")
    builder.appendLine("Timeout ms: ${scanDiagnostics.scanMode.timeoutMs}")
    builder.appendLine("Start time: ${scanDiagnostics.startTimeMs?.let(Formatters::formatTimestamp) ?: "none"}")
    builder.appendLine("Outcome: ${scanDiagnostics.outcome?.label ?: "none"}")
    builder.appendLine("Timeout reached: ${scanDiagnostics.timeoutReached}")
    builder.appendLine("BLE startup attempted: ${scanDiagnostics.bleStartup != null}")
    builder.appendLine("BLE startup succeeded: ${scanDiagnostics.bleStartup?.started == true}")
    builder.appendLine("BLE startup detail: ${scanDiagnostics.bleStartup?.reason ?: "none"}")
    builder.appendLine("Classic startup attempted: ${scanDiagnostics.classicStartup != null}")
    builder.appendLine("Classic startup succeeded: ${scanDiagnostics.classicStartup?.started == true}")
    builder.appendLine("Classic startup detail: ${scanDiagnostics.classicStartup?.reason ?: "none"}")
    builder.appendLine("BLE scanner unavailable: ${scanDiagnostics.bleScannerUnavailable}")
    builder.appendLine(
      "Last BLE error: ${
        scanDiagnostics.lastBleErrorCode?.let {
          "$it (${ScanStateDecider.describeBleFailureCode(it)})"
        } ?: "none"
      }"
    )
    builder.appendLine("BLE callbacks: ${scanDiagnostics.bleCallbackCount}")
    builder.appendLine("Classic callbacks: ${scanDiagnostics.classicCallbackCount}")
    builder.appendLine("Raw callbacks: ${scanDiagnostics.rawCallbackCount}")
    builder.appendLine("Unique device keys this session: ${scanDiagnostics.uniqueDeviceCount}")
    builder.appendLine(
      "Permission snapshot: ${
        scanDiagnostics.missingPermissions.ifEmpty { listOf("none") }.joinToString()
      }"
    )
    builder.appendLine("Bluetooth enabled snapshot: ${scanDiagnostics.bluetoothEnabled ?: "unknown"}")
    builder.appendLine("Location services snapshot: ${scanDiagnostics.locationServicesEnabled ?: "unknown"}")
    builder.appendLine()
    builder.appendLine("Persisted devices: ${persistedDevices.size}")
    if (persistedDevices.isEmpty()) {
      builder.appendLine("  (none yet)")
    } else {
      persistedDevices.take(MAX_DEVICE_LINES).forEachIndexed { index, device ->
        builder.appendLine(
          "  ${index + 1}. name=${device.displayName ?: "Unknown device"} " +
            "address=${device.lastAddress ?: "n/a"} sightings=${device.sightingsCount} " +
            "lastSeen=${Formatters.formatTimestamp(device.lastSeen)} " +
            "rssi=${device.lastRssi} key=${device.deviceKey.take(12)}"
        )
      }
    }
    builder.appendLine()
    builder.appendLine("Active BLE enrichments: ${persistedEnrichments.size}")
    if (persistedEnrichments.isEmpty()) {
      builder.appendLine("  (none yet)")
    } else {
      persistedEnrichments.take(MAX_DEVICE_LINES).forEachIndexed { index, enrichment ->
        val services = DeviceEnrichmentFormatter.parseServices(enrichment.servicesPresentJson)
        builder.appendLine(
          "  ${index + 1}. key=${enrichment.deviceKey.take(12)} method=${enrichment.queryMethod} " +
            "lastQuery=${Formatters.formatTimestamp(enrichment.lastQueryTimestamp)} " +
            "dis=${enrichment.disAvailable}/${enrichment.disReadStatus} " +
            "services=${services.size} " +
            "reads=${enrichment.characteristicReadSuccessCount}/${enrichment.characteristicReadFailureCount} " +
            "gatt=${enrichment.finalGattStatus ?: "n/a"} " +
            "error=${enrichment.errorMessage ?: "none"}"
        )
      }
    }
    builder.appendLine()
    builder.appendLine("Suggestions:")
    val suggestions = buildSuggestions(scanDiagnostics, permissionInfo, bluetoothEnabled, platformInfo.grapheneOsLikely)
    if (suggestions.isEmpty()) {
      builder.appendLine("  - Capture this report again after another scan attempt if the issue persists.")
    } else {
      suggestions.forEach { suggestion ->
        builder.appendLine("  - $suggestion")
      }
    }
    builder.appendLine()
    builder.appendLine("Recent events:")
    if (entries.isEmpty()) {
      builder.appendLine("  (none yet)")
    } else {
      entries.takeLast(200).forEach { line ->
        builder.appendLine(line)
      }
    }
    return builder.toString()
  }

  private fun buildSuggestions(
    scanDiagnostics: ScanDiagnosticsSnapshot,
    permissionInfo: DiagnosticsPermissionInfo,
    bluetoothEnabled: Boolean,
    grapheneOsLikely: Boolean
  ): List<String> {
    val suggestions = mutableListOf<String>()
    if (permissionInfo.missingPermissions.isNotEmpty()) {
      suggestions += "Grant ${permissionInfo.permissionLabels.ifEmpty { listOf("required scan permissions") }.joinToString()} before scanning again."
    }
    if (!bluetoothEnabled) {
      suggestions += "Turn Bluetooth on before retrying the scan."
    }
    if (permissionInfo.locationServicesRequired && !permissionInfo.locationServicesEnabled) {
      suggestions += "Enable location services; Android 11 and below can suppress BLE results without them."
    }
    if (scanDiagnostics.bleScannerUnavailable) {
      suggestions += "Bluetooth LE scanner is unavailable; retry outside restricted profiles and re-open the app after confirming Bluetooth is enabled."
    }
    if (scanDiagnostics.outcome == ScanSessionOutcome.ZERO_RESULTS) {
      suggestions += "The scan started but found zero devices; try Compatibility mode and compare BLE callback counts."
    }
    if (scanDiagnostics.anyPathStarted && scanDiagnostics.bleCallbackCount == 0 && scanDiagnostics.classicCallbackCount == 0) {
      suggestions += "No callbacks arrived from BLE or classic discovery; capture this report after testing both Normal and Compatibility modes."
    }
    if (grapheneOsLikely) {
      suggestions += "GrapheneOS appears likely; confirm the app is not running in a restricted or private profile while testing."
    }
    return suggestions
  }

  private const val MAX_DEVICE_LINES = 10
}
