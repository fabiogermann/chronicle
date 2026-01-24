package local.oss.chronicle.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of an ongoing seek operation.
 */
data class SeekState(
    val isActive: Boolean = false,
    val targetTrackIndex: Int = 0,
    val targetPositionMs: Long = 0L,
    val startedAtMs: Long = 0L
) {
    companion object {
        val IDLE = SeekState()
        
        /** Maximum time a seek can be active before timing out */
        const val SEEK_TIMEOUT_MS = 5000L
    }
    
    /**
     * Whether this seek has timed out.
     */
    fun hasTimedOut(): Boolean {
        if (!isActive) return false
        return System.currentTimeMillis() - startedAtMs > SEEK_TIMEOUT_MS
    }
}

/**
 * Manages seek operations to prevent position update races.
 * 
 * When a seek is in progress:
 * - State updates are blocked until seek completes
 * - A timeout ensures we don't block forever if seek callback fails
 * 
 * Usage:
 * ```
 * // When starting a seek
 * seekHandler.onSeekStart(targetTrack, targetPosition)
 * player.seekTo(targetTrack, targetPosition)
 * 
 * // When seek completes (in Player.Listener.onPositionDiscontinuity)
 * seekHandler.onSeekComplete()
 * 
 * // When checking if state updates should be processed
 * if (seekHandler.shouldProcessStateUpdate(reportedPosition)) {
 *     // Update state
 * }
 * ```
 */
@Singleton
class SeekHandler @Inject constructor() {
    
    private val _seekState = MutableStateFlow(SeekState.IDLE)
    
    /**
     * Current seek state for observation.
     */
    val seekState: StateFlow<SeekState> = _seekState.asStateFlow()
    
    /**
     * Whether a seek is currently in progress.
     */
    val isSeeking: Boolean
        get() {
            val state = _seekState.value
            // Auto-clear timed out seeks
            if (state.isActive && state.hasTimedOut()) {
                Timber.w("Seek timed out, clearing state")
                _seekState.value = SeekState.IDLE
                return false
            }
            return state.isActive
        }
    
    /**
     * Called when a seek operation starts.
     * 
     * @param targetTrackIndex The track being seeked to
     * @param targetPositionMs The position being seeked to within the track
     */
    fun onSeekStart(targetTrackIndex: Int, targetPositionMs: Long) {
        Timber.d("Seek started: track=$targetTrackIndex, position=${targetPositionMs}ms")
        _seekState.value = SeekState(
            isActive = true,
            targetTrackIndex = targetTrackIndex,
            targetPositionMs = targetPositionMs,
            startedAtMs = System.currentTimeMillis()
        )
    }
    
    /**
     * Called when a seek operation completes successfully.
     */
    fun onSeekComplete() {
        Timber.d("Seek completed")
        _seekState.value = SeekState.IDLE
    }
    
    /**
     * Called when a seek operation fails or is cancelled.
     */
    fun onSeekCancelled() {
        Timber.d("Seek cancelled")
        _seekState.value = SeekState.IDLE
    }
    
    /**
     * Determines whether a position update should be processed.
     * 
     * Returns false if:
     * - A seek is active AND
     * - The reported position doesn't match the seek target (within tolerance)
     * 
     * This prevents stale position updates from interfering with seeks.
     * 
     * @param reportedTrackIndex The track index from the position update
     * @param reportedPositionMs The position from the position update
     * @param toleranceMs Position tolerance for matching (default 500ms)
     * @return true if the state update should be processed
     */
    fun shouldProcessStateUpdate(
        reportedTrackIndex: Int,
        reportedPositionMs: Long,
        toleranceMs: Long = 500L
    ): Boolean {
        val state = _seekState.value
        
        // Not seeking - always process
        if (!state.isActive) return true
        
        // Check for timeout
        if (state.hasTimedOut()) {
            Timber.w("Seek timed out during state check, allowing update")
            _seekState.value = SeekState.IDLE
            return true
        }
        
        // If track index matches target, check position tolerance
        if (reportedTrackIndex == state.targetTrackIndex) {
            val positionDiff = kotlin.math.abs(reportedPositionMs - state.targetPositionMs)
            if (positionDiff <= toleranceMs) {
                // Position is close to target - seek is effectively complete
                Timber.d("Position close to seek target, completing seek")
                onSeekComplete()
                return true
            }
        }
        
        // Still seeking, don't process this stale update
        Timber.d("Blocking stale position update during seek: reported=$reportedPositionMs, target=${state.targetPositionMs}")
        return false
    }
    
    /**
     * Resets the seek handler state.
     * Call when clearing playback or on errors.
     */
    fun reset() {
        _seekState.value = SeekState.IDLE
    }
}
