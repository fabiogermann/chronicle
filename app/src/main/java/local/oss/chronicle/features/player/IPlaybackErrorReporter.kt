package local.oss.chronicle.features.player

/**
 * Interface for reporting playback errors to the MediaSession.
 * Used by AudiobookMediaSessionCallback to communicate errors back to MediaPlayerService.
 *
 * This interface allows the callback to report errors without directly depending on
 * MediaPlayerService, making it easier to test in isolation.
 */
interface IPlaybackErrorReporter {
    /**
     * Sets the playback state to STATE_ERROR and broadcasts the error message.
     * @param errorCode One of PlaybackStateCompat.ERROR_CODE_* constants
     * @param errorMessage User-facing error message to display
     */
    fun setPlaybackStateError(errorCode: Int, errorMessage: String)

    /**
     * Clears any existing error state when playback resumes.
     */
    fun clearPlaybackError()
}
