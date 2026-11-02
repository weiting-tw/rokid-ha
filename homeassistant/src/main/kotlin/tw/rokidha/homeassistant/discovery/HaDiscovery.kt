package tw.rokidha.homeassistant.discovery

import kotlinx.coroutines.flow.Flow
import tw.rokidha.homeassistant.config.HaServerUrl

/** A Home Assistant instance found on the local network via mDNS/DNS-SD. */
data class DiscoveredHaInstance(
    val serviceName: String,
    val uuid: String?,
    val locationName: String?,
    val version: String?,
    /** Candidate base URLs, best first. The user still picks/edits one; nothing is auto-trusted. */
    val candidates: List<HaServerUrl>,
) {
    val key: String get() = uuid ?: serviceName
    val displayName: String get() = locationName ?: serviceName
}

/**
 * Discovery source. The Android implementation uses NsdManager; it may find nothing on
 * networks with client isolation, across VLANs/VPNs, or when multicast is filtered —
 * manual URL entry must always remain available.
 */
interface HaDiscovery {
    /** Emits instances as they are resolved; completes after [timeoutMs]. */
    fun discover(timeoutMs: Long = 8_000): Flow<DiscoveredHaInstance>
}

/**
 * Parses the TXT record Home Assistant publishes for `_home-assistant._tcp`
 * (keys: location_name, uuid, version, internal_url, external_url, base_url).
 */
object ZeroconfParser {
    const val SERVICE_TYPE = "_home-assistant._tcp."

    fun parse(
        serviceName: String,
        hostAddresses: List<String>,
        port: Int,
        txt: Map<String, String?>,
    ): DiscoveredHaInstance {
        val raw = buildList {
            txt["internal_url"]?.let(::add)
            txt["base_url"]?.let(::add)
            // IPv4 literals first; IPv6 needs brackets.
            hostAddresses.sortedBy { it.contains(':') }.forEach { addr ->
                val host = if (addr.contains(':')) "[${addr.substringBefore('%')}]" else addr
                if (port > 0) add("http://$host:$port")
            }
            txt["external_url"]?.let(::add)
        }
        val candidates = raw.asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { (HaServerUrl.parse(it) as? HaServerUrl.ParseResult.Ok)?.url }
            .distinct()
            .toList()
        return DiscoveredHaInstance(
            serviceName = serviceName,
            uuid = txt["uuid"]?.takeIf { it.isNotBlank() },
            locationName = txt["location_name"]?.takeIf { it.isNotBlank() },
            version = txt["version"]?.takeIf { it.isNotBlank() },
            candidates = candidates,
        )
    }

    /** Merges repeated sightings of the same instance (e.g. IPv4 + IPv6 resolutions). */
    fun merge(existing: List<DiscoveredHaInstance>, found: DiscoveredHaInstance): List<DiscoveredHaInstance> {
        val i = existing.indexOfFirst { it.key == found.key }
        if (i < 0) return existing + found
        val old = existing[i]
        val merged = old.copy(
            locationName = old.locationName ?: found.locationName,
            version = old.version ?: found.version,
            candidates = (old.candidates + found.candidates).distinct(),
        )
        return existing.toMutableList().also { it[i] = merged }
    }
}
