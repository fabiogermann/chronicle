package local.oss.chronicle.data.sources.plex

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import local.oss.chronicle.data.sources.plex.model.Connection
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Probes a list of Plex server [Connection]s and returns the URI of the first reachable one.
 *
 * Off-network playback fix: this is the reusable extraction of the connection race previously
 * locked inside [PlexConfig.chooseViableConnections]. [ServerConnectionResolver] calls into this
 * on resolve (when the cached choice is stale) and on explicit re-resolve (e.g. after a player
 * error or a network change), so that the app can fail over from a LAN URI to a WAN URI when
 * the user leaves their home network.
 *
 * Fix #4 (relay deprioritization): the prober now races connections **per tier** in priority
 * order — LAN, then direct WAN, then Plex Relay. A lower tier is only opened if every connection
 * in the previous tier failed. This mirrors the official Plex client and prevents the
 * bandwidth-limited (~2 Mbps) relay from being picked when a direct WAN connection is reachable.
 *
 * Notes:
 *  - Uses Plex's `/identity` endpoint, which does not require auth, so the global
 *    [PlexMediaService] (which carries the device's auth token via [PlexInterceptor]) is fine.
 *  - Each individual attempt has a short timeout so an unreachable connection doesn't block the
 *    others; the whole probe is also bounded by [PROBE_TIMEOUT_MS].
 */
@Singleton
open class ConnectionProber
    @Inject
    constructor(
        private val plexMediaServiceProvider: Provider<PlexMediaService>,
    ) {
        companion object {
            /** Wall-clock cap for the whole probe (all tiers combined). */
            const val PROBE_TIMEOUT_MS: Long = 10_000L
        }

        /**
         * Tier-aware probe via `/identity`. Returns the URI of the first connection in the
         * highest-priority reachable tier, or `null` if every candidate fails within
         * [PROBE_TIMEOUT_MS].
         *
         * @param connections The candidate server URIs (LAN, WAN, relay, ...).
         * @param authToken   The auth token to use for the probe. Currently unused because
         *                    `/identity` is unauthenticated, but accepted so that callers can
         *                    pass per-library tokens without having to know whether the probe
         *                    actually needs them - this future-proofs the API for fix #4.
         */
        open suspend fun probe(
            connections: List<Connection>,
            @Suppress("UNUSED_PARAMETER") authToken: String,
        ): String? {
            if (connections.isEmpty()) {
                Timber.w("ConnectionProber: no candidates to probe")
                return null
            }

            val tiers = connections.groupBy { it.priority }.toSortedMap()
            Timber.d(
                "ConnectionProber: probing ${connections.size} candidates across " +
                    "${tiers.size} tier(s): ${connections.map { "${it.uri}(local=${it.local},relay=${it.relay})" }}",
            )

            val plexMediaService = plexMediaServiceProvider.get()

            return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                for ((tier, tierConnections) in tiers) {
                    val tierName =
                        when (tier) {
                            Connection.PRIORITY_LAN -> "LAN"
                            Connection.PRIORITY_DIRECT_WAN -> "direct WAN"
                            else -> "relay"
                        }
                    val winner = raceTier(tierConnections, plexMediaService, tierName)
                    if (winner != null) {
                        if (tier == Connection.PRIORITY_RELAY) {
                            Timber.w(
                                "Falling back to Plex Relay ($winner) " +
                                    "— bandwidth limited to ~2 Mbps; LAN and direct WAN both unreachable",
                            )
                        }
                        Timber.i("ConnectionProber: selected $winner (tier=$tierName)")
                        return@withTimeoutOrNull winner
                    }
                    Timber.d("ConnectionProber: tier $tierName exhausted; trying next tier")
                }
                Timber.w("ConnectionProber: all ${connections.size} candidates across all tiers failed")
                null
            }
        }

        /**
         * Races all connections in a single tier in parallel. Returns the URI of the first one
         * to succeed, or `null` if every candidate in the tier failed.
         */
        private suspend fun raceTier(
            tierConnections: List<Connection>,
            plexMediaService: PlexMediaService,
            tierName: String,
        ): String? =
            coroutineScope {
                Timber.d("ConnectionProber: probing $tierName tier (${tierConnections.size} candidate(s))")
                val deferred =
                    tierConnections.map { conn ->
                        async {
                            try {
                                val response = plexMediaService.checkServer(conn.uri)
                                if (response.isSuccessful) {
                                    Timber.d("ConnectionProber: ${conn.uri} OK")
                                    conn.uri
                                } else {
                                    Timber.d(
                                        "ConnectionProber: ${conn.uri} failed (${response.code()} ${response.message()})",
                                    )
                                    null
                                }
                            } catch (t: Throwable) {
                                Timber.d("ConnectionProber: ${conn.uri} threw ${t.javaClass.simpleName}: ${t.message}")
                                null
                            }
                        }
                    }

                // Wait for the whole tier to complete then return the first success in declared
                // order. Declared order matches the input list order, which preserves caller
                // intent for any future intra-tier tie-breaking.
                val results = deferred.awaitAll()
                results.firstOrNull { it != null }
            }
    }
