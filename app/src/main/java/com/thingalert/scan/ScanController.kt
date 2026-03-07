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
import com.thingalert.alerts.AlertObservation
import com.thingalert.alerts.DeviceAlertMatcher
import com.thingalert.alerts.DeviceAlertNotifier
import com.thingalert.data.DeviceObservation
import com.thingalert.data.AlertRuleEntity
import com.thingalert.data.AlertRuleRepository
import com.thingalert.data.DeviceRepository
import com.thingalert.util.DebugLog
import com.thingalert.util.ObservedIdentityResolver
import com.thingalert.util.PermissionsHelper
import com.thingalert.util.VendorPrefixRegistryProvider
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
  private val repository: DeviceRepository,
  private val alertRuleRepository: AlertRuleRepository,
  private val deviceAlertNotifier: DeviceAlertNotifier
) {
  private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
  private val vendorRegistry by lazy { VendorPrefixRegistryProvider.get(context) }
  private fun currentLeScanner(): BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

  private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
  val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

  private var timeoutJob: Job? = null
  private var receiverRegistered = false
  private var blePathActive = false
  private var classicPathActive = false
  private var enabledAlertRules: List<AlertRuleEntity> = emptyList()
  private val firedAlertKeys = mutableSetOf<String>()

  init {
    scope.launch {
      alertRuleRepository.observeEnabledRules().collect { rules ->
        enabledAlertRules = rules
      }
    }
  }

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      recordBleCallbacks(1)
      DebugLog.log("BLE scan result callbackType=$callbackType rssi=${result.rssi}")
      handleBleResult(result)
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>) {
      recordBleCallbacks(results.size)
      DebugLog.log("BLE batch scan results count=${results.size}")
      results.forEach { handleBleResult(it) }
    }

    override fun onScanFailed(errorCode: Int) {
      blePathActive = false
      val description = ScanStateDecider.describeBleFailureCode(errorCode)
      ScanDiagnosticsStore.update {
        it.copy(lastBleErrorCode = errorCode)
      }
      DebugLog.log(
        "BLE scan failed errorCode=$errorCode description=$description",
        level = android.util.Log.ERROR
      )

      if (!classicPathActive) {
        timeoutJob?.cancel()
        timeoutJob = null
        stopBleScan()
        stopClassicDiscovery()

        val snapshot = ScanDiagnosticsStore.snapshot.value
        ScanDiagnosticsStore.update {
          it.copy(
            outcome = if (snapshot.uniqueDeviceCount > 0) {
              ScanSessionOutcome.RESULTS
            } else {
              ScanSessionOutcome.FAILED_TO_START
            }
          )
        }

        _scanState.value = if (snapshot.uniqueDeviceCount > 0) {
          ScanState.Complete(snapshot.uniqueDeviceCount)
        } else {
          ScanState.Error("BLE scan failed: $description")
        }
      }
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
            recordClassicCallback()
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
    timeoutJob?.cancel()
    timeoutJob = null
    stopBleScan()
    stopClassicDiscovery()
    firedAlertKeys.clear()

    val scanMode = ScanModePreferences.get(context)
    val preflight = preflight()
    ScanDiagnosticsStore.reset(
      ScanDiagnosticsSnapshot(
        scanMode = scanMode,
        missingPermissions = preflight.missingPermissions,
        bluetoothEnabled = preflight.bluetoothEnabled,
        locationServicesEnabled = preflight.locationServicesEnabled,
        bleScannerUnavailable = preflight.bleScannerAvailable == false
      )
    )

    if (preflight.state != ScanState.Idle) {
      _scanState.value = preflight.state
      DebugLog.log("Scan blocked by preflight state=${preflight.state}", level = android.util.Log.WARN)
      return
    }

    DebugLog.log("Starting scan")
    ScanDiagnosticsStore.update {
      it.copy(startTimeMs = System.currentTimeMillis(), scanMode = scanMode)
    }

    val bleResult = startBleScan(scanMode)
    val classicResult = startClassicDiscovery(scanMode)
    ScanDiagnosticsStore.update {
      it.copy(
        bleStartup = bleResult,
        classicStartup = classicResult,
        bleScannerUnavailable = bleResult.reason == ScanStateDecider.BLE_SCANNER_UNAVAILABLE
      )
    }

    val nextState = ScanStateDecider.stateAfterStartup(listOf(bleResult, classicResult))
    _scanState.value = nextState
    DebugLog.log(
      "Scan startup bleStarted=${bleResult.started} classicStarted=${classicResult.started}"
    )

    if (nextState !is ScanState.Scanning) {
      ScanDiagnosticsStore.update {
        it.copy(outcome = ScanSessionOutcome.FAILED_TO_START)
      }
      DebugLog.log((nextState as ScanState.Error).message, level = android.util.Log.WARN)
      return
    }

    timeoutJob = scope.launch {
      delay(scanMode.timeoutMs)
      finishTimedOutScan()
    }
  }

  fun refreshState() {
    val preflight = preflight()
    ScanDiagnosticsStore.update {
      it.copy(
        missingPermissions = preflight.missingPermissions,
        bluetoothEnabled = preflight.bluetoothEnabled,
        locationServicesEnabled = preflight.locationServicesEnabled,
        bleScannerUnavailable = preflight.bleScannerAvailable == false || it.bleScannerUnavailable
      )
    }
    val state = preflight.state
    if (_scanState.value is ScanState.Scanning && state != ScanState.Idle) {
      interruptScan(state)
      DebugLog.log("Scanning interrupted by preflight state=$state", level = android.util.Log.WARN)
      return
    }

    if (_scanState.value !is ScanState.Scanning) {
      _scanState.value = state
    }
  }

  fun stopScan() {
    timeoutJob?.cancel()
    timeoutJob = null
    stopBleScan()
    stopClassicDiscovery()
    firedAlertKeys.clear()
    if (_scanState.value is ScanState.Scanning) {
      ScanDiagnosticsStore.update {
        it.copy(outcome = ScanSessionOutcome.INTERRUPTED, timeoutReached = false)
      }
    }
    _scanState.value = ScanState.Idle
    DebugLog.log("Stopping scan")
  }

  private fun startBleScan(scanMode: ScanModePreset): ScanStartupResult {
    val scanner = currentLeScanner()
    if (scanner == null) {
      DebugLog.log("BluetoothLeScanner unavailable", level = android.util.Log.WARN)
      blePathActive = false
      return ScanStartupResult(
        path = ScanPath.BLE,
        started = false,
        reason = ScanStateDecider.BLE_SCANNER_UNAVAILABLE
      )
    }
    try {
      val settings = ScanSettings.Builder()
        .setScanMode(scanMode.bleScanMode)
        .build()
      scanner.startScan(null, settings, scanCallback)
      blePathActive = true
      DebugLog.log("BLE scan started mode=${scanMode.label}")
      return ScanStartupResult(path = ScanPath.BLE, started = true)
    } catch (sec: SecurityException) {
      blePathActive = false
      DebugLog.log("BLE scan missing permission", level = android.util.Log.WARN, throwable = sec)
      return ScanStartupResult(
        path = ScanPath.BLE,
        started = false,
        reason = "Missing permission"
      )
    } catch (ex: Exception) {
      blePathActive = false
      DebugLog.log("BLE scan error: ${ex.message}", level = android.util.Log.ERROR, throwable = ex)
      return ScanStartupResult(
        path = ScanPath.BLE,
        started = false,
        reason = ex.message ?: "Unknown BLE error"
      )
    }
  }

  private fun stopBleScan() {
    blePathActive = false
    val scanner = currentLeScanner() ?: return
    try {
      scanner.stopScan(scanCallback)
      DebugLog.log("BLE scan stopped")
    } catch (_: SecurityException) {
      // Ignore
    }
  }

  private fun startClassicDiscovery(scanMode: ScanModePreset): ScanStartupResult {
    if (!scanMode.startsClassicDiscovery) {
      classicPathActive = false
      DebugLog.log("Classic discovery skipped in compatibility mode", level = android.util.Log.INFO)
      return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = "Skipped in compatibility mode"
      )
    }

    val adapter = bluetoothAdapter
      ?: return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = "Bluetooth adapter unavailable"
      )
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
      val started = adapter.startDiscovery()
      classicPathActive = started
      if (started) {
        DebugLog.log("Classic discovery started")
        return ScanStartupResult(path = ScanPath.CLASSIC, started = true)
      }

      DebugLog.log("Classic discovery failed to start", level = android.util.Log.WARN)
      return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = "Bluetooth classic discovery failed to start"
      )
    } catch (sec: SecurityException) {
      classicPathActive = false
      DebugLog.log("Classic discovery missing permission", level = android.util.Log.WARN, throwable = sec)
      return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = "Missing permission"
      )
    } catch (ex: Exception) {
      classicPathActive = false
      DebugLog.log("Classic discovery error: ${ex.message}", level = android.util.Log.ERROR, throwable = ex)
      return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = ex.message ?: "Unknown classic discovery error"
      )
    }
  }

  private fun stopClassicDiscovery() {
    val adapter = bluetoothAdapter ?: return
    classicPathActive = false
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

  private fun preflight(): ScanPreflightResult {
    val missingPermissions = PermissionsHelper.missingPermissions(context)
    val locationServicesEnabled = PermissionsHelper.isLocationServicesEnabled(context)

    if (missingPermissions.isNotEmpty()) {
      DebugLog.log(
        "Missing permissions: $missingPermissions",
        level = android.util.Log.WARN
      )
      return ScanPreflightResult(
        state = ScanState.MissingPermission,
        missingPermissions = missingPermissions,
        bluetoothEnabled = bluetoothAdapter?.takeIf { hasBluetoothAccess() }?.isEnabled,
        bleScannerAvailable = null,
        locationServicesEnabled = locationServicesEnabled
      )
    }

    if (bluetoothAdapter == null) {
      DebugLog.log("Bluetooth adapter missing", level = android.util.Log.WARN)
      return ScanPreflightResult(
        state = ScanState.Unsupported,
        missingPermissions = missingPermissions,
        bluetoothEnabled = null,
        bleScannerAvailable = null,
        locationServicesEnabled = locationServicesEnabled
      )
    }

    val bluetoothEnabled = try {
      bluetoothAdapter.isEnabled
    } catch (sec: SecurityException) {
      DebugLog.log("Bluetooth enabled check missing permission", level = android.util.Log.WARN, throwable = sec)
      return ScanPreflightResult(
        state = ScanState.MissingPermission,
        missingPermissions = missingPermissions,
        bluetoothEnabled = null,
        bleScannerAvailable = null,
        locationServicesEnabled = locationServicesEnabled
      )
    }

    if (!bluetoothEnabled) {
      DebugLog.log("Bluetooth disabled", level = android.util.Log.WARN)
      return ScanPreflightResult(
        state = ScanState.BluetoothOff,
        missingPermissions = missingPermissions,
        bluetoothEnabled = false,
        bleScannerAvailable = null,
        locationServicesEnabled = locationServicesEnabled
      )
    }

    if (!locationServicesEnabled) {
      DebugLog.log("Location services disabled on pre-Android-12 device", level = android.util.Log.WARN)
      return ScanPreflightResult(
        state = ScanState.LocationServicesOff,
        missingPermissions = missingPermissions,
        bluetoothEnabled = true,
        bleScannerAvailable = currentLeScanner() != null,
        locationServicesEnabled = false
      )
    }

    return ScanPreflightResult(
      state = ScanState.Idle,
      missingPermissions = missingPermissions,
      bluetoothEnabled = true,
      bleScannerAvailable = currentLeScanner() != null,
      locationServicesEnabled = true
    )
  }

  private fun handleBleResult(result: ScanResult) {
    val device = result.device
    val address = safeAddress(device)
    val advertisedName = normalizeName(result.scanRecord?.deviceName)
    val systemName = safeName(device)
    val identity = ObservedIdentityResolver.forBle(
      advertisedName = advertisedName,
      systemName = systemName,
      address = address,
      vendorRegistry = vendorRegistry
    )
    val serviceUuids = result.scanRecord?.serviceUuids
      ?.mapNotNull { it.uuid?.toString() }
      ?: emptyList()
    val manufacturerData = parseManufacturerData(result.scanRecord?.manufacturerSpecificData)

    val input = ObservationInput(
      name = identity.displayName,
      address = address,
      rssi = result.rssi,
      timestamp = System.currentTimeMillis(),
      serviceUuids = serviceUuids,
      manufacturerData = manufacturerData,
      source = "BLE",
      advertisedName = identity.advertisedName,
      systemName = identity.systemName,
      nameSource = identity.nameSource.metadataValue,
      vendorName = identity.vendorName,
      vendorSource = identity.vendorSource,
      locallyAdministeredAddress = identity.locallyAdministeredAddress
    )

    handleObservation(input)
  }

  private fun handleClassicResult(device: BluetoothDevice, rssi: Int) {
    val systemName = safeName(device)
    val address = safeAddress(device)
    val identity = ObservedIdentityResolver.forClassic(
      systemName = systemName,
      address = address,
      vendorRegistry = vendorRegistry
    )
    val input = ObservationInput(
      name = identity.displayName,
      address = address,
      rssi = rssi,
      timestamp = System.currentTimeMillis(),
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "Classic",
      systemName = identity.systemName,
      nameSource = identity.nameSource.metadataValue,
      vendorName = identity.vendorName,
      vendorSource = identity.vendorSource,
      locallyAdministeredAddress = identity.locallyAdministeredAddress
    )

    handleObservation(input)
  }

  private fun handleObservation(input: ObservationInput) {
    val key = DeviceKey.from(input)
    ScanDiagnosticsStore.update {
      it.copy(deviceKeys = it.deviceKeys + key)
    }
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
      emitAlertNotifications(
        key = key,
        input = input
      )
    }
  }

  private fun emitAlertNotifications(
    key: String,
    input: ObservationInput
  ) {
    val matches = DeviceAlertMatcher.findMatches(
      rules = enabledAlertRules,
      observation = AlertObservation(
        deviceKey = key,
        displayName = input.name,
        advertisedName = input.advertisedName,
        systemName = input.systemName,
        address = input.address,
        vendorName = input.vendorName,
        source = input.source
      )
    )

    matches.forEach { match ->
      val dedupeKey = buildString {
        append(match.rule.id)
        append(':')
        append(input.address ?: key)
      }
      if (!firedAlertKeys.add(dedupeKey)) {
        return@forEach
      }
      deviceAlertNotifier.notifyMatch(
        match = match,
        observation = AlertObservation(
          deviceKey = key,
          displayName = input.name,
          advertisedName = input.advertisedName,
          systemName = input.systemName,
          address = input.address,
          vendorName = input.vendorName,
          source = input.source
        )
      )
    }
  }

  private fun safeName(device: BluetoothDevice): String? {
    return try {
      normalizeName(device.name)
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
    json.put("advertisedName", input.advertisedName)
    json.put("systemName", input.systemName)
    json.put("nameSource", input.nameSource)
    json.put("vendorName", input.vendorName)
    json.put("vendorSource", input.vendorSource)
    json.put("locallyAdministeredAddress", input.locallyAdministeredAddress)
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

  private fun normalizeName(name: String?): String? {
    return name?.trim()?.takeIf { it.isNotEmpty() }
  }

  private fun recordBleCallbacks(count: Int) {
    ScanDiagnosticsStore.update {
      it.copy(
        bleCallbackCount = it.bleCallbackCount + count,
        rawCallbackCount = it.rawCallbackCount + count
      )
    }
  }

  private fun recordClassicCallback() {
    ScanDiagnosticsStore.update {
      it.copy(
        classicCallbackCount = it.classicCallbackCount + 1,
        rawCallbackCount = it.rawCallbackCount + 1
      )
    }
  }

  private fun interruptScan(state: ScanState) {
    timeoutJob?.cancel()
    timeoutJob = null
    stopBleScan()
    stopClassicDiscovery()
    ScanDiagnosticsStore.update {
      it.copy(outcome = ScanSessionOutcome.INTERRUPTED, timeoutReached = false)
    }
    _scanState.value = state
  }

  private fun finishTimedOutScan() {
    timeoutJob?.cancel()
    timeoutJob = null
    stopBleScan()
    stopClassicDiscovery()

    ScanDiagnosticsStore.update { snapshot ->
      snapshot.copy(
        timeoutReached = true,
        outcome = when {
          !snapshot.anyPathStarted -> ScanSessionOutcome.FAILED_TO_START
          snapshot.uniqueDeviceCount == 0 -> ScanSessionOutcome.ZERO_RESULTS
          else -> ScanSessionOutcome.RESULTS
        }
      )
    }

    val snapshot = ScanDiagnosticsStore.snapshot.value
    _scanState.value = ScanStateDecider.stateAfterTimeout(snapshot)
    when (val state = _scanState.value) {
      is ScanState.Complete -> {
        if (state.deviceCount == 0) {
          DebugLog.log("Scan ran but found no devices", level = android.util.Log.WARN)
        } else {
          DebugLog.log("Scan completed with ${state.deviceCount} devices", level = android.util.Log.INFO)
        }
      }
      is ScanState.Error -> DebugLog.log(state.message, level = android.util.Log.WARN)
      else -> Unit
    }
  }

  private fun hasBluetoothAccess(): Boolean {
    return try {
      bluetoothAdapter?.isEnabled
      true
    } catch (_: SecurityException) {
      false
    }
  }

  companion object {
  }
}
