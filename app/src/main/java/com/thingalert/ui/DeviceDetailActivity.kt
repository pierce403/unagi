package com.thingalert.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.thingalert.ThingAlertApp
import com.thingalert.databinding.ActivityDeviceDetailBinding
import com.thingalert.util.Formatters
import com.thingalert.util.WindowInsetsHelper
import kotlinx.coroutines.launch

class DeviceDetailActivity : AppCompatActivity() {
  private lateinit var binding: ActivityDeviceDetailBinding
  private lateinit var adapter: SightingAdapter

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

    adapter = SightingAdapter()
    binding.sightingsList.layoutManager = LinearLayoutManager(this)
    binding.sightingsList.adapter = adapter

    val repository = (application as ThingAlertApp).repository

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          repository.observeDevice(deviceKey).collect { device ->
            if (device == null) return@collect
            val name = Formatters.formatName(device.displayName)
            binding.detailName.text = name
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
