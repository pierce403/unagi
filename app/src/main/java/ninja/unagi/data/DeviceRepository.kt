package ninja.unagi.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import ninja.unagi.util.DeviceNoteFormatter

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

  suspend fun setUserCustomName(deviceKey: String, name: String?) {
    deviceDao.setUserCustomName(deviceKey, DeviceNoteFormatter.normalize(name))
  }

  suspend fun recordObservation(observation: DeviceObservation) {
    recordBufferedObservations(listOf(BufferedObservation.from(observation)))
  }

  suspend fun recordBufferedObservations(observations: List<BufferedObservation>) {
    if (observations.isEmpty()) {
      return
    }

    database.withTransaction {
      observations.forEach { observation ->
        recordBufferedObservation(observation)
      }
      pruneIfNeeded(observations.maxOf { it.lastTimestamp })
    }
  }

  private suspend fun recordBufferedObservation(observation: BufferedObservation) {
    val existing = deviceDao.getDevice(observation.deviceKey)
    val updated = if (existing == null) {
      DeviceEntity(
        deviceKey = observation.deviceKey,
        displayName = observation.name,
        lastAddress = observation.address,
        firstSeen = observation.firstTimestamp,
        lastSeen = observation.lastTimestamp,
        lastSightingAt = observation.lastTimestamp,
        sightingsCount = 1,
        observationCount = observation.observationCount,
        lastRssi = observation.lastRssi,
        rssiMin = observation.rssiMin,
        rssiMax = observation.rssiMax,
        rssiAvg = observation.rssiSum.toDouble() / observation.observationCount,
        lastMetadataJson = observation.metadataJson,
        starred = false
      )
    } else {
      val isNewSighting = ContinuousSightingPolicy.isNewSighting(
        existing.lastSightingAt,
        observation.lastTimestamp
      )
      val observationCount = existing.observationCount + observation.observationCount
      val sightingsCount = if (isNewSighting) {
        existing.sightingsCount + 1
      } else {
        existing.sightingsCount
      }
      val avg = ((existing.rssiAvg * existing.observationCount) + observation.rssiSum) / observationCount
      DeviceEntity(
        deviceKey = existing.deviceKey,
        displayName = observation.name ?: existing.displayName,
        lastAddress = observation.address ?: existing.lastAddress,
        firstSeen = minOf(existing.firstSeen, observation.firstTimestamp),
        lastSeen = maxOf(existing.lastSeen, observation.lastTimestamp),
        lastSightingAt = if (isNewSighting) observation.lastTimestamp else existing.lastSightingAt,
        sightingsCount = sightingsCount,
        observationCount = observationCount,
        lastRssi = observation.lastRssi,
        rssiMin = minOf(existing.rssiMin, observation.rssiMin),
        rssiMax = maxOf(existing.rssiMax, observation.rssiMax),
        rssiAvg = avg,
        lastMetadataJson = observation.metadataJson ?: existing.lastMetadataJson,
        starred = existing.starred,
        userCustomName = existing.userCustomName,
        sharedFromGroupIds = existing.sharedFromGroupIds
      )
    }

    deviceDao.upsertDevice(updated)
    if (existing == null || updated.lastSightingAt == observation.lastTimestamp) {
      sightingDao.insertSighting(
        SightingEntity(
          deviceKey = observation.deviceKey,
          timestamp = observation.lastTimestamp,
          rssi = observation.lastRssi,
          name = observation.name,
          address = observation.address,
          metadataJson = observation.metadataJson
        )
      )
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
