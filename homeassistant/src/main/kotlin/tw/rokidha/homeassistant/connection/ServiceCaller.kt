package tw.rokidha.homeassistant.connection

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Anything that can execute a Home Assistant service call (the live WebSocket session in production). */
interface ServiceCaller {
    /** Throws [tw.rokidha.homeassistant.HaException] on failure; never queues. */
    suspend fun callService(domain: String, service: String, entityId: String?, data: JsonObject = JsonObject(emptyMap())): JsonElement?
}
