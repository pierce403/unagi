package com.thingalert.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.thingalert.R
import com.thingalert.databinding.ActivityMainBinding
import com.thingalert.scan.ScanState
import com.thingalert.util.AppVersion
import com.thingalert.util.DebugLog
import com.thingalert.util.PermissionsHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
  private enum class RecoveryAction {
    NONE,
    REQUEST_PERMISSIONS,
    OPEN_APP_SETTINGS,
    ENABLE_BLUETOOTH,
    OPEN_LOCATION_SETTINGS,
    RETRY_SCAN
  }

  private lateinit var binding: ActivityMainBinding
  private lateinit var viewModel: MainViewModel
  private lateinit var adapter: DeviceAdapter
  private var recoveryAction = RecoveryAction.NONE

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { result ->
    val granted = result.values.all { it }
    DebugLog.log("Permission results: $result")
    if (granted) {
      handleStartScan()
    } else {
      viewModel.refreshPreflightState()
    }
  }

  private val enableBluetoothLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    if (isBluetoothEnabled()) {
      DebugLog.log("Bluetooth enabled by user")
      viewModel.startScan()
    } else {
      DebugLog.log("Bluetooth enable declined")
      binding.permissionHint.isVisible = true
      binding.permissionHint.text = getString(R.string.enable_bluetooth)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    viewModel = ViewModelProvider(this)[MainViewModel::class.java]

    setSupportActionBar(binding.toolbar)
    val appVersion = AppVersion.read(this)
    val versionLabel = appVersion.visibleLabel
    supportActionBar?.subtitle = versionLabel
    binding.appVersionLabel.text = versionLabel

    adapter = DeviceAdapter { item ->
      startActivity(DeviceDetailActivity.intent(this, item.deviceKey))
    }

    binding.deviceList.layoutManager = LinearLayoutManager(this)
    binding.deviceList.adapter = adapter

    val sortAdapter = ArrayAdapter(
      this,
      R.layout.spinner_item,
      SortMode.values().map { it.label }
    )
    sortAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
    binding.sortSpinner.adapter = sortAdapter

    binding.sortSpinner.setSelection(SortMode.RECENT.ordinal)
    binding.sortSpinner.setOnItemSelectedListener(
      object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
          parent: android.widget.AdapterView<*>?,
          view: android.view.View?,
          position: Int,
          id: Long
        ) {
          viewModel.updateSortMode(SortMode.values()[position])
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
          // No-op
        }
      }
    )

    binding.filterInput.doAfterTextChanged { text ->
      viewModel.updateQuery(text?.toString().orEmpty())
    }

    binding.unknownOnly.setOnCheckedChangeListener { _, isChecked ->
      viewModel.setUnknownOnly(isChecked)
    }

    binding.startScanButton.setOnClickListener { handleStartScan() }
    binding.stopScanButton.setOnClickListener { viewModel.stopScan() }
    binding.permissionActionButton.setOnClickListener { runRecoveryAction() }

    DebugLog.log("MainActivity created")
    viewModel.refreshPreflightState()

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          viewModel.devices.collect { devices ->
            adapter.submitList(devices)
            val empty = devices.isEmpty()
            binding.emptyState.isVisible = empty
            binding.deviceList.isVisible = !empty
          }
        }

        launch {
          viewModel.scanState.collect { state ->
            updateScanState(state)
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshPreflightState()
  }

  override fun onStop() {
    super.onStop()
    viewModel.stopScan()
  }

  override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
    menuInflater.inflate(R.menu.main_menu, menu)
    return true
  }

  override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
    return when (item.itemId) {
      R.id.menu_diagnostics -> {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun handleStartScan() {
    if (!PermissionsHelper.hasPermissions(this)) {
      if (PermissionsHelper.shouldOpenAppSettings(this)) {
        DebugLog.log("Permissions blocked; directing user to app settings", level = android.util.Log.WARN)
        viewModel.refreshPreflightState()
      } else {
        requestScanPermissions()
      }
      return
    }

    if (!PermissionsHelper.isLocationServicesEnabled(this)) {
      DebugLog.log("Location services disabled before scan", level = android.util.Log.WARN)
      viewModel.refreshPreflightState()
      return
    }

    if (!isBluetoothEnabled()) {
      DebugLog.log("Bluetooth disabled, requesting enable")
      val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
      enableBluetoothLauncher.launch(enableIntent)
      return
    }

    viewModel.startScan()
  }

  private fun updateScanState(state: ScanState) {
    clearRecoveryUi()

    when (state) {
      is ScanState.Scanning -> {
        binding.scanStatus.text = getString(R.string.scan_active)
        binding.startScanButton.isEnabled = false
        binding.stopScanButton.isEnabled = true
        binding.permissionHint.isVisible = false
      }
      is ScanState.Complete -> {
        binding.scanStatus.text = getString(R.string.scan_complete)
        binding.permissionHint.isVisible = true
        binding.permissionHint.text = if (state.deviceCount == 0) {
          getString(R.string.scan_complete_no_devices)
        } else {
          getString(R.string.scan_complete_with_devices, state.deviceCount)
        }
        binding.startScanButton.isEnabled = true
        binding.stopScanButton.isEnabled = false
        recoveryAction = RecoveryAction.RETRY_SCAN
        binding.permissionActionButton.text = getString(R.string.retry_scan)
        binding.permissionActionButton.isVisible = true
      }
      is ScanState.Idle -> {
        binding.scanStatus.text = getString(R.string.scan_inactive)
        binding.startScanButton.isEnabled = true
        binding.stopScanButton.isEnabled = false
        binding.permissionHint.isVisible = false
      }
      is ScanState.MissingPermission -> {
        binding.scanStatus.text = getString(R.string.scan_inactive)
        binding.permissionHint.isVisible = true
        val missing = PermissionsHelper.missingPermissionLabels(this).joinToString()
        val blocked = PermissionsHelper.shouldOpenAppSettings(this)
        binding.permissionHint.text = if (blocked) {
          getString(R.string.permissions_blocked_detail, missing)
        } else {
          getString(R.string.permissions_required_detail, missing)
        }
        binding.startScanButton.isEnabled = true
        binding.stopScanButton.isEnabled = false
        recoveryAction = if (blocked) {
          RecoveryAction.OPEN_APP_SETTINGS
        } else {
          RecoveryAction.REQUEST_PERMISSIONS
        }
        binding.permissionActionButton.text = if (blocked) {
          getString(R.string.open_app_settings)
        } else {
          getString(R.string.grant_access)
        }
        binding.permissionActionButton.isVisible = true
      }
      is ScanState.LocationServicesOff -> {
        binding.scanStatus.text = getString(R.string.scan_inactive)
        binding.permissionHint.isVisible = true
        binding.permissionHint.text = getString(R.string.location_services_required_detail)
        binding.startScanButton.isEnabled = true
        binding.stopScanButton.isEnabled = false
        recoveryAction = RecoveryAction.OPEN_LOCATION_SETTINGS
        binding.permissionActionButton.text = getString(R.string.open_location_settings)
        binding.permissionActionButton.isVisible = true
      }
      is ScanState.BluetoothOff -> {
        binding.scanStatus.text = getString(R.string.scan_inactive)
        binding.permissionHint.isVisible = true
        binding.permissionHint.text = getString(R.string.enable_bluetooth)
        binding.startScanButton.isEnabled = true
        binding.stopScanButton.isEnabled = false
        recoveryAction = RecoveryAction.ENABLE_BLUETOOTH
        binding.permissionActionButton.text = getString(R.string.enable_bluetooth_action)
        binding.permissionActionButton.isVisible = true
      }
      is ScanState.Unsupported -> {
        binding.scanStatus.text = getString(R.string.bluetooth_unsupported)
        binding.permissionHint.isVisible = true
        binding.permissionHint.text = getString(R.string.bluetooth_unsupported_detail)
        binding.startScanButton.isEnabled = false
        binding.stopScanButton.isEnabled = false
      }
      is ScanState.Error -> {
        binding.scanStatus.text = getString(R.string.scan_error)
        binding.permissionHint.isVisible = true
        binding.permissionHint.text = state.message
        binding.startScanButton.isEnabled = true
        binding.stopScanButton.isEnabled = false
        recoveryAction = RecoveryAction.RETRY_SCAN
        binding.permissionActionButton.text = getString(R.string.retry_scan)
        binding.permissionActionButton.isVisible = true
      }
    }
  }

  private fun isBluetoothEnabled(): Boolean {
    val manager = getSystemService(BluetoothManager::class.java)
    val adapter = manager?.adapter
    return try {
      adapter?.isEnabled == true
    } catch (_: SecurityException) {
      false
    }
  }

  private fun requestScanPermissions() {
    DebugLog.log("Requesting permissions: ${PermissionsHelper.requiredPermissions()}")
    PermissionsHelper.markPermissionRequestAttempted(this)
    permissionLauncher.launch(PermissionsHelper.requiredPermissions().toTypedArray())
  }

  private fun openAppSettings() {
    val intent = Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", packageName, null)
    )
    startActivity(intent)
  }

  private fun openLocationSettings() {
    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
  }

  private fun runRecoveryAction() {
    when (recoveryAction) {
      RecoveryAction.NONE -> Unit
      RecoveryAction.REQUEST_PERMISSIONS -> requestScanPermissions()
      RecoveryAction.OPEN_APP_SETTINGS -> openAppSettings()
      RecoveryAction.ENABLE_BLUETOOTH -> {
        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableIntent)
      }
      RecoveryAction.OPEN_LOCATION_SETTINGS -> openLocationSettings()
      RecoveryAction.RETRY_SCAN -> handleStartScan()
    }
  }

  private fun clearRecoveryUi() {
    recoveryAction = RecoveryAction.NONE
    binding.permissionActionButton.isVisible = false
  }
}
