package ninja.unagi.scan

import android.bluetooth.le.ScanCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ScanPath(val label: String) {
  BLE("ble"),
  CLASSIC("classic")
}

data class ScanStartupResult(
  val path: ScanPath,
  val started: Boolean,
  val reason: String? = null,
  val errorCode: Int? = null
)

enum class ScanSessionOutcome(val label: String) {
  FAILED_TO_START("failed to start"),
  ZERO_RESULTS("success with zero results"),
  RESULTS("success with results"),
  INTERRUPTED("interrupted/cancelled")
}

data class ScanPreflightResult(
  val state: ScanState,
  val missingPermissions: List<String> = emptyList(),
  val bluetoothEnabled: Boolean? = null,
  val bleScannerAvailable: Boolean? = null,
  val locationServicesEnabled: Boolean? = null
)

data class ScanDiagnosticsSnapshot(
  val scanMode: ScanModePreset = ScanModePreset.NORMAL,
  val startTimeMs: Long? = null,
  val bleStartup: ScanStartupResult? = null,
  val classicStartup: ScanStartupResult? = null,
  val bleScannerUnavailable: Boolean = false,
  val lastBleErrorCode: Int? = null,
  val bleCallbackCount: Int = 0,
  val classicCallbackCount: Int = 0,
  val rawCallbackCount: Int = 0,
  val deviceKeys: Set<String> = emptySet(),
  val timeoutReached: Boolean = false,
  val outcome: ScanSessionOutcome? = null,
  val missingPermissions: List<String> = emptyList(),
  val bluetoothEnabled: Boolean? = null,
  val locationServicesEnabled: Boolean? = null
) {
  val uniqueDeviceCount: Int
    get() = deviceKeys.size

  val anyPathStarted: Boolean
    get() = bleStartup?.started == true || classicStartup?.started == true
}

object ScanDiagnosticsStore {
  private val _snapshot = MutableStateFlow(ScanDiagnosticsSnapshot())
  val snapshot: StateFlow<ScanDiagnosticsSnapshot> = _snapshot.asStateFlow()

  fun reset(snapshot: ScanDiagnosticsSnapshot = ScanDiagnosticsSnapshot()) {
    _snapshot.value = snapshot
  }

  fun update(transform: (ScanDiagnosticsSnapshot) -> ScanDiagnosticsSnapshot) {
    _snapshot.update(transform)
  }
}

object ScanStateDecider {
  const val BLE_SCANNER_UNAVAILABLE = "Bluetooth LE scanner unavailable"
  private const val BLE_SCANNER_UNAVAILABLE_DETAIL =
    "Bluetooth may be off, restricted, or unavailable in this profile"

  fun stateAfterStartup(results: List<ScanStartupResult>): ScanState {
    return if (results.any { it.started }) {
      ScanState.Scanning
    } else {
      ScanState.Error(buildStartupFailureMessage(results))
    }
  }

  fun stateAfterTimeout(snapshot: ScanDiagnosticsSnapshot): ScanState {
    if (!snapshot.anyPathStarted) {
      return ScanState.Error(buildStartupFailureMessage(listOfNotNull(snapshot.bleStartup, snapshot.classicStartup)))
    }

    return ScanState.Complete(snapshot.uniqueDeviceCount)
  }

  fun buildStartupFailureMessage(results: List<ScanStartupResult>): String {
    val reasons = results.filterNot { it.started }.map { result ->
      val reason = when {
        result.reason == BLE_SCANNER_UNAVAILABLE ->
          "$BLE_SCANNER_UNAVAILABLE. $BLE_SCANNER_UNAVAILABLE_DETAIL."
        result.reason == "Skipped in compatibility mode" ->
          "${result.path.label} path skipped in compatibility mode."
        result.reason.isNullOrBlank() ->
          "${result.path.label} startup failed."
        else ->
          "${result.path.label} startup failed: ${result.reason}."
      }
      reason
    }

    return if (reasons.isEmpty()) {
      "Scan failed to start."
    } else {
      "Scan failed to start. ${reasons.joinToString(" ")}"
    }
  }

  fun describeBleFailureCode(errorCode: Int): String {
    return when (errorCode) {
      ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
      ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
        "App registration with the Bluetooth stack failed"
      ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Bluetooth LE scan feature unsupported"
      ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal Bluetooth stack error"
      ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Out of Bluetooth hardware resources"
      ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scanning too frequently"
      else -> "Unknown BLE scan failure"
    }
  }
}
