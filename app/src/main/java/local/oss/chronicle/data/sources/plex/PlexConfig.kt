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
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
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
    constructor(private val plexPrefsRepo: PlexPrefsRepo) {
        companion object {
            /** Timeout for individual connection attempt (reduced from 15s) */
            const val CONNECTION_TIMEOUT_MS = 10_000L // 10 seconds per attempt

            /** Maximum total connection time with retries */
            const val MAX_CONNECTION_TIME_MS = 30_000L // 30 seconds total

            const val PLACEHOLDER_URL = "http://placeholder.com"
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
                    null
                }
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
         * On the first successful connection, return a [ConnectionResult.Success] with
         *   [ConnectionResult.Success.url] from the [Connection.uri]
         *
         * If all connections fail: return a [Failure] as soon as all connections have completed
         *
         * If no connections are made within 10 seconds, return a [ConnectionResult.Failure].
         */
        @InternalCoroutinesApi
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun chooseViableConnections(plexMediaService: PlexMediaService): ConnectionResult {
            val timeoutFailureReason = "Connection timed out"
            val connections = connectionSet.sortedByDescending { it.local }
            Timber.d("URL_DEBUG: Testing ${connections.size} connections: ${connections.map { "${it.uri} (local=${it.local})" }}")

            //  If there's only one connection, don't catch exceptions - let them propagate for proper retry handling
            if (connections.size == 1) {
                val conn = connections.first()
                Timber.d("URL_DEBUG: Testing single connection: ${conn.uri} (local=${conn.local})")
                Timber.i("Testing single connection: ${conn.uri}")
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

            // Multiple connections - catch exceptions and try all
            return withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                val unknownFailureReason = "Failed for unknown reason"
                Timber.i("Choosing viable connection from: $connectionSet")
                val deferredConnections =
                    connections.map { conn ->
                        async {
                            Timber.i("Testing connection: ${conn.uri}")
                            try {
                                val result = plexMediaService.checkServer(conn.uri)
                                if (result.isSuccessful) {
                                    Timber.d("URL_DEBUG: Connection test SUCCESS: ${conn.uri} (local=${conn.local})")
                                    return@async Success(conn.uri)
                                } else {
                                    Timber.d("URL_DEBUG: Connection test FAILED: ${conn.uri} (local=${conn.local}) - ${result.message()}")
                                    return@async Failure(result.message() ?: unknownFailureReason)
                                }
                            } catch (e: Throwable) {
                                Timber.d("URL_DEBUG: Connection test EXCEPTION: ${conn.uri} (local=${conn.local}) - ${e.message}")
                                // Preserve original exception for proper retry handling
                                return@async Failure(e.localizedMessage ?: unknownFailureReason, e)
                            }
                        }
                    }

                while (deferredConnections.any { it.isActive }) {
                    Timber.i("Connections: $deferredConnections")
                    deferredConnections.forEach { deferred ->
                        if (deferred.isCompleted) {
                            val completed = deferred.getCompleted()
                            if (completed is Success) {
                                Timber.d("URL_DEBUG: SELECTED URL (first success): ${completed.url}")
                                Timber.i("Returning connection $completed")
                                deferredConnections.forEach { it.cancel("Sibling completed, killing connection attempt: $it") }
                                return@withTimeoutOrNull completed
                            }
                        }
                    }
                    delay(500)
                }

                // Check if the final completed job was a success
                Timber.i("Connections: $deferredConnections")
                deferredConnections.forEach { deferred ->
                    if (deferred.isCompleted && deferred.getCompleted() is Success) {
                        val success = deferred.getCompleted() as Success
                        Timber.d("URL_DEBUG: SELECTED URL (final check): ${success.url}")
                        Timber.i("Returning final completed connection ${deferred.getCompleted()}")
                        return@withTimeoutOrNull deferred.getCompleted()
                    } else {
                        if (deferred.isCompleted) {
                            Timber.i("Connection failed: ${(deferred.getCompleted() as Failure).reason}")
                        }
                    }
                }

                Timber.i("Returning connection ${Failure(unknownFailureReason)}")
                Failure(unknownFailureReason)
            } ?: Failure(timeoutFailureReason)
        }
    }
