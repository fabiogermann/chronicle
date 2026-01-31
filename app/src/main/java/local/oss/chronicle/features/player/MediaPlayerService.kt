package local.oss.chronicle.features.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_MEDIA_STOP
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.R
import local.oss.chronicle.application.ChronicleApplication
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.getActiveTrack
import local.oss.chronicle.data.model.getProgress
import local.oss.chronicle.data.model.toMediaItem
import local.oss.chronicle.data.sources.plex.*
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.*
import local.oss.chronicle.data.sources.plex.model.getDuration
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.features.player.SleepTimer.Companion.ARG_SLEEP_TIMER_ACTION
import local.oss.chronicle.features.player.SleepTimer.Companion.ARG_SLEEP_TIMER_DURATION_MILLIS
import local.oss.chronicle.features.player.SleepTimer.SleepTimerAction
import local.oss.chronicle.injection.components.DaggerServiceComponent
import local.oss.chronicle.injection.modules.ServiceModule
import local.oss.chronicle.util.PackageValidator
import local.oss.chronicle.util.ServiceUtils
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** The service responsible for media playback, notification */
@ExperimentalCoroutinesApi
@OptIn(ExperimentalTime::class)
class MediaPlayerService :
    MediaBrowserServiceCompat(),
    ForegroundServiceController,
    ServiceController,
    IPlaybackErrorReporter,
    SleepTimer.SleepTimerBroadcaster,
    local.oss.chronicle.features.currentlyplaying.OnChapterChangeListener {
    val serviceJob: CompletableJob = SupervisorJob()
    val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    @Inject
    lateinit var onMediaChangedCallback: OnMediaChangedCallback

    @Inject
    lateinit var packageValidator: PackageValidator

    @Inject
    lateinit var notificationBuilder: NotificationBuilder

    @Inject
    lateinit var plexConfig: PlexConfig

    @Inject
    lateinit var becomingNoisyReceiver: BecomingNoisyReceiver

    @Inject
    lateinit var mediaSession: MediaSessionCompat

    @Inject
    lateinit var mediaController: MediaControllerCompat

    @Inject
    lateinit var exoPlayer: ExoPlayer

    @Inject
    lateinit var currentlyPlaying: CurrentlyPlaying

    @Inject
    lateinit var bookRepository: IBookRepository

    @Inject
    lateinit var trackRepository: ITrackRepository

    @Inject
    lateinit var trackListManager: TrackListStateManager

    @Inject
    lateinit var mediaSessionCallback: AudiobookMediaSessionCallback

    @Inject
    lateinit var prefsRepo: PrefsRepo

    @Inject
    lateinit var plexPrefs: PlexPrefsRepo

    @Inject
    lateinit var plexLoginRepo: IPlexLoginRepo

    @Inject
    lateinit var playbackStateController: PlaybackStateController

    companion object {
        /** Strings used by plex to indicate playback state */
        const val PLEX_STATE_PLAYING = "playing"
        const val PLEX_STATE_STOPPED = "stopped"
        const val PLEX_STATE_PAUSED = "paused"

        /** Strings used to indicate playback errors */
        const val ACTION_PLAYBACK_ERROR = "playback error action intent"
        const val PLAYBACK_ERROR_MESSAGE = "playback error message"

        /**
         * Key for storing absolute track position in PlaybackStateCompat extras.
         * This is used to avoid confusion with chapter-relative position stored in PlaybackStateCompat.position
         */
        const val EXTRA_ABSOLUTE_TRACK_POSITION = "ABSOLUTE_TRACK_POSITION"

        /**
         * Key indicating playback start time offset relative to the start of the track being
         * played (only use for, m4b chapters, as mp3 durations are generally too imprecise)
         */
        const val KEY_START_TIME_TRACK_OFFSET = "track index bundle 2939829 tubers"

        // Key indicating the ID of the track to begin playback at
        const val KEY_SEEK_TO_TRACK_WITH_ID = "MediaPlayerService.key_seek_to_track_with_id"

        // Value indicating to begin playback at the most recently listened position
        const val ACTIVE_TRACK = Long.MIN_VALUE + 22233L
        const val USE_SAVED_TRACK_PROGRESS = Long.MIN_VALUE + 22250L

        private const val CHRONICLE_MEDIA_ROOT_ID = "chronicle_media_root_id"
        private const val CHRONICLE_MEDIA_EMPTY_ROOT = "empty root"
        private const val CHRONICLE_MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

        /**
         * Exoplayer back-buffer (millis to keep of playback prior to current location)
         *
         * @see DefaultLoadControl.Builder.setBufferDurationsMs
         */
        val EXOPLAYER_BACK_BUFFER_DURATION_MILLIS: Int = 120.seconds.inWholeMilliseconds.toInt()

        /**
         * Exoplayer min-buffer (the minimum millis of buffer which exo will attempt to keep in
         * memory)
         *
         * @see DefaultLoadControl.Builder.setBufferDurationsMs
         */
        val EXOPLAYER_MIN_BUFFER_DURATION_MILLIS: Int = 10.seconds.inWholeMilliseconds.toInt()

        /**
         * Exoplayer max-buffer (the maximum duration of buffer which Exoplayer will store in memory)
         *
         * @see DefaultLoadControl.Builder.setBufferDurationsMs
         */
        val EXOPLAYER_MAX_BUFFER_DURATION_MILLIS: Int = 360.seconds.inWholeMilliseconds.toInt()
    }

    @Inject
    lateinit var sleepTimer: SleepTimer

    @Inject
    lateinit var progressUpdater: ProgressUpdater

    private fun mediaBrowserCompatStringField(name: String): String? {
        return runCatching { MediaBrowserCompat::class.java.getField(name).get(null) as? String }
            .getOrElse {
                runCatching {
                    MediaBrowserCompat.MediaItem::class.java.getField(name).get(null) as? String
                }.getOrNull()
            }
    }

    private fun mediaBrowserCompatIntField(name: String): Int? {
        return runCatching { MediaBrowserCompat::class.java.getField(name).getInt(null) }
            .getOrElse {
                runCatching { MediaBrowserCompat.MediaItem::class.java.getField(name).getInt(null) }.getOrNull()
            }
    }

    @Inject
    lateinit var mediaSource: PlexMediaRepository

    @Inject
    lateinit var localBroadcastManager: LocalBroadcastManager

    var currentPlayer: Player? = null

    private var sessionErrorMessage: String? = null
    private var isErrorState: Boolean = false
    private var sessionCustomActions: List<PlaybackStateCompat.CustomAction> = emptyList()
    private val timelineWindow = Timeline.Window()

    override fun onCreate() {
        super.onCreate()

        DaggerServiceComponent.builder()
            .appComponent((application as ChronicleApplication).appComponent)
            .serviceModule(ServiceModule(this))
            .build()
            .inject(this)

        ServiceUtils.notifyServiceStarted(this)

        Timber.i("Service created! $this")

        updateAudioAttrs(exoPlayer)

        prefsRepo.registerPrefsListener(prefsListener)

        serviceScope.launch(Injector.get().unhandledExceptionHandler()) { mediaSource.load() }

        mediaSession.setPlaybackState(PlaybackStateCompat.Builder().build())
        mediaSession.setCallback(mediaSessionCallback)

        updateCustomActions()
        switchToPlayer(exoPlayer)

        mediaController.registerCallback(onMediaChangedCallback)

        // Register chapter change listener to update metadata when chapter changes
        currentlyPlaying.setOnChapterChangeListener(this)

        // Observe PlaybackStateController to keep MediaSession in sync
        // This ensures Android Auto and other media clients always have current state
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            playbackStateController.state.collect { state ->
                Timber.d(
                    "[AndroidAuto] PlaybackStateController state changed: hasMedia=${state.hasMedia}, " +
                        "isPlaying=${state.isPlaying}, trackIndex=${state.currentTrackIndex}",
                )
                // Trigger MediaSession update when controller state changes
                updateSessionPlaybackState()
            }
        }

        // startForeground has to be called within 5 seconds of starting the service or the app
        // will ANR (on Android 9.0 and above, maybe earlier).
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            val notification = notificationBuilder.buildNotification(mediaSession.sessionToken)
            startForeground(NOW_PLAYING_NOTIFICATION, notification)
        }

        localBroadcastManager.registerReceiver(
            sleepTimerBroadcastReceiver,
            IntentFilter(SleepTimer.ACTION_SLEEP_TIMER_CHANGE),
        )

        invalidatePlaybackParams()
        progressUpdater.startRegularProgressUpdates()

        plexConfig.connectionState.observeForever(serverChangedListener)
    }

    private fun updateAudioAttrs(exoPlayer: ExoPlayer) {
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(
                    if (prefsRepo.pauseOnFocusLost) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC,
                )
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true,
        )
    }

    private fun updateCustomActions() {
        sessionCustomActions = buildCustomActions(prefsRepo)
        updateSessionPlaybackState()
    }

    private fun setSessionCustomErrorMessage(message: String?) {
        if (message != null) {
            // When setting error message, also set STATE_ERROR
            setPlaybackStateError(PlaybackStateCompat.ERROR_CODE_APP_ERROR, message)
        } else {
            // Clear error state
            sessionErrorMessage = null
            isErrorState = false
            updateSessionPlaybackState()
        }
    }

    /**
     * Sets the MediaSession playback state to STATE_ERROR with appropriate error code and message.
     * This ensures Android Auto displays the error to the user instead of failing silently.
     *
     * @param errorCode PlaybackStateCompat error code (e.g., ERROR_CODE_AUTHENTICATION_EXPIRED)
     * @param errorMessage User-facing error message to display in Android Auto
     */
    override fun setPlaybackStateError(errorCode: Int, errorMessage: String) {
        Timber.e("[AndroidAuto] Setting error state: code=$errorCode, message=$errorMessage")
        
        // Stop current playback when entering error state
        currentPlayer?.playWhenReady = false
        
        isErrorState = true
        sessionErrorMessage = errorMessage
        
        val errorState = PlaybackStateCompat.Builder()
            .setActions(basePlaybackActions())
            .setState(
                PlaybackStateCompat.STATE_ERROR,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                0f
            )
            .setErrorMessage(errorCode, errorMessage)
            .build()
        
        mediaSession.setPlaybackState(errorState)
    }

    /**
     * Clears the error state when playback starts successfully.
     */
    override fun clearPlaybackError() {
        if (isErrorState) {
            Timber.d("[AndroidAuto] Clearing error state")
            isErrorState = false
            sessionErrorMessage = null
            updateSessionPlaybackState()
        }
    }

    override fun broadcastUpdate(
        sleepTimerAction: SleepTimerAction,
        durationMillis: Long,
    ) {
        val broadcastIntent =
            Intent(SleepTimer.ACTION_SLEEP_TIMER_CHANGE).apply {
                putExtra(ARG_SLEEP_TIMER_ACTION, sleepTimerAction)
                putExtra(ARG_SLEEP_TIMER_DURATION_MILLIS, durationMillis)
            }
        localBroadcastManager.sendBroadcast(broadcastIntent)
    }

    private val sleepTimerBroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent != null) {
                    val durationMillis = intent.getLongExtra(ARG_SLEEP_TIMER_DURATION_MILLIS, 0L)
                    val action =
                        IntentCompat.getSerializableExtra(
                            intent,
                            ARG_SLEEP_TIMER_ACTION,
                            SleepTimerAction::class.java,
                        )
                    if (action != null) {
                        sleepTimer.handleAction(action, durationMillis)
                    }
                }
            }
        }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PrefsRepo.KEY_SKIP_SILENCE, PrefsRepo.KEY_PLAYBACK_SPEED -> {
                    invalidatePlaybackParams()
                }
                PrefsRepo.KEY_PAUSE_ON_FOCUS_LOST -> {
                    updateAudioAttrs(exoPlayer)
                }
                PrefsRepo.KEY_JUMP_FORWARD_SECONDS, PrefsRepo.KEY_JUMP_BACKWARD_SECONDS -> {
                    updateCustomActions()
                    serviceScope.launch {
                        withContext(Dispatchers.IO) {
                            sessionToken?.let {
                                val notification = notificationBuilder.buildNotification(it)
                                startForeground(NOW_PLAYING_NOTIFICATION, notification)
                            }
                        }
                    }
                }
            }
        }

    private val serverChangedListener =
        Observer<PlexConfig.ConnectionState> {
            if (mediaController.playbackState.isPrepared) {
                // Only can change server when playback is prepared because otherwise we would be
                // attempting to load data on a null/empty tracklist
                onChangeConnection()
            }
        }

    /**
     * Change the tracks in the player to refer to the new server url. Because [PlexConfig] is a
     * Singleton we don't need to keep track of state here
     */
    private fun onChangeConnection() {
        when (mediaController.playbackState.state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                mediaSessionCallback.onPlayFromMediaId(
                    trackListManager.trackList.map { it.id }.firstOrNull { true }.toString(),
                    Bundle().apply {
                        putLong(KEY_SEEK_TO_TRACK_WITH_ID, ACTIVE_TRACK)
                        putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)
                    },
                )
            }
            PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.STATE_BUFFERING -> {
                mediaSessionCallback.onPrepareFromMediaId(
                    trackListManager.trackList.map { it.id }.firstOrNull { true }.toString(),
                    Bundle().apply {
                        putLong(KEY_SEEK_TO_TRACK_WITH_ID, ACTIVE_TRACK)
                        putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)
                    },
                )
            }
            else -> {
                // if there isn't playback, there's nothing to change
            }
        }
    }

    private fun invalidatePlaybackParams() {
        Timber.i(
            "Playback params: speed = ${prefsRepo.playbackSpeed}, skip silence = ${prefsRepo.skipSilence}",
        )
        currentPlayer?.setPlaybackParameters(PlaybackParameters(prefsRepo.playbackSpeed, 1.0f))
        (currentPlayer as? ExoPlayer)?.skipSilenceEnabled = prefsRepo.skipSilence
    }

    private fun updateSessionPlaybackState() {
        val player = currentPlayer
        val playbackState =
            if (player != null) {
                buildPlaybackState(player)
            } else {
                buildEmptyPlaybackState()
            }
        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildPlaybackState(player: Player): PlaybackStateCompat {
        // If in error state, return error PlaybackState
        if (isErrorState && sessionErrorMessage != null) {
            val builder =
                PlaybackStateCompat.Builder()
                    .setActions(basePlaybackActions())
                    .setState(PlaybackStateCompat.STATE_ERROR, 0L, 0f)
                    .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, sessionErrorMessage)
            
            sessionCustomActions.forEach(builder::addCustomAction)
            return builder.build()
        }

        val playbackState = mapPlayerState(player)
        val playbackSpeed = player.playbackParameters.speed

        // Calculate chapter-relative position for the progress bar
        val trackPosition = if (player.playbackState == Player.STATE_IDLE) 0L else player.currentPosition
        val chapter = currentlyPlaying.chapter.value
        val position =
            if (chapter != local.oss.chronicle.data.model.EMPTY_CHAPTER) {
                // Chapter-scoped position: current position minus chapter start
                kotlin.math.max(0L, trackPosition - chapter.startTimeOffset)
            } else {
                // Fallback to track position when no chapter data
                trackPosition
            }

        // Calculate chapter-relative buffered position
        val trackBufferedPosition = player.bufferedPosition
        val bufferedPosition =
            if (chapter != local.oss.chronicle.data.model.EMPTY_CHAPTER) {
                kotlin.math.max(0L, trackBufferedPosition - chapter.startTimeOffset)
            } else {
                trackBufferedPosition
            }

        // Store absolute track position in extras to avoid confusion with chapter-relative position
        val extras =
            Bundle().apply {
                putLong(EXTRA_ABSOLUTE_TRACK_POSITION, trackPosition)
            }

        val builder =
            PlaybackStateCompat.Builder()
                .setActions(basePlaybackActions())
                .setBufferedPosition(bufferedPosition)
                .setState(playbackState, position, playbackSpeed)
                .setExtras(extras)

        sessionCustomActions.forEach(builder::addCustomAction)
        sessionErrorMessage?.let {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it)
        }

        return builder.build()
    }

    private fun basePlaybackActions(): Long =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
            PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED

    private fun buildEmptyPlaybackState(): PlaybackStateCompat {
        val builder =
            PlaybackStateCompat.Builder()
                .setActions(basePlaybackActions())
                .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
        sessionCustomActions.forEach(builder::addCustomAction)
        sessionErrorMessage?.let {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it)
        }
        return builder.build()
    }

    private fun mapPlayerState(player: Player): Int =
        when (player.playbackState) {
            Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            Player.STATE_READY ->
                if (player.playWhenReady) {
                    PlaybackStateCompat.STATE_PLAYING
                } else {
                    PlaybackStateCompat.STATE_PAUSED
                }
            Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }

    private fun updateSessionMetadataFromPlayer(player: Player) {
        val description =
            player.currentMediaItem?.localConfiguration?.tag as? MediaDescriptionCompat
                ?: extractDescriptionFromTimeline(player)

        if (description != null) {
            // Get current chapter information
            val chapter = currentlyPlaying.chapter.value
            val book = currentlyPlaying.book.value
            val track = currentlyPlaying.track.value

            // Build metadata with chapter-scoped information
            val metadata =
                if (chapter != local.oss.chronicle.data.model.EMPTY_CHAPTER) {
                    // Chapter exists: use chapter title and duration
                    val metadataBuilder =
                        MediaMetadataCompat.Builder()
                            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, description.mediaId)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "${chapter.title} - ${book.title}")
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapter.title)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, book.title)
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book.author)
                            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, book.title)
                            .putLong(
                                MediaMetadataCompat.METADATA_KEY_DURATION,
                                chapter.endTimeOffset - chapter.startTimeOffset,
                            )

                    // Copy album art if available
                    description.iconUri?.toString()?.let {
                        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, it)
                        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it)
                    }

                    metadataBuilder.build()
                } else {
                    // No chapter data: fallback to standard track-based metadata
                    description.toMediaMetadataCompat()
                }

            mediaSession.setMetadata(metadata)
        }
    }

    private fun extractDescriptionFromTimeline(player: Player): MediaDescriptionCompat? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) {
            return null
        }
        timeline.getWindow(player.currentMediaItemIndex, timelineWindow)
        return timelineWindow.mediaItem?.localConfiguration?.tag as? MediaDescriptionCompat
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // Ensures that players will not block being removed as a foreground service
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    override fun onDestroy() {
        Timber.i("Service destroyed")
        // Send one last update to local/remote servers that playback has stopped
        val metadata = mediaController.metadata
        val trackId = metadata?.id
        Timber.d("onDestroy: metadata=${if (metadata != null) "present" else "null"}, trackId=$trackId")
        if (trackId != null && trackId.toInt() != TRACK_NOT_FOUND) {
            val finalPosition = currentPlayer?.currentPosition ?: 0L
            Timber.d("onDestroy: Sending final progress update for trackId=$trackId, position=$finalPosition")
            progressUpdater.updateProgress(
                trackId.toInt(),
                PLEX_STATE_STOPPED,
                finalPosition,
                true,
            )
        } else {
            Timber.d("onDestroy: Skipping progress update - no valid track metadata available")
        }

        // Clear playback state in controller
        serviceScope.launch {
            playbackStateController.clear()
        }

        progressUpdater.cancel()
        serviceJob.cancel()

        plexConfig.connectionState.removeObserver(serverChangedListener)
        prefsRepo.unregisterPrefsListener(prefsListener)
        localBroadcastManager.unregisterReceiver(sleepTimerBroadcastReceiver)
        sleepTimer.cancel()

        mediaSession.run {
            isActive = false
            release()
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            intent.setPackage(packageName)
            intent.component =
                ComponentName(
                    packageName,
                    MediaPlayerService::class.qualifiedName
                        ?: "local.oss.chronicle.features.player.MediaPlayerService",
                )
            intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, 312202))
            // Allow the system to restart app past death on media button click. See onStartCommand
            setMediaButtonReceiver(
                PendingIntent.getService(
                    this@MediaPlayerService,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        mediaSession.setCallback(null)
        mediaController.unregisterCallback(onMediaChangedCallback)
        becomingNoisyReceiver.unregister()
        serviceJob.cancel()

        exoPlayer.removeListener(playerEventListener)

        ServiceUtils.notifyServiceStopped(this)
        super.onDestroy()
    }

    /** Handle hardware commands from notifications and custom actions from UI as intents */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // No need to parse actions if none were provided
        Timber.i("Start command!")

        // Handle intents sent from notification clicks as media button events
        val ke: KeyEvent? =
            intent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }
        Timber.i("Key event: $ke")
        if (ke != null) {
            mediaSessionCallback.onMediaButtonEvent(intent)
        }

        // startForeground has to be called within 5 seconds of starting the service or the app
        // will ANR (on Android 9.0+). Even if we don't have full metadata here for unknown reasons,
        // we should launch with whatever it is we have, assuming the event isn't the notification
        // itself being removed (KEYCODE_MEDIA_STOP)
        if (ke?.keyCode != KEYCODE_MEDIA_STOP) {
            serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                val notification = notificationBuilder.buildNotification(mediaSession.sessionToken)
                startForeground(NOW_PLAYING_NOTIFICATION, notification)
            }
        }

        /**
         * Return [START_NOT_STICKY] to instruct the system not to restart the
         * service upon death by the OS. This will prevent an empty notification
         * from appearing on service restart
         */
        return START_NOT_STICKY
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        Timber.d("[AndroidAuto] onLoadChildren: parentId=$parentId")

        // Handle error/message items - these are not browsable, return empty
        if (parentId.startsWith("__error_") || parentId.startsWith("__message_")) {
            Timber.d("[AndroidAuto] Message item requested, returning empty (not browsable)")
            result.sendResult(mutableListOf())
            return
        }

        if (parentId == CHRONICLE_MEDIA_EMPTY_ROOT || !prefsRepo.allowAuto) {
            Timber.d("[AndroidAuto] Returning empty result (empty root or auto disabled)")
            result.sendResult(mutableListOf())
            return
        }

        result.detach()
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            try {
                withContext(Dispatchers.IO) {
                    when (parentId) {
                        CHRONICLE_MEDIA_ROOT_ID -> {
                            // Check login state and display appropriate message if not fully logged in
                            val loginState = plexLoginRepo.loginEvent.value?.peekContent()
                            if (loginState != LOGGED_IN_FULLY) {
                                Timber.w("[AndroidAuto] Not logged in fully, showing message. State: $loginState")
                                val errorMessage =
                                    when (loginState) {
                                        NOT_LOGGED_IN -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_access_error_not_logged_in),
                                                subtitle = "Open the Chronicle app on your phone to log in",
                                                iconRes = R.drawable.ic_lock_white_24dp,
                                                mediaId = "__error_not_logged_in__",
                                            )
                                        }
                                        LOGGED_IN_NO_USER_CHOSEN -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_access_error_no_user_chosen),
                                                iconRes = R.drawable.ic_error_outline_white,
                                                mediaId = "__error_no_user__",
                                            )
                                        }
                                        LOGGED_IN_NO_SERVER_CHOSEN -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_access_error_no_server_chosen),
                                                iconRes = R.drawable.ic_error_outline_white,
                                                mediaId = "__error_no_server__",
                                            )
                                        }
                                        LOGGED_IN_NO_LIBRARY_CHOSEN -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_access_error_no_library_chosen),
                                                iconRes = R.drawable.ic_error_outline_white,
                                                mediaId = "__error_no_library__",
                                            )
                                        }
                                        else -> {
                                            makeMessageItem(
                                                title = "Please open Chronicle app to complete setup",
                                                iconRes = R.drawable.ic_error_outline_white,
                                                mediaId = "__error_unknown__",
                                            )
                                        }
                                    }
                                result.sendResult(mutableListOf(errorMessage))
                                return@withContext
                            }

                            Timber.d("[AndroidAuto] Loading root categories")
                            result.sendResult(
                                (
                                    listOf(
                                        makeBrowsable(
                                            getString(R.string.auto_category_recently_listened),
                                            R.drawable.ic_recent,
                                        ),
                                    ) +
                                        listOf(
                                            makeBrowsable(
                                                getString(R.string.auto_category_offline),
                                                R.drawable.ic_cloud_download_white,
                                            ),
                                        ) +
                                        listOf(
                                            makeBrowsable(
                                                getString(R.string.auto_category_recently_added),
                                                R.drawable.ic_add,
                                            ),
                                        ) +
                                        listOf(
                                            makeBrowsable(
                                                getString(R.string.auto_category_library),
                                                R.drawable.nav_library,
                                            ),
                                        )
                                ).toMutableList(),
                            )
                        }
                        getString(R.string.auto_category_recently_listened) -> {
                            Timber.d("[AndroidAuto] Loading recently listened")
                            val recentlyListened = bookRepository.getRecentlyListenedAsync()
                            val items =
                                recentlyListened
                                    .filterNotNull()
                                    .map { it.toMediaItem(plexConfig) }
                                    .toMutableList()
                            Timber.d("[AndroidAuto] Loaded ${items.size} recently listened items")
                            result.sendResult(items)
                        }
                        getString(R.string.auto_category_recently_added) -> {
                            Timber.d("[AndroidAuto] Loading recently added")
                            val recentlyAdded = bookRepository.getRecentlyAddedAsync()
                            val items =
                                recentlyAdded
                                    .filterNotNull()
                                    .map { it.toMediaItem(plexConfig) }
                                    .toMutableList()
                            Timber.d("[AndroidAuto] Loaded ${items.size} recently added items")
                            result.sendResult(items)
                        }
                        getString(R.string.auto_category_library) -> {
                            Timber.d("[AndroidAuto] Loading full library")
                            val books = bookRepository.getAllBooksAsync()
                            val items =
                                books
                                    .filterNotNull()
                                    .map { it.toMediaItem(plexConfig) }
                                    .toMutableList()
                            Timber.d("[AndroidAuto] Loaded ${items.size} library items")
                            result.sendResult(items)
                        }
                        getString(R.string.auto_category_offline) -> {
                            Timber.d("[AndroidAuto] Loading offline content")
                            val offline = bookRepository.getCachedAudiobooksAsync()
                            val items =
                                offline
                                    .filterNotNull()
                                    .map { it.toMediaItem(plexConfig) }
                                    .toMutableList()
                            Timber.d("[AndroidAuto] Loaded ${items.size} offline items")
                            result.sendResult(items)
                        }
                        else -> {
                            Timber.w("[AndroidAuto] Unknown parentId in onLoadChildren: $parentId")
                            result.sendResult(mutableListOf())
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[AndroidAuto] Error loading children for parentId: $parentId")
                result.sendResult(mutableListOf())
            }
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        Timber.i("[AndroidAuto] onSearch: query='$query'")

        if (!prefsRepo.allowAuto) {
            Timber.w("[AndroidAuto] Search rejected - Android Auto is disabled")
            result.sendResult(mutableListOf())
            return
        }

        if (query.isBlank()) {
            Timber.w("[AndroidAuto] Empty search query")
            result.sendResult(mutableListOf())
            return
        }

        result.detach()
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            try {
                withContext(Dispatchers.IO) {
                    val books = bookRepository.searchAsync(query)
                    val items =
                        books
                            .filterNotNull()
                            .map { it.toMediaItem(plexConfig) }
                            .toMutableList()
                    Timber.d("[AndroidAuto] Search found ${items.size} results for: $query")
                    result.sendResult(items)
                }
            } catch (e: Exception) {
                Timber.e(e, "[AndroidAuto] Error searching for: $query")
                result.sendResult(mutableListOf())
            }
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        Timber.i("[AndroidAuto] onGetRoot: package=$clientPackageName, uid=$clientUid")

        val isClientLegal = packageValidator.isKnownCaller(clientPackageName, clientUid) || BuildConfig.DEBUG

        val extras =
            Bundle().apply {
                putBoolean(
                    CHRONICLE_MEDIA_SEARCH_SUPPORTED,
                    isClientLegal && prefsRepo.allowAuto && plexLoginRepo.loginEvent.value?.peekContent() == LOGGED_IN_FULLY,
                )
                mediaBrowserCompatStringField("EXTRA_MEDIA_SEARCH_SUPPORTED")?.let { putBoolean(it, true) }
                mediaBrowserCompatStringField("EXTRA_SUGGESTED_PRESENTATION_DISPLAY_HINT")?.let { putBoolean(it, true) }
                val focusKey = mediaBrowserCompatStringField("EXTRA_MEDIA_FOCUS")
                val focusValue = mediaBrowserCompatIntField("FOCUS_FULL")
                if (focusKey != null && focusValue != null) {
                    putInt(focusKey, focusValue)
                }
            }

        return when {
            !prefsRepo.allowAuto -> {
                Timber.w("[AndroidAuto] Access denied - Android Auto is disabled")
                setSessionCustomErrorMessage(
                    getString(R.string.auto_access_error_auto_is_disabled),
                )
                BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
            }
            !isClientLegal -> {
                Timber.w("[AndroidAuto] Access denied - invalid client: $clientPackageName")
                setSessionCustomErrorMessage(
                    getString(R.string.auto_access_error_invalid_client),
                )
                BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
            }
            else -> {
                // Return normal root even if not logged in - onLoadChildren() will display appropriate message
                if (plexLoginRepo.loginEvent.value?.peekContent() != LOGGED_IN_FULLY) {
                    Timber.w("[AndroidAuto] Not fully logged in - will show message in browse tree")
                } else {
                    Timber.d("[AndroidAuto] Access granted")
                    setSessionCustomErrorMessage(null)
                }
                BrowserRoot(CHRONICLE_MEDIA_ROOT_ID, extras)
            }
        }
    }

    private val playerEventListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.e("Exoplayer playback error: $error")
                val errorIntent = Intent(ACTION_PLAYBACK_ERROR)
                errorIntent.putExtra(PLAYBACK_ERROR_MESSAGE, error.message)
                localBroadcastManager.sendBroadcast(errorIntent)
                setSessionCustomErrorMessage(error.message)
                updateSessionPlaybackState()
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                // Update playing state in controller
                serviceScope.launch {
                    playbackStateController.updatePlayingState(playWhenReady)
                }
                updateSessionPlaybackState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Update playing state in controller
                serviceScope.launch {
                    playbackStateController.updatePlayingState(isPlaying)
                }
                updateSessionPlaybackState()
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                currentPlayer?.let {
                    updateSessionMetadataFromPlayer(it)
                    updateSessionPlaybackState()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        Timber.i("Playing next track")
                        // Update track progress
                        val trackId = mediaController.metadata.id
                        if (trackId != null && trackId != TRACK_NOT_FOUND.toString()) {
                            val plexState = PLEX_STATE_PLAYING
                            withContext(Dispatchers.IO) {
                                val bookId = trackRepository.getBookIdForTrack(trackId.toInt())
                                val track = trackRepository.getTrackAsync(trackId.toInt())
                                val tracks = trackRepository.getTracksForAudiobookAsync(bookId)

                                if (tracks.getDuration() == tracks.getProgress()) {
                                    mediaController.transportControls.stop()
                                }
                                progressUpdater.updateProgress(
                                    trackId.toInt(),
                                    plexState,
                                    track?.duration ?: 0L,
                                    true,
                                )
                            }
                        }
                    }
                }
                currentPlayer?.let { updateSessionMetadataFromPlayer(it) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_IDLE) {
                    setSessionCustomErrorMessage(null)
                }
                updateSessionPlaybackState()
                if (playbackState != Player.STATE_ENDED) {
                    return
                }
                Timber.i("Player STATE ENDED")
                serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                    withContext(Dispatchers.IO) {
                        // get track through tracklistmanager b/c metadata will be empty
                        val activeTrack = trackListManager.trackList.getActiveTrack()
                        if (activeTrack.id != MediaItemTrack.EMPTY_TRACK.id) {
                            progressUpdater.updateProgress(
                                activeTrack.id,
                                PLEX_STATE_STOPPED,
                                activeTrack.duration,
                                true,
                            )
                        }
                    }
                }
            }
        }

    private fun switchToPlayer(player: Player) {
        if (player == currentPlayer) {
            Timber.i("NOT SWITCHING PLAYER")
            return
        }
        Timber.i("SWITCHING PLAYER to $player")

        val prevPlayer: Player? = currentPlayer

        prevPlayer?.removeListener(playerEventListener)
        if (prevPlayer?.playbackState == Player.STATE_ENDED) {
            prevPlayer.stop()
        }

        currentPlayer = player
        mediaSessionCallback.currentPlayer = player

        prevPlayer?.let {
            val previousIndex = it.currentMediaItemIndex
            if (previousIndex != C.INDEX_UNSET) {
                player.seekTo(previousIndex, it.currentPosition)
            } else {
                player.seekTo(it.currentPosition)
            }
            player.playWhenReady = it.playWhenReady
        }

        player.addListener(playerEventListener)

        prevPlayer?.takeIf { it != player }?.let {
            if (it.playbackState != Player.STATE_ENDED) {
                it.stop()
            }
            it.clearMediaItems()
        }

        updateSessionMetadataFromPlayer(player)
        updateSessionPlaybackState()
        invalidatePlaybackParams()
    }

    override fun stopService() {
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    override fun stopForegroundService(removeNotification: Boolean) {
        stopForegroundCompat(removeNotification)
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val stopMode =
                if (removeNotification) {
                    Service.STOP_FOREGROUND_REMOVE
                } else {
                    Service.STOP_FOREGROUND_DETACH
                }
            stopForeground(stopMode)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    override fun onChapterChange(chapter: local.oss.chronicle.data.model.Chapter) {
        Timber.i("Chapter changed to: ${chapter.title}")
        // Dispatch to main thread since we're accessing the player (ExoPlayer requires main thread access)
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            currentPlayer?.let { player ->
                // [ChapterDebug] Log the chapter change with player context (must be on main thread)
                val playerPosition = player.currentPosition
                val isPlaying = player.isPlaying
                val playbackState = player.playbackState

                Timber.d(
                    "[ChapterDebug] MediaPlayerService.onChapterChange: " +
                        "newChapter='${chapter.title}' (idx=${chapter.index}), " +
                        "chapterRange=[${chapter.startTimeOffset} - ${chapter.endTimeOffset}], " +
                        "playerPosition=$playerPosition, " +
                        "isPlaying=$isPlaying, " +
                        "playbackState=$playbackState",
                )

                // [ChapterDebug] Check if player position is actually in the new chapter range
                val isPositionInChapter = playerPosition in chapter.startTimeOffset..chapter.endTimeOffset
                if (!isPositionInChapter) {
                    Timber.w(
                        "[ChapterDebug] WARNING: playerPosition=$playerPosition is NOT in chapter range [${chapter.startTimeOffset} - ${chapter.endTimeOffset}]! " +
                            "This may indicate the chapter was set before seek completed.",
                    )
                }

                updateSessionMetadataFromPlayer(player)
                updateSessionPlaybackState()
            }
        }
    }
}

interface ServiceController {
    fun stopService()
}

interface ForegroundServiceController {
    fun startForeground(
        nowPlayingNotification: Int,
        notification: Notification,
    )

    fun stopForegroundService(removeNotification: Boolean)
}
