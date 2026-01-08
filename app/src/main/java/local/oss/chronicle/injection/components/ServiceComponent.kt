package local.oss.chronicle.injection.components

import android.app.PendingIntent
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import dagger.Component
import local.oss.chronicle.data.sources.plex.PlexMediaRepository
import local.oss.chronicle.data.sources.plex.PlexMediaSource
import local.oss.chronicle.features.player.*
import local.oss.chronicle.injection.modules.ServiceModule
import local.oss.chronicle.injection.scopes.ServiceScope
import local.oss.chronicle.util.PackageValidator
import kotlinx.coroutines.CoroutineScope

@OptIn(kotlin.time.ExperimentalTime::class)
@ServiceScope
@Component(dependencies = [AppComponent::class], modules = [ServiceModule::class])
interface ServiceComponent {
    fun progressUpdater(): ProgressUpdater

    fun exoPlayer(): ExoPlayer

    fun mediaSession(): MediaSessionCompat

    fun pendingIntent(): PendingIntent

    fun sleepTimer(): SleepTimer

    fun localBroadcastManager(): LocalBroadcastManager

    fun notificationManager(): NotificationManagerCompat

    fun notificationBuilder(): NotificationBuilder

    fun becomingNoisyReceiver(): BecomingNoisyReceiver

    fun mediaSessionCallback(): AudiobookMediaSessionCallback

    fun mediaSource(): PlexMediaRepository

    fun serviceScope(): CoroutineScope

    fun serviceController(): ServiceController

    fun plexDataSourceFactory(): DefaultHttpDataSource.Factory

    fun packageValidator(): PackageValidator

    fun foregroundServiceController(): ForegroundServiceController

    fun trackListManager(): TrackListStateManager

    fun mediaController(): MediaControllerCompat

    fun plexMediaSource(): PlexMediaSource

    fun inject(mediaPlayerService: MediaPlayerService)
}
