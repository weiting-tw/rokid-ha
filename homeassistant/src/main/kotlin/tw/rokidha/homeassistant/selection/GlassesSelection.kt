package tw.rokidha.homeassistant.selection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tw.rokidha.homeassistant.HaException
import tw.rokidha.homeassistant.credentials.KeyValueStore
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.model.str
import tw.rokidha.homeassistant.notifications.HaNotification

/** One allowed `domain.service` call on one entity. The set of quick actions IS the allowlist. */
data class QuickAction(
    val entityId: String,
    val service: String,
    val label: String,
    val requiresConfirmation: Boolean,
) {
    val domain: String get() = entityId.substringBefore('.')
    val id: String get() = "$entityId/$service"
}

/** What the user chose to expose to the glasses. Nothing is exposed by default. */
data class GlassesSelection(
    val favorites: List<String> = emptyList(),
    val quickActions: List<QuickAction> = emptyList(),
    val notificationEntityId: String = HaNotification.DEFAULT_ENTITY,
) {
    fun withFavorite(entityId: String, selected: Boolean): GlassesSelection {
        require(HaEntity.ENTITY_ID.matches(entityId)) { "invalid entity_id" }
        return if (selected) {
            if (entityId in favorites || favorites.size >= MAX_FAVORITES) this else copy(favorites = favorites + entityId)
        } else {
            copy(favorites = favorites - entityId, quickActions = quickActions.filterNot { it.entityId == entityId })
        }
    }

    /** Adds an action only if the service is in [ServiceCatalog] for that domain and the entity is a favorite. */
    fun withQuickAction(entityId: String, service: String, label: String): GlassesSelection {
        val option = ServiceCatalog.find(entityId.substringBefore('.'), service)
            ?: throw HaException.NotAllowed("$service is not offered for ${entityId.substringBefore('.')}")
        require(entityId in favorites) { "select the entity as a favorite first" }
        val action = QuickAction(entityId, service, label.ifBlank { option.label }, option.sensitive)
        return copy(quickActions = quickActions.filterNot { it.id == action.id } + action)
    }

    fun withoutQuickAction(id: String): GlassesSelection = copy(quickActions = quickActions.filterNot { it.id == id })

    companion object {
        /** Lines available for entities on the glasses dashboard (see DashboardRenderer). */
        const val MAX_FAVORITES = 6
    }
}

data class ServiceOption(val service: String, val label: String, val sensitive: Boolean)

/**
 * Services a quick action may use, per domain. Anything not listed cannot be called from
 * this app. `alarm_control_panel` and other security domains are intentionally absent.
 * Sensitive services always require an explicit confirmation step.
 */
object ServiceCatalog {
    private fun on(label: String = "On") = ServiceOption("turn_on", label, false)
    private val off = ServiceOption("turn_off", "Off", false)
    private val toggle = ServiceOption("toggle", "Toggle", false)

    val byDomain: Map<String, List<ServiceOption>> = mapOf(
        "light" to listOf(on(), off, toggle),
        "switch" to listOf(on(), off, toggle),
        "fan" to listOf(on(), off, toggle),
        "input_boolean" to listOf(on(), off, toggle),
        "scene" to listOf(on("Activate")),
        "script" to listOf(on("Run")),
        "button" to listOf(ServiceOption("press", "Press", false)),
        "media_player" to listOf(ServiceOption("media_play_pause", "Play/Pause", false), on(), off),
        "climate" to listOf(on(), off),
        "cover" to listOf(
            ServiceOption("open_cover", "Open", true),
            ServiceOption("close_cover", "Close", true),
            ServiceOption("stop_cover", "Stop", false),
        ),
        "lock" to listOf(ServiceOption("lock", "Lock", true), ServiceOption("unlock", "Unlock", true)),
    )

    fun find(domain: String, service: String): ServiceOption? = byDomain[domain]?.firstOrNull { it.service == service }
}

/** Last line of defence before any service call leaves the phone. */
class ServiceCallGuard(private val selection: () -> GlassesSelection) {
    /** Returns the matching allowlisted action or throws [HaException.NotAllowed]. */
    fun check(entityId: String, service: String): QuickAction {
        val s = selection()
        if (entityId !in s.favorites) throw HaException.NotAllowed("$entityId is not exposed to the glasses")
        val action = s.quickActions.firstOrNull { it.entityId == entityId && it.service == service }
            ?: throw HaException.NotAllowed("$service on $entityId is not an allowed quick action")
        if (ServiceCatalog.find(action.domain, service) == null) throw HaException.NotAllowed("$service is not in the service catalog")
        return action
    }
}

/** JSON persistence of [GlassesSelection]. */
class SelectionStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): GlassesSelection {
        val text = store.getString(KEY) ?: return GlassesSelection()
        val o = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return GlassesSelection()
        val favorites = (o["favorites"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }.filter { HaEntity.ENTITY_ID.matches(it) }
            .distinct().take(GlassesSelection.MAX_FAVORITES)
        // Re-validate every stored action against the current catalog.
        val actions = (o["quickActions"] as? JsonArray).orEmpty().mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            val id = a.str("entityId") ?: return@mapNotNull null
            val svc = a.str("service") ?: return@mapNotNull null
            val opt = ServiceCatalog.find(id.substringBefore('.'), svc) ?: return@mapNotNull null
            if (id !in favorites) return@mapNotNull null
            QuickAction(id, svc, a.str("label") ?: opt.label, opt.sensitive)
        }
        val notif = o.str("notificationEntityId")?.takeIf { HaEntity.ENTITY_ID.matches(it) } ?: HaNotification.DEFAULT_ENTITY
        return GlassesSelection(favorites, actions, notif)
    }

    fun save(s: GlassesSelection) {
        val o = buildJsonObject {
            put("favorites", buildJsonArray { s.favorites.forEach { add(JsonPrimitive(it)) } })
            put("quickActions", buildJsonArray {
                s.quickActions.forEach { a ->
                    add(buildJsonObject { put("entityId", a.entityId); put("service", a.service); put("label", a.label) })
                }
            })
            put("notificationEntityId", s.notificationEntityId)
        }
        store.putString(KEY, o.toString())
    }

    fun clear() = store.remove(KEY)

    private companion object {
        const val KEY = "glasses.selection"
    }
}
