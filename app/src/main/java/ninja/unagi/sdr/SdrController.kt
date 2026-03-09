package ninja.unagi.sdr

import android.content.BroadcastReceiver
import android.content.Context
import ninja.unagi.scan.ObservationRecorder
import ninja.unagi.scan.ScanDiagnosticsStore
import ninja.unagi.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SdrController(
  private val context: Context,
  private val scope: CoroutineScope,
  private val observationRecorder: ObservationRecorder
) {
  private val _sdrState = MutableStateFlow<SdrState>(SdrState.Idle)
  val sdrState: StateFlow<SdrState> = _sdrState.asStateFlow()

  private val networkBridge = Rtl433NetworkBridge()
  private val rtl433Process by lazy { Rtl433Process(context) }
  private var usbReceiver: BroadcastReceiver? = null

  fun startSdr() {
    if (!SdrPreferences.isEnabled(context)) {
      _sdrState.value = SdrState.Idle
      return
    }
    if (_sdrState.value is SdrState.Scanning) return

    when (SdrPreferences.source(context)) {
      SdrPreferences.SdrSource.NETWORK -> startNetworkBridge()
      SdrPreferences.SdrSource.USB -> startUsbSdr()
    }
  }

  fun stopSdr() {
    networkBridge.disconnect()
    rtl433Process.stop()
    _sdrState.value = SdrState.Idle
    DebugLog.log("SDR scanning stopped")
  }

  fun registerUsbDetection() {
    if (usbReceiver != null) return
    usbReceiver = SdrUsbDetector.registerReceiver(
      context = context,
      onAttached = {
        if (SdrPreferences.isEnabled(context) &&
          SdrPreferences.source(context) == SdrPreferences.SdrSource.USB &&
          _sdrState.value !is SdrState.Scanning
        ) {
          startUsbSdr()
        }
      },
      onDetached = {
        if (_sdrState.value is SdrState.Scanning &&
          SdrPreferences.source(context) == SdrPreferences.SdrSource.USB
        ) {
          rtl433Process.stop()
          _sdrState.value = SdrState.UsbNotConnected
        }
      },
      onPermissionResult = { granted ->
        if (granted) {
          startUsbSdr()
        } else {
          _sdrState.value = SdrState.UsbPermissionDenied
        }
      }
    )
  }

  fun unregisterUsbDetection() {
    usbReceiver?.let {
      SdrUsbDetector.unregisterReceiver(context, it)
      usbReceiver = null
    }
  }

  private fun startNetworkBridge() {
    val host = SdrPreferences.networkHost(context)
    val port = SdrPreferences.networkPort(context)
    DebugLog.log("SDR starting network bridge to $host:$port")
    _sdrState.value = SdrState.Scanning

    networkBridge.connect(
      scope = scope,
      host = host,
      port = port,
      onReading = ::handleTpmsReading,
      onError = { message ->
        _sdrState.value = SdrState.Error(message)
      }
    )
  }

  private fun startUsbSdr() {
    val device = SdrUsbDetector.findSdrDevice(context)
    if (device == null) {
      _sdrState.value = SdrState.UsbNotConnected
      DebugLog.log("No SDR USB device found")
      return
    }

    if (!SdrUsbDetector.hasPermission(context, device)) {
      SdrUsbDetector.requestPermission(context, device)
      return
    }

    DebugLog.log("SDR starting on-device rtl_433: ${SdrUsbDetector.deviceDescription(device)}")
    _sdrState.value = SdrState.Scanning

    rtl433Process.start(
      scope = scope,
      frequencyHz = SdrPreferences.frequencyHz(context),
      gain = SdrPreferences.gain(context),
      onReading = ::handleTpmsReading,
      onError = { message ->
        _sdrState.value = SdrState.Error(message)
      }
    )
  }

  internal fun handleTpmsReading(reading: TpmsReading) {
    val input = TpmsObservationBuilder.build(reading)
    ScanDiagnosticsStore.update {
      it.copy(
        sdrCallbackCount = it.sdrCallbackCount + 1,
        rawCallbackCount = it.rawCallbackCount + 1
      )
    }
    DebugLog.log(
      "SDR observation model=${reading.model} sensor=${reading.sensorId} " +
        "pressure=${reading.pressureKpa} temp=${reading.temperatureC}"
    )
    observationRecorder.record(input)
  }
}
