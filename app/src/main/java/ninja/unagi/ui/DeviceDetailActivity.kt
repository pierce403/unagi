package ninja.unagi.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ninja.unagi.ThingAlertApp
import ninja.unagi.databinding.ActivityDeviceDetailBinding
import ninja.unagi.enrichment.BleDeviceInfoQueryClient
import ninja.unagi.enrichment.DeviceEnrichmentFormatter
import ninja.unagi.data.DeviceEntity
import ninja.unagi.util.BluetoothAssignedNumbersProvider
import ninja.unagi.util.DeviceIdentityPresenter
import ninja.unagi.util.Formatters
import ninja.unagi.util.ObservationMetadata
import ninja.unagi.util.ObservationMetadataParser
import ninja.unagi.util.ObservedTransport
import ninja.unagi.util.VendorPrefixRegistryProvider
import ninja.unagi.util.WindowInsetsHelper
import kotlinx.coroutines.launch

class DeviceDetailActivity : AppCompatActivity() {
  private lateinit var binding: ActivityDeviceDetailBinding
  private lateinit var adapter: SightingAdapter
  private var currentDevice: DeviceEntity? = null
  private var currentMetadata: ObservationMetadata = ObservationMetadata()
  private var queryInProgress = false
  private val vendorRegistry by lazy { VendorPrefixRegistryProvider.get(this) }
  private val assignedNumbers by lazy { BluetoothAssignedNumbersProvider.get(this) }
  private val app by lazy { application as ThingAlertApp }
  private val repository by lazy { app.repository }
  private val enrichmentRepository by lazy { app.deviceEnrichmentRepository }
  private val queryClient by lazy {
    BleDeviceInfoQueryClient(
      context = this,
      bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter,
      scanController = app.scanController
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    WindowInsetsHelper.applyToolbarInsets(binding.toolbar)
    WindowInsetsHelper.applyBottomInsets(binding.detailScroll)
    WindowInsetsHelper.requestApplyInsets(binding.root)

    val deviceKey = intent.getStringExtra(EXTRA_DEVICE_KEY) ?: run {
      finish()
      return
    }

    adapter = SightingAdapter(assignedNumbers)
    binding.sightingsList.layoutManager = LinearLayoutManager(this)
    binding.sightingsList.adapter = adapter
    binding.queryDeviceInfoButton.setOnClickListener {
      val device = currentDevice ?: return@setOnClickListener
      runBleQuery(device, currentMetadata)
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          repository.observeDevice(deviceKey).collect { device ->
            if (device == null) return@collect
            currentDevice = device
            currentMetadata = ObservationMetadataParser.parse(device.lastMetadataJson)
            val identity = DeviceIdentityPresenter.present(
              displayName = device.displayName,
              address = device.lastAddress,
              metadataJson = device.lastMetadataJson,
              vendorRegistry = vendorRegistry,
              assignedNumbers = assignedNumbers
            )
            binding.detailName.text = identity.title
            val identityLines = mutableListOf<String>()
            identity.vendorName?.let { vendor ->
              val sourceSuffix = buildString {
                identity.vendorSource?.let { append(" ($it") }
                identity.vendorConfidenceLabel?.let { confidence ->
                  if (isNotEmpty()) {
                    append(", ")
                    append(confidence)
                  } else {
                    append(" (")
                    append(confidence)
                  }
                }
                if (isNotEmpty()) {
                  append(')')
                }
              }
              identityLines += "Vendor: $vendor$sourceSuffix"
            }
            identity.addressLabel?.let { address ->
              identityLines += "Address: $address"
            }
            identity.addressTypeLabel?.let(identityLines::add)
            identity.classificationLabel?.let { label ->
              val confidenceSuffix = identity.classificationConfidenceLabel?.let { " ($it)" }.orEmpty()
              identityLines += "Likely classification: $label$confidenceSuffix"
            }
            if (identity.classificationEvidence.isNotEmpty()) {
              identityLines += "Classification evidence: ${identity.classificationEvidence.joinToString(", ")}"
            }
            identity.nameSourceLabel?.let { label ->
              identityLines += "Name source: $label"
            }
            identity.advertisedName
              ?.takeUnless { it == device.displayName }
              ?.let { identityLines += "BLE advertised name: $it" }
            identity.systemName
              ?.takeUnless { it == device.displayName }
              ?.let { identityLines += "Bluetooth device name: $it" }
            identityLines += identity.metadataSummary.detailLines.filterNot { line ->
              line.startsWith("Vendor source:") ||
                line.startsWith("Likely classification:") ||
                line.startsWith("Classification evidence:")
            }
            identity.classificationFingerprint?.let { fingerprint ->
              identityLines += "Classification fingerprint: ${fingerprint.take(12)}…"
            }
            binding.detailIdentity.isVisible = identityLines.isNotEmpty()
            binding.detailIdentity.text = identityLines.joinToString("\n")
            binding.detailKey.text = "Device key: ${device.deviceKey}"
            binding.detailFirstSeen.text = "First seen: ${Formatters.formatTimestamp(device.firstSeen)}"
            binding.detailLastSeen.text = "Last seen: ${Formatters.formatTimestamp(device.lastSeen)}"
            binding.detailStats.text =
              "RSSI range: ${device.rssiMin}..${device.rssiMax} dBm • Avg: ${"%.1f".format(device.rssiAvg)} • Count: ${device.sightingsCount}"
            binding.detailMetadata.text = device.lastMetadataJson ?: "No metadata recorded yet."
            renderQueryControls(device, currentMetadata)
          }
        }

        launch {
          repository.observeSightings(deviceKey).collect { sightings ->
            adapter.submitList(sightings)
          }
        }

        launch {
          enrichmentRepository.observeEnrichment(deviceKey).collect { enrichment ->
            binding.detailEnrichment.text = DeviceEnrichmentFormatter.formatForDetail(
              enrichment = enrichment,
              assignedNumbers = assignedNumbers
            )
            if (!queryInProgress) {
              binding.queryDeviceInfoStatus.isVisible = enrichment != null
              binding.queryDeviceInfoStatus.text = enrichment?.let {
                "Last BLE query: ${Formatters.formatTimestamp(it.lastQueryTimestamp)}"
              }.orEmpty()
            }
          }
        }
      }
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun runBleQuery(device: DeviceEntity, metadata: ObservationMetadata) {
    val address = device.lastAddress ?: return
    queryInProgress = true
    renderQueryControls(device, metadata)
    binding.queryDeviceInfoStatus.isVisible = true
    binding.queryDeviceInfoStatus.text = getString(ninja.unagi.R.string.query_device_info_running_status)

    lifecycleScope.launch {
      val result = queryClient.query(address, metadata.rawAndroidAddressType)
      enrichmentRepository.upsertEnrichment(result.toEntity(device.deviceKey))
      queryInProgress = false
      binding.queryDeviceInfoStatus.isVisible = true
      binding.queryDeviceInfoStatus.text = if (result.errorMessage.isNullOrBlank()) {
        getString(ninja.unagi.R.string.query_device_info_finished)
      } else {
        getString(ninja.unagi.R.string.query_device_info_failed, result.errorMessage)
      }
      renderQueryControls(device, metadata)
    }
  }

  private fun renderQueryControls(device: DeviceEntity, metadata: ObservationMetadata) {
    val eligibility = queryEligibility(device, metadata)
    binding.queryDeviceInfoButton.isEnabled = !queryInProgress && eligibility.enabled
    binding.queryDeviceInfoButton.text = getString(
      if (queryInProgress) {
        ninja.unagi.R.string.query_device_info_running
      } else {
        ninja.unagi.R.string.query_device_info
      }
    )
    binding.queryDeviceInfoNote.isVisible = eligibility.message.isNotBlank()
    binding.queryDeviceInfoNote.text = eligibility.message
  }

  private fun queryEligibility(device: DeviceEntity, metadata: ObservationMetadata): QueryEligibility {
    if (device.lastAddress.isNullOrBlank()) {
      return QueryEligibility(false, getString(ninja.unagi.R.string.query_device_info_unavailable_address))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
      return QueryEligibility(false, getString(ninja.unagi.R.string.query_device_info_unavailable_permission))
    }
    if (metadata.transport !in setOf(ObservedTransport.BLE, ObservedTransport.DUAL)) {
      return QueryEligibility(false, getString(ninja.unagi.R.string.query_device_info_unavailable_classic))
    }
    if (System.currentTimeMillis() - device.lastSeen > RECENT_SIGHTING_WINDOW_MS) {
      return QueryEligibility(false, getString(ninja.unagi.R.string.query_device_info_unavailable_stale))
    }
    if (device.lastRssi <= VERY_WEAK_RSSI_THRESHOLD) {
      return QueryEligibility(false, getString(ninja.unagi.R.string.query_device_info_unavailable_weak_signal))
    }
    if (metadata.connectable == false) {
      return QueryEligibility(false, getString(ninja.unagi.R.string.query_device_info_unavailable_non_connectable))
    }
    return QueryEligibility(true, getString(ninja.unagi.R.string.query_device_info_note))
  }

  companion object {
    private const val EXTRA_DEVICE_KEY = "device_key"
    private const val RECENT_SIGHTING_WINDOW_MS = 10 * 60 * 1000L
    private const val VERY_WEAK_RSSI_THRESHOLD = -95

    fun intent(context: Context, deviceKey: String): Intent {
      return Intent(context, DeviceDetailActivity::class.java).apply {
        putExtra(EXTRA_DEVICE_KEY, deviceKey)
      }
    }
  }

  private data class QueryEligibility(
    val enabled: Boolean,
    val message: String
  )
}
