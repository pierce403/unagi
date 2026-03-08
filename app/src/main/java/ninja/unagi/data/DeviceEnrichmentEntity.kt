package ninja.unagi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_enrichments")
data class DeviceEnrichmentEntity(
  @PrimaryKey val deviceKey: String,
  val lastQueryTimestamp: Long,
  val queryMethod: String,
  val servicesPresentJson: String?,
  val disAvailable: Boolean,
  val disReadStatus: String,
  val manufacturerName: String?,
  val modelNumber: String?,
  val serialNumber: String?,
  val hardwareRevision: String?,
  val firmwareRevision: String?,
  val softwareRevision: String?,
  val systemId: String?,
  val pnpVendorIdSource: Int?,
  val pnpVendorId: Int?,
  val pnpProductId: Int?,
  val pnpProductVersion: Int?,
  val errorCode: Int?,
  val errorMessage: String?,
  val connectDurationMs: Long?,
  val servicesDiscovered: Int,
  val characteristicReadSuccessCount: Int,
  val characteristicReadFailureCount: Int,
  val finalGattStatus: Int?
)
