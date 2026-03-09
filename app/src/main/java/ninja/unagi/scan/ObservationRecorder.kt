package ninja.unagi.scan

import ninja.unagi.alerts.AlertObservation
import ninja.unagi.alerts.DeviceAlertMatcher
import ninja.unagi.alerts.DeviceAlertNotifier
import ninja.unagi.data.AlertRuleEntity
import ninja.unagi.data.AlertRuleRepository
import ninja.unagi.data.DeviceObservation
import ninja.unagi.data.DeviceRepository
import ninja.unagi.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ObservationRecorder(
  private val repository: DeviceRepository,
  private val alertRuleRepository: AlertRuleRepository,
  private val deviceAlertNotifier: DeviceAlertNotifier,
  private val scope: CoroutineScope
) {
  private var enabledAlertRules: List<AlertRuleEntity> = emptyList()
  private val firedAlertKeys = mutableSetOf<String>()

  init {
    scope.launch {
      alertRuleRepository.observeEnabledRules().collect { rules ->
        enabledAlertRules = rules
      }
    }
  }

  fun clearFiredAlerts() {
    firedAlertKeys.clear()
  }

  fun record(input: ObservationInput) {
    val key = DeviceKey.from(input)
    ScanDiagnosticsStore.update { snap ->
      val samples = if (snap.callbackSamples.size < CallbackSample.MAX_SAMPLES) {
        snap.callbackSamples + CallbackSample(
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
      } else {
        snap.callbackSamples
      }
      snap.copy(deviceKeys = snap.deviceKeys + key, callbackSamples = samples)
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
      emitAlertNotifications(key = key, input = input)
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

    json.putIfNotNull("tpmsModel", input.tpmsModel)
    json.putIfNotNull("tpmsSensorId", input.tpmsSensorId)
    json.putIfNotNull("tpmsPressureKpa", input.tpmsPressureKpa)
    json.putIfNotNull("tpmsTemperatureC", input.tpmsTemperatureC)
    json.putIfNotNull("tpmsBatteryOk", input.tpmsBatteryOk)
    json.putIfNotNull("tpmsFrequencyMhz", input.tpmsFrequencyMhz)
    json.putIfNotNull("tpmsSnr", input.tpmsSnr)

    return json.toString(2)
  }

  private fun JSONObject.putIfNotNull(key: String, value: Any?) {
    if (value != null) {
      put(key, value)
    }
  }
}
