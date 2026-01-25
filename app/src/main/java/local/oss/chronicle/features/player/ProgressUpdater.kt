package local.oss.chronicle.features.player

import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.work.*
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.*
import local.oss.chronicle.data.model.MediaItemTrack.Companion.EMPTY_TRACK
import local.oss.chronicle.data.sources.plex.PlexSyncScrobbleWorker
import local.oss.chronicle.data.sources.plex.model.getDuration
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.features.player.ProgressUpdater.Companion.BOOK_FINISHED_END_OFFSET_MILLIS
import local.oss.chronicle.features.player.ProgressUpdater.Companion.NETWORK_CALL_FREQUENCY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * Responsible for updating playback progress of the current book/track to the local DB and to the
 * server at regular intervals while a [MediaControllerCompat] indicates that playback is active
 */
interface ProgressUpdater {
    /** Begin regularly updating the local DB and remote servers while playback is active */
    fun startRegularProgressUpdates()

    /**
     * Updates local DBs to reflect the track/book progress passed in via [progress] for a track
     * with id [trackId] and a book containing that track.
     *
     * Updates book/track progress in remote DB if [forceNetworkUpdate] == true or every
     * [NETWORK_CALL_FREQUENCY] calls. Pass additional [playbackState] for [PlexSyncScrobbleWorker]
     * to pass playback state to server
     */
    fun updateProgress(
        trackId: Int,
        playbackState: String,
        progress: Long,
        forceNetworkUpdate: Boolean,
    )

    /** Update progress without providing any parameters */
    fun updateProgressWithoutParameters()

    /** Cancels regular progress updates */
    fun cancel()

    companion object {
        val BOOK_FINISHED_END_OFFSET_MILLIS = 2.minutes.inWholeMilliseconds

        /**
         * The frequency which the remote server is updated at: once for every [NETWORK_CALL_FREQUENCY]
         * calls to the local database
         */
        const val NETWORK_CALL_FREQUENCY = 10
    }
}

class SimpleProgressUpdater
    @Inject
    constructor(
        private val serviceScope: CoroutineScope,
        private val trackRepository: ITrackRepository,
        private val bookRepository: IBookRepository,
        private val workManager: WorkManager,
        private val prefsRepo: PrefsRepo,
        private val currentlyPlaying: CurrentlyPlaying,
        private val playbackStateController: PlaybackStateController,
    ) : ProgressUpdater {
        var mediaController: MediaControllerCompat? = null

        /** Frequency of progress updates */
        private val updateProgressFrequencyMs = 1000L

        /** Tracks the number of times [updateLocalProgress] has been called this session */
        private var tickCounter = 0L

        private val handler = Handler(Looper.getMainLooper())
        private val updateProgressAction = { startRegularProgressUpdates() }

        /**
         * Updates the current track/audiobook progress in the local db and remote server.
         *
         * If we are within 2 minutes of the end of the book and playback stops, mark the book as
         * "finished" by updating the first track to progress=0 and setting it as the most recently viewed
         */
        override fun startRegularProgressUpdates() {
            requireNotNull(mediaController).let { controller ->
                // Use the absolute track position from extras as baseline
                val absolutePositionFromExtras = controller.playbackState?.extras?.getLong(MediaPlayerService.EXTRA_ABSOLUTE_TRACK_POSITION) ?: 0L
                
                // Get auto-calculated chapter-relative position
                val chapterRelativePosition = controller.playbackState?.currentPlayBackPosition ?: 0L
                
                // Get chapter info to convert chapter-relative to absolute
                val chapter = currentlyPlaying.chapter.value
                
                // Calculate absolute position: use chapter-relative auto-calculated position + chapter start
                // Fall back to extras if no valid chapter
                val playerPosition = if (chapter != EMPTY_CHAPTER && chapterRelativePosition >= 0) {
                    chapter.startTimeOffset + chapterRelativePosition
                } else {
                    // No chapter or invalid chapter-relative position: use absolute from extras
                    absolutePositionFromExtras
                }
                val isPlaying = controller.playbackState?.isPlaying != false
                val playbackState = controller.playbackState?.state
                
                // [ChapterDebug] Log the raw player state before updating progress
                Timber.d("[ChapterDebug] startRegularProgressUpdates: " +
                    "playerPosition=$playerPosition, " +
                    "isPlaying=$isPlaying, " +
                    "playbackState=$playbackState, " +
                    "trackId=${controller.metadata?.id}")
                
                if (isPlaying) {
                    serviceScope.launch(context = serviceScope.coroutineContext + Dispatchers.IO) {
                        updateProgress(
                            controller.metadata?.id?.toInt() ?: TRACK_NOT_FOUND,
                            MediaPlayerService.PLEX_STATE_PLAYING,
                            playerPosition,
                            false,
                        )
                    }
                }
            }
            handler.postDelayed(updateProgressAction, updateProgressFrequencyMs)
        }

        override fun updateProgressWithoutParameters() {
            val controller = mediaController ?: return
            val playbackState =
                when (controller.playbackState.state) {
                    PlaybackStateCompat.STATE_PLAYING -> MediaPlayerService.PLEX_STATE_PLAYING
                    PlaybackStateCompat.STATE_PAUSED -> MediaPlayerService.PLEX_STATE_PAUSED
                    PlaybackStateCompat.STATE_STOPPED -> MediaPlayerService.PLEX_STATE_PAUSED
                    else -> ""
                }
            val currentTrack = controller.metadata.id?.toInt() ?: return
            
            // Use the absolute track position from extras as baseline
            val absolutePositionFromExtras = controller.playbackState?.extras?.getLong(MediaPlayerService.EXTRA_ABSOLUTE_TRACK_POSITION) ?: 0L
            
            // Get auto-calculated chapter-relative position
            val chapterRelativePosition = controller.playbackState?.currentPlayBackPosition ?: 0L
            
            // Get chapter info to convert chapter-relative to absolute
            val chapter = currentlyPlaying.chapter.value
            
            // Calculate absolute position: use chapter-relative auto-calculated position + chapter start
            // Fall back to extras if no valid chapter
            val playerPosition = if (chapter != EMPTY_CHAPTER && chapterRelativePosition >= 0) {
                chapter.startTimeOffset + chapterRelativePosition
            } else {
                // No chapter or invalid chapter-relative position: use absolute from extras
                absolutePositionFromExtras
            }
            updateProgress(
                currentTrack,
                playbackState,
                playerPosition,
                false,
            )
        }

        override fun updateProgress(
            trackId: Int,
            playbackState: String,
            progress: Long,
            forceNetworkUpdate: Boolean,
        ) {
            Timber.i("Updating progress")
            if (trackId == TRACK_NOT_FOUND) {
                return
            }
            val currentTime = System.currentTimeMillis()
            serviceScope.launch(context = serviceScope.coroutineContext + Dispatchers.IO) {
                val bookId: Int = trackRepository.getBookIdForTrack(trackId)
                val track: MediaItemTrack = trackRepository.getTrackAsync(trackId) ?: EMPTY_TRACK

                // No reason to update if the track or book doesn't exist in the DB
                if (trackId == TRACK_NOT_FOUND || bookId == NO_AUDIOBOOK_FOUND_ID) {
                    return@launch
                }

                val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
                val book = bookRepository.getAudiobookAsync(bookId)
                val bookProgress = tracks.getTrackStartTime(track) + progress
                val bookDuration = tracks.getDuration()

                // [ChapterDebug] Log the difference between player position and DB saved progress
                Timber.d("[ChapterDebug] ProgressUpdater.updateProgress: " +
                    "playerPosition=$progress, " +
                    "dbProgress=${track.progress}, " +
                    "diff=${progress - track.progress}, " +
                    "trackId=$trackId, " +
                    "playbackState=$playbackState")

                // Find track index in the track list (0-based)
                val trackIndex = tracks.indexOfFirst { it.id == trackId }
                if (trackIndex >= 0) {
                    // Update PlaybackStateController with current position
                    playbackStateController.updatePosition(trackIndex, progress)
                }

                // Use the current progress from the player, not the stale value from DB
                val trackWithCurrentProgress = track.copy(progress = progress)
                
                // [ChapterDebug] Calculate expected chapter based on player position vs DB position
                val chapters = if (book?.chapters?.isNotEmpty() == true) book.chapters else tracks.asChapterList()
                if (chapters.isNotEmpty()) {
                    val chapterFromPlayerPos = chapters.getChapterAt(trackId.toLong(), progress)
                    val chapterFromDbPos = chapters.getChapterAt(trackId.toLong(), track.progress)
                    Timber.d("[ChapterDebug] ProgressUpdater BEFORE update: " +
                        "chapterFromPlayerPos='${chapterFromPlayerPos.title}' (idx=${chapterFromPlayerPos.index}), " +
                        "chapterFromDbPos='${chapterFromDbPos.title}' (idx=${chapterFromDbPos.index}), " +
                        "willUsePlayerPos=$progress")
                }
                
                // Keep calling currentlyPlaying.update() for backward compatibility
                // This will be a no-op once CurrentlyPlayingSingleton fully observes the controller
                currentlyPlaying.update(
                    book = book ?: EMPTY_AUDIOBOOK,
                    track = trackWithCurrentProgress,
                    tracks = tracks,
                )

                // Update local DB
                if (!prefsRepo.debugOnlyDisableLocalProgressTracking) {
                    updateLocalProgress(
                        bookId = bookId,
                        currentTime = currentTime,
                        trackProgress = progress,
                        trackId = trackId,
                        bookProgress = bookProgress,
                        tracks = tracks,
                        bookDuration = bookDuration,
                    )
                } else {
                    Timber.w("[ProgressSaveRestoreDebug] SKIPPED WRITE: debugOnlyDisableLocalProgressTracking is TRUE - " +
                        "progress will NOT be saved to database (bookId=$bookId, trackId=$trackId, progress=$progress)")
                }

                // Update server once every [networkCallFrequency] calls, or when manual updates
                // have been specifically requested
                if (forceNetworkUpdate || tickCounter % NETWORK_CALL_FREQUENCY == 0L) {
                    updateNetworkProgress(
                        trackId,
                        playbackState,
                        progress,
                        bookProgress,
                    )
                }
            }
        }

        private fun updateNetworkProgress(
            trackId: Int,
            playbackState: String,
            trackProgress: Long,
            bookProgress: Long,
        ) {
            val syncWorkerConstraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val inputData =
                PlexSyncScrobbleWorker.makeWorkerData(
                    trackId = trackId,
                    playbackState = playbackState,
                    trackProgress = trackProgress,
                    bookProgress = bookProgress,
                )
            val worker =
                OneTimeWorkRequestBuilder<PlexSyncScrobbleWorker>()
                    .setInputData(inputData)
                    .setConstraints(syncWorkerConstraints)
                    .setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                    .build()

            workManager
                .beginUniqueWork(trackId.toString(), ExistingWorkPolicy.REPLACE, worker)
                .enqueue()
        }

        private suspend fun updateLocalProgress(
            bookId: Int,
            currentTime: Long,
            trackProgress: Long,
            trackId: Int,
            bookProgress: Long,
            tracks: List<MediaItemTrack>,
            bookDuration: Long,
        ) {
            tickCounter++
            bookRepository.updateProgress(bookId, currentTime, bookProgress)
            trackRepository.updateTrackProgress(trackProgress, trackId, currentTime)
            Timber.d("[ProgressSaveRestoreDebug] WRITE: bookId=$bookId, trackId=$trackId, " +
                "trackProgress=$trackProgress, bookProgress=$bookProgress, timestamp=$currentTime")
            bookRepository.updateTrackData(
                bookId,
                bookProgress,
                tracks.getDuration(),
                tracks.size,
            )
            if (bookDuration - bookProgress <= BOOK_FINISHED_END_OFFSET_MILLIS) {
                Timber.i("Marking $bookId as finished")
                bookRepository.setWatched(bookId)
            }
        }

        override fun cancel() {
            handler.removeCallbacks(updateProgressAction)
        }
    }
