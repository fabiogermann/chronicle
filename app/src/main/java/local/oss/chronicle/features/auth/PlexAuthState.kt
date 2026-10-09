package local.oss.chronicle.features.auth

/**
 * Immutable state representation for the Plex OAuth authentication flow.
 *
 * This sealed class represents all possible states during the Chrome Custom Tabs
 * OAuth flow, from initialization through completion (success or failure).
 */
sealed class PlexAuthState {
    /**
     * Initial state before authentication begins.
     */
    object Idle : PlexAuthState()

    /**
     * State while creating a PIN with the Plex API.
     */
    object CreatingPin : PlexAuthState()

    /**
     * State after PIN is created and browser is ready to be launched.
     * Waiting for user to complete authentication in the browser.
     *
     * @property pinId The PIN identifier for polling
     * @property pinCode The PIN code displayed to the user (unused in CCT flow)
     * @property authUrl The OAuth URL to open in Chrome Custom Tabs
     */
    data class WaitingForUser(
        val pinId: Long,
        val pinCode: String,
        val authUrl: String,
    ) : PlexAuthState()

    /**
     * State while polling the Plex API for authentication token.
     *
     * @property pinId The PIN identifier being polled
     * @property elapsedMs Time elapsed since authentication started (for timeout detection)
     * @property pinCode The PIN code the user can enter at plex.tv/link if the deep-link
     *   redirect doesn't fire (e.g. on devices whose App Links are broken, such as some
     *   Vivo/Funtouch builds). Surfaced so the UI can offer a manual-linking fallback.
     * @property shouldShowManualFallback True once polling has run long enough that the
     *   deep link is unlikely to fire — the UI should then offer the manual-linking path.
     */
    data class Polling(
        val pinId: Long,
        val elapsedMs: Long,
        val pinCode: String = "",
        val shouldShowManualFallback: Boolean = false,
    ) : PlexAuthState()

    /**
     * Terminal state: Authentication completed successfully.
     *
     * @property authToken The obtained authentication token
     */
    data class Success(
        val authToken: String = "",
    ) : PlexAuthState()

    /**
     * Terminal state: Authentication failed with an error.
     *
     * @property message Human-readable error message
     * @property throwable Optional exception that caused the error
     */
    data class Error(
        val message: String,
        val throwable: Throwable? = null,
    ) : PlexAuthState()

    /**
     * Terminal state: Authentication timed out (2 minutes).
     */
    object Timeout : PlexAuthState()

    /**
     * Terminal state: Authentication was cancelled by the user.
     *
     * @property reason Reason for cancellation (e.g., "User cancelled", "Back button pressed")
     */
    data class Cancelled(
        val reason: String = "User cancelled",
    ) : PlexAuthState()

    /**
     * Returns true if this state is a terminal state (no further transitions expected).
     */
    fun isTerminal(): Boolean =
        when (this) {
            is Success, is Error, is Timeout, is Cancelled -> true
            is Idle, is CreatingPin, is WaitingForUser, is Polling -> false
        }
}
