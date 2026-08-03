package ninja.unagi.ui

import ninja.unagi.data.SightingEntity
import ninja.unagi.util.BluetoothAssignedNumbersRegistry
import ninja.unagi.util.Formatters
import ninja.unagi.util.ObservationMetadataParser
import ninja.unagi.util.PassiveMetadataInterpreter

data class SightingListItem(
  val id: Long,
  val timestampText: String,
  val rssiText: String,
  val metaText: String
)

object SightingListItemMapper {
  fun map(
    sightings: List<SightingEntity>,
    assignedNumbers: BluetoothAssignedNumbersRegistry
  ): List<SightingListItem> {
    return sightings.map { sighting ->
      val metadataSummary = PassiveMetadataInterpreter.summarize(
        ObservationMetadataParser.parse(sighting.metadataJson),
        assignedNumbers
      )
      val metaParts = buildList {
        sighting.name?.takeIf { it.isNotBlank() }?.let { add("Name: $it") }
        sighting.address?.takeIf { it.isNotBlank() }?.let { add("Addr: $it") }
        addAll(metadataSummary.listLabels.take(2))
      }
      SightingListItem(
        id = sighting.id,
        timestampText = Formatters.formatTimestamp(sighting.timestamp),
        rssiText = Formatters.formatRssi(sighting.rssi),
        metaText = metaParts.joinToString(" • ")
      )
    }
  }
}
