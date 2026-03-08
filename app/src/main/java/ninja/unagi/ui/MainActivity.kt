package ninja.unagi.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ninja.unagi.R
import ninja.unagi.databinding.ActivityMainBinding
import ninja.unagi.scan.ActiveScanPreferences
import ninja.unagi.scan.ActiveScanService
import ninja.unagi.scan.ScanState
import ninja.unagi.scan.StartOnBootPreferences
import ninja.unagi.util.AppVersion
import ninja.unagi.util.AppVersionInfo
import ninja.unagi.util.BatteryOptimizationHelper
import ninja.unagi.util.DebugLog
import ninja.unagi.util.NotificationPermissionHelper
import ninja.unagi.util.PermissionsHelper
import ninja.unagi.util.WindowInsetsHelper

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
  private var bannerCollapsed = false
  private var compactCards = false
  private var activeScanningEnabled = false
  private var continueActiveScanAfterNotificationPrompt = false
  private lateinit var appVersionInfo: AppVersionInfo

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { result ->
    val granted = result.values.all { it }
    DebugLog.log("Permission results: $result")
    if (granted) {
      if (
        activeScanningEnabled &&
        PermissionsHelper.requiresBackgroundLocationForActiveScan() &&
        !PermissionsHelper.hasBackgroundLocationPermission(this)
      ) {
        requestBackgroundLocationPermission()
      } else {
        handleStartScan()
      }
    } else {
      viewModel.refreshPreflightState()
    }
  }

  private val backgroundLocationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    DebugLog.log("Background location permission result: $granted")
    if (granted) {
      handleStartScan()
    } else {
      viewModel.refreshPreflightState()
    }
  }

  private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) {
    val shouldContinue = continueActiveScanAfterNotificationPrompt
    continueActiveScanAfterNotificationPrompt = false
    if (shouldContinue) {
      startActiveScanService()
    }
  }

  private val batteryOptimizationLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    viewModel.refreshPreflightState()
  }

  private val enableBluetoothLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    if (isBluetoothEnabled()) {
      DebugLog.log("Bluetooth enabled by user")
      beginScan()
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
    appVersionInfo = AppVersion.read(this)

    setSupportActionBar(binding.toolbar)
    supportActionBar?.title = getString(R.string.app_name_header)

    WindowInsetsHelper.applyToolbarInsets(binding.toolbar)
    WindowInsetsHelper.applyBottomInsets(binding.deviceList)
    WindowInsetsHelper.requestApplyInsets(binding.root)

    bannerCollapsed = MainDisplayPreferences.isTopBannerCollapsed(this)
    compactCards = MainDisplayPreferences.isCompactDeviceCards(this)
    activeScanningEnabled = ActiveScanPreferences.isEnabled(this)

    adapter = DeviceAdapter(
      onClick = { item ->
        startActivity(DeviceDetailActivity.intent(this, item.deviceKey))
      },
      onStarToggle = { item, starred ->
        viewModel.setStarred(item.deviceKey, starred)
      }
    )
    setCompactCards(compactCards, persist = false)

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
    binding.sortSpinner.onItemSelectedListener =
      object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
          parent: android.widget.AdapterView<*>?,
          view: android.view.View?,
          position: Int,
          id: Long
        ) {
          viewModel.updateSortMode(SortMode.values()[position])
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
      }

    binding.filterInput.doAfterTextChanged { text ->
      viewModel.updateQuery(text?.toString().orEmpty())
    }
    binding.topBannerHeader.setOnClickListener { setBannerCollapsed(!bannerCollapsed) }
    binding.unknownOnly.setOnCheckedChangeListener { _, isChecked ->
      viewModel.setUnknownOnly(isChecked)
    }
    binding.starredOnly.setOnCheckedChangeListener { _, isChecked ->
      viewModel.setStarredOnly(isChecked)
    }
    binding.permissionActionButton.setOnClickListener { runRecoveryAction() }
    setBannerCollapsed(bannerCollapsed, persist = false)

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
            invalidateOptionsMenu()
          }
        }

        launch {
          viewModel.liveDeviceCount.collect { count ->
            supportActionBar?.subtitle = getString(R.string.live_device_count, count)
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    activeScanningEnabled = ActiveScanPreferences.isEnabled(this)
    viewModel.refreshPreflightState()
    invalidateOptionsMenu()
  }

  override fun onStop() {
    super.onStop()
    if (!activeScanningEnabled) {
      viewModel.stopScan()
    }
  }

  override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
    menuInflater.inflate(R.menu.main_menu, menu)
    syncMenuState(menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: android.view.Menu?): Boolean {
    syncMenuState(menu)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
    return when (item.itemId) {
      R.id.menu_alerts -> {
        startActivity(Intent(this, AlertsActivity::class.java))
        true
      }
      R.id.menu_scan_toggle -> {
        if (viewModel.scanState.value is ScanState.Scanning) {
          stopCurrentScan()
        } else {
          handleStartScan()
        }
        true
      }
      R.id.menu_diagnostics -> {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
        true
      }
      R.id.menu_active_scanning -> {
        val enabled = !item.isChecked
        item.isChecked = enabled
        setActiveScanningEnabled(enabled)
        true
      }
      R.id.menu_compact_cards -> {
        val enabled = !item.isChecked
        item.isChecked = enabled
        setCompactCards(enabled)
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun syncMenuState(menu: android.view.Menu?) {
    menu ?: return
    menu.findItem(R.id.menu_active_scanning)?.isChecked = activeScanningEnabled
    menu.findItem(R.id.menu_compact_cards)?.isChecked = compactCards
    menu.findItem(R.id.menu_version)?.title = appVersionInfo.menuLabel
    val scanToggle = menu.findItem(R.id.menu_scan_toggle)
    when (val state = viewModel.scanState.value) {
      is ScanState.Scanning -> {
        scanToggle?.title = getString(R.string.scan_stop)
        scanToggle?.isEnabled = true
      }
      is ScanState.Unsupported -> {
        scanToggle?.title = getString(R.string.scan_start)
        scanToggle?.isEnabled = false
      }
      else -> {
        scanToggle?.title = getString(R.string.scan_start)
        scanToggle?.isEnabled = true
      }
    }
  }

  private fun setBannerCollapsed(collapsed: Boolean, persist: Boolean = true) {
    bannerCollapsed = collapsed
    binding.topBannerContent.isVisible = !collapsed
    binding.topBannerDivider.isVisible = !collapsed
    binding.bannerToggleLabel.text = getString(
      if (collapsed) R.string.show_controls else R.string.hide_controls
    )
    if (persist) {
      MainDisplayPreferences.setTopBannerCollapsed(this, collapsed)
    }
  }

  private fun setCompactCards(enabled: Boolean, persist: Boolean = true) {
    compactCards = enabled
    adapter.setCompactMode(enabled)
    if (persist) {
      MainDisplayPreferences.setCompactDeviceCards(this, enabled)
    }
  }

  private fun setActiveScanningEnabled(enabled: Boolean) {
    if (activeScanningEnabled == enabled) {
      return
    }
    activeScanningEnabled = enabled
    ActiveScanPreferences.setEnabled(this, enabled)
    if (!enabled) {
      ActiveScanService.stop(this)
    } else if (viewModel.scanState.value is ScanState.Scanning) {
      ActiveScanService.start(this)
    }
    if (enabled) {
      maybePromptForStartOnBoot()
      maybePromptForBatteryOptimization()
    }
    viewModel.refreshPreflightState()
    invalidateOptionsMenu()
  }

  private fun handleStartScan() {
    if (!PermissionsHelper.hasPermissions(this, activeScanningEnabled)) {
      if (PermissionsHelper.shouldOpenAppSettings(this, activeScanningEnabled)) {
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
      enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
      return
    }

    beginScan()
  }

  private fun beginScan() {
    if (activeScanningEnabled) {
      maybeRequestNotificationPermissionForActiveScan()
    } else {
      viewModel.startScan()
    }
  }

  private fun stopCurrentScan() {
    if (activeScanningEnabled) {
      ActiveScanService.stop(this)
    } else {
      viewModel.stopScan()
    }
  }

  private fun updateScanState(state: ScanState) {
    clearRecoveryUi()

    when (state) {
      is ScanState.Scanning -> {
        binding.scanStatus.text = getString(R.string.scan_active)
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
        recoveryAction = RecoveryAction.RETRY_SCAN
        binding.permissionActionButton.text = getString(R.string.retry_scan)
        binding.permissionActionButton.isVisible = true
      }
      is ScanState.Idle -> {
        binding.scanStatus.text = getString(R.string.scan_inactive)
        binding.permissionHint.isVisible = false
      }
      is ScanState.MissingPermission -> {
        binding.scanStatus.text = getString(R.string.scan_inactive)
        binding.permissionHint.isVisible = true
        val missing = PermissionsHelper.missingPermissionLabels(this, activeScanningEnabled).joinToString()
        val blocked = PermissionsHelper.shouldOpenAppSettings(this, activeScanningEnabled)
        binding.permissionHint.text = if (blocked) {
          getString(R.string.permissions_blocked_detail, missing)
        } else {
          getString(R.string.permissions_required_detail, missing)
        }
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
        recoveryAction = RecoveryAction.OPEN_LOCATION_SETTINGS
        binding.permissionActionButton.text = getString(R.string.open_location_settings)
        binding.permissionActionButton.isVisible = true
      }
      is ScanState.BluetoothOff -> {
        binding.scanStatus.text = getString(R.string.scan_inactive)
        binding.permissionHint.isVisible = true
        binding.permissionHint.text = getString(R.string.enable_bluetooth)
        recoveryAction = RecoveryAction.ENABLE_BLUETOOTH
        binding.permissionActionButton.text = getString(R.string.enable_bluetooth_action)
        binding.permissionActionButton.isVisible = true
      }
      is ScanState.Unsupported -> {
        binding.scanStatus.text = getString(R.string.bluetooth_unsupported)
        binding.permissionHint.isVisible = true
        binding.permissionHint.text = getString(R.string.bluetooth_unsupported_detail)
      }
      is ScanState.Error -> {
        binding.scanStatus.text = getString(R.string.scan_error)
        binding.permissionHint.isVisible = true
        binding.permissionHint.text = state.message
        recoveryAction = RecoveryAction.RETRY_SCAN
        binding.permissionActionButton.text = getString(R.string.retry_scan)
        binding.permissionActionButton.isVisible = true
      }
    }
  }

  private fun isBluetoothEnabled(): Boolean {
    val adapter = getSystemService(BluetoothManager::class.java)?.adapter
    return try {
      adapter?.isEnabled == true
    } catch (_: SecurityException) {
      false
    }
  }

  private fun requestScanPermissions() {
    val permissions = PermissionsHelper.foregroundPermissions(activeScanningEnabled)
    DebugLog.log("Requesting permissions: $permissions")
    PermissionsHelper.markPermissionRequestAttempted(this)
    if (permissions.isEmpty()) {
      handleStartScan()
      return
    }
    permissionLauncher.launch(permissions.toTypedArray())
  }

  private fun requestBackgroundLocationPermission() {
    if (!PermissionsHelper.requiresBackgroundLocationForActiveScan()) {
      handleStartScan()
      return
    }
    DebugLog.log("Requesting background location permission for active scanning")
    PermissionsHelper.markBackgroundLocationRequestAttempted(this)
    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
  }

  private fun maybePromptForBatteryOptimization() {
    if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
      return
    }
    val requestIntent = BatteryOptimizationHelper.requestIntent(this) ?: return
    AlertDialog.Builder(this)
      .setTitle(R.string.battery_optimization_title)
      .setMessage(R.string.battery_optimization_message)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.battery_optimization_action) { _, _ ->
        batteryOptimizationLauncher.launch(requestIntent)
      }
      .show()
  }

  private fun maybeRequestNotificationPermissionForActiveScan() {
    if (!NotificationPermissionHelper.requiresRuntimePermission()) {
      startActiveScanService()
      return
    }
    if (NotificationPermissionHelper.canPostNotifications(this)) {
      startActiveScanService()
      return
    }

    continueActiveScanAfterNotificationPrompt = true
    AlertDialog.Builder(this)
      .setTitle(R.string.notification_permission_title)
      .setMessage(R.string.notification_permission_detail)
      .setNegativeButton(android.R.string.cancel) { _, _ ->
        startActiveScanService()
      }
      .setPositiveButton(R.string.allow_notifications) { _, _ ->
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
      .show()
  }

  private fun startActiveScanService() {
    maybePromptForBatteryOptimization()
    ActiveScanService.start(this)
  }

  private fun maybePromptForStartOnBoot() {
    AlertDialog.Builder(this)
      .setTitle(R.string.start_on_boot_title)
      .setMessage(R.string.start_on_boot_message)
      .setNegativeButton(R.string.start_on_boot_disable) { _, _ ->
        StartOnBootPreferences.setEnabled(this, false)
      }
      .setPositiveButton(R.string.start_on_boot_enable) { _, _ ->
        StartOnBootPreferences.setEnabled(this, true)
      }
      .show()
  }

  private fun openAppSettings() {
    startActivity(
      Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
      )
    )
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
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
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
