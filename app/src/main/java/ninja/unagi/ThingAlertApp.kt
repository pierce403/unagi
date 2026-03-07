package ninja.unagi

import android.app.Application
import ninja.unagi.alerts.DeviceAlertNotifier
import ninja.unagi.data.AppDatabase
import ninja.unagi.data.AlertRuleRepository
import ninja.unagi.data.DeviceRepository

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
