package local.oss.chronicle.injection.modules

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.support.v4.media.RatingCompat.RATING_NONE
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.MediaSessionCompat.*
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CompletableJob
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.R
import local.oss.chronicle.application.MainActivity
import local.oss.chronicle.data.sources.plex.APP_NAME
import local.oss.chronicle.data.sources.plex.PlaybackUrlResolver
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.features.player.*
import local.oss.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_BACK_BUFFER_DURATION_MILLIS
import local.oss.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_MAX_BUFFER_DURATION_MILLIS
import local.oss.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_MIN_BUFFER_DURATION_MILLIS
import local.oss.chronicle.injection.scopes.ServiceScope
import local.oss.chronicle.util.PackageValidator
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
    fun serviceJob(): CompletableJob = service.serviceJob

    @Provides
    @ServiceScope
    fun serviceScope() = service.serviceScope

    @Provides
    @ServiceScope
    fun exoPlayer(): ExoPlayer =
        ExoPlayer.Builder(attributedContext).setLoadControl(
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
    fun plexDataSourceFactory(plexPrefs: PlexPrefsRepo): DefaultHttpDataSource.Factory {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
        dataSourceFactory.setUserAgent(Util.getUserAgent(service, APP_NAME))

        dataSourceFactory.setDefaultRequestProperties(
            mapOf(
                "X-Plex-Platform" to "Android",
                "X-Plex-Provides" to "player",
                "X-Plex_Client-Name" to APP_NAME,
                "X-Plex-Client-Identifier" to plexPrefs.uuid,
                "X-Plex-Version" to BuildConfig.VERSION_NAME,
                "X-Plex-Product" to APP_NAME,
                "X-Plex-Platform-Version" to Build.VERSION.RELEASE,
                "X-Plex-Device" to Build.MODEL,
                "X-Plex-Device-Name" to Build.MODEL,
                "X-Plex-Token" to (
                    plexPrefs.server?.accessToken ?: plexPrefs.user?.authToken
                        ?: plexPrefs.accountAuthToken
                ),
                // Adding X-Plex-Session-Identifier to help server track playback sessions
                // This allows the Plex server to make transcoding decisions if needed
                "X-Plex-Session-Identifier" to plexPrefs.uuid,
                // Client profile declares what audio formats this app can directly play
                // Generic profile already has transcode targets, so only adding direct play profile
                "X-Plex-Client-Profile-Extra" to
                    "add-direct-play-profile(type=musicProfile&container=mp4,m4a,m4b,mp3,flac,ogg,opus&audioCodec=aac,mp3,flac,vorbis,opus&videoCodec=*&subtitleCodec=*)",
            ),
        )

        return dataSourceFactory
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
    fun playbackUrlResolver(
        plexMediaService: PlexMediaService,
        plexConfig: PlexConfig,
    ): PlaybackUrlResolver = PlaybackUrlResolver(plexMediaService, plexConfig)
}
