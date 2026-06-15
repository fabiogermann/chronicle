package local.oss.chronicle.data.sources.plex

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.features.account.CredentialManager
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves server URL and authentication token for a given library ID.
 *
 * This component enables library-aware playback by maintaining a cache of server
 * connections per library. When a track from a specific library needs to be played,
 * this resolver provides the correct server URL and auth token to use.
 *
 * **Off-network playback (this fix):**
 * Each [Library] row now persists the full list of [local.oss.chronicle.data.sources.plex.model.Connection]s
 * returned by Plex at login / sync time. When [resolve] is called and the persisted choice is stale
 * (no successful probe within [CONNECTION_FRESHNESS_MS]) or absent, the resolver re-races those
 * connections via [ConnectionProber], persists the winner back to the [Library] row, and caches it
 * in memory. This is what lets the app fail over from a LAN URI to a WAN URI when the user leaves
 * their home network instead of being stuck on the LAN URL that was active at login time.
 *
 * **Seams for related fixes:**
 *  - [invalidate] - call after a network change to force re-probe on the next resolve (fix #2).
 *  - [reResolve]  - call from `MediaPlayerService.onPlayerError` to immediately re-race (fix #3).
 *
 * **Caching Strategy:**
 * - Uses ConcurrentHashMap for thread-safe O(1) lookups
 * - In-memory cache persists for the lifetime of the application or until [invalidate]/[clearCache]
 * - Database row carries [Library.lastConnectionCheckAt] so the probe TTL survives process death
 * - Per-library [Mutex] prevents thundering herd: concurrent resolve()s on the same library only
 *   probe once
 *
 * **Fallback Behavior:**
 * - Primary: Uses library.authToken from database
 * - Fallback 1: If library.authToken is null/empty, looks up Account credentials
 * - Fallback 2: If Account lookup fails, uses global PlexPrefsRepo
 * - **Self-healing:** Updates library.authToken in DB when resolved from fallback
 * - Ensures backward compatibility with single-account setups
 *
 * @see ServerConnection
 * @see LibraryRepository
 * @see ConnectionProber
 */
@Singleton
class ServerConnectionResolver
    @Inject
    constructor(
        private val libraryRepository: LibraryRepository,
        private val accountRepository: AccountRepository,
        private val credentialManager: CredentialManager,
        private val plexConfig: PlexConfig,
        private val plexPrefsRepo: PlexPrefsRepo,
        private val connectionProber: ConnectionProber,
    ) {
        companion object {
            /**
             * How long a successful connection probe is considered fresh before the resolver
             * will re-probe on the next [resolve] call. 5 minutes is short enough to catch
             * network handoffs (Wi-Fi → cellular) reasonably quickly while not hammering Plex
             * during normal playback.
             */
            const val CONNECTION_FRESHNESS_MS: Long = 5 * 60 * 1000L
        }

        /**
         * Thread-safe cache of server connections by library ID.
         * Key: libraryId (e.g., "plex:library:4")
         * Value: ServerConnection with URL and token
         */
        private val cache = ConcurrentHashMap<String, ServerConnection>()

        /**
         * Library IDs whose next [resolve] should force a re-probe regardless of TTL. Populated by
         * [invalidate] so that callers like the NetworkMonitor (fix #2) can say "the network just
         * changed, re-evaluate the chosen connection on the next resolve" without having to launch
         * a coroutine themselves.
         *
         * A sentinel `"*"` entry means "force every library".
         */
        private val pendingReprobe: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * Per-library mutex map. Ensures that concurrent [resolve]s for the same library don't
         * each kick off their own probe (thundering herd) - only the first one probes, the rest
         * see the cached result once it returns.
         */
        private val perLibraryProbeMutex = ConcurrentHashMap<String, Mutex>()

        /**
         * Resolves server connection for a library ID.
         *
         * Resolution order:
         * 1. Check cache for existing connection
         * 2. If cache miss, query database for library's serverUrl and authToken
         * 3. If the library has a persisted [Library.connections] list and the choice is stale,
         *    re-probe and update the row
         * 4. If library lacks connection info, fall back to global PlexConfig
         *
         * @param libraryId The library ID (e.g., "plex:library:4"), or null to use global config
         * @return ServerConnection with URL and token (never null - always returns fallback if needed)
         */
        suspend fun resolve(libraryId: String): ServerConnection = doResolve(libraryId, forceReprobe = false)

        /**
         * Forces a fresh probe of the library's persisted [Library.connections] list, bypassing
         * both the in-memory cache and the on-disk freshness TTL. Intended for recovery paths like
         * `MediaPlayerService.onPlayerError` (fix #3) where we already know the cached URI failed.
         *
         * Falls back to the same behavior as [resolve] if no [Library.connections] list is persisted.
         */
        suspend fun reResolve(libraryId: String): ServerConnection {
            Timber.d("reResolve($libraryId): forcing re-probe")
            cache.remove(libraryId)
            return doResolve(libraryId, forceReprobe = true)
        }

        private suspend fun doResolve(
            libraryId: String,
            forceReprobe: Boolean,
        ): ServerConnection {
            // If invalidate() was called for this library (or globally) since the last resolve,
            // treat this as a forced re-probe and consume the marker.
            val invalidationPending =
                pendingReprobe.remove(libraryId) || pendingReprobe.remove("*")
            val effectiveForce = forceReprobe || invalidationPending

            // Check cache first (skipped when effectiveForce)
            if (!effectiveForce) {
                cache[libraryId]?.let { cached ->
                    if (cached.serverUrl != null && cached.authToken != null) {
                        Timber.v("Cache hit for libraryId=$libraryId: serverUrl=${cached.serverUrl}")
                        return cached
                    }
                }
            }

            // Cache miss or incomplete data - query database
            val library = libraryRepository.getLibraryById(libraryId)
            val dbConnection = libraryRepository.getServerConnection(libraryId)

            // Detect corrupted authToken (raw JSON string from previous bug)
            val libraryToken = dbConnection?.authToken
            val isTokenCorrupted = libraryToken?.startsWith("{") == true
            if (isTokenCorrupted) {
                Timber.w("Library authToken appears corrupted (JSON string) for $libraryId, treating as empty")
            }
            val validLibraryToken = if (isTokenCorrupted) null else libraryToken

            // Attempt to resolve authToken with multi-tier fallback
            var resolvedToken = validLibraryToken
            var usedFallback = false
            var selfHealNeeded = isTokenCorrupted // Need to heal if token was corrupted

            if (resolvedToken.isNullOrEmpty()) {
                Timber.w("Library $libraryId has empty authToken, attempting fallback")

                if (library != null) {
                    // Fallback 1: Try to get token from library's parent Account
                    val credentialsJson =
                        try {
                            credentialManager.getCredentials(library.accountId)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to retrieve credentials for account ${library.accountId}")
                            null
                        }

                    if (!credentialsJson.isNullOrEmpty()) {
                        val userToken = extractUserToken(credentialsJson)
                        if (userToken.isNotEmpty()) {
                            resolvedToken = userToken
                            usedFallback = true
                            selfHealNeeded = true
                            Timber.i("Resolved authToken from Account credentials for libraryId=$libraryId")
                        }
                    }
                }

                // Fallback 2: Use global PlexPrefsRepo (last resort) if still no token
                if (resolvedToken.isNullOrEmpty()) {
                    resolvedToken = plexPrefsRepo.server?.accessToken
                        ?: plexPrefsRepo.user?.authToken
                        ?: plexPrefsRepo.accountAuthToken

                    if (!resolvedToken.isNullOrEmpty()) {
                        usedFallback = true
                        if (library != null) {
                            selfHealNeeded = true
                        }
                        Timber.w("Resolved authToken from global PlexPrefsRepo for libraryId=$libraryId (last resort)")
                    }
                }
            }

            // ---- Off-network re-probing -----------------------------------------------------
            // If the library has a persisted Connection list and either:
            //  - forceReprobe was requested, or
            //  - the chosen URI is stale (no successful probe within CONNECTION_FRESHNESS_MS), or
            //  - the chosen URI is missing entirely,
            // then race the list and pick a reachable one.
            val probedUrl: String? =
                if (library != null && !library.connections.isNullOrEmpty() && !resolvedToken.isNullOrEmpty()) {
                    maybeReprobe(library, resolvedToken, effectiveForce)
                } else {
                    null
                }

            // Don't fall back to PlexConfig.url if it's still the placeholder
            val fallbackUrl = plexConfig.url.takeUnless { it == PlexConfig.PLACEHOLDER_URL }

            val effectiveServerUrl =
                probedUrl
                    ?: dbConnection?.serverUrl
                    ?: fallbackUrl

            val resolved =
                ServerConnection(
                    serverUrl = effectiveServerUrl,
                    authToken = resolvedToken,
                )

            // Cache the result
            cache[libraryId] = resolved

            // Self-healing: Update library in DB with resolved token for future use
            if (selfHealNeeded && library != null && !resolvedToken.isNullOrEmpty()) {
                try {
                    val updatedLibrary = library.copy(authToken = resolvedToken)
                    libraryRepository.updateLibrary(updatedLibrary)
                    Timber.i("Self-heal: Updated library $libraryId with resolved authToken")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to self-heal library $libraryId authToken")
                }
            }

            if (dbConnection?.serverUrl != null && dbConnection.authToken != null && !usedFallback) {
                Timber.d("Resolved connection for libraryId=$libraryId from database: serverUrl=${resolved.serverUrl}")
            } else if (usedFallback) {
                Timber.w(
                    "Used fallback for libraryId=$libraryId: serverUrl=${resolved.serverUrl}, tokenSource=${if (selfHealNeeded) "Account/PlexPrefs" else "unknown"}",
                )
            } else {
                Timber.d("Using fallback PlexConfig for libraryId=$libraryId: serverUrl=${resolved.serverUrl}")
            }

            return resolved
        }

        /**
         * Race the library's persisted [Library.connections] list and persist the winner.
         *
         * Returns the winning URI on success, or `null` if no probe was performed (choice is fresh)
         * or all candidates failed. When `null` is returned the caller falls back to whatever URL
         * the row already had - that's intentional: a possibly-dead URL is still a better starting
         * point than none at all (mirrors [PlexConfig.connectionHasBeenLost] philosophy).
         */
        private suspend fun maybeReprobe(
            library: Library,
            authToken: String,
            forceReprobe: Boolean,
        ): String? {
            val connections = library.connections ?: return null
            val now = System.currentTimeMillis()
            val lastCheck = library.lastConnectionCheckAt ?: 0L
            val isStale = (now - lastCheck) > CONNECTION_FRESHNESS_MS
            val hasNoChoice = library.chosenConnectionUri.isNullOrEmpty()

            if (!forceReprobe && !isStale && !hasNoChoice) {
                Timber.v(
                    "Library ${library.id}: chosen URI ${library.chosenConnectionUri} is still fresh (${now - lastCheck}ms old), skipping probe",
                )
                return null
            }

            // Per-library mutex prevents concurrent probes for the same library.
            val mutex = perLibraryProbeMutex.getOrPut(library.id) { Mutex() }
            return mutex.withLock {
                // Re-check freshness inside the lock: another coroutine may have probed while we waited.
                val fresh = libraryRepository.getLibraryById(library.id) ?: library
                val now2 = System.currentTimeMillis()
                val lastCheck2 = fresh.lastConnectionCheckAt ?: 0L
                val stillStale = (now2 - lastCheck2) > CONNECTION_FRESHNESS_MS
                val stillNoChoice = fresh.chosenConnectionUri.isNullOrEmpty()
                if (!forceReprobe && !stillStale && !stillNoChoice) {
                    Timber.v("Library ${library.id}: another coroutine probed while we waited; reusing ${fresh.chosenConnectionUri}")
                    return@withLock fresh.chosenConnectionUri
                }

                Timber.i(
                    "Library ${library.id}: probing ${connections.size} connection(s) (forceReprobe=$forceReprobe, stale=$isStale, hasNoChoice=$hasNoChoice)",
                )
                val winner = connectionProber.probe(connections, authToken)
                if (winner != null) {
                    try {
                        val updated =
                            fresh.copy(
                                chosenConnectionUri = winner,
                                serverUrl = winner, // keep legacy column in sync for callers still reading it
                                lastConnectionCheckAt = System.currentTimeMillis(),
                            )
                        libraryRepository.updateLibrary(updated)
                        Timber.d("Library ${library.id}: persisted chosen URI = $winner")
                    } catch (e: Exception) {
                        Timber.e(e, "Library ${library.id}: failed to persist chosen URI")
                    }
                } else {
                    Timber.w("Library ${library.id}: probe returned no reachable connection")
                }
                winner
            }
        }

        /**
         * Clears all cached connections.
         * Call this when accounts change or when global configuration is updated.
         */
        fun clearCache() {
            cache.clear()
            Timber.d("Cleared all cached server connections")
        }

        /**
         * Invalidates the cached connection for a specific library, or for **all** libraries when
         * [libraryId] is null. The next [resolve] call will re-query the database AND force a
         * re-probe of the persisted [Library.connections] list (regardless of the freshness TTL).
         *
         * This is the seam that fix #2 (NetworkMonitor wiring) will call on every network change:
         * the network just shifted, so whatever we picked before is no longer trustworthy.
         *
         * @param libraryId The library ID to invalidate, or `null` to invalidate every library.
         */
        fun invalidate(libraryId: String? = null) {
            if (libraryId == null) {
                val size = cache.size
                cache.clear()
                pendingReprobe.add("*")
                Timber.d("Invalidated all cached server connections ($size entries); next resolve will re-probe")
            } else {
                cache.remove(libraryId)
                pendingReprobe.add(libraryId)
                Timber.d("Invalidated cached connection for libraryId=$libraryId; next resolve will re-probe")
            }
        }

        /**
         * Extracts userToken from credentials JSON string.
         * Format: {"userToken":"abc123","serverToken":"xyz789"}
         *
         * Using simple string parsing to avoid org.json.JSONObject dependency
         * issues in unit tests (android.jar stubs throw exceptions).
         */
        private fun extractUserToken(credentialsJson: String): String {
            return try {
                val userTokenPattern = """"userToken"\s*:\s*"([^"]+)"""".toRegex()
                userTokenPattern.find(credentialsJson)?.groupValues?.get(1) ?: ""
            } catch (e: Exception) {
                Timber.e(e, "Failed to extract userToken from credentials JSON")
                ""
            }
        }
    }
