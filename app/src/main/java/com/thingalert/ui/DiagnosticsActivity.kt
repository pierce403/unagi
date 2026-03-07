package com.thingalert.ui

import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.thingalert.databinding.ActivityDiagnosticsBinding
import com.thingalert.scan.ScanDiagnosticsSnapshot
import com.thingalert.scan.ScanDiagnosticsStore
import com.thingalert.scan.ScanModePreferences
import com.thingalert.scan.ScanModePreset
import com.thingalert.scan.ScanStateDecider
import com.thingalert.util.DebugLog
import com.thingalert.util.Formatters
import com.thingalert.util.PermissionsHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {
  private lateinit var binding: ActivityDiagnosticsBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    binding.compatibilityModeSwitch.isChecked = ScanModePreferences.get(this) == ScanModePreset.COMPATIBILITY
    binding.compatibilityModeSwitch.setOnCheckedChangeListener { _, isChecked ->
      val mode = if (isChecked) ScanModePreset.COMPATIBILITY else ScanModePreset.NORMAL
      ScanModePreferences.set(this, mode)
      ScanDiagnosticsStore.update { it.copy(scanMode = mode) }
      DebugLog.log("Scan mode set to ${mode.label}")
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        combine(DebugLog.entries, ScanDiagnosticsStore.snapshot) { entries, snapshot ->
          buildDiagnostics(entries, snapshot)
        }.collect { text ->
          binding.diagnosticsText.text = text
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    binding.diagnosticsText.text = buildDiagnostics(DebugLog.entries.value, ScanDiagnosticsStore.snapshot.value)
    DebugLog.log("Diagnostics opened")
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun buildDiagnostics(entries: List<String>, scanDiagnostics: ScanDiagnosticsSnapshot): String {
    val builder = StringBuilder()
    builder.appendLine("unagi diagnostics")
    builder.appendLine()
    builder.appendLine("SDK: ${Build.VERSION.SDK_INT}")
    builder.appendLine("Release: ${Build.VERSION.RELEASE}")

    val manager = getSystemService(BluetoothManager::class.java)
    val adapter = manager?.adapter
    builder.appendLine("Bluetooth supported: ${adapter != null}")

    val enabled = try {
      adapter?.isEnabled == true
    } catch (_: SecurityException) {
      false
    }
    builder.appendLine("Bluetooth enabled: $enabled")

    val missing = PermissionsHelper.missingPermissions(this)
    builder.appendLine("Missing permissions: ${if (missing.isEmpty()) "none" else missing.joinToString()}")
    builder.appendLine("Permission labels: ${PermissionsHelper.missingPermissionLabels(this).ifEmpty { listOf("none") }.joinToString()}")
    builder.appendLine("Permissions blocked by 'don't ask again' or policy: ${PermissionsHelper.shouldOpenAppSettings(this)}")
    if (PermissionsHelper.isLocationServicesRequired()) {
      builder.appendLine("Location services enabled: ${PermissionsHelper.isLocationServicesEnabled(this)}")
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      builder.appendLine("Note: Location permission and location services may be required for BLE scans on Android 11 and below.")
    }

    builder.appendLine("GrapheneOS note: unagi does not request sensor-class permissions in its APK manifest.")
    builder.appendLine()
    builder.appendLine("Latest scan session:")
    builder.appendLine("Scan mode: ${scanDiagnostics.scanMode.label}")
    builder.appendLine("BLE scan mode: ${bleModeLabel(scanDiagnostics.scanMode)}")
    builder.appendLine("Classic discovery enabled: ${scanDiagnostics.scanMode.startsClassicDiscovery}")
    builder.appendLine("Timeout ms: ${scanDiagnostics.scanMode.timeoutMs}")
    builder.appendLine(
      "Start time: ${scanDiagnostics.startTimeMs?.let(Formatters::formatTimestamp) ?: "none"}"
    )
    builder.appendLine(
      "Elapsed ms: ${scanDiagnostics.startTimeMs?.let { System.currentTimeMillis() - it } ?: 0L}"
    )
    builder.appendLine("Outcome: ${scanDiagnostics.outcome?.label ?: "none"}")
    builder.appendLine("Timeout reached: ${scanDiagnostics.timeoutReached}")
    builder.appendLine("BLE startup attempted: ${scanDiagnostics.bleStartup != null}")
    builder.appendLine("BLE startup succeeded: ${scanDiagnostics.bleStartup?.started == true}")
    builder.appendLine("BLE startup detail: ${scanDiagnostics.bleStartup?.reason ?: "none"}")
    builder.appendLine("Classic startup attempted: ${scanDiagnostics.classicStartup != null}")
    builder.appendLine("Classic startup succeeded: ${scanDiagnostics.classicStartup?.started == true}")
    builder.appendLine("Classic startup detail: ${scanDiagnostics.classicStartup?.reason ?: "none"}")
    builder.appendLine("BLE scanner unavailable: ${scanDiagnostics.bleScannerUnavailable}")
    builder.appendLine(
      "Last BLE error: ${
        scanDiagnostics.lastBleErrorCode?.let {
          "$it (${ScanStateDecider.describeBleFailureCode(it)})"
        } ?: "none"
      }"
    )
    builder.appendLine("BLE callbacks: ${scanDiagnostics.bleCallbackCount}")
    builder.appendLine("Classic callbacks: ${scanDiagnostics.classicCallbackCount}")
    builder.appendLine("Raw callbacks: ${scanDiagnostics.rawCallbackCount}")
    builder.appendLine("Unique devices: ${scanDiagnostics.uniqueDeviceCount}")
    builder.appendLine(
      "Permission snapshot: ${
        if (scanDiagnostics.missingPermissions.isEmpty()) "none" else scanDiagnostics.missingPermissions.joinToString()
      }"
    )
    builder.appendLine("Bluetooth enabled snapshot: ${scanDiagnostics.bluetoothEnabled ?: "unknown"}")
    builder.appendLine("Location services snapshot: ${scanDiagnostics.locationServicesEnabled ?: "unknown"}")

    builder.appendLine()
    builder.appendLine("Recent events:")
    if (entries.isEmpty()) {
      builder.appendLine("  (none yet)")
    } else {
      entries.takeLast(200).forEach { line ->
        builder.appendLine(line)
      }
    }

    return builder.toString()
  }

  private fun bleModeLabel(scanMode: ScanModePreset): String {
    return when (scanMode.bleScanMode) {
      android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
      android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
      android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
      else -> scanMode.bleScanMode.toString()
    }
  }
}
