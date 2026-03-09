package ninja.unagi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
import ninja.unagi.data.AffinityGroupRepository
import ninja.unagi.databinding.ActivityAffinityGroupsBinding
import ninja.unagi.group.GroupKeyManager
import ninja.unagi.group.GroupSharingConfig
import ninja.unagi.util.WindowInsetsHelper
import org.json.JSONObject
import java.util.UUID

class AffinityGroupsActivity : AppCompatActivity() {
  private lateinit var binding: ActivityAffinityGroupsBinding
  private lateinit var adapter: AffinityGroupAdapter
  private val repository: AffinityGroupRepository by lazy {
    (application as ThingAlertApp).affinityGroupRepository
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityAffinityGroupsBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.title = getString(R.string.affinity_groups)
    WindowInsetsHelper.applyToolbarInsets(binding.toolbar)
    WindowInsetsHelper.applyBottomInsets(binding.groupsList)
    WindowInsetsHelper.requestApplyInsets(binding.root)

    adapter = AffinityGroupAdapter { group ->
      startActivity(
        Intent(this, AffinityGroupDetailActivity::class.java)
          .putExtra("groupId", group.groupId)
      )
    }
    binding.groupsList.layoutManager = LinearLayoutManager(this)
    binding.groupsList.adapter = adapter

    binding.addGroupFab.setOnClickListener { showCreateOrJoinDialog() }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        repository.observeGroups().collect { groups ->
          adapter.submitList(groups)
          binding.emptyGroupState.isVisible = groups.isEmpty()
          binding.groupsList.isVisible = groups.isNotEmpty()
        }
      }
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun showCreateOrJoinDialog() {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.affinity_groups)
      .setItems(arrayOf(getString(R.string.create_group), getString(R.string.join_group))) { _, which ->
        when (which) {
          0 -> showCreateGroupDialog()
          1 -> showJoinGroupDialog()
        }
      }
      .show()
  }

  private fun showCreateGroupDialog() {
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(64, 32, 64, 0)
    }
    val nameInput = EditText(this).apply { hint = getString(R.string.group_name_hint) }
    val displayNameInput = EditText(this).apply { hint = getString(R.string.your_display_name_hint) }
    layout.addView(nameInput)
    layout.addView(displayNameInput)

    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.create_group)
      .setView(layout)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.create_group) { _, _ ->
        val name = nameInput.text.toString().trim()
        val displayName = displayNameInput.text.toString().trim()
        if (name.isNotEmpty() && displayName.isNotEmpty()) {
          createGroup(name, displayName)
        }
      }
      .show()
  }

  private fun createGroup(name: String, displayName: String) {
    lifecycleScope.launch {
      val groupId = UUID.randomUUID().toString()
      val memberId = UUID.randomUUID().toString()
      val rawKey = GroupKeyManager.generateGroupKey()
      val wrappedKey = GroupKeyManager.wrapKey(rawKey)
      val config = GroupSharingConfig()

      val group = AffinityGroupEntity(
        groupId = groupId,
        groupName = name,
        createdAt = System.currentTimeMillis(),
        myMemberId = memberId,
        myDisplayName = displayName,
        groupKeyWrapped = wrappedKey,
        keyEpoch = 1,
        sharingConfigJson = config.toJson()
      )
      repository.createGroup(group)
      repository.addMember(
        AffinityGroupMemberEntity(
          groupId = groupId,
          memberId = memberId,
          displayName = displayName,
          joinedAt = System.currentTimeMillis(),
          lastSeenEpoch = 1,
          publicKeyBase64 = null,
          revoked = false
        )
      )

      // Fix #12: include creator's memberId in share payload
      // Fix #14: include checksum for key integrity verification
      val shareJson = JSONObject().apply {
        put("g", groupId)
        put("n", name)
        put("k", GroupKeyManager.exportKeyForSharing(rawKey))
        put("e", 1)
        put("c", displayName)
        put("m", memberId)
        put("h", GroupKeyManager.computeKeyChecksum(rawKey, groupId))
      }

      // Fix #1: mark clipboard content as sensitive on Android 13+
      copyToClipboardSensitive(shareJson.toString())
      toast(getString(R.string.group_created))
      toast(getString(R.string.group_key_copied))
    }
  }

  private fun showJoinGroupDialog() {
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(64, 32, 64, 0)
    }
    val keyInput = EditText(this).apply {
      hint = getString(R.string.join_paste_hint)
      minLines = 3
    }
    val displayNameInput = EditText(this).apply { hint = getString(R.string.your_display_name_hint) }
    layout.addView(keyInput)
    layout.addView(displayNameInput)

    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.join_group)
      .setView(layout)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.join_group) { _, _ ->
        val keyJson = keyInput.text.toString().trim()
        val displayName = displayNameInput.text.toString().trim()
        if (keyJson.isNotEmpty() && displayName.isNotEmpty()) {
          joinGroup(keyJson, displayName)
        }
      }
      .show()
  }

  private fun joinGroup(keyJson: String, displayName: String) {
    lifecycleScope.launch {
      try {
        val obj = JSONObject(keyJson)
        val groupId = obj.getString("g")
        val groupName = obj.getString("n")
        val rawKeyBase64 = obj.getString("k")
        val epoch = obj.getInt("e")
        val creatorName = obj.optString("c", "Creator")
        // Fix #12: use real creator memberId from the share payload
        val creatorMemberId = obj.optString("m", UUID.randomUUID().toString())

        if (repository.getGroup(groupId) != null) {
          toast(getString(R.string.group_joined))
          return@launch
        }

        val rawKey = GroupKeyManager.importKeyFromSharing(rawKeyBase64)

        // Fix #14: verify key checksum if present
        val checksum = obj.optString("h", "")
        if (checksum.isNotEmpty() && !GroupKeyManager.verifyKeyChecksum(rawKey, groupId, checksum)) {
          toast(getString(R.string.join_invalid_key))
          return@launch
        }

        val wrappedKey = GroupKeyManager.wrapKey(rawKey)
        val memberId = UUID.randomUUID().toString()
        val config = GroupSharingConfig()

        repository.createGroup(
          AffinityGroupEntity(
            groupId = groupId,
            groupName = groupName,
            createdAt = System.currentTimeMillis(),
            myMemberId = memberId,
            myDisplayName = displayName,
            groupKeyWrapped = wrappedKey,
            keyEpoch = epoch,
            sharingConfigJson = config.toJson()
          )
        )

        repository.addMember(
          AffinityGroupMemberEntity(
            groupId = groupId, memberId = memberId, displayName = displayName,
            joinedAt = System.currentTimeMillis(), lastSeenEpoch = epoch,
            publicKeyBase64 = null, revoked = false
          )
        )

        // Fix #12: use real creator memberId instead of hardcoded "creator"
        repository.addMember(
          AffinityGroupMemberEntity(
            groupId = groupId, memberId = creatorMemberId, displayName = creatorName,
            joinedAt = System.currentTimeMillis(), lastSeenEpoch = epoch,
            publicKeyBase64 = null, revoked = false
          )
        )

        toast(getString(R.string.group_joined))
      } catch (e: Exception) {
        toast(getString(R.string.join_invalid_key))
      }
    }
  }

  /**
   * Fix #1: copy to clipboard with IS_SENSITIVE flag on Android 13+ to prevent
   * clipboard content from appearing in the notification shade.
   */
  private fun copyToClipboardSensitive(text: String) {
    val clip = ClipData.newPlainText("UNAGI group key", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
      }
    }
    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
  }

  private fun toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
  }
}
