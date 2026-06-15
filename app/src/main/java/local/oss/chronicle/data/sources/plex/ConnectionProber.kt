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
 * Notes:
 *  - Uses Plex's `/identity` endpoint, which does not require auth, so the global
 *    [PlexMediaService] (which carries the device's auth token via [PlexInterceptor]) is fine.
 *  - Local connections are tried in parallel with remote ones but sorted first so that, all else
 *    equal, a reachable LAN connection wins the race when on the home network.
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
            /** Wall-clock cap for the whole probe (all connections combined). */
            const val PROBE_TIMEOUT_MS: Long = 10_000L
        }

        /**
         * Race [connections] via `/identity` and return the URI of the first one to succeed,
         * or `null` if none did within [PROBE_TIMEOUT_MS].
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

            // Prefer LAN connections in the ordering (same heuristic as legacy chooseViableConnections),
            // but race all of them so off-network we still find WAN/relay quickly.
            val ordered = connections.sortedByDescending { it.local }
            Timber.d(
                "ConnectionProber: probing ${ordered.size} candidates: ${ordered.map { "${it.uri}(local=${it.local})" }}",
            )

            val plexMediaService = plexMediaServiceProvider.get()

            return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                coroutineScope {
                    val deferred =
                        ordered.map { conn ->
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

                    // Return as soon as any one succeeds. Walk results in declared (local-first) order
                    // so that when both LAN and WAN come back roughly at the same time the LAN wins.
                    val results = deferred.awaitAll()
                    val winner = results.firstOrNull { it != null }
                    if (winner == null) {
                        Timber.w("ConnectionProber: all ${ordered.size} candidates failed")
                    } else {
                        Timber.i("ConnectionProber: selected $winner")
                    }
                    winner
                }
            }
        }
    }
