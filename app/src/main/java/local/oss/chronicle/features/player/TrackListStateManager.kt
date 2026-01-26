package local.oss.chronicle.features.player

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.getActiveTrack
import local.oss.chronicle.data.model.getTrackStartTime
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max

/**
 * Shadows the state of tracks in the queue in order to calculate seeks for
 * [AudiobookMediaSessionCallback] with information that exoplayer's window management doesn't
 * have (access to track durations outside of current track).
 *
 * Thread-safe implementation using Kotlin coroutines Mutex for state protection.
 */
class TrackListStateManager {
    /**
     * Immutable state representation for thread-safe access.
     * All mutations must go through the mutex to ensure thread-safety.
     *
     * This is exposed publicly to allow atomic state access patterns.
     * Since it's immutable, it's safe to pass around and read from any thread.
     */
    data class State(
        val trackList: List<MediaItemTrack> = emptyList(),
        val currentTrackIndex: Int = 0,
        val currentTrackProgress: Long = 0L,
    ) {
        val currentTrack: MediaItemTrack
            get() = trackList[currentTrackIndex]

        /**
         * The number of milliseconds between current playback position and the start of the first
         * track. This is not authoritative, as [MediaItemTrack.duration] is not necessarily correct
         */
        val currentBookPosition: Long
            get() = trackList.getTrackStartTime(currentTrack) + currentTrackProgress
    }

    private val mutex = Mutex()

    @Volatile
    private var _state = State()

    // ========== Backward-compatible property accessors ==========

    /** The list of [MediaItemTrack]s currently playing */
    var trackList: List<MediaItemTrack>
        get() = _state.trackList
        set(value) {
            // Synchronous setter for backward compatibility
            // Callers should migrate to setTrackList() suspend function
            _state = _state.copy(trackList = value)
        }

    /** The index of the current track within [trackList] */
    val currentTrackIndex: Int
        get() = _state.currentTrackIndex

    /** The number of milliseconds from the start of the currently playing track */
    val currentTrackProgress: Long
        get() = _state.currentTrackProgress

    /**
     * The number of milliseconds between current playback position and the start of the first
     * track. This is not authoritative, as [MediaItemTrack.duration] is not necessarily correct
     */
    val currentBookPosition: Long
        get() = _state.currentBookPosition

    // ========== Thread-safe mutation methods ==========

    /**
     * Set the track list. Thread-safe version.
     * Prefer this over the property setter for consistent thread-safety.
     */
    suspend fun setTrackList(tracks: List<MediaItemTrack>) =
        mutex.withLock {
            _state = _state.copy(trackList = tracks)
        }

    /**
     * Update [currentTrackIndex] to [activeTrackIndex] and [currentTrackProgress] to
     * [offsetFromTrackStart]. Thread-safe suspension function.
     *
     * **Prefer this suspend version when calling from coroutine context.**
     */
    suspend fun updatePosition(
        activeTrackIndex: Int,
        offsetFromTrackStart: Long,
    ) = mutex.withLock {
        if (activeTrackIndex >= _state.trackList.size) {
            throw IndexOutOfBoundsException(
                "Cannot set current track index = $activeTrackIndex if tracklist.size == ${_state.trackList.size}",
            )
        }
        _state =
            _state.copy(
                currentTrackIndex = activeTrackIndex,
                currentTrackProgress = offsetFromTrackStart,
            )
    }

    /**
     * Update [currentTrackIndex] to [activeTrackIndex] and [currentTrackProgress] to
     * [offsetFromTrackStart]. Blocking version for backward compatibility.
     *
     * **Deprecated:** Use the suspend version when possible. This blocking version
     * is provided for gradual migration of calling code.
     */
    fun updatePositionBlocking(
        activeTrackIndex: Int,
        offsetFromTrackStart: Long,
    ) = runBlocking {
        updatePosition(activeTrackIndex, offsetFromTrackStart)
    }

    /**
     * Update position based on tracks in [trackList], picking the one with the most recent
     * [MediaItemTrack.lastViewedAt]. Thread-safe suspension function.
     *
     * **Prefer this suspend version when calling from coroutine context.**
     */
    suspend fun seekToActiveTrack() =
        mutex.withLock {
            Timber.i("Seeking to active track")
            val activeTrack = _state.trackList.getActiveTrack()
            _state =
                _state.copy(
                    currentTrackIndex = _state.trackList.indexOf(activeTrack),
                    currentTrackProgress = activeTrack.progress,
                )
        }

    /**
     * Update position based on tracks in [trackList], picking the one with the most recent
     * [MediaItemTrack.lastViewedAt]. Blocking version for backward compatibility.
     *
     * **Deprecated:** Use the suspend version when possible. This blocking version
     * is provided for gradual migration of calling code.
     */
    fun seekToActiveTrackBlocking() =
        runBlocking {
            seekToActiveTrack()
        }

    /**
     * Seeks forwards or backwards in the playlist by [offsetMillis] millis.
     * Thread-safe suspension function.
     *
     * **Prefer this suspend version when calling from coroutine context.**
     */
    suspend fun seekByRelative(offsetMillis: Long) =
        mutex.withLock {
            if (offsetMillis >= 0) {
                _state = seekForwards(_state, offsetMillis)
            } else {
                _state = seekBackwards(_state, abs(offsetMillis))
            }
        }

    /**
     * Seeks forwards or backwards in the playlist by [offsetMillis] millis.
     * Blocking version for backward compatibility.
     *
     * **Deprecated:** Use the suspend version when possible. This blocking version
     * is provided for gradual migration of calling code.
     */
    fun seekByRelativeBlocking(offsetMillis: Long) =
        runBlocking {
            seekByRelative(offsetMillis)
        }

    // ========== Atomic state access methods ==========

    /**
     * Atomically read the current state and apply a transformation.
     * Use this when you need to read multiple state properties atomically.
     *
     * Example:
     * ```
     * val (index, progress) = manager.withState { state ->
     *     Pair(state.currentTrackIndex, state.currentTrackProgress)
     * }
     * ```
     */
    suspend fun <T> withState(block: (State) -> T): T =
        mutex.withLock {
            block(_state)
        }

    /**
     * Atomically update state with a transformation function.
     * Use this for complex state updates that depend on current state.
     *
     * Example:
     * ```
     * manager.updateState { currentState ->
     *     currentState.copy(currentTrackProgress = newProgress)
     * }
     * ```
     */
    suspend fun updateState(transform: (State) -> State) =
        mutex.withLock {
            _state = transform(_state)
        }

    /**
     * Get an immutable snapshot of the current state.
     * This is atomic - all fields come from the same point in time.
     */
    suspend fun getCurrentState(): State =
        mutex.withLock {
            _state
        }

    // ========== Private helper methods (pure functions) ==========

    /**
     * Seek backwards by [offset] ms. [offset] must be a positive [Long].
     * Pure function - returns new state without modifying input.
     */
    private fun seekBackwards(
        state: State,
        offset: Long,
    ): State {
        check(offset >= 0) { "Attempted to seek by a negative number: $offset" }
        var offsetRemaining =
            offset + (state.trackList[state.currentTrackIndex].duration - state.currentTrackProgress)
        for (index in state.currentTrackIndex downTo 0) {
            if (offsetRemaining < state.trackList[index].duration) {
                return state.copy(
                    currentTrackIndex = index,
                    currentTrackProgress = max(0, state.trackList[index].duration - offsetRemaining),
                )
            } else {
                offsetRemaining -= state.trackList[index].duration
            }
        }
        return state.copy(
            currentTrackIndex = 0,
            currentTrackProgress = 0,
        )
    }

    /**
     * Seek forwards by [offset] ms. [offset] must be a positive [Long].
     * Pure function - returns new state without modifying input.
     */
    private fun seekForwards(
        state: State,
        offset: Long,
    ): State {
        check(offset >= 0) { "Attempted to seek by a negative number: $offset" }
        var offsetRemaining = offset + state.currentTrackProgress
        for (index in state.currentTrackIndex until state.trackList.size) {
            if (offsetRemaining < state.trackList[index].duration) {
                return state.copy(
                    currentTrackIndex = index,
                    currentTrackProgress = offsetRemaining,
                )
            } else {
                offsetRemaining -= state.trackList[index].duration
            }
        }
        return state.copy(
            currentTrackIndex = state.trackList.size - 1,
            currentTrackProgress = state.trackList.lastOrNull()?.duration ?: 0L,
        )
    }
}
