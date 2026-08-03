package ninja.unagi.scan

import android.bluetooth.le.ScanCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScanPath(val label: String) {
  BLE("ble"),
  CLASSIC("classic"),
  SDR("sdr")
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

enum class PermissionDenialState(val label: String) {
  NOT_REQUESTED("not yet requested"),
  DENIED_CAN_ASK("denied, can ask again"),
  PERMANENTLY_DENIED("permanently denied")
}

data class PermissionStatus(
  val permission: String,
  val label: String,
  val denialState: PermissionDenialState
)

data class ScanPreflightResult(
  val state: ScanState,
  val missingPermissions: List<String> = emptyList(),
  val bluetoothEnabled: Boolean? = null,
  val bleScannerAvailable: Boolean? = null,
  val locationServicesEnabled: Boolean? = null
)

data class CallbackSample(
  val path: ScanPath,
  val timestampMs: Long,
  val address: String?,
  val name: String?,
  val rssi: Int,
  val serviceUuidCount: Int,
  val manufacturerDataKeys: List<Int>
) {
  companion object {
    const val MAX_SAMPLES = 20
  }
}

data class ScanDiagnosticsSnapshot(
  val sessionId: Long = 0L,
  val scanMode: ScanModePreset = ScanModePreset.NORMAL,
  val startTimeMs: Long? = null,
  val bleStartup: ScanStartupResult? = null,
  val classicStartup: ScanStartupResult? = null,
  val bleScannerUnavailable: Boolean = false,
  val lastBleErrorCode: Int? = null,
  val bleCallbackCount: Int = 0,
  val classicCallbackCount: Int = 0,
  val sdrCallbackCount: Int = 0,
  val rawCallbackCount: Int = 0,
  val scanQueueDepth: Int = 0,
  val scanQueueHighWaterMark: Int = 0,
  val coalescedCallbackCount: Int = 0,
  val droppedCallbackCount: Int = 0,
  val lateCallbackCount: Int = 0,
  val callbackSamples: List<CallbackSample> = emptyList(),
  val uniqueDeviceCount: Int = 0,
  val timeoutReached: Boolean = false,
  val outcome: ScanSessionOutcome? = null,
  val missingPermissions: List<String> = emptyList(),
  val permissionStatuses: List<PermissionStatus> = emptyList(),
  val bluetoothEnabled: Boolean? = null,
  val locationServicesEnabled: Boolean? = null
) {
  val anyPathStarted: Boolean
    get() = bleStartup?.started == true || classicStartup?.started == true
}

object ScanDiagnosticsStore {
  private val lock = Any()
  private val _snapshot = MutableStateFlow(ScanDiagnosticsSnapshot())
  val snapshot: StateFlow<ScanDiagnosticsSnapshot> = _snapshot.asStateFlow()
  private val deviceKeys = mutableSetOf<String>()

  fun reset(snapshot: ScanDiagnosticsSnapshot = ScanDiagnosticsSnapshot()) {
    synchronized(lock) {
      deviceKeys.clear()
      _snapshot.value = snapshot
    }
  }

  fun startSession(sessionId: Long, snapshot: ScanDiagnosticsSnapshot) {
    synchronized(lock) {
      deviceKeys.clear()
      _snapshot.value = snapshot.copy(sessionId = sessionId)
    }
  }

  fun update(transform: (ScanDiagnosticsSnapshot) -> ScanDiagnosticsSnapshot) {
    synchronized(lock) {
      _snapshot.value = transform(_snapshot.value)
    }
  }

  fun updateForSession(
    sessionId: Long,
    transform: (ScanDiagnosticsSnapshot) -> ScanDiagnosticsSnapshot
  ): Boolean {
    return synchronized(lock) {
      val current = _snapshot.value
      if (current.sessionId != sessionId) {
        false
      } else {
        _snapshot.value = transform(current).copy(sessionId = sessionId)
        true
      }
    }
  }

  fun snapshotForSession(sessionId: Long): ScanDiagnosticsSnapshot? {
    return synchronized(lock) {
      _snapshot.value.takeIf { it.sessionId == sessionId }
    }
  }

  fun recordObservation(
    sessionId: Long?,
    deviceKey: String,
    sample: CallbackSample
  ) {
    synchronized(lock) {
      val current = _snapshot.value
      if (sessionId != null && current.sessionId != sessionId) {
        return
      }

      val addedDevice = deviceKeys.add(deviceKey)
      val samples = if (current.callbackSamples.size < CallbackSample.MAX_SAMPLES) {
        current.callbackSamples + sample
      } else {
        current.callbackSamples
      }
      if (!addedDevice && samples === current.callbackSamples) {
        return
      }

      _snapshot.value = current.copy(
        uniqueDeviceCount = deviceKeys.size,
        callbackSamples = samples
      )
    }
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
