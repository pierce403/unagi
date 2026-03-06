package com.thingalert.scan

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.SparseArray
import com.thingalert.data.DeviceObservation
import com.thingalert.data.DeviceRepository
import com.thingalert.util.DebugLog
import com.thingalert.util.PermissionsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ScanController(
  private val context: Context,
  private val scope: CoroutineScope,
  private val repository: DeviceRepository
) {
  private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
  private fun currentLeScanner(): BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

  private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
  val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

  private var timeoutJob: Job? = null
  private var receiverRegistered = false

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      DebugLog.log("BLE scan result callbackType=$callbackType rssi=${result.rssi}")
      handleBleResult(result)
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>) {
      DebugLog.log("BLE batch scan results count=${results.size}")
      results.forEach { handleBleResult(it) }
    }

    override fun onScanFailed(errorCode: Int) {
      _scanState.value = ScanState.Error("BLE scan failed: $errorCode")
      DebugLog.log("BLE scan failed errorCode=$errorCode", level = android.util.Log.ERROR)
    }
  }

  private val discoveryReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        BluetoothDevice.ACTION_FOUND -> {
          val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
          }
          val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
          if (device != null) {
            DebugLog.log("Classic result name=${safeName(device) ?: "unknown"} addr=${safeAddress(device) ?: "n/a"} rssi=$rssi")
            handleClassicResult(device, rssi)
          }
        }
        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
          // Discovery can finish before the overall scan session ends.
        }
      }
    }
  }

  fun startScan() {
    if (!PermissionsHelper.hasPermissions(context)) {
      _scanState.value = ScanState.MissingPermission
      DebugLog.log("Missing permissions: ${PermissionsHelper.missingPermissions(context)}", level = android.util.Log.WARN)
      return
    }

    if (bluetoothAdapter == null) {
      _scanState.value = ScanState.Unsupported
      DebugLog.log("Bluetooth adapter missing", level = android.util.Log.WARN)
      return
    }

    if (!bluetoothAdapter.isEnabled) {
      _scanState.value = ScanState.BluetoothOff
      DebugLog.log("Bluetooth disabled", level = android.util.Log.WARN)
      return
    }

    _scanState.value = ScanState.Scanning
    DebugLog.log("Starting scan")

    startBleScan()
    startClassicDiscovery()

    timeoutJob?.cancel()
    timeoutJob = scope.launch {
      delay(SCAN_TIMEOUT_MS)
      stopScan()
    }
  }

  fun stopScan() {
    timeoutJob?.cancel()
    stopBleScan()
    stopClassicDiscovery()
    _scanState.value = ScanState.Idle
    DebugLog.log("Stopping scan")
  }

  private fun startBleScan() {
    val scanner = currentLeScanner()
    if (scanner == null) {
      DebugLog.log("BluetoothLeScanner unavailable", level = android.util.Log.WARN)
      return
    }
    try {
      val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
      scanner.startScan(null, settings, scanCallback)
      DebugLog.log("BLE scan started")
    } catch (sec: SecurityException) {
      _scanState.value = ScanState.MissingPermission
      DebugLog.log("BLE scan missing permission", level = android.util.Log.WARN, throwable = sec)
    } catch (ex: Exception) {
      _scanState.value = ScanState.Error("BLE scan error: ${ex.message}")
      DebugLog.log("BLE scan error: ${ex.message}", level = android.util.Log.ERROR, throwable = ex)
    }
  }

  private fun stopBleScan() {
    val scanner = currentLeScanner() ?: return
    try {
      scanner.stopScan(scanCallback)
      DebugLog.log("BLE scan stopped")
    } catch (_: SecurityException) {
      // Ignore
    }
  }

  private fun startClassicDiscovery() {
    val adapter = bluetoothAdapter ?: return
    if (!receiverRegistered) {
      val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(discoveryReceiver, filter)
      }
      receiverRegistered = true
    }

    try {
      if (adapter.isDiscovering) {
        adapter.cancelDiscovery()
      }
      adapter.startDiscovery()
      DebugLog.log("Classic discovery started")
    } catch (sec: SecurityException) {
      _scanState.value = ScanState.MissingPermission
      DebugLog.log("Classic discovery missing permission", level = android.util.Log.WARN, throwable = sec)
    } catch (ex: Exception) {
      _scanState.value = ScanState.Error("Discovery error: ${ex.message}")
      DebugLog.log("Classic discovery error: ${ex.message}", level = android.util.Log.ERROR, throwable = ex)
    }
  }

  private fun stopClassicDiscovery() {
    val adapter = bluetoothAdapter ?: return
    try {
      if (adapter.isDiscovering) {
        adapter.cancelDiscovery()
      }
    } catch (_: SecurityException) {
      // Ignore
    }

    if (receiverRegistered) {
      try {
        context.unregisterReceiver(discoveryReceiver)
      } catch (_: IllegalArgumentException) {
        // Ignore
      }
      receiverRegistered = false
    }
  }

  private fun handleBleResult(result: ScanResult) {
    val device = result.device
    val name = safeName(device)
    val address = safeAddress(device)
    val serviceUuids = result.scanRecord?.serviceUuids
      ?.mapNotNull { it.uuid?.toString() }
      ?: emptyList()
    val manufacturerData = parseManufacturerData(result.scanRecord?.manufacturerSpecificData)

    val input = ObservationInput(
      name = name,
      address = address,
      rssi = result.rssi,
      timestamp = System.currentTimeMillis(),
      serviceUuids = serviceUuids,
      manufacturerData = manufacturerData,
      source = "BLE"
    )

    handleObservation(input)
  }

  private fun handleClassicResult(device: BluetoothDevice, rssi: Int) {
    val input = ObservationInput(
      name = safeName(device),
      address = safeAddress(device),
      rssi = rssi,
      timestamp = System.currentTimeMillis(),
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "Classic"
    )

    handleObservation(input)
  }

  private fun handleObservation(input: ObservationInput) {
    val key = DeviceKey.from(input)
    val metadata = buildMetadataJson(input)
    DebugLog.log(
      "Observation ${input.source} name=${input.name ?: "unknown"} addr=${input.address ?: "n/a"} " +
        "rssi=${input.rssi} services=${input.serviceUuids.size} mfg=${input.manufacturerData.size}"
    )
    val observation = DeviceObservation(
      deviceKey = key,
      name = input.name,
      address = input.address,
      rssi = input.rssi,
      timestamp = input.timestamp,
      metadataJson = metadata
    )

    scope.launch {
      repository.recordObservation(observation)
    }
  }

  private fun safeName(device: BluetoothDevice): String? {
    return try {
      device.name
    } catch (_: SecurityException) {
      null
    }
  }

  private fun safeAddress(device: BluetoothDevice): String? {
    return try {
      device.address
    } catch (_: SecurityException) {
      null
    }
  }

  private fun parseManufacturerData(data: SparseArray<ByteArray>?): Map<Int, String> {
    if (data == null || data.size() == 0) return emptyMap()
    val map = mutableMapOf<Int, String>()
    for (i in 0 until data.size()) {
      val id = data.keyAt(i)
      val bytes = data.valueAt(i)
      map[id] = bytes.toHexString()
    }
    return map
  }

  private fun buildMetadataJson(input: ObservationInput): String {
    val json = JSONObject()
    json.put("source", input.source)
    json.put("name", input.name)
    json.put("address", input.address)
    json.put("rssi", input.rssi)
    json.put("timestamp", input.timestamp)

    val services = JSONArray()
    input.serviceUuids.forEach { services.put(it) }
    json.put("serviceUuids", services)

    val manufacturerJson = JSONObject()
    input.manufacturerData.forEach { (id, data) ->
      manufacturerJson.put(id.toString(), data)
    }
    json.put("manufacturerData", manufacturerJson)

    return json.toString(2)
  }

  private fun ByteArray.toHexString(): String {
    return joinToString("") { b -> "%02x".format(b) }
  }

  companion object {
    private const val SCAN_TIMEOUT_MS = 20000L
  }
}
