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

  fun observeRecentSightings(deviceKey: String): Flow<List<SightingEntity>> =
    sightingDao.observeRecentSightings(deviceKey, DETAIL_SIGHTING_LIMIT)

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
      val deviceKeys = observations
        .map { it.deviceKey }
        .distinct()
      val devicesByKey = deviceKeys
        .chunked(DEVICE_LOOKUP_CHUNK_SIZE)
        .flatMap { keys -> deviceDao.getDevices(keys) }
        .associateBy { it.deviceKey }
        .toMutableMap()
      val newSightings = ArrayList<SightingEntity>(observations.size)

      // Fold in input order so a single flush can still contain multiple sightings for
      // the same device when their timestamps cross the continuous-sighting window.
      observations.forEach { observation ->
        val existing = devicesByKey[observation.deviceKey]
        val result = mergeObservation(existing, observation)
        devicesByKey[observation.deviceKey] = result.device
        if (result.shouldInsertSighting) {
          newSightings += SightingEntity(
            deviceKey = observation.deviceKey,
            timestamp = observation.lastTimestamp,
            rssi = observation.lastRssi,
            name = observation.name,
            address = observation.address,
            metadataJson = observation.metadataJson
          )
        }
      }

      deviceDao.upsertDevices(devicesByKey.values.toList())
      if (newSightings.isNotEmpty()) {
        sightingDao.insertSightings(newSightings)
      }
      pruneIfNeeded(observations.maxOf { it.lastTimestamp })
    }
  }

  private fun mergeObservation(
    existing: DeviceEntity?,
    observation: BufferedObservation
  ): ObservationMergeResult {
    var shouldInsertSighting = existing == null
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
      val incomingIsLatest = observation.lastTimestamp >= existing.lastSeen
      val isNewSighting = ContinuousSightingPolicy.isNewSighting(
        existing.lastSightingAt,
        observation.lastTimestamp
      )
      shouldInsertSighting = isNewSighting
      val observationCount = existing.observationCount + observation.observationCount
      val sightingsCount = if (isNewSighting) {
        existing.sightingsCount + 1
      } else {
        existing.sightingsCount
      }
      val avg = ((existing.rssiAvg * existing.observationCount) + observation.rssiSum) / observationCount
      DeviceEntity(
        deviceKey = existing.deviceKey,
        displayName = if (incomingIsLatest) observation.name ?: existing.displayName else existing.displayName,
        lastAddress = if (incomingIsLatest) observation.address ?: existing.lastAddress else existing.lastAddress,
        firstSeen = minOf(existing.firstSeen, observation.firstTimestamp),
        lastSeen = maxOf(existing.lastSeen, observation.lastTimestamp),
        lastSightingAt = if (isNewSighting) observation.lastTimestamp else existing.lastSightingAt,
        sightingsCount = sightingsCount,
        observationCount = observationCount,
        lastRssi = if (incomingIsLatest) observation.lastRssi else existing.lastRssi,
        rssiMin = minOf(existing.rssiMin, observation.rssiMin),
        rssiMax = maxOf(existing.rssiMax, observation.rssiMax),
        rssiAvg = avg,
        lastMetadataJson = if (incomingIsLatest) {
          observation.metadataJson ?: existing.lastMetadataJson
        } else {
          existing.lastMetadataJson
        },
        starred = existing.starred,
        userCustomName = existing.userCustomName,
        sharedFromGroupIds = existing.sharedFromGroupIds
      )
    }

    return ObservationMergeResult(updated, shouldInsertSighting)
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

  private data class ObservationMergeResult(
    val device: DeviceEntity,
    val shouldInsertSighting: Boolean
  )

  companion object {
    const val DETAIL_SIGHTING_LIMIT = 100
    private const val DEVICE_LOOKUP_CHUNK_SIZE = 900
  }
}
