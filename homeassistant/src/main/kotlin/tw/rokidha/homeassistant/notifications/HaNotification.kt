package tw.rokidha.homeassistant.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.model.str

/**
 * A notification produced by a Home Assistant automation.
 *
 * Transport: the automation writes to a helper entity (default
 * `input_text.rokid_ha_notification`). We use an entity instead of a custom event because
 * Home Assistant only lets non-admin users `subscribe_events` to a fixed allowlist of event
 * types (custom events are refused), and this project recommends a dedicated non-admin user.
 *
 * Accepted state formats (README "Notifications"):
 * - JSON: `{"title": "Kitchen", "message": "Dishwasher done", "priority": "medium"}`
 * - `priority|title|message`, e.g. `high|Front door|Door opened`
 * - plain text → MEDIUM priority, no title
 * Template sensors may instead set attributes `title` / `priority` with the message as state.
 */
data class HaNotification(
    val id: String,
    val title: String?,
    val message: String,
    val priority: Priority,
    val receivedAtMs: Long,
) {
    enum class Priority { HIGH, MEDIUM, LOW }

    companion object {
        const val DEFAULT_ENTITY = "input_text.rokid_ha_notification"
        private val json = Json { ignoreUnknownKeys = true }

        fun parsePriority(s: String?): Priority = when (s?.trim()?.lowercase()) {
            "high", "critical", "urgent" -> Priority.HIGH
            "low", "info" -> Priority.LOW
            else -> Priority.MEDIUM
        }

        /** Returns null for empty / unknown / unavailable states (nothing to show). */
        fun fromEntity(e: HaEntity, nowMs: Long): HaNotification? {
            val raw = e.state.trim()
            if (raw.isEmpty() || e.isUnknown || e.isUnavailable) return null
            val id = e.lastChanged ?: "$nowMs"
            if (raw.startsWith("{")) {
                val o = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                if (o != null) {
                    val msg = o.str("message") ?: return null
                    return HaNotification(id, o.str("title"), msg, parsePriority(o.str("priority")), nowMs)
                }
            }
            val parts = raw.split('|', limit = 3)
            if (parts.size == 3 && parts[0].trim().lowercase() in setOf("high", "medium", "low", "critical", "urgent", "info")) {
                return HaNotification(id, parts[1].trim().ifEmpty { null }, parts[2].trim(), parsePriority(parts[0]), nowMs)
            }
            return HaNotification(id, e.attr("title"), raw, parsePriority(e.attr("priority")), nowMs)
        }
    }
}
