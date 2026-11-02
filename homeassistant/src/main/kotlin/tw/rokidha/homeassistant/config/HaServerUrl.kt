package tw.rokidha.homeassistant.config

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * Validated Home Assistant base URL (scheme + host + port, no path).
 *
 * Transport policy (README "Security"):
 * - HTTPS is required for any host that is not on a local/private network.
 * - Plain HTTP is accepted only for local hosts (private IPv4/IPv6, `.local`, `.lan`,
 *   `.home.arpa`, single-label names, CGNAT 100.64/10 used by VPNs such as Tailscale).
 * - The scheme the user chose is never changed later: there is no automatic HTTPS → HTTP fallback.
 */
class HaServerUrl private constructor(val base: HttpUrl) {

    val isHttps: Boolean get() = base.isHttps
    val host: String get() = base.host

    /** `ws://` or `wss://` URL of the WebSocket API, matching the base scheme. */
    val webSocketUrl: String
        get() = base.newBuilder().encodedPath("/api/websocket").build().toString()
            .replaceFirst(if (isHttps) "https://" else "http://", if (isHttps) "wss://" else "ws://")

    fun resolve(path: String): HttpUrl = base.newBuilder().encodedPath(path).build()

    /** Display form, e.g. `http://homeassistant.local:8123`. */
    override fun toString(): String = base.toString().trimEnd('/')
    override fun equals(other: Any?): Boolean = other is HaServerUrl && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()

    sealed interface ParseResult {
        data class Ok(val url: HaServerUrl, val warnings: List<String>) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    companion object {
        fun parse(input: String): ParseResult {
            var text = input.trim()
            if (text.isEmpty()) return ParseResult.Invalid("Enter the Home Assistant URL, e.g. http://homeassistant.local:8123")
            val warnings = mutableListOf<String>()
            if (!text.contains("://")) {
                val hostGuess = text.substringBefore('/').substringBefore(':').trim('[', ']')
                text = (if (isLocalHost(hostGuess)) "http://" else "https://") + text
                warnings += "No scheme given; using ${text.substringBefore("://")}://"
            }
            val scheme = text.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") {
                return ParseResult.Invalid("Unsupported scheme '$scheme'. Use http:// or https://")
            }
            val url = text.toHttpUrlOrNull() ?: return ParseResult.Invalid("Not a valid URL: $input")
            if (!url.isHttps && !isLocalHost(url.host)) {
                return ParseResult.Invalid(
                    "Remote servers must use https://. Plain http:// is only allowed on the local network " +
                        "(e.g. 192.168.x.x, homeassistant.local).",
                )
            }
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                return ParseResult.Invalid("Do not put credentials in the URL; use the access token field.")
            }
            if (url.encodedPath != "/" || url.query != null || url.fragment != null) {
                warnings += "Path '${url.encodedPath}' removed; Home Assistant is served from the root."
            }
            val base = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
            return ParseResult.Ok(HaServerUrl(base), warnings)
        }

        /** Throws [IllegalArgumentException] if invalid; for stored, already-validated values. */
        fun of(input: String): HaServerUrl = when (val r = parse(input)) {
            is ParseResult.Ok -> r.url
            is ParseResult.Invalid -> throw IllegalArgumentException(r.reason)
        }

        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain")

        /** Pure string check: never performs DNS resolution. */
        fun isLocalHost(rawHost: String): Boolean {
            val host = rawHost.lowercase().trim('[', ']').trimEnd('.')
            if (host.isEmpty()) return false
            if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
            val ip = parseIpLiteral(host)
            if (ip != null) {
                if (ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress) return true
                val b = ip.address
                if (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) return true // 100.64/10
                if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true // fc00::/7 ULA
                return false
            }
            return !host.contains('.') // single-label name, e.g. "homeassistant"
        }

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        private fun parseIpLiteral(host: String): InetAddress? {
            val looksV4 = IPV4.matches(host) && host.split('.').all { it.toInt() in 0..255 }
            val looksV6 = host.contains(':') && host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
            if (!looksV4 && !looksV6) return null
            // getByName on a literal does not perform a DNS lookup.
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }
    }
}

/** URL + token. Never sent to the glasses; only held by the phone-side client. */
data class HaConfig(val url: HaServerUrl, val token: AccessToken)
