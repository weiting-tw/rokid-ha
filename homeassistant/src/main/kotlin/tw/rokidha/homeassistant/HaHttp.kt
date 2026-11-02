package tw.rokidha.homeassistant

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HaHttp {
    /**
     * Shared client settings:
     * - redirects disabled: a redirect could move the Bearer token to another host or
     *   downgrade https → http, both of which we refuse;
     * - WebSocket ping every 30 s so dead connections are detected and reconnected;
     * - no logging interceptor (it would print the Authorization header).
     */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
}
