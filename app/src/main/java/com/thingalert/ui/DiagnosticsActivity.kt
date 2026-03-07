package com.thingalert.ui

import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.thingalert.ThingAlertApp
import com.thingalert.data.DeviceEntity
import com.thingalert.databinding.ActivityDiagnosticsBinding
import com.thingalert.scan.ScanDiagnosticsSnapshot
import com.thingalert.scan.ScanDiagnosticsStore
import com.thingalert.scan.ScanModePreferences
import com.thingalert.scan.ScanModePreset
import com.thingalert.util.DebugLog
import com.thingalert.util.PermissionsHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {
  private lateinit var binding: ActivityDiagnosticsBinding
  private val repository by lazy { (application as ThingAlertApp).repository }
  private var latestDiagnosticsReport: String = ""

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
    binding.copyDebugReportButton.isEnabled = false
    binding.copyDebugReportButton.setOnClickListener { copyDebugReport() }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        combine(DebugLog.entries, ScanDiagnosticsStore.snapshot, repository.observeDevices()) { entries, snapshot, devices ->
          buildDiagnostics(entries, snapshot, devices)
        }.collect { text ->
          latestDiagnosticsReport = text
          binding.diagnosticsText.text = text
          binding.copyDebugReportButton.isEnabled = text.isNotBlank()
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    DebugLog.log("Diagnostics opened")
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun buildDiagnostics(
    entries: List<String>,
    scanDiagnostics: ScanDiagnosticsSnapshot,
    devices: List<DeviceEntity>
  ): String {
    val manager = getSystemService(BluetoothManager::class.java)
    val adapter = manager?.adapter
    val enabled = try {
      adapter?.isEnabled == true
    } catch (_: SecurityException) {
      false
    }

    return DiagnosticsReportBuilder.build(
      entries = entries,
      scanDiagnostics = scanDiagnostics,
      persistedDevices = devices,
      platformInfo = DiagnosticsPlatformInfo(
        appVersionName = appVersionName(),
        appVersionCode = appVersionCode(),
        packageName = packageName,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        product = Build.PRODUCT,
        buildType = Build.TYPE,
        buildDisplay = Build.DISPLAY,
        fingerprint = Build.FINGERPRINT,
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        grapheneOsLikely = isGrapheneOsLikely()
      ),
      permissionInfo = DiagnosticsPermissionInfo(
        missingPermissions = PermissionsHelper.missingPermissions(this),
        permissionLabels = PermissionsHelper.missingPermissionLabels(this),
        permissionsBlocked = PermissionsHelper.shouldOpenAppSettings(this),
        locationServicesRequired = PermissionsHelper.isLocationServicesRequired(),
        locationServicesEnabled = PermissionsHelper.isLocationServicesEnabled(this)
      ),
      bluetoothSupported = adapter != null,
      bluetoothEnabled = enabled
    )
  }

  private fun copyDebugReport() {
    if (latestDiagnosticsReport.isBlank()) {
      return
    }

    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
      ClipData.newPlainText(getString(com.thingalert.R.string.diagnostics), latestDiagnosticsReport)
    )
    DebugLog.log("Copied scan debug report")
    Toast.makeText(this, com.thingalert.R.string.scan_debug_report_copied, Toast.LENGTH_SHORT).show()
  }

  private fun isGrapheneOsLikely(): Boolean {
    val fields = listOf(Build.FINGERPRINT, Build.DISPLAY, Build.VERSION.INCREMENTAL, Build.ID)
    return fields.any { value -> value.contains("graphene", ignoreCase = true) }
  }

  @Suppress("DEPRECATION")
  private fun appVersionName(): String {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      packageManager.getPackageInfo(packageName, 0)
    }
    return info.versionName ?: "unknown"
  }

  @Suppress("DEPRECATION")
  private fun appVersionCode(): Long {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      packageManager.getPackageInfo(packageName, 0)
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      info.longVersionCode
    } else {
      info.versionCode.toLong()
    }
  }
}
