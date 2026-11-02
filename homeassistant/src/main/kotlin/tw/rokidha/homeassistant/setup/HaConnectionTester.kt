package tw.rokidha.homeassistant.setup

import okhttp3.OkHttpClient
import tw.rokidha.homeassistant.HaException
import tw.rokidha.homeassistant.HaHttp
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.rest.HaRestClient
import tw.rokidha.homeassistant.ws.HaWebSocketConnector

/** Validates a URL + token pair before it is saved: REST `/api/`, `/api/config`, then WebSocket auth. */
class HaConnectionTester(private val client: OkHttpClient = HaHttp.newClient()) {

    sealed interface Result {
        data class Ok(val locationName: String?, val version: String?) : Result
        data class Failed(val error: HaException) : Result
    }

    suspend fun test(config: HaConfig): Result = try {
        val rest = HaRestClient(config, client)
        rest.checkApi()
        val info = rest.getConfig()
        val session = HaWebSocketConnector(client).connect(config)
        val version = session.haVersion ?: info.version
        session.close()
        Result.Ok(info.locationName, version)
    } catch (e: HaException) {
        Result.Failed(e)
    }
}
