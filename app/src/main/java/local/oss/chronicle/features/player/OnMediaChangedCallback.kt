package local.oss.chronicle.features.player

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import androidx.core.app.NotificationManagerCompat
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.features.currentlyplaying.OnChapterChangeListener
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/** Responsible for observing changes in media metadata */
@ExperimentalCoroutinesApi
class OnMediaChangedCallback
    @Inject
    constructor(
        private val mediaController: MediaControllerCompat,
        private val serviceScope: CoroutineScope,
        private val notificationBuilder: NotificationBuilder,
        private val mediaSession: MediaSessionCompat,
        private val becomingNoisyReceiver: BecomingNoisyReceiver,
        private val notificationManager: NotificationManagerCompat,
        private val foregroundServiceController: ForegroundServiceController,
        private val serviceController: ServiceController,
        private val currentlyPlaying: CurrentlyPlaying,
        private val trackRepo: ITrackRepository,
        private val bookRepo: IBookRepository,
    ) : MediaControllerCompat.Callback(), OnChapterChangeListener {
        init {
            currentlyPlaying.setOnChapterChangeListener(this)
        }

        // Book ID, Track ID, Chapter ID

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            Timber.i("METADATA CHANGE")
            mediaController.playbackState?.let { state ->
                serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                    withContext(Dispatchers.IO) {
                        // OnMediaChangedCallback should NOT update currentlyPlaying
                        // ProgressUpdater is the single source of truth for progress updates
                        // This callback only rebuilds notifications based on current state
                        updateNotification(state.state)
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            Timber.i("Playback state changed to ${state?.stateName} ${System.currentTimeMillis()}")
            if (state == null) {
                return
            }
            serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                updateNotification(state.state)
            }
        }

        override fun onChapterChange(chapter: Chapter) {
            Timber.d(
                "onChapterChange called: chapter = [${chapter.index}] ${chapter.title} || current: [${currentlyPlaying.chapter.value.index}] ${currentlyPlaying.chapter.value.title}",
            )

            mediaController.playbackState?.let { state ->
                serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                    updateNotification(state.state)
                }
            }
        }

        private suspend fun updateNotification(state: Int) {
            val notification =
                if (mediaController.sessionToken != null) {
                    notificationBuilder.buildNotification(mediaSession.sessionToken)
                } else {
                    null
                }

            Timber.i("Created notif: $notification")

            when (state) {
                STATE_PLAYING, STATE_BUFFERING -> {
                    becomingNoisyReceiver.register()
                    if (notification != null) {
                        notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification)
                        foregroundServiceController.startForeground(
                            NOW_PLAYING_NOTIFICATION,
                            notification,
                        )
                    }
                }
                STATE_PAUSED -> {
                    becomingNoisyReceiver.unregister()
                    if (notification != null) {
                        notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification)
                        foregroundServiceController.startForeground(
                            NOW_PLAYING_NOTIFICATION,
                            notification,
                        )
                    }
                    // Enables dismiss-on-swipe when paused- swiping triggers the delete
                    // intent on the notification to be called, which kills the service
                    foregroundServiceController.stopForegroundService(false)
                }
                STATE_STOPPED -> {
                    // If playback has ended, fully stop the service.
                    Timber.i("Playback has finished, stopping service!")
                    notificationManager.cancel(NOW_PLAYING_NOTIFICATION)
                    foregroundServiceController.stopForegroundService(true)
                    serviceController.stopService()
                }
                else -> {
                    // When not actively playing media, notification becomes cancellable on swipe and
                    // we stop listening for audio interruptions
                    becomingNoisyReceiver.unregister()
                    foregroundServiceController.stopForegroundService(true)
                }
            }
        }
    }
