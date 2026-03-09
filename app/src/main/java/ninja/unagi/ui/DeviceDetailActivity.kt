package ninja.unagi.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ninja.unagi.ThingAlertApp
import ninja.unagi.databinding.ActivityDeviceDetailBinding
import ninja.unagi.enrichment.ActiveBleQueryPreferences
import ninja.unagi.enrichment.BleDeviceInfoQueryClient
import ninja.unagi.enrichment.DeviceEnrichmentFormatter
import ninja.unagi.data.DeviceEntity
import ninja.unagi.data.DeviceEnrichmentEntity
import ninja.unagi.util.BluetoothAssignedNumbersProvider
import ninja.unagi.util.DeviceIdentityPresenter
import ninja.unagi.util.Formatters
import ninja.unagi.util.ObservationMetadata
import ninja.unagi.util.ObservationMetadataParser
import ninja.unagi.util.ObservedTransport
import ninja.unagi.util.VendorPrefixRegistryProvider
import ninja.unagi.util.WindowInsetsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceDetailActivity : AppCompatActivity() {
  private lateinit var binding: ActivityDeviceDetailBinding
  private lateinit var adapter: SightingAdapter
  private var currentDevice: DeviceEntity? = null
  private var currentEnrichment: DeviceEnrichmentEntity? = null
  private var currentMetadata: ObservationMetadata = ObservationMetadata()
  private var pendingExport: DeviceJsonExport? = null
  private var queryInProgress = false
  private val vendorRegistry by lazy { VendorPrefixRegistryProvider.get(this) }
  private val assignedNumbers by lazy { BluetoothAssignedNumbersProvider.get(this) }
  private val app by lazy { application as ThingAlertApp }
  private val repository by lazy { app.repository }
  private val enrichmentRepository by lazy { app.deviceEnrichmentRepository }
  private val affinityGroupRepository by lazy { app.affinityGroupRepository }
  private val queryClient by lazy {
    BleDeviceInfoQueryClient(
      context = this,
      bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter,
      scanController = app.scanController
    )
  }
  private val saveDeviceJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    if (uri == null) {
      pendingExport = null
      return@registerForActivityResult
    }
    writePendingExportToUri(uri)
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
            if (device == null) {
              currentDevice = null
              currentMetadata = ObservationMetadata()
              invalidateOptionsMenu()
              return@collect
            }
            currentDevice = device
            currentMetadata = ObservationMetadataParser.parse(device.lastMetadataJson)
            invalidateOptionsMenu()
            val identity = DeviceIdentityPresenter.present(
              displayName = device.displayName,
              address = device.lastAddress,
              metadataJson = device.lastMetadataJson,
              vendorRegistry = vendorRegistry,
              assignedNumbers = assignedNumbers,
              userCustomName = device.userCustomName
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
            updateSharedOrigin(device.sharedFromGroupIds)
            binding.detailKey.text = "Device key: ${device.deviceKey}"
            binding.detailFirstSeen.text = "First seen: ${Formatters.formatTimestamp(device.firstSeen)}"
            binding.detailLastSeen.text = "Last seen: ${Formatters.formatTimestamp(device.lastSeen)}"
            binding.detailStats.text =
              "RSSI range: ${device.rssiMin}..${device.rssiMax} dBm • Avg: ${"%.1f".format(device.rssiAvg)} • Sightings: ${device.sightingsCount} • Samples: ${device.observationCount}"
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
            currentEnrichment = enrichment
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

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(ninja.unagi.R.menu.device_detail_menu, menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    val exportReady = currentDevice != null
    menu.findItem(ninja.unagi.R.id.menu_rename_device)?.isEnabled = exportReady
    menu.findItem(ninja.unagi.R.id.menu_copy_device_json)?.isEnabled = exportReady
    menu.findItem(ninja.unagi.R.id.menu_save_device_json)?.isEnabled = exportReady
    menu.findItem(ninja.unagi.R.id.menu_share_device_json)?.isEnabled = exportReady
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      ninja.unagi.R.id.menu_rename_device -> {
        showRenameDialog()
        true
      }
      ninja.unagi.R.id.menu_copy_device_json -> {
        copyCurrentDeviceJson()
        true
      }
      ninja.unagi.R.id.menu_save_device_json -> {
        saveCurrentDeviceJson()
        true
      }
      ninja.unagi.R.id.menu_share_device_json -> {
        shareCurrentDeviceJson()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun showRenameDialog() {
    val device = currentDevice ?: return
    val input = android.widget.EditText(this).apply {
      setText(device.userCustomName ?: device.displayName.orEmpty())
      hint = getString(ninja.unagi.R.string.rename_device_hint)
      selectAll()
    }
    val container = android.widget.FrameLayout(this).apply {
      val margin = (16 * resources.displayMetrics.density).toInt()
      setPadding(margin, margin / 2, margin, 0)
      addView(input)
    }
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle(ninja.unagi.R.string.rename_device_title)
      .setView(container)
      .setMessage(ninja.unagi.R.string.rename_device_clear_hint)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val newName = input.text.toString().trim().takeIf(String::isNotEmpty)
        lifecycleScope.launch {
          repository.setUserCustomName(device.deviceKey, newName)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun runBleQuery(device: DeviceEntity, metadata: ObservationMetadata) {
    if (!ActiveBleQueryPreferences.isEnabled(this)) {
      renderQueryControls(device, metadata)
      return
    }
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
    if (!ActiveBleQueryPreferences.isEnabled(this)) {
      binding.queryDeviceInfoButton.isEnabled = false
      binding.queryDeviceInfoButton.text = getString(ninja.unagi.R.string.query_device_info)
      binding.queryDeviceInfoNote.isVisible = true
      binding.queryDeviceInfoNote.text = getString(ninja.unagi.R.string.query_device_info_toggle_disabled)
      return
    }
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

  private fun copyCurrentDeviceJson() {
    val export = buildCurrentExport() ?: return
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
      ClipData.newPlainText(getString(ninja.unagi.R.string.copy_device_json), export.json)
    )
    Toast.makeText(
      this,
      ninja.unagi.R.string.device_json_copied,
      Toast.LENGTH_SHORT
    ).show()
  }

  private fun saveCurrentDeviceJson() {
    val export = buildCurrentExport() ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      lifecycleScope.launch {
        val saved = withContext(Dispatchers.IO) { saveExportToDownloads(export) }
        if (saved) {
          Toast.makeText(
            this@DeviceDetailActivity,
            getString(ninja.unagi.R.string.device_json_saved_downloads, export.fileName),
            Toast.LENGTH_SHORT
          ).show()
        } else {
          Toast.makeText(
            this@DeviceDetailActivity,
            ninja.unagi.R.string.device_json_save_failed,
            Toast.LENGTH_SHORT
          ).show()
        }
      }
      return
    }
    pendingExport = export
    saveDeviceJsonLauncher.launch(export.fileName)
  }

  private fun shareCurrentDeviceJson() {
    val export = buildCurrentExport() ?: return
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, getString(ninja.unagi.R.string.device_json_share_subject, export.fileName))
      putExtra(Intent.EXTRA_TEXT, export.json)
    }
    startActivity(Intent.createChooser(shareIntent, getString(ninja.unagi.R.string.share_device_json)))
  }

  private fun buildCurrentExport(): DeviceJsonExport? {
    val device = currentDevice ?: return null
    return DeviceDetailExport.build(device, currentEnrichment)
  }

  private fun writePendingExportToUri(uri: Uri) {
    val export = pendingExport ?: return
    pendingExport = null
    lifecycleScope.launch {
      val saved = withContext(Dispatchers.IO) {
        runCatching {
          contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(export.json.toByteArray())
          } ?: error("Unable to open export destination")
        }.isSuccess
      }
      Toast.makeText(
        this@DeviceDetailActivity,
        if (saved) {
          getString(ninja.unagi.R.string.device_json_saved, export.fileName)
        } else {
          getString(ninja.unagi.R.string.device_json_save_failed)
        },
        Toast.LENGTH_SHORT
      ).show()
    }
  }

  private fun saveExportToDownloads(export: DeviceJsonExport): Boolean {
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val existingUri = findDownloadsExportUri(collection, export.fileName)
    if (existingUri != null) {
      return runCatching {
        contentResolver.openOutputStream(existingUri, "wt")?.use { stream ->
          stream.write(export.json.toByteArray())
        } ?: error("Unable to open existing Downloads export")
      }.isSuccess
    }
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, export.fileName)
      put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
      put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = contentResolver.insert(collection, values) ?: return false
    return try {
      contentResolver.openOutputStream(uri, "wt")?.use { stream ->
        stream.write(export.json.toByteArray())
      } ?: error("Unable to open Downloads export")
      val completedValues = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, 0)
      }
      contentResolver.update(uri, completedValues, null, null)
      true
    } catch (_: Exception) {
      contentResolver.delete(uri, null, null)
      false
    }
  }

  private fun findDownloadsExportUri(collection: Uri, fileName: String): Uri? {
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(fileName, "${Environment.DIRECTORY_DOWNLOADS}/")
    contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
      if (!cursor.moveToFirst()) {
        return null
      }
      val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
      return ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
    }
    return null
  }

  private fun updateSharedOrigin(sharedFromGroupIds: String?) {
    if (sharedFromGroupIds == null) {
      binding.detailSharedOrigin.isVisible = false
      return
    }
    binding.detailSharedOrigin.isVisible = true
    val groupIds = sharedFromGroupIds.split(",").filter(String::isNotBlank)
    lifecycleScope.launch {
      val names = groupIds.mapNotNull { id ->
        affinityGroupRepository.getGroup(id)?.groupName
      }
      val label = if (names.isNotEmpty()) {
        "Shared from: ${names.joinToString(", ")}"
      } else {
        "Shared from ${groupIds.size} group${if (groupIds.size != 1) "s" else ""}"
      }
      binding.detailSharedOrigin.text = label
    }
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
