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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ScanController(
  private val context: Context,
  private val scope: CoroutineScope,
  val observationRecorder: ObservationRecorder
) {
  private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
  private val vendorRegistry by lazy { VendorPrefixRegistryProvider.get(context) }
  private val assignedNumbers by lazy { BluetoothAssignedNumbersProvider.get(context) }
  private fun currentLeScanner(): BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

  private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
  val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

  @Volatile
  private var classicRestartJob: Job? = null
  @Volatile
  private var activeSession: ScanSession? = null
  private val nextScanSessionId = AtomicLong(0L)

  private class ScanSession(
    val id: Long,
    val scanMode: ScanModePreset
  ) {
    val gate = Any()
    val counters = ScanSessionCounters()
    lateinit var scanCallback: ScanCallback
    lateinit var discoveryReceiver: BroadcastReceiver

    @Volatile var acceptingBle = true
    @Volatile var acceptingClassic = true
    @Volatile var acceptingSession = true
    @Volatile var blePathActive = false
    @Volatile var classicPathActive = false
    @Volatile var receiverRegistered = false
    @Volatile var classicRestartFailures = 0
  }

  private class ScanSessionCounters {
    val bleCallbacks = AtomicInteger(0)
    val classicCallbacks = AtomicInteger(0)
    val coalescedCallbacks = AtomicInteger(0)
    val droppedCallbacks = AtomicInteger(0)
    val lateCallbacks = AtomicInteger(0)
    val queueHighWaterMark = AtomicInteger(0)

    fun observeQueueDepth(depth: Int) {
      while (true) {
        val current = queueHighWaterMark.get()
        if (depth <= current || queueHighWaterMark.compareAndSet(current, depth)) {
          return
        }
      }
    }
  }

  private class ByteArrayContentKey(bytes: ByteArray) {
    private val value = bytes.copyOf()
    private val hash = value.contentHashCode()

    override fun equals(other: Any?): Boolean {
      return other is ByteArrayContentKey && value.contentEquals(other.value)
    }

    override fun hashCode(): Int = hash
  }

  private data class BleReportKey(
    val sessionId: Long,
    val address: String?,
    val advertisement: ByteArrayContentKey?
  )

  private data class ClassicReportKey(
    val sessionId: Long,
    val address: String
  )

  private sealed interface ScanWork {
    val session: ScanSession

    data class BleResult(
      override val session: ScanSession,
      val result: ScanResult,
      val coalescingKey: Any?
    ) : ScanWork

    data class ClassicResult(
      override val session: ScanSession,
      val device: BluetoothDevice,
      val rssi: Int,
      val coalescingKey: Any?
    ) : ScanWork

    data class BeginSession(override val session: ScanSession) : ScanWork

    data class EndSession(
      override val session: ScanSession,
      val outcome: ScanSessionOutcome
    ) : ScanWork

    data class BleFailure(
      override val session: ScanSession,
      val errorCode: Int,
      val terminal: Boolean
    ) : ScanWork

    data class ClassicFinished(override val session: ScanSession) : ScanWork
  }

  private val scanWorkQueue = ScanWorkMailbox<ScanWork>(
    reportCapacity = SCAN_REPORT_QUEUE_CAPACITY,
    isControl = { work ->
      work !is ScanWork.BleResult && work !is ScanWork.ClassicResult
    },
    reportKey = { work ->
      when (work) {
        is ScanWork.BleResult -> work.coalescingKey
        is ScanWork.ClassicResult -> work.coalescingKey
        else -> null
      }
    }
  )

  init {
    scope.launch {
      while (true) {
        val work = scanWorkQueue.receive()
        try {
          when (work) {
            is ScanWork.BleResult -> handleBleResult(work.result, work.session.id)
            is ScanWork.ClassicResult -> handleClassicResult(
              device = work.device,
              rssi = work.rssi,
              diagnosticsSessionId = work.session.id
            )
            is ScanWork.BeginSession -> observationRecorder.clearFiredAlerts()
            is ScanWork.EndSession -> finishSession(work.session, work.outcome)
            is ScanWork.BleFailure -> handleBleFailure(work)
            is ScanWork.ClassicFinished -> handleClassicFinished(work.session)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          DebugLog.log(
            "Background scan result processing failed: ${error.message}",
            level = android.util.Log.ERROR,
            throwable = error
          )
        }
      }
    }
    scope.launch {
      while (true) {
        delay(DIAGNOSTICS_PUBLISH_INTERVAL_MS)
        activeSession?.let(::publishSessionDiagnostics)
      }
    }
  }

  fun startScan() {
    val continuousScanning = ContinuousScanPreferences.isEnabled(context)
    val scanMode = ScanModePreferences.get(context)
    activeSession?.let { previous ->
      retireSession(previous, ScanSessionOutcome.INTERRUPTED)
    }
    val session = createSession(scanMode)
    val preflight = preflight(continuousScanning)
    ScanDiagnosticsStore.startSession(
      sessionId = session.id,
      snapshot = ScanDiagnosticsSnapshot(
        scanMode = scanMode,
        missingPermissions = preflight.missingPermissions,
        bluetoothEnabled = preflight.bluetoothEnabled,
        locationServicesEnabled = preflight.locationServicesEnabled,
        bleScannerUnavailable = preflight.bleScannerAvailable == false
      )
    )
    scanWorkQueue.offer(ScanWork.BeginSession(session))

    if (preflight.state != ScanState.Idle) {
      synchronized(session.gate) {
        session.acceptingSession = false
        session.acceptingBle = false
        session.acceptingClassic = false
      }
      _scanState.value = preflight.state
      DebugLog.log("Scan blocked by preflight state=${preflight.state}", level = android.util.Log.WARN)
      return
    }

    activeSession = session
    DebugLog.log("Starting scan")
    ScanDiagnosticsStore.updateForSession(session.id) {
      it.copy(startTimeMs = System.currentTimeMillis(), scanMode = scanMode)
    }

    val bleResult = startBleScan(session)
    val classicResult = startClassicDiscovery(session)
    ScanDiagnosticsStore.updateForSession(session.id) {
      it.copy(
        bleStartup = bleResult,
        classicStartup = classicResult,
        bleScannerUnavailable = bleResult.reason == ScanStateDecider.BLE_SCANNER_UNAVAILABLE
      )
    }

    if (
      bleResult.reason == STARTUP_REASON_MISSING_PERMISSION ||
        classicResult.reason == STARTUP_REASON_MISSING_PERMISSION
    ) {
      retireSession(session, ScanSessionOutcome.FAILED_TO_START)
      _scanState.value = ScanState.MissingPermission
      DebugLog.log("Scan startup blocked by permission failure reported from Bluetooth stack", level = android.util.Log.WARN)
      return
    }

    val nextState = ScanStateDecider.stateAfterStartup(listOf(bleResult, classicResult))
    _scanState.value = nextState
    DebugLog.log(
      "Scan startup bleStarted=${bleResult.started} classicStarted=${classicResult.started}"
    )

    if (nextState !is ScanState.Scanning) {
      retireSession(session, ScanSessionOutcome.FAILED_TO_START)
      DebugLog.log((nextState as ScanState.Error).message, level = android.util.Log.WARN)
      return
    }

    if (!classicResult.started && scanMode.startsClassicDiscovery) {
      scheduleClassicDiscoveryRestart(session)
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
      activeSession?.let { session -> interruptScan(state, session) }
        ?: run { _scanState.value = state }
      DebugLog.log("Scanning interrupted by preflight state=$state", level = android.util.Log.WARN)
      return
    }

    if (_scanState.value !is ScanState.Scanning) {
      _scanState.value = state
    }
  }

  fun stopScan() {
    val session = activeSession
    if (session != null) {
      retireSession(session, ScanSessionOutcome.INTERRUPTED)
    } else {
      observationRecorder.flushPending()
      observationRecorder.clearFiredAlerts()
    }
    _scanState.value = ScanState.Idle
    DebugLog.log("Stopping scan")
  }

  private fun createSession(scanMode: ScanModePreset): ScanSession {
    val session = ScanSession(
      id = nextScanSessionId.incrementAndGet(),
      scanMode = scanMode
    )
    session.scanCallback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        session.counters.bleCallbacks.incrementAndGet()
        offerBleResult(session, result)
      }

      override fun onBatchScanResults(results: MutableList<ScanResult>) {
        session.counters.bleCallbacks.addAndGet(results.size)
        results.forEach { result -> offerBleResult(session, result) }
      }

      override fun onScanFailed(errorCode: Int) {
        enqueueBleFailure(session, errorCode)
      }
    }
    session.discoveryReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
          BluetoothDevice.ACTION_FOUND -> {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
              @Suppress("DEPRECATION")
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (device != null) {
              session.counters.classicCallbacks.incrementAndGet()
              offerClassicResult(
                session = session,
                device = device,
                rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
              )
            }
          }

          BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> enqueueClassicFinished(session)
        }
      }
    }
    return session
  }

  private fun startBleScan(session: ScanSession): ScanStartupResult {
    synchronized(session.gate) {
      if (!session.acceptingSession) {
        return ScanStartupResult(
          path = ScanPath.BLE,
          started = false,
          reason = STARTUP_REASON_SESSION_ENDED
        )
      }
      session.acceptingBle = true
    }
    val scanner = currentLeScanner()
    if (scanner == null) {
      DebugLog.log("BluetoothLeScanner unavailable", level = android.util.Log.WARN)
      synchronized(session.gate) {
        session.blePathActive = false
        session.acceptingBle = false
      }
      return ScanStartupResult(
        path = ScanPath.BLE,
        started = false,
        reason = ScanStateDecider.BLE_SCANNER_UNAVAILABLE
      )
    }
    try {
      val settings = ScanSettings.Builder()
        .setScanMode(session.scanMode.bleScanMode)
        .build()
      scanner.startScan(null, settings, session.scanCallback)
      val sessionStillActive = synchronized(session.gate) {
        if (session.acceptingSession) {
          session.blePathActive = true
          true
        } else {
          session.acceptingBle = false
          false
        }
      }
      if (!sessionStillActive) {
        runCatching { scanner.stopScan(session.scanCallback) }
        return ScanStartupResult(
          path = ScanPath.BLE,
          started = false,
          reason = STARTUP_REASON_SESSION_ENDED
        )
      }
      DebugLog.log("BLE scan started mode=${session.scanMode.label}")
      return ScanStartupResult(path = ScanPath.BLE, started = true)
    } catch (sec: SecurityException) {
      synchronized(session.gate) {
        session.blePathActive = false
        session.acceptingBle = false
      }
      DebugLog.log("BLE scan missing permission", level = android.util.Log.WARN, throwable = sec)
      return ScanStartupResult(
        path = ScanPath.BLE,
        started = false,
        reason = "Missing permission"
      )
    } catch (ex: Exception) {
      synchronized(session.gate) {
        session.blePathActive = false
        session.acceptingBle = false
      }
      DebugLog.log("BLE scan error: ${ex.message}", level = android.util.Log.ERROR, throwable = ex)
      return ScanStartupResult(
        path = ScanPath.BLE,
        started = false,
        reason = ex.message ?: "Unknown BLE error"
      )
    }
  }

  private fun stopBleScan(session: ScanSession) {
    session.blePathActive = false
    val scanner = currentLeScanner() ?: return
    try {
      scanner.stopScan(session.scanCallback)
      DebugLog.log("BLE scan stopped")
    } catch (_: SecurityException) {
      // Ignore
    }
  }

  private fun startClassicDiscovery(session: ScanSession): ScanStartupResult {
    synchronized(session.gate) {
      if (!session.acceptingSession) {
        return ScanStartupResult(
          path = ScanPath.CLASSIC,
          started = false,
          reason = STARTUP_REASON_SESSION_ENDED
        )
      }
    }
    if (!session.scanMode.startsClassicDiscovery) {
      synchronized(session.gate) {
        session.classicPathActive = false
        session.acceptingClassic = false
      }
      DebugLog.log("Classic discovery skipped in compatibility mode", level = android.util.Log.INFO)
      return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = "Skipped in compatibility mode"
      )
    }

    val adapter = bluetoothAdapter
    if (adapter == null) {
      synchronized(session.gate) {
        session.classicPathActive = false
        session.acceptingClassic = false
      }
      return ScanStartupResult(
          path = ScanPath.CLASSIC,
          started = false,
          reason = "Bluetooth adapter unavailable"
        )
    }
    if (!session.receiverRegistered) {
      val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(session.discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(session.discoveryReceiver, filter)
      }
      session.receiverRegistered = true
    }

    try {
      synchronized(session.gate) {
        session.acceptingClassic = true
      }
      if (adapter.isDiscovering) {
        adapter.cancelDiscovery()
      }
      val started = adapter.startDiscovery()
      val sessionStillActive = synchronized(session.gate) {
        if (session.acceptingSession) {
          session.classicPathActive = started
          true
        } else {
          session.acceptingClassic = false
          false
        }
      }
      if (!sessionStillActive) {
        runCatching { adapter.cancelDiscovery() }
        if (session.receiverRegistered) {
          runCatching { context.unregisterReceiver(session.discoveryReceiver) }
          session.receiverRegistered = false
        }
        return ScanStartupResult(
          path = ScanPath.CLASSIC,
          started = false,
          reason = STARTUP_REASON_SESSION_ENDED
        )
      }
      if (started) {
        session.classicRestartFailures = 0
        DebugLog.log("Classic discovery started")
        return ScanStartupResult(path = ScanPath.CLASSIC, started = true)
      }
      session.acceptingClassic = false

      val missingPermissions = PermissionsHelper.missingPermissions(
        context,
        ContinuousScanPreferences.isEnabled(context)
      )
      if (missingPermissions.isNotEmpty()) {
        DebugLog.log(
          "Classic discovery could not start because permissions are missing: $missingPermissions",
          level = android.util.Log.WARN
        )
        return ScanStartupResult(
          path = ScanPath.CLASSIC,
          started = false,
          reason = STARTUP_REASON_MISSING_PERMISSION
        )
      }

      DebugLog.log("Classic discovery failed to start", level = android.util.Log.WARN)
      return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = "Bluetooth classic discovery failed to start"
      )
    } catch (sec: SecurityException) {
      session.classicPathActive = false
      session.acceptingClassic = false
      DebugLog.log("Classic discovery missing permission", level = android.util.Log.WARN, throwable = sec)
      return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = STARTUP_REASON_MISSING_PERMISSION
      )
    } catch (ex: Exception) {
      session.classicPathActive = false
      session.acceptingClassic = false
      DebugLog.log("Classic discovery error: ${ex.message}", level = android.util.Log.ERROR, throwable = ex)
      return ScanStartupResult(
        path = ScanPath.CLASSIC,
        started = false,
        reason = ex.message ?: "Unknown classic discovery error"
      )
    }
  }

  private fun stopClassicDiscovery(session: ScanSession) {
    session.classicPathActive = false
    bluetoothAdapter?.let { adapter ->
      try {
        if (adapter.isDiscovering) {
          adapter.cancelDiscovery()
        }
      } catch (_: SecurityException) {
        // Ignore
      }
    }

    if (session.receiverRegistered) {
      try {
        context.unregisterReceiver(session.discoveryReceiver)
      } catch (_: IllegalArgumentException) {
        // Ignore
      }
      session.receiverRegistered = false
    }
  }

  private fun scheduleClassicDiscoveryRestart(session: ScanSession) {
    if (
      activeSession !== session ||
      !session.acceptingSession ||
      !session.scanMode.startsClassicDiscovery ||
      _scanState.value !is ScanState.Scanning
    ) {
      return
    }
    if (classicRestartJob?.isActive == true) {
      return
    }
    if (session.classicRestartFailures >= MAX_CLASSIC_RESTART_FAILURES) {
      DebugLog.log(
        "Classic discovery disabled for this scan after ${session.classicRestartFailures} consecutive restart failures",
        level = android.util.Log.WARN
      )
      return
    }
    classicRestartJob = scope.launch {
      delay(nextClassicRestartDelayMs(session.classicRestartFailures))
      classicRestartJob = null
      if (
        activeSession !== session ||
        !session.acceptingSession ||
        _scanState.value !is ScanState.Scanning ||
        !session.scanMode.startsClassicDiscovery
      ) {
        return@launch
      }
      val preflight = preflight(ContinuousScanPreferences.isEnabled(context))
      if (preflight.state != ScanState.Idle) {
        interruptScan(preflight.state, session)
        return@launch
      }
      val result = startClassicDiscovery(session)
      ScanDiagnosticsStore.updateForSession(session.id) { it.copy(classicStartup = result) }
      if (!result.started && _scanState.value is ScanState.Scanning) {
        if (result.reason == STARTUP_REASON_MISSING_PERMISSION) {
          DebugLog.log("Classic discovery restart blocked by missing permission", level = android.util.Log.WARN)
          interruptScan(ScanState.MissingPermission, session)
          return@launch
        }
        session.classicRestartFailures += 1
        DebugLog.log(
          "Classic discovery restart failed (${session.classicRestartFailures}/$MAX_CLASSIC_RESTART_FAILURES): ${result.reason ?: "unknown"}",
          level = android.util.Log.WARN
        )
        scheduleClassicDiscoveryRestart(session)
      }
    }
  }

  private fun offerBleResult(session: ScanSession, result: ScanResult) {
    if (!session.acceptingBle) {
      session.counters.lateCallbacks.incrementAndGet()
      return
    }
    val advertisement = result.scanRecord?.bytes?.let(::ByteArrayContentKey)
    val address = safeAddress(result.device)
    val key = if (address != null || advertisement != null) {
      BleReportKey(
        sessionId = session.id,
        address = address,
        advertisement = advertisement
      )
    } else {
      null
    }

    synchronized(session.gate) {
      if (!session.acceptingBle) {
        session.counters.lateCallbacks.incrementAndGet()
        return
      }
      recordQueueOffer(
        session,
        scanWorkQueue.offer(
          ScanWork.BleResult(
            session = session,
            result = result,
            coalescingKey = key
          )
        )
      )
    }
  }

  private fun offerClassicResult(
    session: ScanSession,
    device: BluetoothDevice,
    rssi: Int
  ) {
    if (!session.acceptingClassic) {
      session.counters.lateCallbacks.incrementAndGet()
      return
    }
    val key = safeAddress(device)?.let { address ->
      ClassicReportKey(sessionId = session.id, address = address)
    }

    synchronized(session.gate) {
      if (!session.acceptingClassic) {
        session.counters.lateCallbacks.incrementAndGet()
        return
      }
      recordQueueOffer(
        session,
        scanWorkQueue.offer(
          ScanWork.ClassicResult(
            session = session,
            device = device,
            rssi = rssi,
            coalescingKey = key
          )
        )
      )
    }
  }

  private fun recordQueueOffer(session: ScanSession, offer: ScanMailboxOffer) {
    when (offer.status) {
      ScanMailboxOfferStatus.ACCEPTED -> Unit
      ScanMailboxOfferStatus.COALESCED -> session.counters.coalescedCallbacks.incrementAndGet()
      ScanMailboxOfferStatus.DROPPED -> session.counters.droppedCallbacks.incrementAndGet()
    }
    session.counters.observeQueueDepth(offer.reportDepth)
  }

  private fun enqueueBleFailure(session: ScanSession, errorCode: Int) {
    val terminal = synchronized(session.gate) {
      if (!session.acceptingBle && !session.blePathActive) {
        session.counters.lateCallbacks.incrementAndGet()
        return
      }
      session.acceptingBle = false
      session.blePathActive = false
      val isTerminal = !session.classicPathActive
      if (isTerminal) {
        session.acceptingSession = false
        session.acceptingClassic = false
      }
      scanWorkQueue.offer(
        ScanWork.BleFailure(
          session = session,
          errorCode = errorCode,
          terminal = isTerminal
        )
      )
      isTerminal
    }

    if (terminal) {
      if (activeSession === session) {
        activeSession = null
        classicRestartJob?.cancel()
        classicRestartJob = null
      }
      stopBleScan(session)
      stopClassicDiscovery(session)
    }
  }

  private fun enqueueClassicFinished(session: ScanSession) {
    synchronized(session.gate) {
      if (!session.acceptingClassic || !session.acceptingSession) {
        session.counters.lateCallbacks.incrementAndGet()
        return
      }
      session.acceptingClassic = false
      session.classicPathActive = false
      scanWorkQueue.offer(ScanWork.ClassicFinished(session))
    }
  }

  private fun retireSession(session: ScanSession, outcome: ScanSessionOutcome) {
    val retired = synchronized(session.gate) {
      if (!session.acceptingSession) {
        false
      } else {
        session.acceptingSession = false
        session.acceptingBle = false
        session.acceptingClassic = false
        scanWorkQueue.offer(ScanWork.EndSession(session, outcome))
        true
      }
    }
    if (!retired) {
      return
    }

    if (activeSession === session) {
      activeSession = null
      classicRestartJob?.cancel()
      classicRestartJob = null
    }
    stopBleScan(session)
    stopClassicDiscovery(session)
  }

  private suspend fun finishSession(session: ScanSession, outcome: ScanSessionOutcome) {
    publishSessionDiagnostics(session)
    observationRecorder.flushPendingAndAwait()
    observationRecorder.clearFiredAlerts()
    ScanDiagnosticsStore.updateForSession(session.id) {
      it.copy(
        outcome = outcome,
        timeoutReached = false,
        scanQueueDepth = scanWorkQueue.reportDepth()
      )
    }
  }

  private suspend fun handleBleFailure(work: ScanWork.BleFailure) {
    val description = ScanStateDecider.describeBleFailureCode(work.errorCode)
    ScanDiagnosticsStore.updateForSession(work.session.id) {
      it.copy(lastBleErrorCode = work.errorCode)
    }
    DebugLog.log(
      "BLE scan failed errorCode=${work.errorCode} description=$description",
      level = android.util.Log.ERROR
    )
    if (!work.terminal) {
      return
    }

    publishSessionDiagnostics(work.session)
    observationRecorder.flushPendingAndAwait()
    observationRecorder.clearFiredAlerts()
    val snapshot = ScanDiagnosticsStore.snapshotForSession(work.session.id) ?: return
    val outcome = if (snapshot.uniqueDeviceCount > 0) {
      ScanSessionOutcome.RESULTS
    } else {
      ScanSessionOutcome.FAILED_TO_START
    }
    val updated = ScanDiagnosticsStore.updateForSession(work.session.id) {
      it.copy(outcome = outcome, scanQueueDepth = scanWorkQueue.reportDepth())
    }
    if (!updated) {
      return
    }

    _scanState.value = if (snapshot.uniqueDeviceCount > 0) {
      ScanState.Complete(snapshot.uniqueDeviceCount)
    } else {
      ScanState.Error("BLE scan failed: $description")
    }
  }

  private fun handleClassicFinished(session: ScanSession) {
    if (activeSession === session && session.acceptingSession) {
      scheduleClassicDiscoveryRestart(session)
    }
  }

  private fun publishSessionDiagnostics(session: ScanSession) {
    val bleCount = session.counters.bleCallbacks.get()
    val classicCount = session.counters.classicCallbacks.get()
    ScanDiagnosticsStore.updateForSession(session.id) { snapshot ->
      snapshot.copy(
        bleCallbackCount = bleCount,
        classicCallbackCount = classicCount,
        rawCallbackCount = bleCount + classicCount + snapshot.sdrCallbackCount,
        scanQueueDepth = scanWorkQueue.reportDepth(),
        scanQueueHighWaterMark = session.counters.queueHighWaterMark.get(),
        coalescedCallbackCount = session.counters.coalescedCallbacks.get(),
        droppedCallbackCount = session.counters.droppedCallbacks.get(),
        lateCallbackCount = session.counters.lateCallbacks.get()
      )
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

  private fun handleBleResult(result: ScanResult, diagnosticsSessionId: Long) {
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
        addressType = addressInsight.addressType,
        alternateNames = listOfNotNull(identity.advertisedName, identity.systemName)
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

    observationRecorder.record(input, diagnosticsSessionId)
  }

  private fun handleClassicResult(
    device: BluetoothDevice,
    rssi: Int,
    diagnosticsSessionId: Long
  ) {
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
        addressType = addressInsight.addressType,
        alternateNames = listOfNotNull(identity.systemName)
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

    observationRecorder.record(input, diagnosticsSessionId)
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

  private fun ByteArray.toHexString(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
      val value = byte.toInt() and 0xFF
      chars[index * 2] = HEX_CHARS[value ushr 4]
      chars[index * 2 + 1] = HEX_CHARS[value and 0x0F]
    }
    return String(chars)
  }

  private fun normalizeName(name: String?): String? {
    return name?.trimEnd()?.takeIf { it.isNotBlank() }
  }

  private fun interruptScan(state: ScanState, session: ScanSession) {
    if (activeSession === session) {
      retireSession(session, ScanSessionOutcome.INTERRUPTED)
    }
    _scanState.value = state
  }

  private fun nextClassicRestartDelayMs(restartFailures: Int): Long {
    val multiplier = 1L shl restartFailures.coerceAtMost(3)
    return (CLASSIC_RESTART_BASE_DELAY_MS * multiplier).coerceAtMost(CLASSIC_RESTART_MAX_DELAY_MS)
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
    private const val STARTUP_REASON_MISSING_PERMISSION = "Missing permission"
    private const val STARTUP_REASON_SESSION_ENDED = "Scan session ended"
    private const val CLASSIC_RESTART_BASE_DELAY_MS = 2_000L
    private const val CLASSIC_RESTART_MAX_DELAY_MS = 16_000L
    private const val MAX_CLASSIC_RESTART_FAILURES = 4
    // Exact duplicate raw reports are coalesced. Once 512 distinct reports are pending, a new
    // distinct report is intentionally dropped and reflected in droppedCallbackCount.
    private const val SCAN_REPORT_QUEUE_CAPACITY = 512
    private const val DIAGNOSTICS_PUBLISH_INTERVAL_MS = 500L
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

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
