package ninja.unagi.group

import org.json.JSONObject

/**
 * Configures which data categories a group shares and which an individual member contributes.
 * Group-level defaults are set by the group creator. Individual members can further restrict
 * their contributions by disabling categories at export time.
 */
data class GroupSharingConfig(
  val devices: Boolean = true,
  val sightings: Boolean = true,
  val alertRules: Boolean = false,
  val enrichments: Boolean = false,
  val starredDevices: Boolean = true,
  val tpmsReadings: Boolean = true,
  val exportWindowDays: Int = 30
) {
  fun toJson(): String {
    val obj = JSONObject()
    obj.put("devices", devices)
    obj.put("sightings", sightings)
    obj.put("alertRules", alertRules)
    obj.put("enrichments", enrichments)
    obj.put("starredDevices", starredDevices)
    obj.put("tpmsReadings", tpmsReadings)
    obj.put("exportWindowDays", exportWindowDays)
    return obj.toString()
  }

  /** Merge with individual overrides: a category is shared only if BOTH group and individual allow it. */
  fun mergeWith(individual: GroupSharingConfig): GroupSharingConfig {
    return GroupSharingConfig(
      devices = devices && individual.devices,
      sightings = sightings && individual.sightings,
      alertRules = alertRules && individual.alertRules,
      enrichments = enrichments && individual.enrichments,
      starredDevices = starredDevices && individual.starredDevices,
      tpmsReadings = tpmsReadings && individual.tpmsReadings
    )
  }

  companion object {
    fun fromJson(json: String): GroupSharingConfig {
      val obj = JSONObject(json)
      return GroupSharingConfig(
        devices = obj.optBoolean("devices", true),
        sightings = obj.optBoolean("sightings", true),
        alertRules = obj.optBoolean("alertRules", false),
        enrichments = obj.optBoolean("enrichments", false),
        starredDevices = obj.optBoolean("starredDevices", true),
        tpmsReadings = obj.optBoolean("tpmsReadings", true),
        exportWindowDays = obj.optInt("exportWindowDays", 30)
      )
    }
  }
}
