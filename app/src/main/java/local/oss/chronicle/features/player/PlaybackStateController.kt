package local.oss.chronicle.features.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.oss.chronicle.data.local.BookRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.MediaItemTrack
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for playback state.
 *
 * This controller:
 * 1. Manages immutable PlaybackState with thread-safe updates
 * 2. Exposes state as StateFlow for reactive observation
 * 3. Debounces database writes to avoid excessive I/O
 * 4. Notifies listeners on chapter changes
 *
 * Design Principles:
 * - ExoPlayer position is authoritative
 * - All state updates go through this controller
 * - State is immutable; updates create new instances
 * - Database writes are debounced and asynchronous
 *
 * Usage:
 * ```
 * // Observe state reactively
 * playbackStateController.state.collect { state ->
 *     updateUI(state)
 * }
 *
 * // Update from player
 * playbackStateController.updatePosition(trackIndex, positionMs)
 * ```
 */
@Singleton
class PlaybackStateController
    @Inject
    constructor(
        private val bookRepository: BookRepository,
        private val prefsRepo: PrefsRepo,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val mutex = Mutex()

        private val _state = MutableStateFlow(PlaybackState.EMPTY)

        /**
         * Current playback state as a StateFlow.
         * Observe this for reactive UI updates.
         */
        val state: StateFlow<PlaybackState> = _state.asStateFlow()

        /**
         * Current state value (for non-reactive access).
         */
        val currentState: PlaybackState
            get() = _state.value

        private val chapterChangeListeners = CopyOnWriteArrayList<OnChapterChangeListener>()

        // Debounce mechanism for DB writes
        private var dbWriteJob: Job? = null
        private var lastPersistedState: PlaybackState? = null

        companion object {
            /** Minimum interval between database writes in milliseconds */
            const val DB_WRITE_DEBOUNCE_MS = 3000L

            /** Minimum position change to trigger a DB write in milliseconds */
            const val MIN_POSITION_CHANGE_FOR_PERSIST_MS = 1000L

            private const val TAG = "PlaybackStateController"
        }

        // ========================
        // State Update Methods
        // ========================

        /**
         * Loads a new audiobook for playback.
         * This clears the current state and sets up the new audiobook.
         *
         * @param audiobook The audiobook to load
         * @param tracks List of tracks in the audiobook
         * @param chapters List of chapters in the audiobook
         * @param startTrackIndex Index of the track to start at (0-based)
         * @param startPositionMs Position within the track to start at
         */
        suspend fun loadAudiobook(
            audiobook: Audiobook,
            tracks: List<MediaItemTrack>,
            chapters: List<Chapter>,
            startTrackIndex: Int = 0,
            startPositionMs: Long = 0L,
        ) = mutex.withLock {
            Timber.d("$TAG: Loading audiobook: ${audiobook.title}")

            val newState =
                PlaybackState.fromAudiobook(
                    audiobook = audiobook,
                    tracks = tracks,
                    chapters = chapters,
                    startTrackIndex = startTrackIndex,
                    startPositionMs = startPositionMs,
                )

            val previousState = _state.value
            _state.value = newState

            // Check for chapter change
            notifyChapterChangeIfNeeded(previousState, newState)

            // Persist immediately when loading new audiobook
            persistStateToDatabase(newState, force = true)
        }

        /**
         * Updates the current position from ExoPlayer.
         * This is the primary method called during playback to update position.
         *
         * @param trackIndex Current track index (0-based)
         * @param positionMs Position within the current track in milliseconds
         */
        suspend fun updatePosition(
            trackIndex: Int,
            positionMs: Long,
        ) = mutex.withLock {
            val previousState = _state.value
            if (!previousState.hasMedia) return@withLock

            val newState = previousState.withPosition(trackIndex, positionMs)
            _state.value = newState

            // Check for chapter change
            notifyChapterChangeIfNeeded(previousState, newState)

            // Debounced DB write
            scheduleDatabaseWrite(newState)
        }

        /**
         * Updates the playing state.
         * Called when playback starts or pauses.
         *
         * @param isPlaying Whether playback is currently active
         */
        suspend fun updatePlayingState(isPlaying: Boolean) =
            mutex.withLock {
                val previousState = _state.value
                if (!previousState.hasMedia) return@withLock

                _state.value = previousState.withPlayingState(isPlaying)

                // Persist on pause/stop to ensure position is saved
                if (!isPlaying) {
                    persistStateToDatabase(_state.value, force = true)
                }
            }

        /**
         * Updates the playback speed.
         * Speed is persisted to SharedPreferences, not the database.
         *
         * @param speed Playback speed multiplier (0.5 to 3.0)
         */
        suspend fun updatePlaybackSpeed(speed: Float) =
            mutex.withLock {
                val previousState = _state.value
                if (!previousState.hasMedia) return@withLock

                _state.value = previousState.withPlaybackSpeed(speed)

                // Persist speed change to SharedPreferences
                persistPlaybackSpeed(speed)
            }

        /**
         * Clears the current playback state.
         * Called when playback is stopped or the service is destroyed.
         */
        suspend fun clear() =
            mutex.withLock {
                Timber.d("$TAG: Clearing playback state")

                // Persist current position before clearing
                val currentState = _state.value
                if (currentState.hasMedia) {
                    persistStateToDatabase(currentState, force = true)
                }

                _state.value = PlaybackState.EMPTY
                lastPersistedState = null
                dbWriteJob?.cancel()
            }

        // ========================
        // Read-Only Accessors
        // ========================

        /**
         * Returns the current position as a book position (absolute).
         */
        fun getBookPositionMs(): Long = _state.value.bookPositionMs

        /**
         * Returns the current track and position as a pair.
         */
        fun getCurrentTrackPosition(): Pair<Int, Long> {
            val state = _state.value
            return state.currentTrackIndex to state.currentTrackPositionMs
        }

        /**
         * Returns the current chapter, if any.
         */
        fun getCurrentChapter(): Chapter? = _state.value.currentChapter

        /**
         * Returns the current chapter index (0-based), or -1 if none.
         */
        fun getCurrentChapterIndex(): Int = _state.value.currentChapterIndex

        // ========================
        // Chapter Change Listeners
        // ========================

        /**
         * Adds a listener for chapter change events.
         */
        fun addChapterChangeListener(listener: OnChapterChangeListener) {
            chapterChangeListeners.add(listener)
        }

        /**
         * Removes a chapter change listener.
         */
        fun removeChapterChangeListener(listener: OnChapterChangeListener) {
            chapterChangeListeners.remove(listener)
        }

        private fun notifyChapterChangeIfNeeded(
            previousState: PlaybackState,
            newState: PlaybackState,
        ) {
            val previousChapter = previousState.currentChapter
            val newChapter = newState.currentChapter
            val newChapterIndex = newState.currentChapterIndex

            // Compare by chapter identity (startTimeOffset is unique per chapter)
            if (previousChapter?.startTimeOffset != newChapter?.startTimeOffset) {
                Timber.d("$TAG: Chapter changed from ${previousChapter?.title} to ${newChapter?.title}")
                chapterChangeListeners.forEach { listener ->
                    listener.onChapterChanged(previousChapter, newChapter, newChapterIndex)
                }
            }
        }

        // ========================
        // Database Persistence
        // ========================

        private fun scheduleDatabaseWrite(state: PlaybackState) {
            // Cancel any pending write
            dbWriteJob?.cancel()

            // Check if write is needed
            val lastPersisted = lastPersistedState
            if (lastPersisted != null &&
                !state.hasSignificantPositionChange(lastPersisted, MIN_POSITION_CHANGE_FOR_PERSIST_MS)
            ) {
                return
            }

            // Schedule debounced write
            dbWriteJob =
                scope.launch {
                    delay(DB_WRITE_DEBOUNCE_MS)
                    persistStateToDatabase(state, force = false)
                }
        }

        private fun persistStateToDatabase(
            state: PlaybackState,
            force: Boolean,
        ) {
            val audiobook = state.audiobook ?: return

            // Skip if not significant change (unless forced)
            val lastPersisted = lastPersistedState
            if (!force && lastPersisted != null &&
                !state.hasSignificantPositionChange(lastPersisted, MIN_POSITION_CHANGE_FOR_PERSIST_MS)
            ) {
                return
            }

            scope.launch {
                try {
                    // Update position in database
                    // BookRepository.updateProgress uses (bookId, currentTime, progress)
                    // where currentTime is the timestamp and progress is the book position in ms
                    bookRepository.updateProgress(
                        bookId = audiobook.id,
                        currentTime = System.currentTimeMillis(),
                        progress = state.bookPositionMs,
                    )

                    lastPersistedState = state
                    Timber.d(
                        "$TAG: Persisted state - track=${state.currentTrackIndex}, trackPos=${state.currentTrackPositionMs}ms, bookPos=${state.bookPositionMs}ms",
                    )
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to persist state to database")
                }
            }
        }

        private fun persistPlaybackSpeed(speed: Float) {
            scope.launch {
                try {
                    // Playback speed is stored globally in SharedPreferences
                    prefsRepo.playbackSpeed = speed
                    Timber.d("$TAG: Persisted playback speed: $speed")
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to persist playback speed")
                }
            }
        }

        // ========================
        // Utility Methods
        // ========================

        /**
         * Updates state atomically using a transform function.
         * Use this for complex state updates that need to read current state.
         *
         * @param transform Function that takes the current state and returns the new state
         */
        suspend fun updateState(transform: (PlaybackState) -> PlaybackState) =
            mutex.withLock {
                val previousState = _state.value
                val newState = transform(previousState)
                _state.value = newState

                notifyChapterChangeIfNeeded(previousState, newState)
                scheduleDatabaseWrite(newState)
            }

        /**
         * Performs an action with the current state, ensuring thread safety.
         * Use this when you need to read state and perform related actions atomically.
         */
        suspend fun <T> withState(action: (PlaybackState) -> T): T =
            mutex.withLock {
                action(_state.value)
            }
    }
