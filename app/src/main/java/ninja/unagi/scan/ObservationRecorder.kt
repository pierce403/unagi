package ninja.unagi.scan

import ninja.unagi.alerts.AlertObservation
import ninja.unagi.alerts.DeviceAlertMatcher
import ninja.unagi.alerts.DeviceAlertNotifier
import ninja.unagi.data.AlertRuleEntity
import ninja.unagi.data.BufferedObservation
import ninja.unagi.data.AlertRuleRepository
import ninja.unagi.data.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class ObservationRecorder(
  private val repository: DeviceRepository,
  private val alertRuleRepository: AlertRuleRepository,
  private val deviceAlertNotifier: DeviceAlertNotifier,
  private val scope: CoroutineScope
) {
  @Volatile
  private var enabledAlertRules: List<AlertRuleEntity> = emptyList()
  private val firedAlertKeys = mutableSetOf<String>()
  private val alertLock = Any()
  private val pendingLock = Any()
  private val pendingObservations = mutableMapOf<String, PendingObservation>()
  private val flushMutex = Mutex()
  private var flushJob: Job? = null

  init {
    scope.launch {
      alertRuleRepository.observeEnabledRules().collect { rules ->
        enabledAlertRules = rules
      }
    }
  }

  fun clearFiredAlerts() {
    synchronized(alertLock) {
      firedAlertKeys.clear()
    }
  }

  fun flushPending() {
    scope.launch {
      flushPendingInternal()
    }
  }

  internal suspend fun flushPendingAndAwait() {
    flushPendingInternal()
  }

  fun record(input: ObservationInput, diagnosticsSessionId: Long? = null) {
    val key = DeviceKey.from(input)
    ScanDiagnosticsStore.recordObservation(
      sessionId = diagnosticsSessionId,
      deviceKey = key,
      sample = CallbackSample(
        path = when {
          input.source.equals("BLE", ignoreCase = true) -> ScanPath.BLE
          input.source.equals("SDR", ignoreCase = true) -> ScanPath.SDR
          else -> ScanPath.CLASSIC
        },
        timestampMs = input.timestamp,
        address = input.address,
        name = input.name,
        rssi = input.rssi,
        serviceUuidCount = input.serviceUuids.size,
        manufacturerDataKeys = input.manufacturerData.keys.sorted()
      )
    )
    bufferObservation(key, input)
    if (enabledAlertRules.isNotEmpty()) {
      emitAlertNotifications(key = key, input = input)
    }
  }

  private fun emitAlertNotifications(
    key: String,
    input: ObservationInput
  ) {
    val observation = AlertObservation(
      deviceKey = key,
      displayName = input.name,
      advertisedName = input.advertisedName,
      systemName = input.systemName,
      address = input.address,
      vendorName = input.vendorName,
      source = input.source,
      manufacturerCompanyIds = input.manufacturerData.keys,
      serviceUuids = input.serviceUuids
    )
    val matches = DeviceAlertMatcher.findMatches(
      rules = enabledAlertRules,
      observation = observation
    )

    matches.forEach { match ->
      val dedupeKey = buildString {
        append(match.rule.id)
        append(':')
        append(input.address ?: key)
      }
      val shouldNotify = synchronized(alertLock) {
        firedAlertKeys.add(dedupeKey)
      }
      if (!shouldNotify) {
        return@forEach
      }
      deviceAlertNotifier.notifyMatch(
        match = match,
        observation = observation
      )
    }
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

    json.putIfNotNull("tpmsModel", input.tpmsModel)
    json.putIfNotNull("tpmsSensorId", input.tpmsSensorId)
    json.putIfNotNull("tpmsPressureKpa", input.tpmsPressureKpa)
    json.putIfNotNull("tpmsTemperatureC", input.tpmsTemperatureC)
    json.putIfNotNull("tpmsBatteryOk", input.tpmsBatteryOk)
    json.putIfNotNull("tpmsFrequencyMhz", input.tpmsFrequencyMhz)
    json.putIfNotNull("tpmsSnr", input.tpmsSnr)

    return json.toString()
  }

  private fun JSONObject.putIfNotNull(key: String, value: Any?) {
    if (value != null) {
      put(key, value)
    }
  }

  private fun bufferObservation(key: String, input: ObservationInput) {
    synchronized(pendingLock) {
      val existing = pendingObservations[key]
      pendingObservations[key] = existing?.merge(input)
        ?: PendingObservation.from(key, input)
      if (flushJob?.isActive == true) {
        return
      }
      flushJob = scope.launch {
        delay(FLUSH_INTERVAL_MS)
        flushPendingInternal()
      }
    }
  }

  private suspend fun flushPendingInternal() {
    flushMutex.withLock {
      val buffered = synchronized(pendingLock) {
        flushJob = null
        if (pendingObservations.isEmpty()) {
          emptyList()
        } else {
          val snapshot = pendingObservations.values.toList()
          pendingObservations.clear()
          snapshot
        }
      }

      if (buffered.isEmpty()) {
        return@withLock
      }

      repository.recordBufferedObservations(
        buffered.map { pending ->
          pending.toBufferedObservation(buildMetadataJson(pending.latestInput))
        }
      )
    }
  }

  private data class PendingObservation(
    val deviceKey: String,
    val name: String?,
    val address: String?,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val observationCount: Int,
    val lastRssi: Int,
    val rssiMin: Int,
    val rssiMax: Int,
    val rssiSum: Long,
    val latestInput: ObservationInput
  ) {
    fun merge(input: ObservationInput): PendingObservation {
      val incomingIsLatest = input.timestamp >= lastTimestamp
      return PendingObservation(
        deviceKey = deviceKey,
        name = if (incomingIsLatest) input.name ?: name else name ?: input.name,
        address = if (incomingIsLatest) input.address ?: address else address ?: input.address,
        firstTimestamp = minOf(firstTimestamp, input.timestamp),
        lastTimestamp = maxOf(lastTimestamp, input.timestamp),
        observationCount = observationCount + 1,
        lastRssi = if (incomingIsLatest) input.rssi else lastRssi,
        rssiMin = minOf(rssiMin, input.rssi),
        rssiMax = maxOf(rssiMax, input.rssi),
        rssiSum = rssiSum + input.rssi,
        latestInput = if (incomingIsLatest) input else latestInput
      )
    }

    fun toBufferedObservation(metadataJson: String): BufferedObservation {
      return BufferedObservation(
        deviceKey = deviceKey,
        name = name,
        address = address,
        firstTimestamp = firstTimestamp,
        lastTimestamp = lastTimestamp,
        observationCount = observationCount,
        lastRssi = lastRssi,
        rssiMin = rssiMin,
        rssiMax = rssiMax,
        rssiSum = rssiSum,
        metadataJson = metadataJson
      )
    }

    companion object {
      fun from(deviceKey: String, input: ObservationInput): PendingObservation {
        return PendingObservation(
          deviceKey = deviceKey,
          name = input.name,
          address = input.address,
          firstTimestamp = input.timestamp,
          lastTimestamp = input.timestamp,
          observationCount = 1,
          lastRssi = input.rssi,
          rssiMin = input.rssi,
          rssiMax = input.rssi,
          rssiSum = input.rssi.toLong(),
          latestInput = input
        )
      }
    }
  }

  companion object {
    private const val FLUSH_INTERVAL_MS = 750L
  }
}
