package ninja.unagi.scan

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.SparseArray
import ninja.unagi.alerts.AlertObservation
import ninja.unagi.alerts.DeviceAlertMatcher
import ninja.unagi.alerts.DeviceAlertNotifier
import ninja.unagi.data.AlertRuleEntity
import ninja.unagi.data.AlertRuleRepository
import ninja.unagi.data.DeviceObservation
import ninja.unagi.data.DeviceRepository
import ninja.unagi.util.BluetoothAssignedNumbersProvider
import ninja.unagi.util.ClassificationFingerprint
import ninja.unagi.util.ClassificationMetadata
import ninja.unagi.util.DebugLog
import ninja.unagi.util.DeviceClassificationEngine
import ninja.unagi.util.ObservedTransport
import ninja.unagi.util.ObservedIdentityResolver
import ninja.unagi.util.PassiveAddressResolver
import ninja.unagi.util.PassiveDecoderContext
import ninja.unagi.util.PassiveVendorResolver
import ninja.unagi.util.PassiveVendorDecoderRegistry
import ninja.unagi.util.PermissionsHelper
import ninja.unagi.util.VendorPrefixRegistryProvider
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
  private val assignedNumbers by lazy { BluetoothAssignedNumbersProvider.get(context) }
  private fun currentLeScanner(): BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

  private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
  val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

  private var classicRestartJob: Job? = null
  private var receiverRegistered = false
  private var blePathActive = false
  private var classicPathActive = false
  private var currentScanMode: ScanModePreset = ScanModePreset.NORMAL
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
        classicRestartJob?.cancel()
        classicRestartJob = null
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
          classicPathActive = false
          if (_scanState.value is ScanState.Scanning && currentScanMode.startsClassicDiscovery) {
            scheduleClassicDiscoveryRestart()
          }
        }
      }
    }
  }

  fun startScan() {
    val continuousScanning = ContinuousScanPreferences.isEnabled(context)
    classicRestartJob?.cancel()
    classicRestartJob = null
    stopBleScan()
    stopClassicDiscovery()
    firedAlertKeys.clear()

    val scanMode = ScanModePreferences.get(context)
    currentScanMode = scanMode
    val preflight = preflight(continuousScanning)
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

    if (!classicResult.started && scanMode.startsClassicDiscovery) {
      scheduleClassicDiscoveryRestart()
    }
  }

  fun refreshState() {
    val continuousScanning = ContinuousScanPreferences.isEnabled(context)
    val preflight = preflight(continuousScanning)
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
    classicRestartJob?.cancel()
    classicRestartJob = null
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

  private fun scheduleClassicDiscoveryRestart() {
    if (!currentScanMode.startsClassicDiscovery || _scanState.value !is ScanState.Scanning) {
      return
    }
    if (classicRestartJob?.isActive == true) {
      return
    }
    classicRestartJob = scope.launch {
      delay(CLASSIC_RESTART_DELAY_MS)
      classicRestartJob = null
      if (_scanState.value !is ScanState.Scanning || !currentScanMode.startsClassicDiscovery) {
        return@launch
      }
      val result = startClassicDiscovery(currentScanMode)
      if (!result.started && _scanState.value is ScanState.Scanning) {
        DebugLog.log(
          "Classic discovery restart failed: ${result.reason ?: "unknown"}",
          level = android.util.Log.WARN
        )
        scheduleClassicDiscoveryRestart()
      }
    }
  }

  private fun preflight(continuousScanning: Boolean): ScanPreflightResult {
    val missingPermissions = PermissionsHelper.missingPermissions(context, continuousScanning)
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
    val scanRecord = result.scanRecord
    val address = safeAddress(device)
    val advertisedName = normalizeName(scanRecord?.deviceName)
    val systemName = safeName(device)
    val identity = ObservedIdentityResolver.forBle(
      advertisedName = advertisedName,
      systemName = systemName
    )
    val rawAddressType = safeBleAddressType(device)
    val addressInsight = PassiveAddressResolver.resolve(address, rawAddressType)
    val serviceUuids = scanRecord?.serviceUuids
      ?.mapNotNull { it.uuid?.toString() }
      ?: emptyList()
    val manufacturerData = parseManufacturerData(scanRecord?.manufacturerSpecificData)
    val serviceData = parseServiceData(scanRecord)
    val appearance = parseAppearance(scanRecord)
    val bluetoothClass = safeBluetoothClass(device)
    val transport = effectiveTransport(source = "BLE", deviceType = safeDeviceType(device))
    val vendorHint = PassiveVendorResolver.resolve(
      addressInsight = addressInsight,
      assignedNumbers = assignedNumbers,
      vendorRegistry = vendorRegistry,
      manufacturerData = manufacturerData,
      serviceUuids = serviceUuids,
      displayName = identity.displayName
    )
    val classificationFingerprint = ClassificationFingerprint.from(
      addressInsight = addressInsight,
      manufacturerData = manufacturerData,
      serviceUuids = serviceUuids,
      serviceData = serviceData,
      appearance = appearance,
      classicMajorClass = bluetoothClass?.majorDeviceClass,
      classicDeviceClass = bluetoothClass?.deviceClass,
      displayName = identity.displayName
    )
    val classification = DeviceClassificationEngine.classify(
      metadata = ClassificationMetadata(
        transport = transport,
        addressType = addressInsight.addressType,
        manufacturerData = manufacturerData,
        serviceUuids = serviceUuids,
        serviceData = serviceData,
        appearance = appearance,
        classicMajorClass = bluetoothClass?.majorDeviceClass,
        classicDeviceClass = bluetoothClass?.deviceClass,
        displayName = identity.displayName
      ),
      assignedNumbers = assignedNumbers
    )
    val passiveDecoderHints = PassiveVendorDecoderRegistry.decode(
      PassiveDecoderContext(
        displayName = identity.displayName,
        vendorName = vendorHint.vendorName,
        manufacturerData = manufacturerData,
        serviceUuids = serviceUuids,
        serviceData = serviceData,
        addressType = addressInsight.addressType
      )
    )
    val deviceType = safeDeviceType(device)
    val bondState = safeBondState(device)

    val input = ObservationInput(
      name = identity.displayName,
      address = address,
      rssi = result.rssi,
      timestamp = System.currentTimeMillis(),
      serviceUuids = serviceUuids,
      manufacturerData = manufacturerData,
      serviceData = serviceData,
      source = "BLE",
      transport = transport.metadataValue,
      advertisedName = identity.advertisedName,
      systemName = identity.systemName,
      nameSource = identity.nameSource.metadataValue,
      vendorName = vendorHint.vendorName,
      vendorSource = vendorHint.vendorSource,
      vendorConfidence = vendorHint.confidence.metadataValue,
      locallyAdministeredAddress = addressInsight.locallyAdministered,
      normalizedAddress = addressInsight.normalizedAddress,
      addressType = addressInsight.addressType.metadataValue,
      addressTypeLabel = addressInsight.addressType.label,
      rawAndroidAddressType = rawAddressType,
      deviceType = deviceType,
      deviceTypeLabel = formatDeviceType(deviceType),
      bondState = bondState,
      bondStateLabel = formatBondState(bondState),
      advertiseFlags = scanRecord?.advertiseFlags?.takeIf { it >= 0 },
      txPowerLevel = scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
      resultTxPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
      } else {
        null
      },
      connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        result.isConnectable
      } else {
        null
      },
      legacy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        result.isLegacy
      } else {
        null
      },
      dataStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        result.dataStatus
      } else {
        null
      },
      primaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        result.primaryPhy.takeIf { it != ScanResult.PHY_UNUSED }
      } else {
        null
      },
      secondaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        result.secondaryPhy.takeIf { it != ScanResult.PHY_UNUSED }
      } else {
        null
      },
      advertisingSid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        result.advertisingSid.takeIf { it != ScanResult.SID_NOT_PRESENT }
      } else {
        null
      },
      periodicAdvertisingInterval = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        result.periodicAdvertisingInterval.takeIf { it != ScanResult.PERIODIC_INTERVAL_NOT_PRESENT }
      } else {
        null
      },
      appearance = appearance,
      appearanceLabel = formatAppearance(appearance),
      classicMajorClass = bluetoothClass?.majorDeviceClass,
      classicMajorClassLabel = formatClassicMajorClass(bluetoothClass?.majorDeviceClass),
      classicDeviceClass = bluetoothClass?.deviceClass,
      classicDeviceClassLabel = formatClassicDeviceClass(bluetoothClass?.deviceClass),
      passiveDecoderHints = passiveDecoderHints,
      classificationFingerprint = classificationFingerprint,
      classificationCategory = classification.category.metadataValue
        .takeIf { classification.category.metadataValue != "unknown" },
      classificationLabel = classification.category.label
        .takeIf { classification.category.metadataValue != "unknown" },
      classificationConfidence = classification.confidence.metadataValue
        .takeIf { classification.confidence.metadataValue != "unknown" },
      classificationEvidence = classification.evidence
    )

    handleObservation(input)
  }

  private fun handleClassicResult(device: BluetoothDevice, rssi: Int) {
    val systemName = safeName(device)
    val address = safeAddress(device)
    val identity = ObservedIdentityResolver.forClassic(
      systemName = systemName
    )
    val addressInsight = PassiveAddressResolver.resolve(address, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
    val bluetoothClass = safeBluetoothClass(device)
    val deviceType = safeDeviceType(device)
    val bondState = safeBondState(device)
    val transport = effectiveTransport(source = "Classic", deviceType = deviceType)
    val vendorHint = PassiveVendorResolver.resolve(
      addressInsight = addressInsight,
      assignedNumbers = assignedNumbers,
      vendorRegistry = vendorRegistry,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      displayName = identity.displayName
    )
    val classificationFingerprint = ClassificationFingerprint.from(
      addressInsight = addressInsight,
      manufacturerData = emptyMap(),
      serviceUuids = emptyList(),
      serviceData = emptyMap(),
      appearance = null,
      classicMajorClass = bluetoothClass?.majorDeviceClass,
      classicDeviceClass = bluetoothClass?.deviceClass,
      displayName = identity.displayName
    )
    val classification = DeviceClassificationEngine.classify(
      metadata = ClassificationMetadata(
        transport = transport,
        addressType = addressInsight.addressType,
        manufacturerData = emptyMap(),
        serviceUuids = emptyList(),
        serviceData = emptyMap(),
        appearance = null,
        classicMajorClass = bluetoothClass?.majorDeviceClass,
        classicDeviceClass = bluetoothClass?.deviceClass,
        displayName = identity.displayName
      ),
      assignedNumbers = assignedNumbers
    )
    val passiveDecoderHints = PassiveVendorDecoderRegistry.decode(
      PassiveDecoderContext(
        displayName = identity.displayName,
        vendorName = vendorHint.vendorName,
        manufacturerData = emptyMap(),
        serviceUuids = emptyList(),
        serviceData = emptyMap(),
        addressType = addressInsight.addressType
      )
    )
    val input = ObservationInput(
      name = identity.displayName,
      address = address,
      rssi = rssi,
      timestamp = System.currentTimeMillis(),
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "Classic",
      transport = transport.metadataValue,
      systemName = identity.systemName,
      nameSource = identity.nameSource.metadataValue,
      vendorName = vendorHint.vendorName,
      vendorSource = vendorHint.vendorSource,
      vendorConfidence = vendorHint.confidence.metadataValue,
      locallyAdministeredAddress = addressInsight.locallyAdministered,
      normalizedAddress = addressInsight.normalizedAddress,
      addressType = addressInsight.addressType.metadataValue,
      addressTypeLabel = addressInsight.addressType.label,
      rawAndroidAddressType = BluetoothDevice.ADDRESS_TYPE_PUBLIC,
      deviceType = deviceType,
      deviceTypeLabel = formatDeviceType(deviceType),
      bondState = bondState,
      bondStateLabel = formatBondState(bondState),
      classicMajorClass = bluetoothClass?.majorDeviceClass,
      classicMajorClassLabel = formatClassicMajorClass(bluetoothClass?.majorDeviceClass),
      classicDeviceClass = bluetoothClass?.deviceClass,
      classicDeviceClassLabel = formatClassicDeviceClass(bluetoothClass?.deviceClass),
      passiveDecoderHints = passiveDecoderHints,
      classificationFingerprint = classificationFingerprint,
      classificationCategory = classification.category.metadataValue
        .takeIf { classification.category.metadataValue != "unknown" },
      classificationLabel = classification.category.label
        .takeIf { classification.category.metadataValue != "unknown" },
      classificationConfidence = classification.confidence.metadataValue
        .takeIf { classification.confidence.metadataValue != "unknown" },
      classificationEvidence = classification.evidence
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

  private fun safeBleAddressType(device: BluetoothDevice): Int? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      try {
        device.addressType
      } catch (_: SecurityException) {
        null
      }
    } else {
      null
    }
  }

  private fun safeDeviceType(device: BluetoothDevice): Int? {
    return try {
      device.type
    } catch (_: SecurityException) {
      null
    }
  }

  private fun safeBondState(device: BluetoothDevice): Int? {
    return try {
      device.bondState
    } catch (_: SecurityException) {
      null
    }
  }

  private fun safeBluetoothClass(device: BluetoothDevice): BluetoothClass? {
    return try {
      device.bluetoothClass
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

  private fun parseServiceData(scanRecord: ScanRecord?): Map<String, String> {
    val data = scanRecord?.serviceData ?: return emptyMap()
    return data.entries.associate { (uuid, payload) ->
      uuid.uuid.toString() to payload.toHexString()
    }
  }

  private fun parseAppearance(scanRecord: ScanRecord?): Int? {
    val bytes = scanRecord?.bytes ?: return null
    var index = 0
    while (index < bytes.size) {
      val length = bytes[index].toInt() and 0xFF
      if (length == 0) {
        break
      }
      val typeIndex = index + 1
      val dataStart = typeIndex + 1
      val dataEndExclusive = (index + length + 1).coerceAtMost(bytes.size)
      if (typeIndex >= bytes.size || dataStart >= dataEndExclusive) {
        break
      }
      val type = bytes[typeIndex].toInt() and 0xFF
      if (type == ScanRecord.DATA_TYPE_APPEARANCE && dataStart + 1 < dataEndExclusive) {
        val low = bytes[dataStart].toInt() and 0xFF
        val high = bytes[dataStart + 1].toInt() and 0xFF
        return low or (high shl 8)
      }
      index += length + 1
    }
    return null
  }

  private fun buildMetadataJson(input: ObservationInput): String {
    val json = JSONObject()
    json.put("source", input.source)
    json.putIfNotNull("transport", input.transport)
    json.put("name", input.name)
    json.put("address", input.address)
    json.put("advertisedName", input.advertisedName)
    json.put("systemName", input.systemName)
    json.put("nameSource", input.nameSource)
    json.putIfNotNull("vendorName", input.vendorName)
    json.putIfNotNull("vendorSource", input.vendorSource)
    json.putIfNotNull("vendorConfidence", input.vendorConfidence)
    json.putIfNotNull("locallyAdministeredAddress", input.locallyAdministeredAddress)
    json.putIfNotNull("normalizedAddress", input.normalizedAddress)
    json.putIfNotNull("addressType", input.addressType)
    json.putIfNotNull("addressTypeLabel", input.addressTypeLabel)
    json.putIfNotNull("rawAndroidAddressType", input.rawAndroidAddressType)
    json.putIfNotNull("deviceType", input.deviceType)
    json.putIfNotNull("deviceTypeLabel", input.deviceTypeLabel)
    json.putIfNotNull("bondState", input.bondState)
    json.putIfNotNull("bondStateLabel", input.bondStateLabel)
    json.putIfNotNull("advertiseFlags", input.advertiseFlags)
    json.putIfNotNull("txPowerLevel", input.txPowerLevel)
    json.putIfNotNull("resultTxPower", input.resultTxPower)
    json.putIfNotNull("connectable", input.connectable)
    json.putIfNotNull("legacy", input.legacy)
    json.putIfNotNull("dataStatus", input.dataStatus)
    json.putIfNotNull("primaryPhy", input.primaryPhy)
    json.putIfNotNull("secondaryPhy", input.secondaryPhy)
    json.putIfNotNull("advertisingSid", input.advertisingSid)
    json.putIfNotNull("periodicAdvertisingInterval", input.periodicAdvertisingInterval)
    json.putIfNotNull("appearance", input.appearance)
    json.putIfNotNull("appearanceLabel", input.appearanceLabel)
    json.putIfNotNull("classicMajorClass", input.classicMajorClass)
    json.putIfNotNull("classicMajorClassLabel", input.classicMajorClassLabel)
    json.putIfNotNull("classicDeviceClass", input.classicDeviceClass)
    json.putIfNotNull("classicDeviceClassLabel", input.classicDeviceClassLabel)

    val passiveDecoderHints = JSONArray()
    input.passiveDecoderHints.forEach { passiveDecoderHints.put(it) }
    json.put("passiveDecoderHints", passiveDecoderHints)

    json.putIfNotNull("classificationFingerprint", input.classificationFingerprint)
    json.putIfNotNull("classificationCategory", input.classificationCategory)
    json.putIfNotNull("classificationLabel", input.classificationLabel)
    json.putIfNotNull("classificationConfidence", input.classificationConfidence)
    json.put("rssi", input.rssi)
    json.put("timestamp", input.timestamp)

    val services = JSONArray()
    input.serviceUuids.forEach { services.put(it) }
    json.put("serviceUuids", services)

    val serviceDataJson = JSONObject()
    input.serviceData.forEach { (uuid, data) ->
      serviceDataJson.put(uuid, data)
    }
    json.put("serviceData", serviceDataJson)

    val manufacturerJson = JSONObject()
    input.manufacturerData.forEach { (id, data) ->
      manufacturerJson.put(id.toString(), data)
    }
    json.put("manufacturerData", manufacturerJson)

    val classificationEvidence = JSONArray()
    input.classificationEvidence.forEach { classificationEvidence.put(it) }
    json.put("classificationEvidence", classificationEvidence)

    return json.toString(2)
  }

  private fun JSONObject.putIfNotNull(key: String, value: Any?) {
    if (value != null) {
      put(key, value)
    }
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
    classicRestartJob?.cancel()
    classicRestartJob = null
    stopBleScan()
    stopClassicDiscovery()
    ScanDiagnosticsStore.update {
      it.copy(outcome = ScanSessionOutcome.INTERRUPTED, timeoutReached = false)
    }
    _scanState.value = state
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
    private const val CLASSIC_RESTART_DELAY_MS = 2_000L

    private fun effectiveTransport(source: String, deviceType: Int?): ObservedTransport {
      return when {
        deviceType == BluetoothDevice.DEVICE_TYPE_DUAL -> ObservedTransport.DUAL
        source.equals("BLE", ignoreCase = true) || deviceType == BluetoothDevice.DEVICE_TYPE_LE -> ObservedTransport.BLE
        source.equals("Classic", ignoreCase = true) || deviceType == BluetoothDevice.DEVICE_TYPE_CLASSIC -> ObservedTransport.CLASSIC
        else -> ObservedTransport.UNKNOWN
      }
    }

    private fun formatDeviceType(deviceType: Int?): String? {
      return when (deviceType) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
        BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "Unknown"
        else -> null
      }
    }

    private fun formatBondState(bondState: Int?): String? {
      return when (bondState) {
        BluetoothDevice.BOND_BONDED -> "Bonded"
        BluetoothDevice.BOND_BONDING -> "Bonding"
        BluetoothDevice.BOND_NONE -> "Not bonded"
        else -> null
      }
    }

    private fun formatAppearance(appearance: Int?): String? {
      return when {
        appearance == null -> null
        appearance in 0x03C0..0x03FF -> "Human interface device"
        appearance in 0x0340..0x037F -> "Watch / wearable"
        appearance in 0x0380..0x03BF -> "Heart-rate / health"
        appearance in 0x0940..0x097F -> "Audio / media"
        else -> "Appearance class"
      }
    }

    private fun formatClassicMajorClass(majorClass: Int?): String? {
      return when (majorClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio / video"
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.HEALTH -> "Health"
        BluetoothClass.Device.Major.IMAGING -> "Imaging"
        BluetoothClass.Device.Major.MISC -> "Misc"
        BluetoothClass.Device.Major.NETWORKING -> "Networking"
        BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.TOY -> "Toy"
        BluetoothClass.Device.Major.UNCATEGORIZED -> "Uncategorized"
        BluetoothClass.Device.Major.WEARABLE -> "Wearable"
        else -> null
      }
    }

    private fun formatClassicDeviceClass(deviceClass: Int?): String? {
      return when (deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "Headphones"
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "Wearable headset"
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "Hands-free"
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "Hi-fi audio"
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> "Loudspeaker"
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "Car audio"
        BluetoothClass.Device.COMPUTER_LAPTOP -> "Laptop"
        BluetoothClass.Device.PHONE_SMART -> "Smartphone"
        BluetoothClass.Device.PHONE_CELLULAR -> "Cellular phone"
        BluetoothClass.Device.PERIPHERAL_KEYBOARD -> "Keyboard"
        BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> "Keyboard / pointing"
        BluetoothClass.Device.PERIPHERAL_POINTING -> "Pointing device"
        BluetoothClass.Device.PERIPHERAL_NON_KEYBOARD_NON_POINTING -> "Peripheral"
        BluetoothClass.Device.TOY_CONTROLLER -> "Controller"
        BluetoothClass.Device.HEALTH_GLUCOSE -> "Glucose meter"
        BluetoothClass.Device.HEALTH_PULSE_RATE -> "Pulse monitor"
        BluetoothClass.Device.HEALTH_PULSE_OXIMETER -> "Pulse oximeter"
        BluetoothClass.Device.HEALTH_THERMOMETER -> "Thermometer"
        BluetoothClass.Device.HEALTH_WEIGHING -> "Scale"
        else -> null
      }
    }
  }
}
