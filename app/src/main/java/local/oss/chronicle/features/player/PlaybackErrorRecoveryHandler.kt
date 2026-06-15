package local.oss.chronicle.features.player

import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import local.oss.chronicle.data.sources.plex.ConnectionRefreshCoordinator
import local.oss.chronicle.data.sources.plex.PlaybackUrlResolver
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Coordinates recovery from "network-y" [PlaybackException]s by flushing stale connection caches
 * and re-preparing the player with a freshly-built [MediaSource] for the current track.
 *
 * **Why this exists (fix #3).**
 * Fix #1 lets [local.oss.chronicle.data.sources.plex.ServerConnectionResolver] re-probe a library's
 * `Connection` list on demand, and fix #2 wires [ConnectionRefreshCoordinator] to fire that probe
 * on every network change. Neither one helps the user who is **already playing** when the network
 * drops: ExoPlayer surfaces the I/O error via [androidx.media3.common.Player.Listener.onPlayerError]
 * and the queue stalls. This handler hooks into that callback, distinguishes recoverable network
 * failures from terminal codec/DRM errors, runs the refresh seam, and rebuilds the current track
 * with the now-fresh URL.
 *
 * **Recovery flow** (only entered when [isRecoverable] returns `true`):
 *  1. Broadcast [MediaPlayerService.ACTION_PLAYBACK_ERROR] with [MediaPlayerService.EXTRA_IS_RECOVERING]
 *     set to `true` so the UI can show a transient "Reconnecting…" state.
 *  2. Capture `currentMediaItem`, `currentPosition`, `playWhenReady`.
 *  3. `refreshCoordinator.refresh()` — invalidates every library's `ServerConnection`, clears the
 *     [PlaybackUrlResolver] cache, re-establishes `PlexConfig.url`, re-probes every library.
 *  4. Defensive: also clear the per-track [local.oss.chronicle.data.model.MediaItemTrack.streamingUrlCache]
 *     via [PlaybackUrlResolver.clearCache] (idempotent — coordinator already did this).
 *  5. Rebuild the [MediaSource] for the captured `MediaItem` via [MediaSourceRebuilder] (the
 *     service injects this because rebuilding requires Plex repos + the `PlexHttpDataSourceFactory`).
 *  6. `player.setMediaSource(newSource, resetPosition=false)`, seek to captured position, restore
 *     `playWhenReady`, and `prepare()`.
 *  7. Broadcast [MediaPlayerService.ACTION_PLAYBACK_RECOVERED].
 *
 * **Retry limiting.** [networkRecoveryAttempts] increments before each recovery and is checked
 * against [MAX_RECOVERY_ATTEMPTS]. Beyond that, the handler surfaces the error normally without
 * attempting recovery. The counter is reset by [onPlayerReady] (called from
 * `onPlaybackStateChanged(STATE_READY)`).
 *
 * **Threading.** All player API calls happen on the application's main looper. The handler is
 * given a service-lifecycle [CoroutineScope] (Main + SupervisorJob) so coroutines launched here
 * stay on Main by default. The suspending `refreshCoordinator.refresh()` performs its own
 * dispatcher switching.
 */
class PlaybackErrorRecoveryHandler(
    private val connectionRefreshCoordinator: ConnectionRefreshCoordinator,
    private val playbackUrlResolver: PlaybackUrlResolver,
    private val localBroadcastManager: LocalBroadcastManager,
    private val scope: CoroutineScope,
    private val playerHandle: PlayerHandle,
    private val mediaSourceRebuilder: MediaSourceRebuilder,
) {
    companion object {
        /**
         * Maximum number of consecutive recovery attempts before the handler gives up and surfaces
         * the error normally. Two attempts means "three errors in total within one playback session"
         * — enough to ride out a brief Wi-Fi → cellular handover without forming an infinite retry
         * loop when the server is genuinely unreachable.
         */
        const val MAX_RECOVERY_ATTEMPTS: Int = 2
    }

    private var networkRecoveryAttempts: Int = 0

    /**
     * Abstraction over the parts of [androidx.media3.common.Player] this handler needs. Extracting
     * an interface lets the unit tests run without a real `ExoPlayer` (which requires a Looper).
     */
    interface PlayerHandle {
        fun getCurrentMediaItem(): MediaItem?

        fun getCurrentPosition(): Long

        fun getPlayWhenReady(): Boolean

        fun setMediaSource(
            mediaSource: MediaSource,
            resetPosition: Boolean,
        )

        fun seekTo(positionMs: Long)

        fun setPlayWhenReady(playWhenReady: Boolean)

        fun prepare()
    }

    /**
     * Builds a fresh [MediaSource] for [currentMediaItem] using the now-refreshed connection.
     * The service implements this because it has access to the `PlexHttpDataSourceFactory`, the
     * track repository, and the metadata that goes into a `ProgressiveMediaSource`.
     *
     * Returning `null` aborts the recovery attempt (treated as a failed recovery).
     */
    fun interface MediaSourceRebuilder {
        suspend fun rebuild(currentMediaItem: MediaItem?): MediaSource?
    }

    /**
     * Entry point called from `Player.Listener.onPlayerError`. Always broadcasts the error first
     * so the UI can react immediately; if [isRecoverable] returns `true` and the retry budget
     * hasn't been exhausted, launches an asynchronous recovery coroutine.
     */
    @OptIn(InternalCoroutinesApi::class)
    fun handleError(error: PlaybackException) {
        val recoverable = isRecoverable(error)
        val canRetry = recoverable && networkRecoveryAttempts < MAX_RECOVERY_ATTEMPTS
        broadcastError(error, isRecovering = canRetry)

        if (!canRetry) {
            if (recoverable) {
                Timber.w(
                    "PlaybackErrorRecoveryHandler: recoverable error but retry budget exhausted " +
                        "($networkRecoveryAttempts/$MAX_RECOVERY_ATTEMPTS); surfacing error",
                )
            } else {
                Timber.d("PlaybackErrorRecoveryHandler: non-recoverable error ${error.errorCodeName}; surfacing")
            }
            return
        }

        networkRecoveryAttempts++
        Timber.i(
            "PlaybackErrorRecoveryHandler: attempting recovery $networkRecoveryAttempts/$MAX_RECOVERY_ATTEMPTS " +
                "for code=${error.errorCodeName}",
        )

        // Capture player state synchronously on the calling (Main) thread before any suspension —
        // by the time refresh() returns the underlying ExoPlayer may have advanced past STATE_IDLE
        // or had its currentMediaItem cleared.
        val capturedItem = playerHandle.getCurrentMediaItem()
        val capturedPosition = playerHandle.getCurrentPosition()
        val capturedPlayWhenReady = playerHandle.getPlayWhenReady()

        scope.launch {
            val recovered = runRecovery(capturedItem, capturedPosition, capturedPlayWhenReady)
            if (recovered) {
                broadcastRecovered()
            } else {
                // Recovery failed (refresh threw, rebuild returned null, or prepare() threw).
                // Re-broadcast the original error WITHOUT the "is recovering" flag so the UI
                // settles on the terminal-error state.
                broadcastError(error, isRecovering = false)
            }
        }
    }

    /**
     * Runs the full recovery sequence: refresh → defensive cache clear → rebuild MediaSource →
     * restore player state → prepare. Returns `true` on success, `false` if any step failed.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun runRecovery(
        capturedItem: MediaItem?,
        capturedPosition: Long,
        capturedPlayWhenReady: Boolean,
    ): Boolean {
        try {
            connectionRefreshCoordinator.refresh()
        } catch (e: Exception) {
            Timber.e(e, "PlaybackErrorRecoveryHandler: refresh() failed during recovery")
            return false
        }

        // Defensive: ConnectionRefreshCoordinator.refresh() already clears the PlaybackUrlResolver
        // cache, but double-calling is harmless and protects us if the coordinator's contract
        // changes. Also indirectly clears MediaItemTrack.streamingUrlCache (PlaybackUrlResolver
        // owns that map).
        try {
            playbackUrlResolver.clearCache()
        } catch (e: Exception) {
            Timber.w(e, "PlaybackErrorRecoveryHandler: defensive clearCache() failed (non-fatal)")
        }

        val newSource =
            try {
                mediaSourceRebuilder.rebuild(capturedItem)
            } catch (e: Exception) {
                Timber.e(e, "PlaybackErrorRecoveryHandler: MediaSource rebuild failed")
                null
            }
        if (newSource == null) {
            Timber.w("PlaybackErrorRecoveryHandler: rebuilder returned null; aborting recovery")
            return false
        }

        return try {
            playerHandle.setMediaSource(newSource, false)
            playerHandle.seekTo(capturedPosition)
            playerHandle.setPlayWhenReady(capturedPlayWhenReady)
            playerHandle.prepare()
            true
        } catch (e: Exception) {
            Timber.e(e, "PlaybackErrorRecoveryHandler: failed to re-prepare player after refresh")
            false
        }
    }

    /**
     * Resets the retry counter. Call from `onPlaybackStateChanged(STATE_READY)` so the budget
     * refreshes once a track actually starts playing.
     */
    fun onPlayerReady() {
        if (networkRecoveryAttempts > 0) {
            Timber.d("PlaybackErrorRecoveryHandler: STATE_READY reached, resetting retry counter")
            networkRecoveryAttempts = 0
        }
    }

    /**
     * Classifies [error] as a recoverable network failure. Conservative by design — anything we
     * aren't sure is network-related is treated as terminal so we don't trash the connection cache
     * over a codec/DRM/disk error.
     */
    /**
     * Classifies [error] as a recoverable network failure. Conservative by design — anything we
     * aren't sure is network-related is treated as terminal so we don't trash the connection cache
     * over a codec/DRM/disk error.
     *
     * Recoverable error codes:
     *  - [PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED]
     *  - [PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT]
     *  - [PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS] (e.g. 502/503 from a dying/relocated server)
     *  - [PlaybackException.ERROR_CODE_IO_UNSPECIFIED] **iff** the cause chain contains an
     *    [IOException] / [SocketTimeoutException] / [UnknownHostException] / [ConnectException].
     *    ExoPlayer surfaces wrapped `HttpDataSource$HttpDataSourceException` (and DNS failures via
     *    `UnknownHostException`) as `IO_UNSPECIFIED` so we must inspect the cause to avoid false
     *    negatives. Media3 1.5.0 does not expose a dedicated DNS-failed code.
     *
     * Everything else — decoder init, format errors, source-cached errors, DRM — is terminal.
     */
    fun isRecoverable(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            -> true
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> hasNetworkCause(error.cause)
            else -> false
        }
    }

    /**
     * Walks the cause chain of [throwable] looking for a known network exception. Limits the walk
     * to 16 levels deep to defend against pathological self-referencing chains (some HTTP
     * libraries do this).
     */
    private fun hasNetworkCause(throwable: Throwable?): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 16) {
            when (current) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                -> return true
                is IOException -> {
                    // Most network errors at this level are I/O; treat as recoverable. This also
                    // catches androidx.media3.datasource.HttpDataSource$HttpDataSourceException,
                    // which extends IOException, without us needing a direct media3 dependency in
                    // the classifier.
                    return true
                }
            }
            current = current.cause
            depth++
        }
        return false
    }

    private fun broadcastError(
        error: PlaybackException,
        isRecovering: Boolean,
    ) {
        val intent =
            android.content.Intent(MediaPlayerService.ACTION_PLAYBACK_ERROR).apply {
                putExtra(MediaPlayerService.PLAYBACK_ERROR_MESSAGE, error.message)
                putExtra(MediaPlayerService.EXTRA_IS_RECOVERING, isRecovering)
            }
        localBroadcastManager.sendBroadcast(intent)
    }

    private fun broadcastRecovered() {
        val intent = android.content.Intent(MediaPlayerService.ACTION_PLAYBACK_RECOVERED)
        localBroadcastManager.sendBroadcast(intent)
    }
}
