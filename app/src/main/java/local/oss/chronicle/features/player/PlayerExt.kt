package local.oss.chronicle.features.player

import android.view.Gravity
import android.widget.Toast
import androidx.media3.common.Player
import local.oss.chronicle.R
import local.oss.chronicle.application.Injector
import local.oss.chronicle.application.MILLIS_PER_SECOND
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import timber.log.Timber
import kotlin.math.abs

/**
 * Seek in the play queue by an offset of [durationMillis]. Positive [duration] seeks forwards,
 * negative [duration] seeks backwards
 */
fun Player.seekRelative(
    trackListStateManager: TrackListStateManager,
    durationMillis: Long,
) {
    // if seeking within the current track, no need to calculate seek
    if (durationMillis > 0 && (duration - currentPosition) > durationMillis) {
        Timber.i(
            "Seeking forwards within window: pos = $currentPosition, window duration = $duration, seek= $durationMillis",
        )
        seekTo(currentPosition + durationMillis)
    } else if (durationMillis < 0 && currentPosition > abs(durationMillis)) {
        Timber.i(
            "Seeking backwards within window: pos = $currentPosition, duration = $durationMillis",
        )
        seekTo(currentPosition + durationMillis)
    } else {
        // Defense-in-depth: guard against empty track list
        if (trackListStateManager.trackList.isEmpty()) {
            Timber.w("seekRelative called with empty track list, ignoring seek")
            return
        }

        Timber.i("Seeking via trackliststatemanager")
        trackListStateManager.updatePositionBlocking(currentMediaItemIndex, currentPosition)
        trackListStateManager.seekByRelativeBlocking(durationMillis)
        seekTo(trackListStateManager.currentTrackIndex, trackListStateManager.currentTrackProgress)
    }
}

/** Skip to next chapter */
fun Player.skipToNext(
    trackListStateManager: TrackListStateManager,
    currentlyPlaying: CurrentlyPlaying,
    progressUpdater: ProgressUpdater,
) {
    Timber.i("Player.skipToNext called")
    val chapters = currentlyPlaying.book.value.chapters
    // Defense-in-depth: guard against empty chapter list (e.g. book not yet loaded)
    if (chapters.isEmpty()) {
        Timber.w("skipToNext called with empty chapter list, ignoring")
        return
    }
    val currentChapterIndex = chapters.indexOf(currentlyPlaying.chapter.value)
    val nextChapterIndex = currentChapterIndex + 1
    if (nextChapterIndex in chapters.indices) {
        val nextChapter = chapters[nextChapterIndex]
        Timber.d(
            "NEXT CHAPTER: index=$nextChapterIndex id=${nextChapter.id} trackId=${nextChapter.trackId} offset=${nextChapter.startTimeOffset} title=${nextChapter.title}",
        )
        val containingTrack =
            trackListStateManager.trackList
                .firstOrNull {
                    it.id == nextChapter.trackId
                }
        val containingTrackIndex = trackListStateManager.trackList.indexOf(containingTrack)
        if (containingTrackIndex < 0) {
            Timber.w("skipToNext could not resolve track for chapter ${nextChapter.id}, ignoring")
            return
        }
        seekTo(containingTrackIndex, nextChapter.startTimeOffset + 300)
        progressUpdater.updateProgressWithoutParameters()
    } else {
        val toast =
            Toast.makeText(
                Injector.get().applicationContext(),
                R.string.skip_forwards_reached_last_chapter,
                Toast.LENGTH_LONG,
            )
        toast.setGravity(Gravity.BOTTOM, 0, 200)
        toast.show()
    }
}

/** Skip to previous chapter */
fun Player.skipToPrevious(
    trackListStateManager: TrackListStateManager,
    currentlyPlaying: CurrentlyPlaying,
    progressUpdater: ProgressUpdater,
) {
    Timber.i("Player.skipToPrevious called")
    val chapters = currentlyPlaying.book.value.chapters
    // Defense-in-depth: guard against empty chapter list (e.g. book not yet loaded)
    if (chapters.isEmpty()) {
        Timber.w("skipToPrevious called with empty chapter list, ignoring")
        return
    }
    val currentChapterIndex = chapters.indexOf(currentlyPlaying.chapter.value)
    var previousChapterIndex: Int =
        if ((currentPosition - currentlyPlaying.chapter.value.startTimeOffset) < (SKIP_TO_PREVIOUS_CHAPTER_THRESHOLD_SECONDS * MILLIS_PER_SECOND)) {
            Timber.d("skipToPrevious → skip to previous chapter")
            currentChapterIndex - 1
        } else {
            Timber.d("skipToPrevious → back to start of current chapter")
            currentChapterIndex
        }
    // Clamp into valid range: currentChapterIndex may be -1 if the current chapter
    // is not found, and previousChapterIndex may exceed bounds.
    previousChapterIndex = previousChapterIndex.coerceIn(0, chapters.lastIndex)
    val previousChapter = chapters[previousChapterIndex]
    Timber.d(
        "PREVIOUS CHAPTER: index=$previousChapterIndex id=${previousChapter.id} trackId=${previousChapter.trackId} offset=${previousChapter.startTimeOffset} title=${previousChapter.title}",
    )
    val containingTrack =
        trackListStateManager.trackList
            .firstOrNull {
                it.id == previousChapter.trackId
            }
    val containingTrackIndex = trackListStateManager.trackList.indexOf(containingTrack)
    if (containingTrackIndex < 0) {
        Timber.w("skipToPrevious could not resolve track for chapter ${previousChapter.id}, ignoring")
        return
    }
    seekTo(containingTrackIndex, previousChapter.startTimeOffset)
    progressUpdater.updateProgressWithoutParameters()
}
