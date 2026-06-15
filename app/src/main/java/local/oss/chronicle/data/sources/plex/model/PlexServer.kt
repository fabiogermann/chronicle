package local.oss.chronicle.data.sources.plex.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * A <Device/> type object from the Plex API. Can represent a Plex server, player, or remote, as
 * designated by the [provides] field
 */
@JsonClass(generateAdapter = true)
data class PlexServer(
    val name: String = "",
    val provides: String = "",
    val connections: List<Connection> = emptyList(),
    val clientIdentifier: String = "",
    val accessToken: String? = "",
    // assume owned server as this is probably more common
    val owned: Boolean = true,
)

/**
 * A single reachable address advertised by a Plex server, mirroring the shape returned by
 * `/api/v2/resources?includeHttps=1&includeRelay=1`.
 *
 * Plex servers advertise three flavors of connection:
 *
 *  - **LAN** ([local] = true): same network as the client. Fastest, always preferred.
 *  - **Direct WAN** ([local] = false, [relay] = false): publicly reachable .plex.direct
 *    address. Preferred over relay for off-network playback.
 *  - **Plex Relay** ([local] = false, [relay] = true): bandwidth-limited (≈2 Mbps) fallback
 *    tunnel through plex.tv. Fine for low-bitrate streams, bad for FLAC / high-bitrate audio
 *    and any video. Used only as a last resort.
 *
 * All fields default so:
 *  - Legacy persisted JSON (fix #1 wrote only `uri` + `local`) still deserializes.
 *  - Tests can construct minimal instances without listing every field.
 */
@JsonClass(generateAdapter = true)
data class Connection(
    val uri: String = "",
    val local: Boolean = false,
    /** True if this is a Plex Relay tunnel (capped at ~2 Mbps; use only as last resort). */
    val relay: Boolean = false,
    /** "http" or "https" as advertised by Plex. Empty when missing (e.g. legacy persisted rows). */
    val protocol: String = "",
    /** Host or IP address Plex resolved for this connection. */
    val address: String = "",
    /** Port number. 0 when unknown / missing. */
    val port: Int = 0,
    /**
     * Whether this connection is reachable only over IPv6.
     * Plex emits this field as `IPv6` (capitalized); we map it explicitly so the property name
     * stays idiomatic Kotlin.
     */
    @Json(name = "IPv6")
    val iPv6: Boolean = false,
) {
    /**
     * Connection priority for sorting candidates before probing. Lower values are preferred.
     *
     *  - 0 = LAN (local = true)
     *  - 1 = direct WAN (local = false, relay = false)
     *  - 2 = relay (local = false, relay = true)
     *
     * See [PRIORITY_COMPARATOR] for use with `sortedWith` / `minByOrNull`.
     */
    val priority: Int
        get() =
            when {
                local -> PRIORITY_LAN
                !relay -> PRIORITY_DIRECT_WAN
                else -> PRIORITY_RELAY
            }

    companion object {
        const val PRIORITY_LAN = 0
        const val PRIORITY_DIRECT_WAN = 1
        const val PRIORITY_RELAY = 2

        /** Comparator that orders connections LAN → direct WAN → relay. Stable for sorting. */
        val PRIORITY_COMPARATOR: Comparator<Connection> =
            Comparator.comparingInt { it.priority }
    }
}
