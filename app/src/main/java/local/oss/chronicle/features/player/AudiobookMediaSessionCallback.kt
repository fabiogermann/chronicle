package local.oss.chronicle.features.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import android.view.KeyEvent.*
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.github.michaelbull.result.get
import kotlinx.coroutines.*
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.application.Injector
import local.oss.chronicle.application.MILLIS_PER_SECOND
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.*
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.*
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.model.getDuration
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.features.player.MediaPlayerService.Companion.ACTIVE_TRACK
import local.oss.chronicle.features.player.MediaPlayerService.Companion.KEY_SEEK_TO_TRACK_WITH_ID
import local.oss.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import local.oss.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import local.oss.chronicle.injection.scopes.ServiceScope
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ServiceScope
class AudiobookMediaSessionCallback
    @Inject
    constructor(
        private val plexPrefsRepo: PlexPrefsRepo,
        private val prefsRepo: PrefsRepo,
        private val plexConfig: PlexConfig,
        private val plexLoginRepo: IPlexLoginRepo,
        private val mediaController: MediaControllerCompat,
        private val dataSourceFactory: local.oss.chronicle.data.sources.plex.PlexHttpDataSourceFactory,
        private val trackRepository: ITrackRepository,
        private val bookRepository: IBookRepository,
        private val serviceScope: CoroutineScope,
        private val trackListStateManager: TrackListStateManager,
        private val foregroundServiceController: ForegroundServiceController,
        private val serviceController: ServiceController,
        private val errorReporter: IPlaybackErrorReporter,
        private val mediaSession: MediaSessionCompat,
        private val appContext: Context,
        private val currentlyPlaying: CurrentlyPlaying,
        private val progressUpdater: ProgressUpdater,
        private val playbackUrlResolver: local.oss.chronicle.data.sources.plex.PlaybackUrlResolver,
        private val playbackStateController: PlaybackStateController,
        private val coroutineExceptionHandler: CoroutineExceptionHandler,
        private val voiceCommandBridgeAudio: VoiceCommandBridgeAudio,
        private val plexProgressReporter: local.oss.chronicle.data.sources.plex.PlexProgressReporter,
        defaultPlayer: ExoPlayer,
    ) : MediaSessionCompat.Callback() {
        // Default to ExoPlayer to prevent having a nullable field
        var currentPlayer: Player = defaultPlayer

        /**
         * Timestamp (in milliseconds) when a voice command was received via onPlayFromSearch().
         * Used to measure voice command latency. Null when no voice command is pending.
         */
        var voiceCommandStartTime: Long? = null

        companion object {
            const val ACTION_SEEK = "seek"
            const val OFFSET_MS = "offset"

            // Timeout for resuming from empty state
            private const val RESUME_TIMEOUT_MILLIS = 10_000L // 10 seconds
        }

        /**
         * Detects whether a command originates from an Android Auto voice command.
         *
         * This is critical for determining when to trigger bridge audio. We ONLY want to
         * speak TTS for actual voice commands from Android Auto, not for:
         * - Regular UI playback (taps in the app)
         * - Resume from notification
         * - Bluetooth media button presses
         * - Android Auto UI taps (browsing and selecting from lists)
         *
         * The primary indicator is [voiceCommandStartTime] being non-null, which is set
         * in [onPlayFromSearch] - the method that ONLY gets called for voice commands.
         *
         * @return true if this is an Android Auto voice command requiring bridge audio
         */
        private fun isAndroidAutoVoiceCommand(): Boolean {
            // voiceCommandStartTime is ONLY set in onPlayFromSearch(), which is the
            // MediaSession callback specifically for voice search commands.
            // If it's null, this is NOT a voice command.
            return voiceCommandStartTime != null
        }

        /**
         * Checks if the user is authenticated and in the LOGGED_IN_FULLY state.
         * If not, sets an appropriate error via MediaPlayerService and returns false.
         *
         * @return true if authentication is valid, false otherwise
         */
        private fun checkAuthenticationOrError(): Boolean {
            val loginState = plexLoginRepo.loginEvent.value?.peekContent()

            return when (loginState) {
                NOT_LOGGED_IN -> {
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                        appContext.getString(local.oss.chronicle.R.string.auto_access_error_not_logged_in),
                    )
                    false
                }
                LOGGED_IN_NO_SERVER_CHOSEN -> {
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                        appContext.getString(local.oss.chronicle.R.string.auto_access_error_no_server_chosen),
                    )
                    false
                }
                LOGGED_IN_NO_USER_CHOSEN -> {
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                        appContext.getString(local.oss.chronicle.R.string.auto_access_error_no_user_chosen),
                    )
                    false
                }
                LOGGED_IN_NO_LIBRARY_CHOSEN -> {
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                        appContext.getString(local.oss.chronicle.R.string.auto_access_error_no_library_chosen),
                    )
                    false
                }
                LOGGED_IN_FULLY -> true
                else -> {
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        "Please open Chronicle app on the phone to complete setup",
                    )
                    false
                }
            }
        }

        /**
         * Checks authentication for voice commands and provides TTS feedback on errors.
         * This variant is used in onPlayFromSearch to provide immediate audio feedback
         * when authentication fails during a voice command.
         *
         * **CRITICAL FIX**: When bridge audio is enabled, TTS is spoken FIRST, and the error
         * state is set AFTER TTS completes (or after 3 second timeout). This prevents Android Auto
         * from canceling the audio session before the user hears the error message.
         *
         * @return true if authentication is valid, false otherwise
         */
        private fun checkAuthenticationOrErrorWithVoiceFeedback(): Boolean {
            val loginState = plexLoginRepo.loginEvent.value?.peekContent()
            val shouldPlayBridgeAudio = prefsRepo.bridgeAudioEnabled

            return when (loginState) {
                NOT_LOGGED_IN -> {
                    val errorMessage = appContext.getString(local.oss.chronicle.R.string.error_not_logged_in_voice)
                    if (shouldPlayBridgeAudio) {
                        // Speak FIRST, then set error state AFTER TTS completes
                        voiceCommandBridgeAudio.speakErrorMessage(errorMessage) {
                            onSetPlaybackError(
                                android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                                appContext.getString(local.oss.chronicle.R.string.auto_access_error_not_logged_in),
                            )
                        }
                    } else {
                        // No bridge audio - set error immediately
                        onSetPlaybackError(
                            android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                            appContext.getString(local.oss.chronicle.R.string.auto_access_error_not_logged_in),
                        )
                    }
                    false
                }
                LOGGED_IN_NO_SERVER_CHOSEN -> {
                    val errorMessage = appContext.getString(local.oss.chronicle.R.string.auto_access_error_no_server_chosen)
                    if (shouldPlayBridgeAudio) {
                        // Speak FIRST, then set error state AFTER TTS completes
                        voiceCommandBridgeAudio.speakErrorMessage(errorMessage) {
                            onSetPlaybackError(
                                android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                                errorMessage,
                            )
                        }
                    } else {
                        // No bridge audio - set error immediately
                        onSetPlaybackError(
                            android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                            errorMessage,
                        )
                    }
                    false
                }
                LOGGED_IN_NO_USER_CHOSEN -> {
                    val errorMessage = appContext.getString(local.oss.chronicle.R.string.auto_access_error_no_user_chosen)
                    if (shouldPlayBridgeAudio) {
                        // Speak FIRST, then set error state AFTER TTS completes
                        voiceCommandBridgeAudio.speakErrorMessage(errorMessage) {
                            onSetPlaybackError(
                                android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                                errorMessage,
                            )
                        }
                    } else {
                        // No bridge audio - set error immediately
                        onSetPlaybackError(
                            android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                            errorMessage,
                        )
                    }
                    false
                }
                LOGGED_IN_NO_LIBRARY_CHOSEN -> {
                    val errorMessage = appContext.getString(local.oss.chronicle.R.string.auto_access_error_no_library_chosen)
                    if (shouldPlayBridgeAudio) {
                        // Speak FIRST, then set error state AFTER TTS completes
                        voiceCommandBridgeAudio.speakErrorMessage(errorMessage) {
                            onSetPlaybackError(
                                android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                                errorMessage,
                            )
                        }
                    } else {
                        // No bridge audio - set error immediately
                        onSetPlaybackError(
                            android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                            errorMessage,
                        )
                    }
                    false
                }
                LOGGED_IN_FULLY -> true
                else -> {
                    val errorMessage = "Please open Chronicle app on the phone to complete setup"
                    if (shouldPlayBridgeAudio) {
                        // Speak FIRST, then set error state AFTER TTS completes
                        voiceCommandBridgeAudio.speakErrorMessage(errorMessage) {
                            onSetPlaybackError(
                                android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                                errorMessage,
                            )
                        }
                    } else {
                        // No bridge audio - set error immediately
                        onSetPlaybackError(
                            android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                            errorMessage,
                        )
                    }
                    false
                }
            }
        }

        /**
         * Sets a playback error via the IPlaybackErrorReporter interface.
         * This method bridges the callback to the service's error handling.
         */
        private fun onSetPlaybackError(
            errorCode: Int,
            message: String,
        ) {
            errorReporter.setPlaybackStateError(errorCode, message)
        }

        override fun onPrepareFromSearch(
            query: String?,
            extras: Bundle?,
        ) {
            Timber.i("[AndroidAuto] Prepare from search: $query")
            serviceScope.launch(coroutineExceptionHandler) {
                try {
                    handleSearchSuspend(query, false)
                } catch (e: Exception) {
                    Timber.e(e, "[AndroidAuto] Error in onPrepareFromSearch")
                }
            }
        }

        override fun onPlayFromSearch(
            query: String?,
            extras: Bundle?,
        ) {
            Timber.d("[VoiceCommandTrace] onPlayFromSearch ENTER - query: '$query', extras: $extras")

            // Record timestamp for voice command latency measurement
            voiceCommandStartTime = System.currentTimeMillis()
            Timber.i("[AndroidAuto] Play from search: $query (voice command received at $voiceCommandStartTime)")

            // Pre-flight authentication check BEFORE speaking bridge audio
            // This prevents speaking "Getting ready..." when user isn't logged in
            if (!checkAuthenticationOrErrorWithVoiceFeedback()) {
                Timber.d("[VoiceCommandTrace] onPlayFromSearch early return - reason: authentication check failed")
                voiceCommandStartTime = null // Clear on error
                return
            }

            // Check bridge audio eligibility
            Timber.d("[VoiceCommandTrace] Checking bridge audio eligibility - voiceCommandStartTime: $voiceCommandStartTime")

            // IMMEDIATELY speak bridge message for Android Auto voice commands
            // This provides instant audio feedback while the audiobook loads
            val shouldPlayBridgeAudio = prefsRepo.bridgeAudioEnabled
            Timber.d("[VoiceCommandTrace] Bridge audio check result - will play: $shouldPlayBridgeAudio")

            if (shouldPlayBridgeAudio) {
                // Pass a completion callback to resume playback after TTS completes
                voiceCommandBridgeAudio.speakBridgeMessage {
                    // This callback runs after TTS completes (or after 3 second timeout)
                    // CRITICAL: TTS audio focus causes ExoPlayer to set playWhenReady=false
                    // We must restore it to true since the user commanded playback via voice
                    Timber.d(
                        "[VoiceCommandBridge] TTS completed, player state: playWhenReady=${currentPlayer.playWhenReady}, isPlaying=${currentPlayer.isPlaying}",
                    )

                    // Force playWhenReady=true since this was a voice command to play
                    currentPlayer.playWhenReady = true
                    Timber.i("[VoiceCommandBridge] Restored playWhenReady=true after TTS completion")

                    // Ensure player actually starts playing
                    if (!currentPlayer.isPlaying) {
                        currentPlayer.play()
                        Timber.i("[VoiceCommandBridge] Called play() to start playback")
                    }
                }
                Timber.i("[VoiceCommandBridge] Speaking bridge audio for voice command")
            }

            serviceScope.launch(coroutineExceptionHandler) {
                try {
                    handleSearchSuspend(query, true)
                    Timber.d("[VoiceCommandTrace] onPlayFromSearch EXIT - success")
                } catch (e: Exception) {
                    Timber.e(e, "[VoiceCommandTrace] Exception in onPlayFromSearch: ${e.message}")
                    Timber.e(e, "[AndroidAuto] Error in onPlayFromSearch")
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        appContext.getString(local.oss.chronicle.R.string.auto_error_playback_failed),
                    )
                    voiceCommandStartTime = null // Clear on error to prevent stale state
                }
            }
        }

        private suspend fun handleSearchSuspend(
            query: String?,
            playWhenReady: Boolean,
        ) {
            Timber.d("[VoiceCommandTrace] Starting search for query: '$query'")

            if (query.isNullOrEmpty()) {
                // take most recently played book, start that
                try {
                    val mostRecentlyPlayed = bookRepository.getMostRecentlyPlayed()
                    val bookToPlay =
                        if (mostRecentlyPlayed == EMPTY_AUDIOBOOK) {
                            bookRepository.getRandomBookAsync()
                        } else {
                            mostRecentlyPlayed
                        }

                    if (bookToPlay == EMPTY_AUDIOBOOK) {
                        Timber.w("[AndroidAuto] No book available for empty search query")
                        onSetPlaybackError(
                            android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                            appContext.getString(local.oss.chronicle.R.string.auto_error_library_empty),
                        )
                        return
                    }

                    if (playWhenReady) {
                        Timber.d("[VoiceCommandTrace] Calling onPlayFromMediaId with bookId: ${bookToPlay.id}")
                        onPlayFromMediaId(bookToPlay.id.toString(), null)
                    } else {
                        onPrepareFromMediaId(bookToPlay.id.toString(), null)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[VoiceCommandTrace] Exception in handleSearchSuspend (empty query): ${e.message}")
                    Timber.e(e, "[AndroidAuto] Error handling empty search query")
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        appContext.getString(local.oss.chronicle.R.string.auto_error_playback_failed),
                    )
                }
                return
            }

            try {
                val matchingBooks = bookRepository.searchAsync(query)
                Timber.d(
                    "[VoiceCommandTrace] Search results - found: ${matchingBooks.size} books, first: ${matchingBooks.firstOrNull()?.title}",
                )
                if (matchingBooks.isNotEmpty()) {
                    val result = matchingBooks.first().id.toString()
                    if (playWhenReady) {
                        Timber.d("[VoiceCommandTrace] Calling onPlayFromMediaId with bookId: $result")
                        onPlayFromMediaId(result, null)
                    } else
                        {
                            onPrepareFromMediaId(result, null)
                        }
                } else {
                    Timber.w("[AndroidAuto] No matching books found for query: $query")

                    // Check if voice search fallback is enabled
                    if (prefsRepo.voiceSearchFallbackEnabled) {
                        Timber.i("[AndroidAuto] Voice search fallback enabled, attempting to play most recently played book")
                        try {
                            val mostRecentlyPlayed = bookRepository.getMostRecentlyPlayed()
                            val bookToPlay =
                                if (mostRecentlyPlayed == EMPTY_AUDIOBOOK) {
                                    bookRepository.getRandomBookAsync()
                                } else {
                                    mostRecentlyPlayed
                                }

                            if (bookToPlay == EMPTY_AUDIOBOOK) {
                                Timber.w("[AndroidAuto] No book available for fallback")
                                onSetPlaybackError(
                                    android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                                    appContext.getString(local.oss.chronicle.R.string.auto_error_library_empty),
                                )
                                return
                            }

                            Timber.i("[AndroidAuto] Fallback playing book: ${bookToPlay.title}")
                            if (playWhenReady) {
                                Timber.d("[VoiceCommandTrace] Calling onPlayFromMediaId with bookId: ${bookToPlay.id} (fallback)")
                                onPlayFromMediaId(bookToPlay.id.toString(), null)
                            } else {
                                onPrepareFromMediaId(bookToPlay.id.toString(), null)
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "[VoiceCommandTrace] Exception in handleSearchSuspend (fallback): ${e.message}")
                            Timber.e(e, "[AndroidAuto] Error during voice search fallback")
                            onSetPlaybackError(
                                android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                                appContext.getString(local.oss.chronicle.R.string.auto_error_playback_failed),
                            )
                        }
                    } else {
                        onSetPlaybackError(
                            android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                            appContext.getString(local.oss.chronicle.R.string.auto_error_no_results_for_query, query),
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[VoiceCommandTrace] Exception in handleSearchSuspend (search): ${e.message}")
                Timber.e(e, "[AndroidAuto] Error searching for query: $query")
                onSetPlaybackError(
                    android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                    appContext.getString(local.oss.chronicle.R.string.auto_error_playback_failed),
                )
            }
        }

        override fun onPause() {
            if (!mediaController.playbackState.isPrepared) {
                Timber.i("Started from dead")
                resumePlayFromEmpty(false)
            } else {
                currentPlayer.playWhenReady = false
            }
        }

        override fun onSkipToNext() {
            skipToNext()
        }

        override fun onSkipToPrevious() {
            skipToPrevious()
        }

        private fun skipToNext() {
            currentPlayer.skipToNext(trackListStateManager, currentlyPlaying, progressUpdater)
        }

        private fun skipToPrevious() {
            currentPlayer.skipToPrevious(trackListStateManager, currentlyPlaying, progressUpdater)
        }

        private fun skipForwards() {
            Timber.i("Track manager is $trackListStateManager")
            currentPlayer.seekRelative(
                trackListStateManager,
                prefsRepo.jumpForwardSeconds * MILLIS_PER_SECOND,
            )
        }

        private fun skipBackwards() {
            Timber.i("Track manager is $trackListStateManager")
            currentPlayer.seekRelative(
                trackListStateManager,
                prefsRepo.jumpBackwardSeconds * MILLIS_PER_SECOND * -1,
            )
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            if (mediaButtonEvent == null) {
                return false
            }
            val ke: KeyEvent? = IntentCompat.getParcelableExtra(mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            Timber.i("Media button event: $ke")
            // Media button events usually come in either an "ACTION_DOWN" + "ACTION_UP" pair, or
            // as a single ACTION_DOWN. Just take ACTION_DOWNs
            if (ke?.action == ACTION_DOWN) {
                // These really handle bluetooth media actions only, but the framework handles
                // pause/play for inline wired headphones
                return when (ke.keyCode) {
                    KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_SKIP_FORWARD -> {
                        skipForwards()
                        true
                    }
                    KEYCODE_MEDIA_PREVIOUS, KEYCODE_MEDIA_SKIP_BACKWARD -> {
                        skipBackwards()
                        true
                    }
                    KEYCODE_MEDIA_PAUSE -> {
                        onPause()
                        true
                    }
                    KEYCODE_MEDIA_PLAY -> {
                        onPlay()
                        true
                    }
                    KEYCODE_MEDIA_STOP -> {
                        onStop()
                        true
                    }
                    else -> false
                }
            }
            return false
        }

        override fun onPlay() {
            // Pre-flight authentication check
            if (!checkAuthenticationOrError()) return

            // Check if session is inactive. If so, we are resuming a session right here, so play
            // the most recently played book
            if (!mediaController.playbackState.isPrepared) {
                Timber.i("Started from dead")
                resumePlayFromEmpty(true)
            } else {
                currentPlayer.playWhenReady = true
            }
        }

        override fun onSeekTo(pos: Long) {
            // pos is chapter-relative from MediaSession seekbar (published by buildPlaybackState())
            val chapter = currentlyPlaying.chapter.value
            val absolutePosition =
                if (chapter != null && chapter != EMPTY_CHAPTER) {
                    // Convert chapter-relative to absolute track position
                    chapter.startTimeOffset + pos
                } else {
                    // Fallback when no chapter data
                    pos
                }

            Timber.i("Seeking: chapter-relative=${pos}ms → absolute=${absolutePosition}ms (chapter=${chapter?.title ?: "none"})")
            currentPlayer.seekTo(absolutePosition)
        }

        override fun onCustomAction(
            action: String?,
            extras: Bundle?,
        ) {
            Timber.i("Custom action")
            when (action) {
                // onSeekTo doesn't appear to be called by transportControls.seekTo, so use a custom
                // action instead...
                ACTION_SEEK -> {
                    val offsetMs =
                        extras?.getLong(OFFSET_MS)
                            ?: throw IllegalStateException("Received ACTION_SEEK without an offset")
                    trackListStateManager.seekByRelativeBlocking(offsetMs)
                    currentPlayer.seekTo(
                        trackListStateManager.currentTrackIndex,
                        trackListStateManager.currentTrackProgress,
                    )
                }
                SKIP_FORWARDS_STRING -> skipForwards()
                SKIP_BACKWARDS_STRING -> skipBackwards()
                SKIP_TO_NEXT_STRING -> skipToNext()
                SKIP_TO_PREVIOUS_STRING -> skipToPrevious()
            }
        }

        /**
         * Assume mediaId refers to the audiobook as a whole, and there is an option string in the
         * extras bundle referring to the specific track at key [KEY_START_TIME_TRACK_OFFSET]
         */
        override fun onPlayFromMediaId(
            bookId: String?,
            extras: Bundle?,
        ) {
            Timber.d(
                "[VoiceCommandTrace] onPlayFromMediaId ENTER - bookId: '$bookId', extras: ${if (extras == null) "null" else "Bundle"}",
            )

            // Early return for non-playable message items (from Android Auto error/info display)
            // These items use FLAG_PLAYABLE to ensure Android Auto displays them, but should not trigger playback
            if (bookId?.startsWith("__error") == true || bookId?.startsWith("__message") == true) {
                Timber.d("[AndroidAuto] Ignoring click on message item: $bookId")
                // Set error state to dismiss Android Auto's "Now Playing" screen
                // This signals Android Auto to abort the playback attempt and return to browse view
                onSetPlaybackError(
                    android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                    appContext.getString(local.oss.chronicle.R.string.auto_error_complete_required_steps_on_phone),
                    // "" // Empty message - we don't want to show a toast, just dismiss the UI
                )
                return
            }

            try {
                // Bridge audio already spoken in onPlayFromSearch - don't speak again
                // This prevents double-speaking and audio focus conflicts
                // Clear voice command flag to prevent stale state
                voiceCommandStartTime = null

                // Pre-flight authentication check
                if (!checkAuthenticationOrError()) {
                    Timber.d("[VoiceCommandTrace] onPlayFromMediaId early return - reason: authentication check failed")
                    return
                }

                if (bookId.isNullOrEmpty()) {
                    Timber.d("[VoiceCommandTrace] onPlayFromMediaId early return - reason: null/empty bookId")
                    Timber.e("[AndroidAuto] onPlayFromMediaId called with null/empty bookId")
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        appContext.getString(local.oss.chronicle.R.string.auto_error_audiobook_not_available),
                    )
                    return
                }
                Timber.i("[AndroidAuto] Playing media from ID: $bookId")
                Timber.d("[VoiceCommandTrace] Calling playBook with bookId: $bookId")
                playBook(bookId, extras ?: Bundle(), true)
                Timber.d("[VoiceCommandTrace] onPlayFromMediaId EXIT - success")
            } catch (e: Exception) {
                Timber.e(e, "[VoiceCommandTrace] Exception in onPlayFromMediaId: ${e.message}")
                Timber.e(e, "[AndroidAuto] Error in onPlayFromMediaId for bookId=$bookId")
                onSetPlaybackError(
                    android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                    appContext.getString(local.oss.chronicle.R.string.auto_error_playback_failed),
                )
            }
        }

        override fun onPrepareFromMediaId(
            bookId: String?,
            extras: Bundle?,
        ) {
            try {
                if (bookId.isNullOrEmpty()) {
                    Timber.e("[AndroidAuto] onPrepareFromMediaId called with null/empty bookId")
                    return
                }
                Timber.i("[AndroidAuto] Preparing media from ID: $bookId")
                playBook(bookId, extras ?: Bundle(), false)
            } catch (e: Exception) {
                Timber.e(e, "[AndroidAuto] Error in onPrepareFromMediaId for bookId=$bookId")
            }
        }

        private fun playBook(
            bookId: String,
            extras: Bundle,
            playWhenReady: Boolean,
        ) {
            // Clear thumbnail failure cache when starting a new book playback session
            // This allows thumbnail requests to be retried for the new book
            plexConfig.clearThumbnailFailureCache()

            // The [MediaItemTrack.id] of the track to be played, either a unique ID from
            // the DB, or default to ACTIVE_TRACK, the most recently listened track in [bookId]
            val startingTrackId = extras.getString(KEY_SEEK_TO_TRACK_WITH_ID, ACTIVE_TRACK)

            // [startTimeOffsetMillis] is an offset in milliseconds from start of the track where
            // [MediaItemTrack.id] == [startingTrackId] from the local repo
            //
            // Default to [USE_SAVED_TRACK_PROGRESS], which uses the current progress of track where
            // [MediaItemTrack.id] == [startingTrackId] in the local repo
            val startTimeOffsetMillis =
                extras.getLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)

            if (bookId == EMPTY_AUDIOBOOK.id.toString()) {
                Timber.d("[VoiceCommandTrace] playBook early return - reason: attempted to play empty audiobook")
                throw IllegalStateException("Attempted to play empty audiobook")
            }

            if (BuildConfig.DEBUG) {
                val debugTrackIdString =
                    if (startingTrackId == ACTIVE_TRACK) "ACTIVE_TRACK" else startingTrackId
                val readableOffset =
                    startTimeOffsetMillis.takeIf { it != USE_SAVED_TRACK_PROGRESS }
                        ?: "USE_SAVED_TRACK_PROGRESS"
                Timber.i(
                    "Starting playback for book=$bookId track=$debugTrackIdString at offset $readableOffset",
                )
            }
            serviceScope.launch {
                val tracks =
                    withContext(Dispatchers.IO) {
                        trackRepository.getTracksForAudiobookAsync(bookId)
                    }
                if (tracks.isEmpty()) {
                    Timber.d("[VoiceCommandTrace] playBook - no tracks found, attempting to fetch from network")
                    handlePlayBookWithNoTracks(bookId, tracks, extras, playWhenReady)
                    return@launch
                }

                val updatedCacheTrackIds = mutableListOf<String>()
                val normalizedTracks =
                    tracks.map { track ->
                        if (track.cached) {
                            return@map track
                        }

                        val cachedFile = File(prefsRepo.cachedMediaDir, track.getCachedFileName())
                        if (cachedFile.exists()) {
                            updatedCacheTrackIds += track.id
                            track.copy(cached = true)
                        } else {
                            track
                        }
                    }

                if (updatedCacheTrackIds.isNotEmpty()) {
                    Timber.i(
                        "Detected ${updatedCacheTrackIds.size} locally cached tracks with stale DB flags for book $bookId; using local files",
                    )
                    withContext(Dispatchers.IO) {
                        updatedCacheTrackIds.forEach { trackId ->
                            trackRepository.updateCachedStatus(trackId, true)
                        }
                    }
                }

                Timber.i("Tracks: $normalizedTracks")

                // Pre-resolve streaming URLs using Plex's decision endpoint for bandwidth-aware playback
                // This populates MediaItemTrack.streamingUrlCache which is used by getTrackSource()
                val streamingTracks = normalizedTracks.filterNot { it.cached }
                if (streamingTracks.isNotEmpty()) {
                    try {
                        Timber.i("Pre-resolving streaming URLs for ${streamingTracks.size} tracks...")
                        val resolvedCount =
                            withContext(Dispatchers.IO) {
                                playbackUrlResolver.preResolveUrls(streamingTracks)
                            }
                        Timber.i("Successfully pre-resolved $resolvedCount/${streamingTracks.size} streaming URLs")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to pre-resolve streaming URLs, will fall back to direct file URLs")
                    }
                } else {
                    Timber.i("Skipping streaming URL pre-resolution because all tracks are cached locally")
                }

                trackListStateManager.trackList = normalizedTracks
                val metadataList = buildPlaylist(normalizedTracks, plexConfig)

                val queueItems =
                    metadataList.zip(normalizedTracks).map { (metadata, track) ->
                        val numericTrackId = track.id.removePrefix("plex:").toLongOrNull() ?: track.id.hashCode().toLong()
                        MediaSessionCompat.QueueItem(
                            metadata.fullDescription,
                            numericTrackId,
                        )
                    }
                mediaSession.setQueue(queueItems)

                // startingTrackId is already a String
                check(
                    startingTrackId == ACTIVE_TRACK || startingTrackId in normalizedTracks.map { it.id },
                ) { "Track not found! " }

                val startingTrack =
                    if (startingTrackId == ACTIVE_TRACK) {
                        normalizedTracks.getActiveTrack()
                    } else {
                        normalizedTracks.find { it.id == startingTrackId }
                    }

                checkNotNull(startingTrack) { "No starting track provided for $startingTrackId" }
                val startingTrackIndex = normalizedTracks.sorted().indexOf(startingTrack)
                val trueStartTimeOffsetMillis =
                    if (startTimeOffsetMillis != USE_SAVED_TRACK_PROGRESS) {
                        startTimeOffsetMillis
                    } else {
                        startingTrack.progress
                    }
                Timber.d(
                    "[ProgressSaveRestoreDebug] READ: bookId=$bookId, trackId=${startingTrack.id}, " +
                        "trackProgress=${startingTrack.progress}, lastViewedAt=${startingTrack.lastViewedAt}, " +
                        "usingSavedProgress=${startTimeOffsetMillis == USE_SAVED_TRACK_PROGRESS}",
                )
                Timber.i(
                    "Starting at index: $startingTrackIndex, offset by $trueStartTimeOffsetMillis",
                )
                trackListStateManager.updatePosition(startingTrackIndex, trueStartTimeOffsetMillis)

                val book =
                    withContext(Dispatchers.IO) {
                        return@withContext bookRepository.getAudiobookAsync(bookId)
                    }
                if (book == null || book.id == NO_AUDIOBOOK_FOUND_ID) {
                    Timber.d("[VoiceCommandTrace] playBook early return - reason: no book found with id $bookId")
                    // Return if no book found- no reason to setup playback if there's no book
                    return@launch
                }

                // Phase 3: Set library context on the HTTP data source factory for library-aware token injection
                // This ensures ExoPlayer uses the correct auth token for this book's library
                dataSourceFactory.currentLibraryId = book.libraryId
                Timber.d(
                    "[TokenInjection] Set PlexHttpDataSourceFactory.currentLibraryId = ${book.libraryId} " +
                        "for book: ${book.title}",
                )

                // Auto-rewind depending on last listened time for the book. Don't rewind if we're
                // starting a new chapter/track of a book
                if (startTimeOffsetMillis == USE_SAVED_TRACK_PROGRESS) {
                    trackListStateManager.seekByRelative(-1L * calculateRewindDuration(book))
                }
                // Note: We do NOT seek forward when jumping to a specific chapter/position
                // to avoid interfering with accurate chapter seeking

                val player = currentPlayer

                // NOTE: We used to refresh the auth token here by calling setDefaultRequestProperties,
                // but that method REPLACES all headers (including X-Plex-Platform, X-Plex-Client-Identifier,
                // X-Plex-Client-Profile-Extra, etc.) which causes 500 errors from Plex server.
                // Phase 3: The token is now library-aware via currentLibraryId set above.
                // PlexHttpDataSourceFactory reads the library-specific token on each createDataSource() call.
                val factory = DefaultDataSource.Factory(appContext, dataSourceFactory)
                when (player) {
                    is ExoPlayer -> {
                        val mediaSources = metadataList.toMediaSources(plexPrefsRepo, factory)
                        player.setMediaSources(mediaSources)
                        player.prepare()
                    }
                    else -> throw NoWhenBranchMatchedException("Unknown media player")
                }

                // Load the audiobook into PlaybackStateController
                val chapters = book.chapters.ifEmpty { tracks.asChapterList(book.id) }
                playbackStateController.loadAudiobook(
                    audiobook = book,
                    tracks = normalizedTracks,
                    chapters = chapters,
                    startTrackIndex = startingTrackIndex,
                    startPositionMs = trackListStateManager.currentTrackProgress,
                )

                // Keep calling currentlyPlaying.update() for backward compatibility
                currentlyPlaying.update(
                    book = book,
                    tracks = normalizedTracks,
                    track = startingTrack.copy(progress = trackListStateManager.currentTrackProgress),
                )

                mediaSession.setQueueTitle(book.title)
                mediaSession.setMetadata(metadataList[startingTrackIndex])

                player.seekTo(
                    trackListStateManager.currentTrackIndex,
                    trackListStateManager.currentTrackProgress,
                )

                // Set playWhenReady AFTER player is prepared and seeked
                currentPlayer.playWhenReady = playWhenReady

                // Inform plex server that audio playback session has started
                // Uses library-aware routing to send the session start to the correct server
                Timber.d("[PlayQueue] Calling plexProgressReporter.startMediaSession(bookId=${book.id}, libraryId=${book.libraryId})")
                plexProgressReporter.startMediaSession(
                    bookId = book.id,
                    libraryId = book.libraryId,
                )

                Timber.d("[VoiceCommandTrace] playBook EXIT - success")
            }
        }

        private suspend fun handlePlayBookWithNoTracks(
            bookId: String,
            tracks: List<MediaItemTrack>,
            extras: Bundle,
            playWhenReady: Boolean,
        ) {
            Timber.d("[VoiceCommandTrace] handlePlayBookWithNoTracks ENTER - bookId: $bookId")
            Timber.i("No known tracks for book: $bookId, attempting to fetch them")

            // Get the audiobook first to access libraryId
            val audiobook = bookRepository.getAudiobookAsync(bookId)
            if (audiobook == null) {
                Timber.e("[VoiceCommandTrace] handlePlayBookWithNoTracks EXIT - audiobook not found")
                return
            }

            // Tracks haven't been loaded by UI for this track, so load it here
            val networkTracks =
                withContext(Dispatchers.IO) {
                    trackRepository.loadTracksForAudiobook(bookId, audiobook.libraryId)
                }
            val loadedNetworkTracks = networkTracks.get()
            if (loadedNetworkTracks != null) {
                bookRepository.updateTrackData(
                    bookId,
                    loadedNetworkTracks.getProgress(),
                    loadedNetworkTracks.getDuration(),
                    loadedNetworkTracks.size,
                )
                bookRepository.syncAudiobook(audiobook, tracks)
                playBook(bookId, extras, playWhenReady)
                Timber.d("[VoiceCommandTrace] handlePlayBookWithNoTracks EXIT - success, retrying playBook")
            } else {
                Timber.d("[VoiceCommandTrace] handlePlayBookWithNoTracks EXIT - failed to fetch tracks")
            }
        }

        private fun calculateRewindDuration(book: Audiobook?): Long {
            if (!prefsRepo.autoRewind) return 0L
            val millisSinceLastListen = System.currentTimeMillis() - (book?.lastViewedAt ?: 0L)
            val secondsSinceLastListen = millisSinceLastListen / (1000)
            return when {
                secondsSinceLastListen in 15..60 -> 5 * 1000L
                secondsSinceLastListen in 60..(60 * 60) -> 10 * 1000L
                secondsSinceLastListen > (60 * 60 * 24) -> 20 * 1000L
                else -> 0L
            }
        }

        /**
         * Resume playback, assuming that service is starting from death. We cannot assume that
         * UI exists or that the requisite setup has happened, including connecting to a server or
         * refreshing data.
         */
        private fun resumePlayFromEmpty(playWhenReady: Boolean) {
            serviceScope.launch {
                try {
                    // Wait for connection with timeout
                    withTimeout(RESUME_TIMEOUT_MILLIS) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            val connectedObserver =
                                object : Observer<Boolean> {
                                    override fun onChanged(isConnected: Boolean) {
                                        // Don't try starting playback until we've connected to a server
                                        if (!isConnected) {
                                            return
                                        }

                                        // Only run these resume methods once after reconnecting
                                        plexConfig.isConnected.removeObserver(this)

                                        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                                            val mostRecentBook = bookRepository.getMostRecentlyPlayed()
                                            if (mostRecentBook == EMPTY_AUDIOBOOK) {
                                                onSetPlaybackError(
                                                    android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                                                    appContext.getString(local.oss.chronicle.R.string.auto_error_library_empty),
                                                )
                                                return@launch
                                            }
                                            if (playWhenReady) {
                                                onPlayFromMediaId(mostRecentBook.id.toString(), null)
                                            } else {
                                                onPrepareFromMediaId(mostRecentBook.id.toString(), null)
                                            }
                                        }

                                        continuation.resume(Unit) { }
                                    }
                                }

                            plexConfig.isConnected.observeForever(connectedObserver)

                            continuation.invokeOnCancellation {
                                plexConfig.isConnected.removeObserver(connectedObserver)
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Timber.w("[AndroidAuto] resumePlayFromEmpty timed out after ${RESUME_TIMEOUT_MILLIS}ms")
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        appContext.getString(local.oss.chronicle.R.string.auto_error_connection_timeout),
                    )
                } catch (e: Exception) {
                    Timber.e(e, "[AndroidAuto] Error in resumePlayFromEmpty")
                    onSetPlaybackError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        appContext.getString(local.oss.chronicle.R.string.auto_error_playback_failed),
                    )
                }
            }
        }

        // Kill the playback service when stop() is called, so Service can be recreated when needed
        // with the correct info from global storage
        override fun onStop() {
            Timber.i("Stopping media playback")
            currentPlayer.stop()
            mediaSession.setPlaybackState(EMPTY_PLAYBACK_STATE)

            // Clear streaming URL cache when stopping playback
            playbackUrlResolver.clearCache()

            // Clear play queue item cache to prevent stale IDs
            plexProgressReporter.clearPlayQueueCache()

            foregroundServiceController.stopForegroundService(true)
            serviceController.stopService()
        }

        /**
         * Logs and clears the voice command latency if a voice command is pending.
         * Should be called when playback actually starts.
         */
        fun logVoiceCommandLatencyIfPending() {
            voiceCommandStartTime?.let { startTime ->
                val latencyMs = System.currentTimeMillis() - startTime
                Timber.i("[VoiceCommandLatency] Voice command latency: ${latencyMs}ms (from command received to playback started)")
                voiceCommandStartTime = null // Clear after logging
            }
        }
    }
