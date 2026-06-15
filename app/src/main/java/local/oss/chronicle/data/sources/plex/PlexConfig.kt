package local.oss.chronicle.data.sources.plex

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.request.ImageRequest
import com.tonyodev.fetch2.Request
import kotlinx.coroutines.*
import local.oss.chronicle.R
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.sources.plex.PlexConfig.ConnectionResult.Failure
import local.oss.chronicle.data.sources.plex.PlexConfig.ConnectionResult.Success
import local.oss.chronicle.data.sources.plex.PlexConfig.ConnectionState.*
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.util.RetryConfig
import local.oss.chronicle.util.RetryResult
import local.oss.chronicle.util.getImage
import local.oss.chronicle.util.toUri
import local.oss.chronicle.util.withRetry
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Responsible for the configuration of the Plex.
 *
 * Eventually will provide the sole interface for interacting with the Plex remote source.
 *
 * TODO: merge the behavior here into [PlexMediaSource]
 */
@Singleton
class PlexConfig
    @Inject
    constructor(
        private val plexPrefsRepo: PlexPrefsRepo,
        private val scopedPlexServiceFactoryProvider: Provider<ScopedPlexServiceFactory>,
    ) {
        companion object {
            /** Timeout for individual connection attempt (reduced from 15s) */
            const val CONNECTION_TIMEOUT_MS = 10_000L // 10 seconds per attempt

            /** Maximum total connection time with retries */
            const val MAX_CONNECTION_TIME_MS = 30_000L // 30 seconds total

            const val PLACEHOLDER_URL = "http://placeholder.com"
        }

        /**
         * Session-scoped cache of thumbnail URLs that returned 404 Not Found.
         * Prevents repeated failed HTTP requests during the same playback session.
         * Cleared when a new book starts playing.
         */
        private val failedThumbnailUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Simple OkHttpClient for checking thumbnail URLs (HEAD requests only).
         * Created separately to avoid circular dependency with the main Media OkHttpClient
         * which uses PlexConfig's interceptor.
         */
        private val thumbnailCheckClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        /**
         * Retry configuration for server connections.
         * Initial timeout reduced to 10s per attempt, with 3 attempts total = ~30s max.
         */
        private val connectionRetryConfig =
            RetryConfig(
                maxAttempts = 3,
                initialDelayMs = 1000L,
                maxDelayMs = 5000L,
                multiplier = 2.0,
            )

        private val connectionSet = mutableSetOf<Connection>()

        var url: String = PLACEHOLDER_URL

        private val _isConnected = MutableLiveData(false)
        val isConnected: LiveData<Boolean>
            get() = _isConnected

        private val _connectionState =
            object : MutableLiveData<ConnectionState>(NOT_CONNECTED) {
                override fun postValue(value: ConnectionState?) {
                    _isConnected.postValue(value == CONNECTED)
                    super.postValue(value)
                }

                override fun setValue(value: ConnectionState?) {
                    _isConnected.postValue(value == CONNECTED)
                    super.setValue(value)
                }
            }
        val connectionState: LiveData<ConnectionState>
            get() = _connectionState

        enum class ConnectionState {
            CONNECTING,
            NOT_CONNECTED,
            CONNECTED,
            CONNECTION_FAILED,
        }

        val sessionIdentifier = Random.nextInt(until = 10000).toString()

        /** Prepends the current server url to [relativePath], accounting for trailing/leading `/`s */
        fun toServerString(relativePath: String): String {
            val baseEndsWith = url.endsWith('/')
            val pathStartsWith = relativePath.startsWith('/')
            return if (baseEndsWith && pathStartsWith) {
                "$url/${relativePath.substring(1)}"
            } else if (!baseEndsWith && !pathStartsWith) {
                "$url/$relativePath"
            } else {
                "$url$relativePath"
            }
        }

        val plexMediaInterceptor = PlexInterceptor(plexPrefsRepo, this, isLoginService = false)
        val plexLoginInterceptor = PlexInterceptor(plexPrefsRepo, this, isLoginService = true)

        /** Attempt to load in a cached bitmap for the given thumbnail */
        suspend fun getBitmapFromServer(
            thumb: String?,
            requireCached: Boolean = false,
        ): Bitmap? {
            if (thumb.isNullOrEmpty()) {
                return null
            }

            // Retrieve cached album art from Glide if available
            val appContext = Injector.get().applicationContext()
            val imageSize = appContext.resources.getDimension(R.dimen.audiobook_image_width).toInt()
            val uri =
                if (thumb.startsWith("http")) {
                    thumb.toUri()
                } else {
                    Timber.i("Taking part uri")
                    toServerString(
                        "photo/:/transcode?width=$imageSize&height=$imageSize&url=$thumb",
                    ).toUri()
                }

            val uriString = uri.toString()

            // Check if this URL previously returned 404 in this session
            if (failedThumbnailUrls.contains(uriString)) {
                Timber.d("Skipping thumbnail request for $uriString (cached 404)")
                return null
            }

            Timber.i("Notification thumb uri is: $uri")
            val imagePipeline = Fresco.getImagePipeline()
            return withContext(Dispatchers.IO) {
                val request = ImageRequest.fromUri(uri)
                try {
                    val bm = imagePipeline.fetchDecodedImage(request, null).getImage()
                    Timber.i("Successfully retrieved album art for $thumb")
                    bm
                } catch (t: Throwable) {
                    Timber.e("Failed to retrieve album art for $thumb: $t")

                    // Check if this was a 404 error by making a lightweight HEAD request
                    val is404 = checkIf404(uriString)
                    if (is404) {
                        failedThumbnailUrls.add(uriString)
                        Timber.i("Cached thumbnail 404 for session: $uriString")
                    }

                    null
                }
            }
        }

        /**
         * Checks if a URL returns 404 Not Found using a lightweight HEAD request.
         * This prevents repeatedly requesting thumbnails that don't exist.
         *
         * @param url The full URL to check
         * @return true if the URL returns 404, false otherwise
         */
        private suspend fun checkIf404(url: String): Boolean {
            return withContext(Dispatchers.IO) {
                try {
                    val request =
                        okhttp3.Request.Builder()
                            .url(url)
                            .head() // HEAD request is lightweight (no body)
                            .build()

                    val response = thumbnailCheckClient.newCall(request).execute()
                    response.use {
                        val is404 = it.code == 404
                        if (is404) {
                            Timber.d("Confirmed 404 for thumbnail: $url")
                        }
                        is404
                    }
                } catch (e: Exception) {
                    Timber.w("Failed to check thumbnail status for $url: ${e.message}")
                    false // Don't cache on network errors, only genuine 404s
                }
            }
        }

        /**
         * Clears the failed thumbnail URL cache.
         * Should be called when a new playback session starts (new book playing).
         */
        fun clearThumbnailFailureCache() {
            val size = failedThumbnailUrls.size
            if (size > 0) {
                failedThumbnailUrls.clear()
                Timber.d("Cleared thumbnail failure cache ($size URLs)")
            }
        }

        fun makeDownloadRequest(
            trackSource: String,
            uniqueBookId: Int,
            bookTitle: String,
            downloadLoc: String,
        ): Request {
            Timber.i("Preparing download request for: ${Uri.parse(toServerString(trackSource))}")
            val token = plexPrefsRepo.server?.accessToken ?: plexPrefsRepo.accountAuthToken
            val remoteUri = "${toServerString(trackSource)}?download=1"
            return Request(remoteUri, downloadLoc).apply {
                tag = bookTitle
                groupId = uniqueBookId
                addHeader("X-Plex-Token", token)
            }
        }

        fun makeThumbUri(part: String): Uri {
            val appContext = Injector.get().applicationContext()
            val imageSize = appContext.resources.getDimension(R.dimen.audiobook_image_width).toInt()
            val plexThumbPart = "photo/:/transcode?width=$imageSize&height=$imageSize&url=$part"
            val uri = Uri.parse(toServerString(plexThumbPart))
            return uri.buildUpon()
                .appendQueryParameter(
                    "X-Plex-Token",
                    plexPrefsRepo.server?.accessToken ?: plexPrefsRepo.accountAuthToken,
                ).build()
        }

        /**
         * Creates a thumbnail URI for a given image path, library-aware.
         * Uses ServerConnectionResolver to get the correct server URL and auth token
         * for the library, preventing 404 errors for books in non-active libraries.
         *
         * @param part The relative thumbnail path (e.g., "/library/metadata/106/thumb/...")
         * @param libraryId The library ID to resolve the server connection for
         * @return A complete URI with server URL, transcode parameters, and auth token
         */
        suspend fun makeThumbUriForLibrary(
            part: String,
            libraryId: String,
        ): Uri {
            val appContext = Injector.get().applicationContext()
            val imageSize = appContext.resources.getDimension(R.dimen.audiobook_image_width).toInt()
            val plexThumbPart = "photo/:/transcode?width=$imageSize&height=$imageSize&url=$part"

            // Resolve the correct server connection for this library
            val serverConnectionResolver = Injector.get().serverConnectionResolver()
            val connection = serverConnectionResolver.resolve(libraryId)

            // Build the URL using the library's server
            val baseUrl = connection.serverUrl ?: url
            val fullUrl =
                if (baseUrl.endsWith('/') && plexThumbPart.startsWith('/')) {
                    "$baseUrl${plexThumbPart.substring(1)}"
                } else if (!baseUrl.endsWith('/') && !plexThumbPart.startsWith('/')) {
                    "$baseUrl/$plexThumbPart"
                } else {
                    "$baseUrl$plexThumbPart"
                }

            val uri = Uri.parse(fullUrl)
            return uri.buildUpon()
                .appendQueryParameter(
                    "X-Plex-Token",
                    connection.authToken ?: plexPrefsRepo.accountAuthToken,
                ).build()
        }

        fun setPotentialConnections(connections: List<Connection>) {
            Timber.d("URL_DEBUG: Setting ${connections.size} potential connections: ${connections.map { "${it.uri} (local=${it.local})" }}")
            connectionSet.clear()
            connectionSet.addAll(connections)
        }

        /**
         * Returns the current set of potential server connections.
         * Used by debug tools to display connection options.
         */
        fun getAvailableConnections(): Set<Connection> {
            return connectionSet.toSet() // Return a copy to prevent modification
        }

        /**
         * Indicates to observers that connectivity has been lost, but does not update URL yet, as
         * querying a possibly dead url has a better chance of success than querying no url
         */
        fun connectionHasBeenLost() {
            _connectionState.value = NOT_CONNECTED
        }

        private var prevConnectToServerJob: CompletableJob? = null

        /**
         * Connects to the server without retry.
         *
         * @deprecated Use connectToServerWithRetry() for better network resilience
         */
        @Deprecated(
            message = "Use connectToServerWithRetry() for better network resilience",
            replaceWith = ReplaceWith("connectToServerWithRetry(plexMediaService)"),
        )
        @InternalCoroutinesApi
        fun connectToServer(plexMediaService: PlexMediaService) {
            prevConnectToServerJob?.cancel("Killing previous connection attempt")
            _connectionState.postValue(CONNECTING)
            prevConnectToServerJob =
                Job().also {
                    val context = CoroutineScope(it + Dispatchers.Main)
                    context.launch {
                        val connectionResult = chooseViableConnections(plexMediaService)
                        Timber.i("Returned connection $connectionResult")
                        if (connectionResult is Success && connectionResult.url != PLACEHOLDER_URL) {
                            url = connectionResult.url
                            _connectionState.postValue(CONNECTED)
                            Timber.i("Connection success: $url")
                        } else {
                            _connectionState.postValue(CONNECTION_FAILED)
                        }
                    }
                }
        }

        /**
         * Connects to the server with retry logic and state management.
         * Manages connection state updates for observers.
         *
         * @param plexMediaService Service for checking server connectivity
         */
        @InternalCoroutinesApi
        fun connectToServerWithRetryAndState(plexMediaService: PlexMediaService) {
            prevConnectToServerJob?.cancel("Killing previous connection attempt")
            _connectionState.postValue(CONNECTING)
            prevConnectToServerJob =
                Job().also {
                    val context = CoroutineScope(it + Dispatchers.Main)
                    context.launch {
                        val success = connectToServerWithRetry(plexMediaService)
                        if (success) {
                            _connectionState.postValue(CONNECTED)
                            Timber.i("Connection success with retry: $url")
                        } else {
                            _connectionState.postValue(CONNECTION_FAILED)
                            Timber.e("Connection failed with retry")
                        }
                    }
                }
        }

        /** Clear server data from [plexPrefsRepo] and [url] managed by [PlexConfig] */
        fun clear() {
            plexPrefsRepo.clear()
            _connectionState.postValue(NOT_CONNECTED)
            url = PLACEHOLDER_URL
            connectionSet.clear()
            scopedPlexServiceFactoryProvider.get().clearCache()
        }

        fun clearServer() {
            _connectionState.postValue(NOT_CONNECTED)
            url = PLACEHOLDER_URL
            plexPrefsRepo.server = null
            plexPrefsRepo.library = null
        }

        fun clearLibrary() {
            plexPrefsRepo.library = null
        }

        fun clearUser() {
            plexPrefsRepo.library = null
            plexPrefsRepo.server = null
            plexPrefsRepo.user = null
        }

        /**
         * Temporarily update server configuration for multi-account sync.
         * This allows syncing libraries from different servers/accounts by updating
         * the global PlexConfig state temporarily during the sync operation.
         *
         * @param connections List of server connections to test
         * @param authToken The auth token to use for this server
         */
        suspend fun updateServerForSync(
            connections: List<Connection>,
            authToken: String,
        ): Boolean {
            Timber.d("updateServerForSync: Setting ${connections.size} connections")
            setPotentialConnections(connections)

            // Temporarily update the auth token in prefs for the interceptor to use
            val previousToken = plexPrefsRepo.accountAuthToken
            plexPrefsRepo.accountAuthToken = authToken

            return try {
                // Connect to the server with the new connections
                // This will update PlexConfig.url with the viable connection
                val plexMediaService = Injector.get().plexMediaService()
                connectToServerWithRetry(plexMediaService)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update server for sync")
                false
            } finally {
                // Note: We keep the updated token and URL for the duration of the sync
                // The caller is responsible for restoring previous state if needed
            }
        }

        sealed class ConnectionResult {
            data class Success(val url: String) : ConnectionResult()

            data class Failure(val reason: String, val originalException: Throwable? = null) : ConnectionResult()
        }

        /**
         * Connects to the Plex server with retry logic.
         * Attempts multiple connection methods in order, retrying on network failures.
         *
         * @param plexMediaService Service for checking server connectivity
         * @return true if connection was established, false if all attempts failed
         */
        @OptIn(InternalCoroutinesApi::class)
        suspend fun connectToServerWithRetry(plexMediaService: PlexMediaService): Boolean {
            Timber.d("Attempting to connect to server with retry")

            return when (
                val result =
                    withRetry(
                        config = connectionRetryConfig,
                        shouldRetry = { error -> isRetryableConnectionError(error) },
                        onRetry = { attempt, delay, error ->
                            Timber.w("Connection attempt $attempt failed, retrying in ${delay}ms: ${error.message}")
                        },
                    ) { attempt ->
                        Timber.d("Connection attempt $attempt")
                        connectToServerInternal(plexMediaService)
                    }
            ) {
                is RetryResult.Success -> {
                    Timber.d("Server connection successful after ${result.attemptNumber} attempt(s)")
                    true
                }
                is RetryResult.Failure -> {
                    Timber.e("Server connection failed after ${result.attemptsMade} attempts: ${result.error.message}")
                    false
                }
            }
        }

        /**
         * Determines if an error is retryable based on its type.
         * Network-related errors are retryable, while other errors are not.
         */
        private fun isRetryableConnectionError(error: Throwable): Boolean {
            return when (error) {
                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is IOException,
                -> true
                else -> false
            }
        }

        /**
         * Internal connection logic extracted from existing connectToServer.
         * Throws exception on failure for retry handler to catch.
         *
         * @return true on successful connection
         * @throws Exception on connection failure
         */
        @OptIn(InternalCoroutinesApi::class)
        private suspend fun connectToServerInternal(plexMediaService: PlexMediaService): Boolean {
            val startTime = System.currentTimeMillis()
            Timber.d("Starting connection attempt to server")

            try {
                val connectionResult = chooseViableConnections(plexMediaService)
                val elapsed = System.currentTimeMillis() - startTime

                Timber.i("Returned connection $connectionResult after ${elapsed}ms")

                if (connectionResult is Success && connectionResult.url != PLACEHOLDER_URL) {
                    url = connectionResult.url
                    Timber.d("URL_DEBUG: Connection established - PlexConfig.url set to: $url")
                    Timber.d("Connection established in ${elapsed}ms to: $url")
                    return true
                } else {
                    val failure = connectionResult as? Failure
                    val message = failure?.reason ?: "Unknown failure"
                    Timber.w("Connection failed after ${elapsed}ms: $message")

                    // Re-throw original exception if present (preserves non-retryable errors)
                    if (failure?.originalException != null) {
                        throw failure.originalException
                    }

                    // Otherwise throw IOException for retryable network errors
                    throw IOException("Connection failed: $message")
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                Timber.w("Connection failed after ${elapsed}ms: ${e.message}")
                throw e
            }
        }

        /**
         * Attempts to connect to all [Connection]s in [connectionSet] via [PlexMediaService.checkServer].
         *
         * Probes connections in **tiered priority order**:
         *   1. LAN ([Connection.local] = true)
         *   2. Direct WAN ([Connection.local] = false, [Connection.relay] = false)
         *   3. Plex Relay ([Connection.relay] = true) — last resort, ~2 Mbps bandwidth cap
         *
         * Within each tier the candidates race in parallel (lowest-RTT wins). A lower tier is
         * only opened if every connection in the previous tier failed. This mirrors the
         * official Plex client and avoids unnecessarily burning the user's relay quota / forcing
         * high-bitrate streams down a 2 Mbps tunnel when a direct WAN connection is reachable.
         *
         * If all tiers exhaust without a success, returns [Failure]. The whole probe is bounded
         * by [CONNECTION_TIMEOUT_MS]; if it elapses, returns a "Connection timed out" [Failure].
         */
        @InternalCoroutinesApi
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun chooseViableConnections(plexMediaService: PlexMediaService): ConnectionResult {
            val timeoutFailureReason = "Connection timed out"
            val connections = connectionSet.sortedWith(Connection.PRIORITY_COMPARATOR)
            Timber.d(
                "URL_DEBUG: Testing ${connections.size} connections (tiered): " +
                    connections.map { "${it.uri} (local=${it.local}, relay=${it.relay})" },
            )

            //  If there's only one connection, don't catch exceptions - let them propagate for proper retry handling
            if (connections.size == 1) {
                val conn = connections.first()
                Timber.d("URL_DEBUG: Testing single connection: ${conn.uri} (local=${conn.local}, relay=${conn.relay})")
                Timber.i("Testing single connection: ${conn.uri}")
                if (conn.relay) {
                    Timber.w(
                        "Falling back to Plex Relay for server ${plexPrefsRepo.server?.name ?: "?"} " +
                            "— bandwidth limited to ~2 Mbps (relay is the only known connection)",
                    )
                }
                return withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                    val result = plexMediaService.checkServer(conn.uri)
                    if (result.isSuccessful) {
                        Timber.d("URL_DEBUG: Single connection test SUCCESS: ${conn.uri}")
                        Success(conn.uri)
                    } else {
                        Timber.d("URL_DEBUG: Single connection test FAILED: ${conn.uri} - ${result.message()}")
                        Failure(result.message() ?: "Failed for unknown reason")
                    }
                } ?: run {
                    Timber.d("URL_DEBUG: Single connection test TIMEOUT: ${conn.uri}")
                    Failure(timeoutFailureReason)
                }
            }

            // Multiple connections - tier them by priority and race within each tier.
            val tiers = connections.groupBy { it.priority }.toSortedMap()
            return withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                val unknownFailureReason = "Failed for unknown reason"
                Timber.i("Choosing viable connection from: $connectionSet")

                var lastFailure: Failure? = null
                for ((tier, tierConnections) in tiers) {
                    val tierName =
                        when (tier) {
                            Connection.PRIORITY_LAN -> "LAN"
                            Connection.PRIORITY_DIRECT_WAN -> "direct WAN"
                            else -> "relay"
                        }
                    Timber.i("Probing $tierName tier with ${tierConnections.size} candidate(s)")

                    val tierResult = raceTier(tierConnections, plexMediaService, unknownFailureReason)
                    if (tierResult is Success) {
                        if (tier == Connection.PRIORITY_RELAY) {
                            Timber.w(
                                "Falling back to Plex Relay for server ${plexPrefsRepo.server?.name ?: "?"} " +
                                    "— bandwidth limited to ~2 Mbps; LAN and direct WAN both unreachable",
                            )
                        }
                        Timber.d("URL_DEBUG: SELECTED URL (tier=$tierName): ${tierResult.url}")
                        return@withTimeoutOrNull tierResult
                    }
                    lastFailure = tierResult as Failure
                    Timber.i("Tier $tierName exhausted: ${tierResult.reason}; trying next tier")
                }

                Timber.i("All tiers exhausted; last failure: ${lastFailure?.reason}")
                lastFailure ?: Failure(unknownFailureReason)
            } ?: Failure(timeoutFailureReason)
        }

        /**
         * Races a single tier of connections in parallel. Returns the first [Success], or a
         * [Failure] aggregating the tier's losers if every candidate fails.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun raceTier(
            tierConnections: List<Connection>,
            plexMediaService: PlexMediaService,
            unknownFailureReason: String,
        ): ConnectionResult =
            coroutineScope {
                val deferredConnections =
                    tierConnections.map { conn ->
                        async {
                            Timber.i("Testing connection: ${conn.uri}")
                            try {
                                val result = plexMediaService.checkServer(conn.uri)
                                if (result.isSuccessful) {
                                    Timber.d(
                                        "URL_DEBUG: Connection test SUCCESS: ${conn.uri} " +
                                            "(local=${conn.local}, relay=${conn.relay})",
                                    )
                                    Success(conn.uri)
                                } else {
                                    Timber.d(
                                        "URL_DEBUG: Connection test FAILED: ${conn.uri} " +
                                            "(local=${conn.local}, relay=${conn.relay}) - ${result.message()}",
                                    )
                                    Failure(result.message() ?: unknownFailureReason)
                                }
                            } catch (e: Throwable) {
                                Timber.d(
                                    "URL_DEBUG: Connection test EXCEPTION: ${conn.uri} " +
                                        "(local=${conn.local}, relay=${conn.relay}) - ${e.message}",
                                )
                                Failure(e.localizedMessage ?: unknownFailureReason, e)
                            }
                        }
                    }

                /**
                 * Returns the first completed [Success] in the tier, or `null` if none has
                 * completed yet. Walks the list in declared order so the lowest-RTT connection
                 * that wins the race is the one that finished first.
                 */
                fun firstCompletedSuccess(): Success? {
                    for (deferred in deferredConnections) {
                        if (deferred.isCompleted) {
                            val completed = deferred.getCompleted()
                            if (completed is Success) return completed
                        }
                    }
                    return null
                }

                // Check for an immediate winner (in case async ran synchronously before we polled).
                firstCompletedSuccess()?.let { winner ->
                    deferredConnections.forEach { it.cancel("Tier sibling won: $winner") }
                    return@coroutineScope winner
                }

                while (deferredConnections.any { it.isActive }) {
                    delay(500)
                    firstCompletedSuccess()?.let { winner ->
                        deferredConnections.forEach { it.cancel("Tier sibling won: $winner") }
                        return@coroutineScope winner
                    }
                }

                // Final sweep after every candidate has completed.
                firstCompletedSuccess()?.let { return@coroutineScope it }

                // All in tier failed. Return the most informative failure.
                val firstFailureWithException =
                    deferredConnections.firstOrNull {
                        it.isCompleted && (it.getCompleted() as? Failure)?.originalException != null
                    }
                if (firstFailureWithException != null) {
                    return@coroutineScope firstFailureWithException.getCompleted() as Failure
                }
                val anyFailure =
                    deferredConnections.firstOrNull { it.isCompleted }?.getCompleted() as? Failure
                anyFailure ?: Failure(unknownFailureReason)
            }
    }
