package tw.rokidha.homeassistant.rest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tw.rokidha.homeassistant.HaException
import tw.rokidha.homeassistant.HaHttp
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.model.HaInstanceInfo
import tw.rokidha.homeassistant.model.str
import java.io.IOException

/**
 * Home Assistant REST API (https://developers.home-assistant.io/docs/api/rest/).
 * Every call is authenticated with `Authorization: Bearer <token>`.
 */
class HaRestClient(
    private val config: HaConfig,
    private val client: OkHttpClient = HaHttp.newClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** `GET /api/` — returns HA's message ("API running.") or throws a classified [HaException]. */
    suspend fun checkApi(): String {
        val body = request("GET", "/api/") as? JsonObject ?: throw HaException.Protocol("/api/ did not return an object")
        return body.str("message") ?: throw HaException.Protocol("/api/ has no 'message'")
    }

    /** `GET /api/config` */
    suspend fun getConfig(): HaInstanceInfo {
        val o = request("GET", "/api/config") as? JsonObject ?: throw HaException.Protocol("/api/config is not an object")
        return HaInstanceInfo(locationName = o.str("location_name"), version = o.str("version"))
    }

    /** `GET /api/states` */
    suspend fun getStates(): List<HaEntity> {
        val arr = request("GET", "/api/states") as? JsonArray ?: throw HaException.Protocol("/api/states is not an array")
        return arr.mapNotNull { (it as? JsonObject)?.let(HaEntity::fromJson) }
    }

    /** `GET /api/states/{entity_id}`; null when HA answers 404. */
    suspend fun getState(entityId: String): HaEntity? {
        require(HaEntity.ENTITY_ID.matches(entityId)) { "invalid entity_id" }
        return try {
            (request("GET", "/api/states/$entityId") as? JsonObject)?.let(HaEntity::fromJson)
        } catch (e: HaException.Http) {
            if (e.code == 404) null else throw e
        }
    }

    /** `POST /api/services/{domain}/{service}`; returns the states HA reports as changed. */
    suspend fun callService(domain: String, service: String, data: JsonObject = JsonObject(emptyMap())): List<HaEntity> {
        require(HaEntity.SLUG.matches(domain) && HaEntity.SLUG.matches(service)) { "invalid domain/service" }
        val arr = request("POST", "/api/services/$domain/$service", data) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(HaEntity::fromJson) }
    }

    private suspend fun request(method: String, path: String, body: JsonObject? = null): JsonElement? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(config.url.resolve(path))
                .header("Authorization", "Bearer ${config.token.value}")
                .header("Accept", "application/json")
                .method(method, body?.toString()?.toRequestBody(JSON))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 -> throw HaException.Unauthorized()
                        resp.code == 403 -> throw HaException.Forbidden()
                        resp.code in 300..399 -> throw HaException.Protocol(
                            "HTTP ${resp.code} redirect refused (check the URL scheme/host; redirects are not followed)",
                        )
                        !resp.isSuccessful -> throw HaException.Http(resp.code)
                    }
                    val text = resp.body?.string().orEmpty()
                    if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }
                        .getOrElse { throw HaException.Protocol("response is not JSON (is this a Home Assistant URL?)") }
                }
            } catch (e: IOException) {
                throw HaException.fromIo(e)
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
