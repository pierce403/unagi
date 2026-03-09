package ninja.unagi.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ninja.unagi.R
import ninja.unagi.ThingAlertApp
import ninja.unagi.databinding.ActivityAffinityImportBinding
import ninja.unagi.group.BundleImporter
import ninja.unagi.group.BundleManifest
import ninja.unagi.group.DataMerger
import ninja.unagi.util.WindowInsetsHelper

class AffinityImportActivity : AppCompatActivity() {
  private lateinit var binding: ActivityAffinityImportBinding
  private val app by lazy { application as ThingAlertApp }
  private var manifest: BundleManifest? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityAffinityImportBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    WindowInsetsHelper.applyToolbarInsets(binding.toolbar)
    WindowInsetsHelper.requestApplyInsets(binding.root)

    val uri = intent.data
    if (uri == null) {
      toast(getString(R.string.import_failed, "No file provided"))
      finish()
      return
    }

    // Preview the manifest
    lifecycleScope.launch {
      val previewManifest = withContext(Dispatchers.IO) {
        try {
          contentResolver.openInputStream(uri)?.use { stream ->
            val importer = createImporter()
            importer.peekManifest(stream)
          }
        } catch (e: Exception) {
          null
        }
      }

      if (previewManifest == null) {
        binding.bundleInfo.text = getString(R.string.import_failed, "Could not read bundle")
        binding.importButton.isEnabled = false
        return@launch
      }

      manifest = previewManifest
      binding.bundleInfo.text = buildString {
        appendLine(getString(R.string.bundle_from, previewManifest.senderDisplayName))
        val types = previewManifest.contentTypes.joinToString(", ")
        appendLine(getString(R.string.bundle_contains, types))
        for ((key, count) in previewManifest.itemCounts) {
          appendLine("  $key: $count")
        }
      }
    }

    binding.importButton.setOnClickListener {
      // Fix #16: show confirmation dialog before performing merge
      val m = manifest
      if (m == null) {
        toast(getString(R.string.import_failed, "No manifest"))
        return@setOnClickListener
      }
      val confirmMessage = buildString {
        appendLine(getString(R.string.bundle_from, m.senderDisplayName))
        val types = m.contentTypes.joinToString(", ")
        append(getString(R.string.bundle_contains, types))
      }
      MaterialAlertDialogBuilder(this)
        .setTitle(R.string.import_bundle)
        .setMessage(confirmMessage)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.import_data) { _, _ ->
          performImport(uri)
        }
        .show()
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun performImport(uri: android.net.Uri) {
    binding.importButton.isEnabled = false
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) {
        try {
          contentResolver.openInputStream(uri)?.use { stream ->
            val importer = createImporter()
            importer.importBundle(stream)
          }
        } catch (e: Exception) {
          BundleImporter.ImportResult.Error(e.message ?: "Unknown error")
        }
      }

      binding.importStatus.isVisible = true
      when (result) {
        is BundleImporter.ImportResult.Success -> {
          val r = result.mergeResult
          binding.importStatus.text = getString(
            R.string.import_success,
            r.devicesAdded + r.devicesUpdated,
            r.sightingsAdded,
            r.alertRulesAdded,
            r.enrichmentsAdded
          )
        }
        is BundleImporter.ImportResult.Error -> {
          binding.importStatus.text = getString(R.string.import_failed, result.message)
          binding.importButton.isEnabled = true
        }
        null -> {
          binding.importStatus.text = getString(R.string.import_failed, "Could not open file")
          binding.importButton.isEnabled = true
        }
      }
    }
  }

  private fun createImporter(): BundleImporter {
    val db = app.database
    val merger = DataMerger(
      db, db.deviceDao(), db.sightingDao(), db.alertRuleDao(), db.deviceEnrichmentDao()
    )
    return BundleImporter(app.affinityGroupRepository, merger)
  }

  private fun toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
  }
}
