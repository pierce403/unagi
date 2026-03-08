package ninja.unagi.data

import kotlinx.coroutines.flow.Flow

class DeviceEnrichmentRepository(
  private val enrichmentDao: DeviceEnrichmentDao
) {
  fun observeEnrichments(): Flow<List<DeviceEnrichmentEntity>> = enrichmentDao.observeEnrichments()

  fun observeEnrichment(deviceKey: String): Flow<DeviceEnrichmentEntity?> =
    enrichmentDao.observeEnrichment(deviceKey)

  suspend fun upsertEnrichment(enrichment: DeviceEnrichmentEntity) {
    enrichmentDao.upsertEnrichment(enrichment)
  }
}
