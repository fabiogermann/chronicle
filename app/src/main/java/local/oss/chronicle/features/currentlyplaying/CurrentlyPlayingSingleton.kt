package local.oss.chronicle.features.currentlyplaying

import local.oss.chronicle.data.model.*
import local.oss.chronicle.data.model.asChapterList
import local.oss.chronicle.data.model.getChapterAt
import local.oss.chronicle.features.player.OnChapterChangeListener as NewOnChapterChangeListener
import local.oss.chronicle.features.player.PlaybackStateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A global store of state containing information on the [Audiobook]/[MediaItemTrack]/[Chapter]
 * currently playing and the relevant playback information.
 * 
 * **ARCHITECTURE CHANGE (PR 2.2):**
 * This singleton now derives all its state from [PlaybackStateController] rather than
 * maintaining its own state. This ensures a single source of truth and prevents
 * state inconsistencies (Critical Issue C1: Chapter detection using stale DB progress).
 * 
 * For reactive observation, prefer using [state] StateFlow directly.
 * For legacy code, the [book], [track], and [chapter] StateFlows still work.
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
 * Implementation of [CurrentlyPlaying]. 
 * 
 * **Post-Refactor Architecture:**
 * - All state is derived from [PlaybackStateController.state]
 * - No internal mutable state (except for backward compatibility flows)
 * - Chapter detection uses current position from controller, not stale DB data
 * - [update] method is deprecated in favor of direct controller updates
 * 
 * **Migration Path:**
 * - Old code using [book], [track], [chapter] flows: continues to work
 * - New code should observe [state] directly from this singleton or controller
 * - [update] is a no-op with deprecation warning
 */
@ExperimentalCoroutinesApi
@Singleton
class CurrentlyPlayingSingleton @Inject constructor(
    private val playbackStateController: PlaybackStateController
) : CurrentlyPlaying {
    
    // Use Dispatchers.Default for tests compatibility - will be replaced by Main dispatcher in production
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // ========================
    // Backward Compatibility Flows
    // ========================
    // These flows derive from PlaybackStateController for legacy code compatibility
    
    private val _book = MutableStateFlow(EMPTY_AUDIOBOOK)
    override val book: StateFlow<Audiobook> = _book
    
    private val _track = MutableStateFlow(EMPTY_TRACK)
    override val track: StateFlow<MediaItemTrack> = _track
    
    private val _chapter = MutableStateFlow(EMPTY_CHAPTER)
    override val chapter: StateFlow<Chapter> = _chapter

    /**
     * Exposes the controller's StateFlow for reactive observation.
     * **Prefer this over individual book/track/chapter flows in new code.**
     */
    val state: StateFlow<local.oss.chronicle.features.player.PlaybackState>
        get() = playbackStateController.state

    private var listener: OnChapterChangeListener? = null

    init {
        // Bridge controller state to backward compatibility flows
        playbackStateController.state.onEach { state ->
            _book.value = state.audiobook ?: EMPTY_AUDIOBOOK
            
            // Update track with current position from state
            val currentTrack = state.currentTrack
            _track.value = if (currentTrack != null) {
                currentTrack.copy(progress = state.currentTrackPositionMs)
            } else {
                EMPTY_TRACK
            }
            
            _chapter.value = state.currentChapter ?: EMPTY_CHAPTER
        }.launchIn(scope)
        
        // Bridge chapter change events from controller to legacy listener
        playbackStateController.addChapterChangeListener(
            object : NewOnChapterChangeListener {
                override fun onChapterChanged(
                    previousChapter: Chapter?,
                    newChapter: Chapter?,
                    chapterIndex: Int
                ) {
                    if (newChapter != null) {
                        listener?.onChapterChange(newChapter)
                    }
                }
            }
        )
    }

    override fun setOnChapterChangeListener(listener: OnChapterChangeListener) {
        this.listener = listener
    }

    /**
     * Updates the current playback state with track, book, and chapter information.
     *
     * **TEMPORARY RESTORATION:**
     * This method was refactored to be a no-op as part of the state management migration,
     * but the migration to PlaybackStateController integration in MediaPlayerService was
     * never completed. This has been restored to fix the broken UI data flow.
     *
     * **Future TODO:** Complete the migration by:
     * 1. Injecting PlaybackStateController in MediaPlayerService
     * 2. Calling playbackStateController.updatePosition() from ExoPlayer callbacks
     * 3. Then this method can be converted back to a no-op
     *
     * @param track The current track being played (with current progress)
     * @param book The audiobook being played
     * @param tracks All tracks in the audiobook
     */
    @Deprecated(
        message = "State management migration incomplete. This method remains functional until PlaybackStateController is integrated in MediaPlayerService.",
        replaceWith = ReplaceWith(
            "playbackStateController.updatePosition(trackIndex, positionMs)",
            "kotlinx.coroutines.launch"
        ),
        level = DeprecationLevel.WARNING
    )
    override fun update(
        track: MediaItemTrack,
        book: Audiobook,
        tracks: List<MediaItemTrack>,
    ) {
        // Update book
        _book.value = book
        
        // Update track with current progress
        _track.value = track
        
        // Calculate and update current chapter
        val previousChapter = _chapter.value
        
        val chapters = if (book.chapters.isNotEmpty()) {
            book.chapters
        } else {
            tracks.asChapterList()
        }
        
        val currentChapter = if (chapters.isNotEmpty()) {
            chapters.getChapterAt(track.id.toLong(), track.progress)
        } else {
            EMPTY_CHAPTER
        }
        
        _chapter.value = currentChapter
        
        // Notify listener if chapter changed
        if (previousChapter.id != currentChapter.id && currentChapter != EMPTY_CHAPTER) {
            listener?.onChapterChange(currentChapter)
        }
    }
}
