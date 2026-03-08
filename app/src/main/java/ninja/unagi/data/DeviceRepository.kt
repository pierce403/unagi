package ninja.unagi.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class DeviceRepository(
  private val database: AppDatabase,
  private val deviceDao: DeviceDao,
  private val sightingDao: SightingDao
) {
  private val retentionDays = 30L
  private var lastPrunedAt = 0L

  fun observeDevices(): Flow<List<DeviceEntity>> = deviceDao.observeDevices()

  fun observeDevice(deviceKey: String): Flow<DeviceEntity?> = deviceDao.observeDevice(deviceKey)

  fun observeSightings(deviceKey: String): Flow<List<SightingEntity>> =
    sightingDao.observeSightings(deviceKey)

  suspend fun setStarred(deviceKey: String, starred: Boolean) {
    deviceDao.setStarred(deviceKey, starred)
  }

  suspend fun recordObservation(observation: DeviceObservation) {
    database.withTransaction {
      ninja.unagi.util.DebugLog.log(
        "Record observation key=${observation.deviceKey.take(8)} name=${observation.name ?: "unknown"} " +
          "rssi=${observation.rssi}"
      )
      val existing = deviceDao.getDevice(observation.deviceKey)
      val updated = if (existing == null) {
        DeviceEntity(
          deviceKey = observation.deviceKey,
          displayName = observation.name,
          lastAddress = observation.address,
          firstSeen = observation.timestamp,
          lastSeen = observation.timestamp,
          lastSightingAt = observation.timestamp,
          sightingsCount = 1,
          observationCount = 1,
          lastRssi = observation.rssi,
          rssiMin = observation.rssi,
          rssiMax = observation.rssi,
          rssiAvg = observation.rssi.toDouble(),
          lastMetadataJson = observation.metadataJson,
          starred = false
        )
      } else {
        val isNewSighting = ContinuousSightingPolicy.isNewSighting(
          existing.lastSightingAt,
          observation.timestamp
        )
        val observationCount = existing.observationCount + 1
        val sightingsCount = if (isNewSighting) {
          existing.sightingsCount + 1
        } else {
          existing.sightingsCount
        }
        val avg = ((existing.rssiAvg * existing.observationCount) + observation.rssi) / observationCount
        DeviceEntity(
          deviceKey = existing.deviceKey,
          displayName = observation.name ?: existing.displayName,
          lastAddress = observation.address ?: existing.lastAddress,
          firstSeen = minOf(existing.firstSeen, observation.timestamp),
          lastSeen = maxOf(existing.lastSeen, observation.timestamp),
          lastSightingAt = if (isNewSighting) observation.timestamp else existing.lastSightingAt,
          sightingsCount = sightingsCount,
          observationCount = observationCount,
          lastRssi = observation.rssi,
          rssiMin = minOf(existing.rssiMin, observation.rssi),
          rssiMax = maxOf(existing.rssiMax, observation.rssi),
          rssiAvg = avg,
          lastMetadataJson = observation.metadataJson ?: existing.lastMetadataJson,
          starred = existing.starred
        )
      }

      deviceDao.upsertDevice(updated)
      if (existing == null || updated.lastSightingAt == observation.timestamp) {
        sightingDao.insertSighting(
          SightingEntity(
            deviceKey = observation.deviceKey,
            timestamp = observation.timestamp,
            rssi = observation.rssi,
            name = observation.name,
            address = observation.address,
            metadataJson = observation.metadataJson
          )
        )
      }

      pruneIfNeeded(observation.timestamp)
    }
  }

  private suspend fun pruneIfNeeded(now: Long) {
    if (!DeviceMaintenancePolicy.shouldPrune(lastPrunedAt, now)) {
      return
    }
    val threshold = now - retentionDays * 24 * 60 * 60 * 1000
    sightingDao.pruneOlderThan(threshold)
    deviceDao.deleteOlderThan(threshold)
    lastPrunedAt = now
  }
}
