package ninja.unagi

import android.app.Application
import ninja.unagi.alerts.DefaultAlertSeeder
import ninja.unagi.scan.ScanController
import ninja.unagi.alerts.DeviceAlertNotifier
import ninja.unagi.data.AppDatabase
import ninja.unagi.data.AlertRuleRepository
import ninja.unagi.data.DeviceEnrichmentRepository
import ninja.unagi.data.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ThingAlertApp : Application() {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val database: AppDatabase by lazy { AppDatabase.build(this) }
  val repository: DeviceRepository by lazy {
    DeviceRepository(database, database.deviceDao(), database.sightingDao())
  }
  val deviceEnrichmentRepository: DeviceEnrichmentRepository by lazy {
    DeviceEnrichmentRepository(database.deviceEnrichmentDao())
  }
  val alertRuleRepository: AlertRuleRepository by lazy {
    AlertRuleRepository(database.alertRuleDao())
  }
  val deviceAlertNotifier: DeviceAlertNotifier by lazy {
    DeviceAlertNotifier(this)
  }
  val scanController: ScanController by lazy {
    ScanController(
      this,
      applicationScope,
      repository,
      alertRuleRepository,
      deviceAlertNotifier
    )
  }

  override fun onCreate() {
    super.onCreate()
    applicationScope.launch {
      DefaultAlertSeeder.seedIfNeeded(this@ThingAlertApp, alertRuleRepository)
    }
  }
}
