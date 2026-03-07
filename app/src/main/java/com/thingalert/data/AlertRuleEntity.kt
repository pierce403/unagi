package com.thingalert.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_rules")
data class AlertRuleEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val matchType: String,
  val matchPattern: String,
  val displayValue: String,
  val emoji: String,
  val soundPreset: String,
  val enabled: Boolean,
  val createdAt: Long
)
