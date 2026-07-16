package local.oss.chronicle.injection.modules

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.support.v4.media.RatingCompat.RATING_NONE
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.MediaSessionCompat.*
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CompletableJob
import local.oss.chronicle.R
import local.oss.chronicle.application.MainActivity
import local.oss.chronicle.data.sources.plex.APP_NAME
import local.oss.chronicle.data.sources.plex.PlexHttpDataSourceFactory
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.features.player.*
import local.oss.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_BACK_BUFFER_DURATION_MILLIS
import local.oss.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_MAX_BUFFER_DURATION_MILLIS
import local.oss.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_MIN_BUFFER_DURATION_MILLIS
import local.oss.chronicle.injection.scopes.ServiceScope
import local.oss.chronicle.util.PackageValidator
import local.oss.chronicle.util.SecurityUtils
import timber.log.Timber
import kotlin.time.ExperimentalTime

@ExperimentalTime
@Module
class ServiceModule(private val service: MediaPlayerService) {
    companion object {
        // Attribution tag for audio operations (must match manifest declaration)
        private const val ATTRIBUTION_TAG_MEDIA_PLAYBACK = "chronicle_media_playback"
    }

    // Create attributed context for audio operations (required for API 31+/Android 12+)
    private val attributedContext: Context by lazy {
        service.createAttributionContext(ATTRIBUTION_TAG_MEDIA_PLAYBACK)
    }

    @Provides
    @ServiceScope
    fun service(): Service = service

    @Provides
    @ServiceScope
    fun serviceController(): ServiceController = service

    @Provides
    @ServiceScope
    fun errorReporter(): IPlaybackErrorReporter = service

    @Provides
    @ServiceScope
    fun serviceJob(): CompletableJob = service.serviceJob

    @Provides
    @ServiceScope
    fun serviceScope() = service.serviceScope

    @Provides
    @ServiceScope
    fun exoPlayer(): ExoPlayer =
        ExoPlayer.Builder(attributedContext)
            // Disable embedded ID3 metadata parsing (e.g. APIC artwork frames). Chronicle fetches
            // cover art separately via Plex, and cloning large embedded images into MediaMetadata
            // caused OutOfMemoryError in MediaMetadata.Builder.maybeSetArtworkData.
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    attributedContext,
                    androidx.media3.extractor.DefaultExtractorsFactory()
                        .setMp3ExtractorFlags(
                            androidx.media3.extractor.mp3.Mp3Extractor.FLAG_DISABLE_ID3_METADATA,
                        ),
                ),
            )
            .setLoadControl(
            // increase buffer size across the board as ExoPlayer defaults are set for video
            DefaultLoadControl.Builder().setBackBuffer(EXOPLAYER_BACK_BUFFER_DURATION_MILLIS, true)
                .setBufferDurationsMs(
                    EXOPLAYER_MIN_BUFFER_DURATION_MILLIS,
                    EXOPLAYER_MAX_BUFFER_DURATION_MILLIS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build(),
        ).build()

    @Provides
    @ServiceScope
    fun pendingIntent(): PendingIntent =
        service.packageManager.getLaunchIntentForPackage(service.packageName).let { sessionIntent ->
            sessionIntent?.putExtra(MainActivity.FLAG_OPEN_ACTIVITY_TO_CURRENTLY_PLAYING, true)
            PendingIntent.getActivity(
                service,
                MainActivity.REQUEST_CODE_OPEN_APP_TO_CURRENTLY_PLAYING,
                sessionIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
        }

    @Provides
    @ServiceScope
    fun mediaSession(launchActivityPendingIntent: PendingIntent): MediaSessionCompat =
        MediaSessionCompat(attributedContext, APP_NAME).apply {
            // Enable queue management; media buttons handled automatically on recent APIs
            setFlags(
                FLAG_HANDLES_QUEUE_COMMANDS,
            )
            service.sessionToken = sessionToken
            setSessionActivity(launchActivityPendingIntent)
            setRatingType(RATING_NONE)
            isActive = true
        }

    @Provides
    @ServiceScope
    fun localBroadcastManager() = LocalBroadcastManager.getInstance(service)

    @Provides
    @ServiceScope
    fun sleepTimerBroadcaster(): SleepTimer.SleepTimerBroadcaster = service

    @Provides
    @ServiceScope
    fun sleepTimer(simpleSleepTimer: SimpleSleepTimer): SleepTimer = simpleSleepTimer

    @Provides
    fun provideProgressUpdater(
        updater: SimpleProgressUpdater,
        mediaControllerCompat: MediaControllerCompat,
    ): ProgressUpdater =
        updater.apply {
            mediaController = mediaControllerCompat
        }

    @Provides
    @ServiceScope
    fun notificationManager(): NotificationManagerCompat = NotificationManagerCompat.from(service)

    @Provides
    @ServiceScope
    fun mediaController(session: MediaSessionCompat) = MediaControllerCompat(service, session.sessionToken)

    @Provides
    @ServiceScope
    fun becomingNoisyReceiver(session: MediaSessionCompat) = BecomingNoisyReceiver(service, session.sessionToken)

    @Provides
    @ServiceScope
    fun plexDataSourceFactory(plexPrefs: PlexPrefsRepo): PlexHttpDataSourceFactory {
        // Log token state at factory creation for race condition diagnosis
        val serverTokenHash = SecurityUtils.hashToken(plexPrefs.server?.accessToken)
        val userTokenHash = SecurityUtils.hashToken(plexPrefs.user?.authToken)
        val accountTokenHash = SecurityUtils.hashToken(plexPrefs.accountAuthToken)

        Timber.d(
            "[TokenInjection] plexDataSourceFactory provider called: " +
                "serverToken=$serverTokenHash, userToken=$userTokenHash, " +
                "accountToken=$accountTokenHash",
        )

        // Return custom factory that reads tokens lazily on each createDataSource() call
        // Phase 3: Return concrete type to allow setting currentLibraryId for library-aware token injection
        return PlexHttpDataSourceFactory(
            context = service.applicationContext,
            plexPrefsRepo = plexPrefs,
        )
    }

    @Provides
    @ServiceScope
    fun httpDataSourceFactory(concreteFactory: PlexHttpDataSourceFactory): HttpDataSource.Factory {
        // Provide interface type for dependencies that don't need library-aware functionality
        // This delegates to the same instance as plexDataSourceFactory()
        return concreteFactory
    }

    @Provides
    @ServiceScope
    fun packageValidator() = PackageValidator(service, R.xml.auto_allowed_callers)

    @Provides
    @ServiceScope
    fun foregroundServiceController(): ForegroundServiceController = service

    @Provides
    @ServiceScope
    fun mediaSessionCallback(callback: AudiobookMediaSessionCallback): Callback = callback

    @Provides
    @ServiceScope
    fun trackListManager(): TrackListStateManager = TrackListStateManager()

    @Provides
    @ServiceScope
    fun sensorManager(): SensorManager =
        service.getSystemService(
            Context.SENSOR_SERVICE,
        ) as SensorManager

    @Provides
    @ServiceScope
    fun toneManager(): ToneGenerator {
        // Use attributed context for audio operations
        val audioManager = attributedContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    }

    @Provides
    @ServiceScope
    fun voiceCommandBridgeAudio(serviceScope: kotlinx.coroutines.CoroutineScope): VoiceCommandBridgeAudio =
        VoiceCommandBridgeAudio(service, serviceScope)
}
