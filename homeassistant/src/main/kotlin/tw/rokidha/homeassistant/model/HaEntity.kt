package tw.rokidha.homeassistant.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One Home Assistant entity state, as returned by `/api/states` or `state_changed`. */
data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val lastChanged: String? = null,
    val lastUpdated: String? = null,
) {
    val domain: String get() = entityId.substringBefore('.')
    val friendlyName: String get() = attr("friendly_name") ?: entityId
    val isUnavailable: Boolean get() = state == "unavailable"
    val isUnknown: Boolean get() = state == "unknown"

    fun attr(name: String): String? = (attributes[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
        val SLUG = Regex("^[a-z0-9_]+$")

        fun fromJson(o: JsonObject): HaEntity? {
            val id = (o["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return null
            val state = (o["state"] as? JsonPrimitive)?.contentOrNull ?: return null
            return HaEntity(
                entityId = id,
                state = state,
                attributes = o["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = (o["last_changed"] as? JsonPrimitive)?.contentOrNull,
                lastUpdated = (o["last_updated"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

/** Basic instance information from `/api/config`. */
data class HaInstanceInfo(val locationName: String?, val version: String?)

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
