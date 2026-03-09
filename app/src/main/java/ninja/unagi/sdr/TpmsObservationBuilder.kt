package ninja.unagi.sdr

import ninja.unagi.scan.ObservationInput
import ninja.unagi.util.ClassificationConfidence
import ninja.unagi.util.DeviceCategory
import ninja.unagi.util.ObservedTransport
import ninja.unagi.util.VendorConfidence

object TpmsObservationBuilder {
  fun build(reading: TpmsReading): ObservationInput {
    val displayName = "TPMS ${reading.model} ${reading.sensorId}"
    val vendor = TpmsSensorVendorLookup.lookup(reading.model)
    val resolvedVendorName = vendor?.manufacturer ?: reading.model
    val resolvedVendorSource = if (vendor != null) "rtl_433 protocol (mapped)" else "rtl_433 protocol"
    val resolvedVendorConfidence = if (vendor != null) VendorConfidence.HIGH else VendorConfidence.MEDIUM
    return ObservationInput(
      name = displayName,
      address = null,
      rssi = reading.rssi?.toInt() ?: -100,
      timestamp = System.currentTimeMillis(),
      serviceUuids = emptyList(),
      manufacturerData = emptyMap(),
      source = "SDR",
      transport = ObservedTransport.SDR.metadataValue,
      nameSource = "sdr_protocol",
      vendorName = resolvedVendorName,
      vendorSource = resolvedVendorSource,
      vendorConfidence = resolvedVendorConfidence.metadataValue,
      classificationCategory = DeviceCategory.TPMS_SENSOR.metadataValue,
      classificationLabel = DeviceCategory.TPMS_SENSOR.label,
      classificationConfidence = ClassificationConfidence.HIGH.metadataValue,
      classificationEvidence = listOf("source:sdr", "protocol:${reading.model}"),
      tpmsModel = reading.model,
      tpmsSensorId = reading.sensorId,
      tpmsPressureKpa = reading.pressureKpa,
      tpmsTemperatureC = reading.temperatureC,
      tpmsBatteryOk = reading.batteryOk,
      tpmsFrequencyMhz = reading.frequencyMhz,
      tpmsSnr = reading.snr
    )
  }
}
