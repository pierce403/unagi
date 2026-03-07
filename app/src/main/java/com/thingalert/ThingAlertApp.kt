package com.thingalert

import android.app.Application
import com.thingalert.alerts.DeviceAlertNotifier
import com.thingalert.data.AppDatabase
import com.thingalert.data.AlertRuleRepository
import com.thingalert.data.DeviceRepository

class ThingAlertApp : Application() {
  val database: AppDatabase by lazy { AppDatabase.build(this) }
  val repository: DeviceRepository by lazy {
    DeviceRepository(database, database.deviceDao(), database.sightingDao())
  }
  val alertRuleRepository: AlertRuleRepository by lazy {
    AlertRuleRepository(database.alertRuleDao())
  }
  val deviceAlertNotifier: DeviceAlertNotifier by lazy {
    DeviceAlertNotifier(this)
  }
}
