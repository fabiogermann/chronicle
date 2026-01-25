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
     * **MIGRATION COMPLETE (PR 5.3):**
     * This method is now a no-op. All state updates are handled by PlaybackStateController.
     * The method signature is kept for backward compatibility with existing callers.
     *
     * State flow:
     * - ProgressUpdater calls playbackStateController.updatePosition()
     * - Controller updates its internal StateFlow
     * - CurrentlyPlayingSingleton observes controller.state in init block
     * - Backward compatibility flows are automatically updated
     *
     * @param track Ignored - for backward compatibility only
     * @param book Ignored - for backward compatibility only
     * @param tracks Ignored - for backward compatibility only
     */
    @Deprecated(
        message = "State is now managed by PlaybackStateController. This method is a no-op kept for backward compatibility.",
        replaceWith = ReplaceWith(
            "// State automatically updated via PlaybackStateController"
        ),
        level = DeprecationLevel.WARNING
    )
    override fun update(
        track: MediaItemTrack,
        book: Audiobook,
        tracks: List<MediaItemTrack>,
    ) {
        // No-op: State updates are handled by PlaybackStateController
        // The init block observes playbackStateController.state and updates the flows
        Timber.d("CurrentlyPlayingSingleton.update() called - delegating to PlaybackStateController (no-op)")
    }
}
