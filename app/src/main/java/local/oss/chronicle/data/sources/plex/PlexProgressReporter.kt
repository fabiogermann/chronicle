package local.oss.chronicle.data.sources.plex

import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.data.sources.plex.model.getDuration
import local.oss.chronicle.data.sources.plex.model.toPlayQueueItemMap
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PAUSED
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_STOPPED
import local.oss.chronicle.features.player.ProgressUpdater.Companion.BOOK_FINISHED_END_OFFSET_MILLIS
import local.oss.chronicle.util.RetryConfig
import local.oss.chronicle.util.RetryResult
import local.oss.chronicle.util.withRetry
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports playback progress to Plex servers with library-aware routing.
 *
 * Creates request-scoped Retrofit instances to avoid global state mutation.
 * Each progress report uses the correct server URL and auth token for the library.
 *
 * This class eliminates the race condition in multi-library setups where concurrent
 * workers would mutate global PlexConfig.url and interfere with each other.
 *
 * @see ServerConnectionResolver
 * @see PlexSyncScrobbleWorker
 */
@Singleton
class PlexProgressReporter
    @Inject
    constructor(
        private val plexConfig: PlexConfig,
        private val plexPrefsRepo: PlexPrefsRepo,
        private val serverConnectionResolver: ServerConnectionResolver,
        private val libraryRepository: LibraryRepository,
        private val scopedPlexServiceFactory: ScopedPlexServiceFactory,
    ) {
        companion object {
            /**
             * Retry configuration for progress reporting.
             * Uses shorter delays than default since progress updates are frequent.
             */
            private val PROGRESS_RETRY_CONFIG =
                RetryConfig(
                    maxAttempts = 3,
                    initialDelayMs = 1000L, // 1 second
                    maxDelayMs = 5000L, // Max 5 seconds
                    multiplier = 2.0,
                )
        }

        /**
         * In-memory cache of play queue item IDs by track ID.
         *
         * This maps track IDs (e.g., "plex:12345") to their playQueueItemID values
         * returned from POST /playQueues. These IDs are required for timeline updates
         * to appear in the Plex dashboard.
         *
         * Cleared when playback stops or a new play queue is created.
         *
         * Thread-safe: ConcurrentHashMap handles concurrent reads/writes from
         * worker threads and main thread.
         */
        private val playQueueItemCache = ConcurrentHashMap<String, Long>()

        /**
         * Clears the play queue item cache.
         * Should be called when playback stops or a new audiobook is loaded.
         */
        fun clearPlayQueueCache() {
            playQueueItemCache.clear()
            Timber.d("[PlayQueue] Cleared play queue item cache")
        }

        /**
         * Reports progress to Plex using library-specific connection.
         *
         * @param connection Library-specific server URL and auth token
         * @param track The track being played
         * @param book The audiobook containing the track
         * @param tracks All tracks in the audiobook
         * @param trackProgress Current position in track (ms)
         * @param bookProgress Current position in book (ms)
         * @param playbackState Plex state: "playing", "paused", or "stopped"
         */
        suspend fun reportProgress(
            connection: ServerConnection,
            track: MediaItemTrack,
            book: Audiobook,
            tracks: List<MediaItemTrack>,
            trackProgress: Long,
            bookProgress: Long,
            playbackState: String,
        ) {
            // Create request-scoped PlexService with library-specific base URL
            val service = scopedPlexServiceFactory.getOrCreateService(connection)

            // Extract numeric IDs for API calls (strip "plex:" prefix)
            val numericTrackId =
                track.id.removePrefix("plex:").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid track ID: ${track.id}")

            val numericBookId =
                book.id.removePrefix("plex:").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid book ID: ${book.id}")

            // Resolve play queue item ID from cache, fall back to -1 if not found
            val playQueueItemId = playQueueItemCache[track.id] ?: -1L

            if (playQueueItemId == -1L) {
                Timber.w(
                    "No playQueueItemID cached for track ${track.id}. " +
                        "Dashboard activity may not work. Cache contents: $playQueueItemCache",
                )
            }

            // Report timeline progress with retry
            when (
                val result =
                    withRetry(
                        config = PROGRESS_RETRY_CONFIG,
                        shouldRetry = { error -> isRetryableError(error) },
                        onRetry = { attempt, delay, error ->
                            Timber.w(
                                "Progress report attempt $attempt failed, retrying in ${delay}ms: ${error.message}",
                            )
                        },
                    ) { _ ->
                        service.progress(
                            ratingKey = numericTrackId.toString(),
                            offset = trackProgress.toString(),
                            playbackTime = trackProgress,
                            playQueueItemId = playQueueItemId,
                            key = "${MediaItemTrack.PARENT_KEY_PREFIX}$numericTrackId",
                            duration = track.duration,
                            playState = playbackState,
                            hasMde = 1,
                        )
                    }
            ) {
                is RetryResult.Success -> {
                    Timber.i(
                        "Synced progress for ${book.title}: $playbackState at ${trackProgress}ms " +
                            "(playQueueItemId=$playQueueItemId)",
                    )
                }
                is RetryResult.Failure -> {
                    // Best-effort: progress will re-sync on the next tick. Do NOT rethrow — a
                    // rethrown network error (SSL/EOF/ConnectException) here would only be caught
                    // by the caller's unhandledExceptionHandler and risks crashing any caller that
                    // launches without one. Consistent with watched()/startMediaSession() below.
                    Timber.e("Failed to sync progress after ${result.attemptsMade} attempts: ${result.error.message}")
                    return
                }
            }

            // Mark track as watched if finished (within 1 second of end)
            val isTrackFinished = trackProgress > track.duration - 1000
            if (isTrackFinished) {
                when (
                    val result =
                        withRetry(
                            config = PROGRESS_RETRY_CONFIG,
                            shouldRetry = { error -> isRetryableError(error) },
                        ) { _ ->
                            service.watched(numericTrackId.toString())
                        }
                ) {
                    is RetryResult.Success -> {
                        Timber.i("Marked track watched: ${track.title}")
                    }
                    is RetryResult.Failure -> {
                        Timber.e("Failed to mark track watched: ${result.error.message}")
                        // Don't throw - this is not critical
                    }
                }
            }

            // Mark book as watched if finished
            val isBookFinished = isBookCompleted(bookProgress, tracks.getDuration(), playbackState)
            if (isBookFinished) {
                when (
                    val result =
                        withRetry(
                            config = PROGRESS_RETRY_CONFIG,
                            shouldRetry = { error -> isRetryableError(error) },
                        ) { _ ->
                            service.watched(numericBookId.toString())
                        }
                ) {
                    is RetryResult.Success -> {
                        Timber.i("Marked book watched: ${book.title}")
                    }
                    is RetryResult.Failure -> {
                        Timber.e("Failed to mark book watched: ${result.error.message}")
                        // Don't throw - this is not critical
                    }
                }
            }
        }

        /**
         * Starts a media session on the Plex server and captures play queue item IDs.
         *
         * CRITICAL: The playQueueItemID values from the response are stored in
         * playQueueItemCache and MUST be sent in subsequent timeline updates for
         * Plex dashboard activity reporting to work.
         *
         * This call is non-critical - failure will be logged but will NOT prevent playback.
         *
         * @param bookId The audiobook's rating key (with "plex:" prefix)
         * @param libraryId The library ID this audiobook belongs to
         */
        suspend fun startMediaSession(
            bookId: String,
            libraryId: String,
        ) {
            try {
                Timber.d("[PlayQueue] startMediaSession() called - bookId=$bookId, libraryId=$libraryId")

                // Clear previous play queue cache when starting new session
                clearPlayQueueCache()
                Timber.d("[PlayQueue] Cleared previous play queue cache")

                // Extract numeric book ID
                val numericBookId =
                    bookId.removePrefix("plex:").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid book ID: $bookId")
                Timber.d("[PlayQueue] Extracted numeric book ID: $numericBookId")

                // Resolve library-specific server connection
                val connection =
                    try {
                        serverConnectionResolver.resolve(libraryId)
                    } catch (e: Exception) {
                        Timber.e(e, "[PlayQueue] Failed to resolve server for library: $libraryId")
                        return // Non-critical, don't prevent playback
                    }
                Timber.d("[PlayQueue] Resolved server connection - serverUrl=${connection.serverUrl}")

                // Get library to retrieve serverId (machine identifier)
                val library =
                    try {
                        libraryRepository.getLibraryById(libraryId)
                    } catch (e: Exception) {
                        Timber.e(e, "[PlayQueue] Failed to get library for id: $libraryId")
                        return // Non-critical, don't prevent playback
                    }

                if (library == null) {
                    Timber.w("[PlayQueue] Library not found for id: $libraryId. Cannot start media session.")
                    return // Non-critical, don't prevent playback
                }

                Timber.d(
                    "[PlayQueue] Retrieved library from DB - name='${library.name}', " +
                        "serverId='${library.serverId}', serverName='${library.serverName}', " +
                        "serverUrl=${library.serverUrl}, authToken=${if (library.authToken.isNullOrEmpty()) "EMPTY" else "SET"}",
                )

                val serverId = library.serverId
                Timber.d("[PlayQueue] Checking serverId value: '$serverId' (isEmpty=${serverId.isEmpty()})")

                if (serverId.isEmpty()) {
                    Timber.w("[PlayQueue] Server ID not set for library: $libraryId. Cannot start media session.")
                    return // Non-critical, don't prevent playback
                }

                // Create library-scoped service
                val service = scopedPlexServiceFactory.getOrCreateService(connection)
                Timber.d("[PlayQueue] Created library-scoped PlexService")

                // Build the media item URI
                val uri = getMediaItemUri(serverId, numericBookId.toString())
                Timber.d("[PlayQueue] Built media item URI for POST /playQueues: $uri")

                // Start media session and capture play queue response
                Timber.d("[PlayQueue] Calling POST /playQueues with URI: $uri")
                val response = service.startMediaSession(uri)
                Timber.d(
                    "[PlayQueue] Received API response - playQueueID=${response.mediaContainer?.playQueueID}, metadata count=${response.mediaContainer?.metadata?.size ?: 0}",
                )

                // Extract and cache play queue item IDs
                val playQueueMap = response.toPlayQueueItemMap()
                Timber.d("[PlayQueue] Extracted play queue item map - size=${playQueueMap.size}, contents=$playQueueMap")

                playQueueItemCache.putAll(playQueueMap)
                Timber.d("[PlayQueue] Updated playQueueItemCache - total cached items: ${playQueueItemCache.size}")

                Timber.i(
                    "[PlayQueue] Started media session for book $bookId on server $serverId (library: $libraryId). " +
                        "Cached ${playQueueMap.size} play queue item IDs",
                )
            } catch (e: Exception) {
                // This is non-critical - log but never throw
                Timber.e(e, "[PlayQueue] Failed to start media session for book $bookId: ${e.message}")
            }
        }

        /**
         * Determines if book is completed based on progress.
         * Called when playback pauses/stops to avoid auto-marking during playback.
         *
         * @param bookProgress Current position in book (ms)
         * @param bookDuration Total duration of book (ms)
         * @param playbackState Current playback state
         * @return true if book should be marked as watched
         */
        private fun isBookCompleted(
            bookProgress: Long,
            bookDuration: Long,
            playbackState: String,
        ): Boolean {
            // Only consider book finished when user explicitly pauses/stops near the end
            val isNearEnd = bookDuration - bookProgress < BOOK_FINISHED_END_OFFSET_MILLIS
            val hasUserStopped = playbackState == PLEX_STATE_PAUSED || playbackState == PLEX_STATE_STOPPED
            return isNearEnd && hasUserStopped
        }

        /**
         * Determines if an error is transient and should be retried.
         *
         * Retryable errors:
         * - Network timeouts and connection failures
         * - HTTP 5xx server errors
         *
         * Non-retryable errors:
         * - HTTP 401/403 authentication/authorization failures
         * - HTTP 404 not found
         * - Other client errors (4xx)
         *
         * @param error The error to check
         * @return true if the error is retryable
         */
        private fun isRetryableError(error: Throwable): Boolean {
            return when (error) {
                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is IOException,
                -> true
                is HttpException -> {
                    // Only retry server errors (5xx), not client errors (4xx)
                    // Specifically do NOT retry 401/403 auth errors
                    error.code() in 500..599
                }
                else -> false
            }
        }
    }
