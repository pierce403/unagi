package ninja.unagi.ui

import android.Manifest
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import ninja.unagi.alerts.AlertEmojiPreset
import ninja.unagi.alerts.AlertRuleInputNormalizer
import ninja.unagi.alerts.AlertRuleType
import ninja.unagi.alerts.AlertSoundPreset
import ninja.unagi.data.AlertRuleEntity
import ninja.unagi.data.AlertRuleRepository
import ninja.unagi.databinding.ActivityAlertsBinding
import ninja.unagi.databinding.DialogAlertRuleBinding
import ninja.unagi.util.NotificationPermissionHelper
import ninja.unagi.util.WindowInsetsHelper

class AlertsActivity : AppCompatActivity() {
  private lateinit var binding: ActivityAlertsBinding
  private lateinit var adapter: AlertRuleAdapter
  private val alertRuleRepository: AlertRuleRepository by lazy {
    (application as ThingAlertApp).alertRuleRepository
  }

  private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) {
    updateNotificationPermissionUi()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityAlertsBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    WindowInsetsHelper.applyToolbarInsets(binding.toolbar)
    WindowInsetsHelper.applyBottomInsets(binding.alertRulesList)
    WindowInsetsHelper.requestApplyInsets(binding.root)

    adapter = AlertRuleAdapter(
      onEnabledChanged = { rule, enabled ->
        lifecycleScope.launch {
          alertRuleRepository.setEnabled(rule.id, enabled)
        }
      },
      onEdit = { rule ->
        showRuleDialog(rule)
      },
      onDelete = { rule ->
        lifecycleScope.launch {
          alertRuleRepository.deleteRule(rule.id)
          toast(getString(R.string.alert_rule_deleted))
        }
      }
    )
    binding.alertRulesList.layoutManager = LinearLayoutManager(this)
    binding.alertRulesList.adapter = adapter

    binding.addAlertFab.setOnClickListener { showRuleDialog() }
    binding.notificationPermissionButton.setOnClickListener { requestNotificationPermission() }
    updateNotificationPermissionUi()

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        alertRuleRepository.observeRules().collect { rules ->
          adapter.submitList(rules)
          binding.emptyAlertState.isVisible = rules.isEmpty()
          binding.alertRulesList.isVisible = rules.isNotEmpty()
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    updateNotificationPermissionUi()
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun showRuleDialog(existingRule: AlertRuleEntity? = null) {
    val dialogBinding = DialogAlertRuleBinding.inflate(layoutInflater)
    setupSpinners(dialogBinding, existingRule)
    dialogBinding.alertTargetInput.setText(existingRule?.displayValue.orEmpty())

    val dialog = MaterialAlertDialogBuilder(this)
      .setTitle(
        if (existingRule == null) {
          R.string.add_alert_rule_title
        } else {
          R.string.edit_alert_rule_title
        }
      )
      .setView(dialogBinding.root)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(
        if (existingRule == null) {
          R.string.add_alert
        } else {
          R.string.save_alert
        },
        null
      )
      .create()

    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        saveRule(dialogBinding, existingRule, dialog)
      }
    }
    dialog.show()
  }

  private fun setupSpinners(
    dialogBinding: DialogAlertRuleBinding,
    existingRule: AlertRuleEntity?
  ) {
    dialogBinding.alertTypeSpinner.adapter = ArrayAdapter(
      this,
      R.layout.spinner_item,
      AlertRuleType.entries.map { it.label }
    ).apply {
      setDropDownViewResource(R.layout.spinner_dropdown_item)
    }
    dialogBinding.alertTypeSpinner.onItemSelectedListener =
      object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
          parent: android.widget.AdapterView<*>?,
          view: android.view.View?,
          position: Int,
          id: Long
        ) {
          updateTargetHint(dialogBinding, AlertRuleType.entries[position])
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
      }
    dialogBinding.alertTypeSpinner.setSelection(
      existingRule
        ?.matchType
        ?.let { storageValue -> AlertRuleType.entries.indexOfFirst { it.storageValue == storageValue } }
        ?.takeIf { it >= 0 }
        ?: AlertRuleType.NAME.ordinal
    )

    dialogBinding.alertEmojiSpinner.adapter = ArrayAdapter(
      this,
      R.layout.spinner_item,
      AlertEmojiPreset.entries.map { "${it.emoji} ${it.label}" }
    ).apply {
      setDropDownViewResource(R.layout.spinner_dropdown_item)
    }
    dialogBinding.alertEmojiSpinner.setSelection(
      existingRule
        ?.emoji
        ?.let { emoji -> AlertEmojiPreset.entries.indexOfFirst { it.emoji == emoji } }
        ?.takeIf { it >= 0 }
        ?: 0
    )

    dialogBinding.alertSoundSpinner.adapter = ArrayAdapter(
      this,
      R.layout.spinner_item,
      AlertSoundPreset.entries.map { it.label }
    ).apply {
      setDropDownViewResource(R.layout.spinner_dropdown_item)
    }
    dialogBinding.alertSoundSpinner.setSelection(
      existingRule
        ?.soundPreset
        ?.let { storageValue -> AlertSoundPreset.entries.indexOfFirst { it.storageValue == storageValue } }
        ?.takeIf { it >= 0 }
        ?: 0
    )

    val initialType = AlertRuleType.entries[dialogBinding.alertTypeSpinner.selectedItemPosition]
    updateTargetHint(dialogBinding, initialType)
  }

  private fun updateTargetHint(dialogBinding: DialogAlertRuleBinding, type: AlertRuleType) {
    dialogBinding.alertTargetInputLayout.hint = type.inputHint
  }

  private fun saveRule(
    dialogBinding: DialogAlertRuleBinding,
    existingRule: AlertRuleEntity?,
    dialog: AlertDialog
  ) {
    val type = AlertRuleType.entries[dialogBinding.alertTypeSpinner.selectedItemPosition]
    val emoji = AlertEmojiPreset.entries[dialogBinding.alertEmojiSpinner.selectedItemPosition]
    val soundPreset = AlertSoundPreset.entries[dialogBinding.alertSoundSpinner.selectedItemPosition]
    val rawInput = dialogBinding.alertTargetInput.text?.toString().orEmpty()
    val normalized = AlertRuleInputNormalizer.normalize(type, rawInput)
    if (normalized == null) {
      val message = when (type) {
        AlertRuleType.OUI -> getString(R.string.alert_invalid_oui)
        AlertRuleType.MAC -> getString(R.string.alert_invalid_mac)
        AlertRuleType.NAME -> getString(R.string.alert_invalid_name)
      }
      toast(message)
      return
    }

    lifecycleScope.launch {
      alertRuleRepository.upsertRule(
        AlertRuleEntity(
          id = existingRule?.id ?: 0L,
          matchType = type.storageValue,
          matchPattern = normalized.pattern,
          displayValue = normalized.displayValue,
          emoji = emoji.emoji,
          soundPreset = soundPreset.storageValue,
          enabled = existingRule?.enabled ?: true,
          createdAt = existingRule?.createdAt ?: System.currentTimeMillis()
        )
      )
      dialog.dismiss()
      toast(
        getString(
          if (existingRule == null) {
            R.string.alert_rule_added
          } else {
            R.string.alert_rule_updated
          }
        )
      )
      if (
        NotificationPermissionHelper.requiresRuntimePermission() &&
        !NotificationPermissionHelper.canPostNotifications(this@AlertsActivity)
      ) {
        requestNotificationPermission()
      }
    }
  }

  private fun updateNotificationPermissionUi() {
    val shouldShow = NotificationPermissionHelper.requiresRuntimePermission() &&
      !NotificationPermissionHelper.canPostNotifications(this)
    binding.notificationPermissionCard.isVisible = shouldShow
  }

  private fun requestNotificationPermission() {
    if (!NotificationPermissionHelper.requiresRuntimePermission()) {
      return
    }
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  private fun toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
  }
}
