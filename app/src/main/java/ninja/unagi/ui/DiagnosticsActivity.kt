package ninja.unagi.ui

import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ninja.unagi.ThingAlertApp
import ninja.unagi.data.DeviceEntity
import ninja.unagi.databinding.ActivityDiagnosticsBinding
import ninja.unagi.scan.ScanDiagnosticsSnapshot
import ninja.unagi.scan.ScanDiagnosticsStore
import ninja.unagi.scan.ScanModePreferences
import ninja.unagi.scan.ScanModePreset
import ninja.unagi.util.AppVersion
import ninja.unagi.util.DebugLog
import ninja.unagi.util.PermissionsHelper
import ninja.unagi.util.WindowInsetsHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {
  private lateinit var binding: ActivityDiagnosticsBinding
  private val repository by lazy { (application as ThingAlertApp).repository }
  private val enrichmentRepository by lazy { (application as ThingAlertApp).deviceEnrichmentRepository }
  private var latestDiagnosticsReport: String = ""

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.subtitle = AppVersion.read(this).visibleLabel
    WindowInsetsHelper.applyToolbarInsets(binding.toolbar)
    WindowInsetsHelper.applyBottomInsets(binding.diagnosticsScroll)
    WindowInsetsHelper.requestApplyInsets(binding.root)

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
        combine(
          DebugLog.entries,
          ScanDiagnosticsStore.snapshot,
          repository.observeDevices(),
          enrichmentRepository.observeEnrichments()
        ) { entries, snapshot, devices, enrichments ->
          buildDiagnostics(entries, snapshot, devices, enrichments)
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
    devices: List<DeviceEntity>,
    enrichments: List<ninja.unagi.data.DeviceEnrichmentEntity>
  ): String {
    val manager = getSystemService(BluetoothManager::class.java)
    val adapter = manager?.adapter
    val appVersion = AppVersion.read(this)
    val enabled = try {
      adapter?.isEnabled == true
    } catch (_: SecurityException) {
      false
    }

    return DiagnosticsReportBuilder.build(
      entries = entries,
      scanDiagnostics = scanDiagnostics,
      persistedDevices = devices,
      persistedEnrichments = enrichments,
      platformInfo = DiagnosticsPlatformInfo(
        appVersionName = appVersion.versionName,
        appVersionCode = appVersion.versionCode,
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
      ClipData.newPlainText(getString(ninja.unagi.R.string.diagnostics), latestDiagnosticsReport)
    )
    DebugLog.log("Copied scan debug report")
    Toast.makeText(this, ninja.unagi.R.string.scan_debug_report_copied, Toast.LENGTH_SHORT).show()
  }

  private fun isGrapheneOsLikely(): Boolean {
    val fields = listOf(Build.FINGERPRINT, Build.DISPLAY, Build.VERSION.INCREMENTAL, Build.ID)
    return fields.any { value -> value.contains("graphene", ignoreCase = true) }
  }
}
