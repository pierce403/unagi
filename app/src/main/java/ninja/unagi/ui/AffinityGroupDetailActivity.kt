package ninja.unagi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import ninja.unagi.R
import ninja.unagi.ThingAlertApp
import ninja.unagi.data.AffinityGroupEntity
import ninja.unagi.data.AffinityGroupMemberEntity
import ninja.unagi.databinding.ActivityAffinityGroupDetailBinding
import ninja.unagi.group.BundleExporter
import ninja.unagi.group.EcdhKeyManager
import ninja.unagi.group.GroupKeyManager
import ninja.unagi.group.GroupSharingConfig
import ninja.unagi.util.DeviceCredentialHelper
import ninja.unagi.util.WindowInsetsHelper
import org.json.JSONObject

class AffinityGroupDetailActivity : AppCompatActivity() {
  private lateinit var binding: ActivityAffinityGroupDetailBinding
  private lateinit var memberAdapter: AffinityMemberAdapter
  private val app by lazy { application as ThingAlertApp }
  private val repository by lazy { app.affinityGroupRepository }

  private var currentGroup: AffinityGroupEntity? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityAffinityGroupDetailBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    WindowInsetsHelper.applyToolbarInsets(binding.toolbar)
    WindowInsetsHelper.applyBottomInsets(binding.scrollView)
    WindowInsetsHelper.requestApplyInsets(binding.root)

    val groupId = intent.getStringExtra("groupId") ?: run {
      finish()
      return
    }

    // Fix #10: use Flow-based observation for reactive updates
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        repository.observeGroup(groupId).collect { group ->
          if (group == null) {
            finish()
            return@collect
          }
          currentGroup = group
          supportActionBar?.title = group.groupName

          val config = GroupSharingConfig.fromJson(group.sharingConfigJson)
          binding.groupDetailInfo.text = getString(
            R.string.group_list_info,
            java.text.DateFormat.getDateInstance().format(java.util.Date(group.createdAt)),
            group.keyEpoch
          )

          // Initialize member adapter once we know the memberId
          if (!::memberAdapter.isInitialized) {
            memberAdapter = AffinityMemberAdapter(group.myMemberId) { member ->
              confirmRevokeMember(member)
            }
            binding.membersList.layoutManager = LinearLayoutManager(this@AffinityGroupDetailActivity)
            binding.membersList.adapter = memberAdapter
          }

          bindSharingConfig(config)
        }
      }
    }

    // Observe members separately
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        repository.observeMembers(groupId).collect { members ->
          if (::memberAdapter.isInitialized) {
            memberAdapter.submitList(members)
          }
        }
      }
    }

    binding.exportBundleButton.setOnClickListener {
      DeviceCredentialHelper.authenticate(
        this,
        getString(R.string.auth_required_title),
        getString(R.string.auth_required_export),
        onSuccess = { exportBundle() }
      )
    }
    binding.shareGroupKeyButton.setOnClickListener {
      DeviceCredentialHelper.authenticate(
        this,
        getString(R.string.auth_required_title),
        getString(R.string.auth_required_share_key),
        onSuccess = { shareGroupKey() }
      )
    }
    binding.deleteGroupButton.setOnClickListener { confirmDeleteGroup() }
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun bindSharingConfig(config: GroupSharingConfig) {
    // Temporarily remove listeners to prevent save loops
    val switches = listOf(
      binding.shareDevices, binding.shareSightings, binding.shareAlertRules,
      binding.shareEnrichments, binding.shareStarred, binding.shareTpms
    )
    switches.forEach { it.setOnCheckedChangeListener(null) }

    binding.shareDevices.isChecked = config.devices
    binding.shareSightings.isChecked = config.sightings
    binding.shareAlertRules.isChecked = config.alertRules
    binding.shareEnrichments.isChecked = config.enrichments
    binding.shareStarred.isChecked = config.starredDevices
    binding.shareTpms.isChecked = config.tpmsReadings

    setupSharingConfigListeners()
  }

  private fun setupSharingConfigListeners() {
    val listener = { _: android.widget.CompoundButton, _: Boolean -> saveSharingConfig() }
    binding.shareDevices.setOnCheckedChangeListener(listener)
    binding.shareSightings.setOnCheckedChangeListener(listener)
    binding.shareAlertRules.setOnCheckedChangeListener(listener)
    binding.shareEnrichments.setOnCheckedChangeListener(listener)
    binding.shareStarred.setOnCheckedChangeListener(listener)
    binding.shareTpms.setOnCheckedChangeListener(listener)
  }

  private fun saveSharingConfig() {
    val group = currentGroup ?: return
    val config = GroupSharingConfig(
      devices = binding.shareDevices.isChecked,
      sightings = binding.shareSightings.isChecked,
      alertRules = binding.shareAlertRules.isChecked,
      enrichments = binding.shareEnrichments.isChecked,
      starredDevices = binding.shareStarred.isChecked,
      tpmsReadings = binding.shareTpms.isChecked
    )
    lifecycleScope.launch {
      repository.updateGroup(group.copy(sharingConfigJson = config.toJson()))
    }
  }

  private fun exportBundle() {
    val group = currentGroup ?: return
    val config = GroupSharingConfig.fromJson(group.sharingConfigJson)
    lifecycleScope.launch {
      try {
        val db = app.database
        val exporter = BundleExporter(
          db.deviceDao(), db.sightingDao(), db.alertRuleDao(), db.deviceEnrichmentDao()
        )
        val members = repository.getMembers(group.groupId)
        val bundleBytes = exporter.export(group, config, members)
        val fileName = BundleExporter.defaultFileName(group.groupName)

        shareBundleViaIntent(fileName, bundleBytes)
      } catch (e: Exception) {
        toast(getString(R.string.export_failed))
      }
    }
  }

  private fun shareBundleViaIntent(fileName: String, bytes: ByteArray) {
    val bundlesDir = java.io.File(cacheDir, "bundles")
    bundlesDir.mkdirs()
    val file = java.io.File(bundlesDir, fileName)
    file.writeBytes(bytes)

    val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "application/octet-stream"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(shareIntent, getString(R.string.export_bundle)))
  }

  private fun shareGroupKey() {
    val group = currentGroup ?: return
    lifecycleScope.launch {
      val rawKey = GroupKeyManager.unwrapKey(group.groupKeyWrapped)
      val publicKey = EcdhKeyManager.getPublicKey(group.groupId, group.myMemberId)
      val shareJson = JSONObject().apply {
        put("g", group.groupId)
        put("n", group.groupName)
        put("k", GroupKeyManager.exportKeyForSharing(rawKey))
        put("e", group.keyEpoch)
        put("c", group.myDisplayName)
        put("m", group.myMemberId)
        if (publicKey != null) put("pk", publicKey)
        put("h", GroupKeyManager.computeKeyChecksum(rawKey, group.groupId))
      }
      copyToClipboardSensitive(shareJson.toString())
      toast(getString(R.string.group_key_copied))
    }
  }

  private fun confirmRevokeMember(member: AffinityGroupMemberEntity) {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.revoke_member)
      .setMessage(getString(R.string.revoke_member_confirm, member.displayName))
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.revoke_member) { _, _ ->
        lifecycleScope.launch {
          val updated = repository.revokeMemberAndRotateKey(member.groupId, member.memberId)
          if (updated != null) {
            toast(getString(R.string.member_revoked_toast, updated.keyEpoch))
          }
        }
      }
      .show()
  }

  private fun confirmDeleteGroup() {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.delete_group)
      .setMessage(R.string.delete_group_confirm)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.delete_group) { _, _ ->
        val group = currentGroup ?: return@setPositiveButton
        lifecycleScope.launch {
          repository.deleteGroup(group.groupId)
          toast(getString(R.string.group_deleted))
          finish()
        }
      }
      .show()
  }

  private fun copyToClipboardSensitive(text: String) {
    val clip = ClipData.newPlainText("UNAGI group key", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
      }
    }
    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
    scheduleClipboardClear(clipboard)
  }

  private fun scheduleClipboardClear(clipboard: ClipboardManager) {
    Handler(Looper.getMainLooper()).postDelayed({
      val current = clipboard.primaryClip
      if (current?.description?.label == "UNAGI group key") {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
      }
    }, 60_000)
  }

  private fun toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
  }
}
