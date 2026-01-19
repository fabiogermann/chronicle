package local.oss.chronicle.features.currentlyplaying

import local.oss.chronicle.data.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Singleton

/**
 * A global store of state containing information on the [Audiobook]/[MediaItemTrack]/[Chapter]
 * currently playing and the relevant playback information.
 */
@ExperimentalCoroutinesApi
interface CurrentlyPlaying {
    val book: StateFlow<Audiobook>
    val track: StateFlow<MediaItemTrack>
    val chapter: StateFlow<Chapter>

    fun setOnChapterChangeListener(listener: OnChapterChangeListener)

    fun update(
        track: MediaItemTrack,
        book: Audiobook,
        tracks: List<MediaItemTrack>,
    )
}

interface OnChapterChangeListener {
    fun onChapterChange(chapter: Chapter)
}

/**
 * Implementation of [CurrentlyPlaying]. Values default to placeholder values until data is
 * made available (the user
 */
@ExperimentalCoroutinesApi
@Singleton
class CurrentlyPlayingSingleton : CurrentlyPlaying {
    override val book = MutableStateFlow(EMPTY_AUDIOBOOK)
    override val track = MutableStateFlow(EMPTY_TRACK)
    override val chapter = MutableStateFlow(EMPTY_CHAPTER)

    private var tracks: List<MediaItemTrack> = emptyList()
    private var chapters: List<Chapter> = emptyList()

    private var listener: OnChapterChangeListener? = null

    override fun setOnChapterChangeListener(listener: OnChapterChangeListener) {
        this.listener = listener
    }

    override fun update(
        track: MediaItemTrack,
        book: Audiobook,
        tracks: List<MediaItemTrack>,
    ) {
        this.book.value = book
        this.track.value = track

        this.tracks = tracks

        this.chapters =
            if (book.chapters.isNotEmpty()) {
                book.chapters
            } else {
                tracks.asChapterList()
            }

        if (tracks.isNotEmpty() && chapters.isNotEmpty()) {
            val previousChapter = this.chapter.value
            val chapter = chapters.getChapterAt(track.id.toLong(), track.progress)
            
            // [ChapterDebug] Log chapter calculation details
            Timber.d("[ChapterDebug] CurrentlyPlayingSingleton.update: " +
                "trackId=${track.id}, " +
                "trackProgress=${track.progress}, " +
                "previousChapter='${previousChapter.title}' (idx=${previousChapter.index}), " +
                "calculatedChapter='${chapter.title}' (idx=${chapter.index}), " +
                "chapterRange=[${chapter.startTimeOffset} - ${chapter.endTimeOffset}]")
            
            if (this.chapter.value != chapter) {
                Timber.d("[ChapterDebug] CHAPTER CHANGE: " +
                    "'${previousChapter.title}' (idx=${previousChapter.index}) -> " +
                    "'${chapter.title}' (idx=${chapter.index}), " +
                    "triggeredByProgress=${track.progress}")
                this.chapter.value = chapter
                listener?.onChapterChange(chapter)
            }
        }

        printDebug()
    }

    private fun printDebug() {
        Timber.i(
            "Currently Playing: track=${track.value.title}, index=${track.value.index}/${tracks.size}",
        )
    }
}
