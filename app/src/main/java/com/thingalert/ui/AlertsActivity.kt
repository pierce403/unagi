package com.thingalert.ui

import android.Manifest
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.thingalert.R
import com.thingalert.ThingAlertApp
import com.thingalert.alerts.AlertEmojiPreset
import com.thingalert.alerts.AlertRuleInputNormalizer
import com.thingalert.alerts.AlertRuleType
import com.thingalert.alerts.AlertSoundPreset
import com.thingalert.data.AlertRuleEntity
import com.thingalert.data.AlertRuleRepository
import com.thingalert.databinding.ActivityAlertsBinding
import com.thingalert.util.NotificationPermissionHelper
import com.thingalert.util.WindowInsetsHelper
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
      onDelete = { rule ->
        lifecycleScope.launch {
          alertRuleRepository.deleteRule(rule.id)
          toast(getString(R.string.alert_rule_deleted))
        }
      }
    )
    binding.alertRulesList.layoutManager = LinearLayoutManager(this)
    binding.alertRulesList.adapter = adapter

    setupSpinners()
    binding.addAlertButton.setOnClickListener { addRule() }
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

  private fun setupSpinners() {
    binding.alertTypeSpinner.adapter = ArrayAdapter(
      this,
      R.layout.spinner_item,
      AlertRuleType.entries.map { it.label }
    ).apply {
      setDropDownViewResource(R.layout.spinner_dropdown_item)
    }
    binding.alertTypeSpinner.setSelection(AlertRuleType.NAME.ordinal)
    binding.alertTypeSpinner.onItemSelectedListener =
      object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
          parent: android.widget.AdapterView<*>?,
          view: android.view.View?,
          position: Int,
          id: Long
        ) {
          updateTargetHint(AlertRuleType.entries[position])
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
      }

    binding.alertEmojiSpinner.adapter = ArrayAdapter(
      this,
      R.layout.spinner_item,
      AlertEmojiPreset.entries.map { "${it.emoji} ${it.label}" }
    ).apply {
      setDropDownViewResource(R.layout.spinner_dropdown_item)
    }

    binding.alertSoundSpinner.adapter = ArrayAdapter(
      this,
      R.layout.spinner_item,
      AlertSoundPreset.entries.map { it.label }
    ).apply {
      setDropDownViewResource(R.layout.spinner_dropdown_item)
    }

    updateTargetHint(AlertRuleType.NAME)
  }

  private fun updateTargetHint(type: AlertRuleType) {
    binding.alertTargetInputLayout.hint = type.inputHint
  }

  private fun addRule() {
    val type = AlertRuleType.entries[binding.alertTypeSpinner.selectedItemPosition]
    val emoji = AlertEmojiPreset.entries[binding.alertEmojiSpinner.selectedItemPosition]
    val soundPreset = AlertSoundPreset.entries[binding.alertSoundSpinner.selectedItemPosition]
    val rawInput = binding.alertTargetInput.text?.toString().orEmpty()
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
      alertRuleRepository.addRule(
        AlertRuleEntity(
          matchType = type.storageValue,
          matchPattern = normalized.pattern,
          displayValue = normalized.displayValue,
          emoji = emoji.emoji,
          soundPreset = soundPreset.storageValue,
          enabled = true,
          createdAt = System.currentTimeMillis()
        )
      )
      binding.alertTargetInput.setText("")
      toast(getString(R.string.alert_rule_added))
      if (NotificationPermissionHelper.requiresRuntimePermission() &&
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
