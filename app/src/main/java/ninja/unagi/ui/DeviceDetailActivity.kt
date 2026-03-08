package ninja.unagi.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ninja.unagi.ThingAlertApp
import ninja.unagi.databinding.ActivityDeviceDetailBinding
import ninja.unagi.util.BluetoothAssignedNumbersProvider
import ninja.unagi.util.DeviceIdentityPresenter
import ninja.unagi.util.Formatters
import ninja.unagi.util.VendorPrefixRegistryProvider
import ninja.unagi.util.WindowInsetsHelper
import kotlinx.coroutines.launch

class DeviceDetailActivity : AppCompatActivity() {
  private lateinit var binding: ActivityDeviceDetailBinding
  private lateinit var adapter: SightingAdapter
  private val vendorRegistry by lazy { VendorPrefixRegistryProvider.get(this) }
  private val assignedNumbers by lazy { BluetoothAssignedNumbersProvider.get(this) }

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

    val repository = (application as ThingAlertApp).repository

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          repository.observeDevice(deviceKey).collect { device ->
            if (device == null) return@collect
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
          }
        }

        launch {
          repository.observeSightings(deviceKey).collect { sightings ->
            adapter.submitList(sightings)
          }
        }
      }
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  companion object {
    private const val EXTRA_DEVICE_KEY = "device_key"

    fun intent(context: Context, deviceKey: String): Intent {
      return Intent(context, DeviceDetailActivity::class.java).apply {
        putExtra(EXTRA_DEVICE_KEY, deviceKey)
      }
    }
  }
}
