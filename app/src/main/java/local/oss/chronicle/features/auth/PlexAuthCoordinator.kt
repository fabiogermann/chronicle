package local.oss.chronicle.features.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import timber.log.Timber
import javax.inject.Inject

/**
 * State machine coordinator for Chrome Custom Tabs OAuth flow.
 *
 * This coordinator manages the entire authentication lifecycle:
 * 1. Creates a PIN via Plex API
 * 2. Builds OAuth URL with [PlexAuthUrlBuilder]
 * 3. Signals UI to launch Chrome Custom Tabs (via state change)
 * 4. Polls Plex API for authentication token
 * 5. Handles browser return callback to expedite polling
 * 6. Enforces 2-minute timeout
 *
 * **State Machine Transitions:**
 * ```
 * Idle → CreatingPin → WaitingForUser → Polling → Success/Error/Timeout/Cancelled
 * ```
 *
 * **Threading:** All API calls and state updates happen on the provided [scope].
 *
 * **Lifecycle:**
 * - Create coordinator (typically in Fragment/ViewModel)
 * - Call [startAuth] to begin flow
 * - Observe [state] for UI updates
 * - Call [dispose] when done (cleanup)
 *
 * @param plexLoginRepo Repository for Plex API calls (PIN creation and token polling)
 * @param scope Coroutine scope for async operations (use viewModelScope for ViewModels)
 */
class PlexAuthCoordinator
    @Inject
    constructor(
        private val plexLoginRepo: IPlexLoginRepo,
        private val scope: CoroutineScope,
    ) {
        companion object {
            /** Default polling interval in milliseconds */
            const val POLLING_INTERVAL_MS = 1500L

            /** Expedited polling interval after browser return (milliseconds) */
            const val EXPEDITED_POLLING_INTERVAL_MS = 200L

            /** Authentication timeout (2 minutes in milliseconds) */
            const val TIMEOUT_MS = 120_000L

            /**
             * How long to poll before flagging that the manual-linking fallback should be
             * offered. Deep-link return is near-instant when it works, so if the user has
             * been waiting this long the App Link almost certainly didn't fire.
             */
            const val MANUAL_FALLBACK_AFTER_MS = 20_000L
        }

        private val _state = MutableStateFlow<PlexAuthState>(PlexAuthState.Idle)

        /**
         * Current authentication state as a StateFlow.
         *
         * Collectors will receive updates as the auth flow progresses through:
         * Idle → CreatingPin → WaitingForUser → Polling → (Success|Error|Timeout|Cancelled)
         */
        val state: StateFlow<PlexAuthState> = _state.asStateFlow()

        private var pollingJob: Job? = null
        private var startTime: Long = 0
        private var pollingInterval = POLLING_INTERVAL_MS

        /**
         * Starts the OAuth authentication flow.
         *
         * **State Transitions:**
         * 1. Idle → CreatingPin (while calling Plex API)
         * 2. CreatingPin → WaitingForUser (PIN created, ready to launch browser)
         * 3. WaitingForUser → Polling (automatically starts polling)
         * 4. Polling → Success/Error/Timeout (based on polling results)
         *
         * **Error Handling:**
         * - PIN creation failure → Error state
         * - Network errors during polling → continues polling (transient failures)
         * - Timeout after 2 minutes → Timeout state
         *
         * @return The StateFlow that emits auth state changes
         */
        suspend fun startAuth(): StateFlow<PlexAuthState> {
            if (_state.value !is PlexAuthState.Idle) {
                Timber.w("Auth already in progress, current state: ${_state.value}")
                return state
            }

            // Register with singleton for deep link callbacks
            AuthCoordinatorSingleton.register(this)

            _state.value = PlexAuthState.CreatingPin

            try {
                // Create PIN via PlexLoginRepo
                val oAuthResponse = plexLoginRepo.postOAuthPin()
                if (oAuthResponse == null) {
                    Timber.e("Failed to create authentication PIN - received null response")
                    _state.value = PlexAuthState.Error("Failed to create authentication PIN")
                    return state
                }

                Timber.i(
                    "OAuth PIN created: id=${oAuthResponse.id}, code=${oAuthResponse.code}",
                )

                // Build OAuth URL using PlexAuthUrlBuilder
                val authUrl =
                    PlexAuthUrlBuilder.buildOAuthUrl(
                        oAuthResponse.clientIdentifier,
                        oAuthResponse.code,
                    )

                // Transition to WaitingForUser - this signals the UI to launch browser
                _state.value =
                    PlexAuthState.WaitingForUser(
                        pinId = oAuthResponse.id,
                        pinCode = oAuthResponse.code,
                        authUrl = authUrl,
                    )

                // Start polling automatically
                startPolling(oAuthResponse.id, oAuthResponse.code)
            } catch (e: Exception) {
                Timber.e(e, "Error creating auth PIN")
                _state.value =
                    PlexAuthState.Error(
                        "Failed to start authentication: ${e.message}",
                        e,
                    )
            }

            return state
        }

        /**
         * Starts polling the Plex API for authentication token.
         *
         * Polls at [POLLING_INTERVAL_MS] intervals (1.5 seconds by default).
         * Can be expedited to [EXPEDITED_POLLING_INTERVAL_MS] via [onBrowserReturned].
         *
         * **Termination Conditions:**
         * - Token received (Success state)
         * - Timeout after [TIMEOUT_MS] (2 minutes)
         * - Job cancelled via [cancelAuth] or [dispose]
         *
         * @param pinId The PIN identifier to poll
         * @param pinCode The PIN code for the manual-linking fallback (plex.tv/link)
         */
        private fun startPolling(pinId: Long, pinCode: String) {
            startTime = System.currentTimeMillis()
            pollingInterval = POLLING_INTERVAL_MS

            pollingJob =
                scope.launch {
                    // Give UI time to observe WaitingForUser and launch Chrome Custom Tab
                    delay(POLLING_INTERVAL_MS)

                    var pollIteration = 0L
                    while (isActive) {
                        pollIteration++
                        val elapsed = System.currentTimeMillis() - startTime

                        // Check timeout (2 minutes)
                        if (elapsed > TIMEOUT_MS) {
                            Timber.w("OAuth authentication timed out after ${elapsed}ms")
                            _state.value = PlexAuthState.Timeout
                            AuthCoordinatorSingleton.unregister()
                            break
                        }

                        // Update state with current elapsed time. Carry the pin code and flag the
                        // manual fallback once polling has run long enough that the deep link is
                        // unlikely to fire (broken App Links, e.g. some Vivo/Funtouch devices).
                        // Use the poll iteration count (not wall-clock) so it's deterministic
                        // under test virtual time and immune to clock skew.
                        _state.value =
                            PlexAuthState.Polling(
                                pinId = pinId,
                                elapsedMs = elapsed,
                                pinCode = pinCode,
                                shouldShowManualFallback = pollIteration * pollingInterval >= MANUAL_FALLBACK_AFTER_MS,
                            )

                        try {
                            // Poll for token via PlexLoginRepo
                            plexLoginRepo.checkForOAuthAccessToken()

                            // Check if login succeeded by inspecting login state
                            val loginState = plexLoginRepo.loginEvent.value?.peekContent()
                            if (loginState != null &&
                                loginState != IPlexLoginRepo.LoginState.NOT_LOGGED_IN &&
                                loginState != IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS &&
                                loginState != IPlexLoginRepo.LoginState.FAILED_TO_LOG_IN
                            ) {
                                Timber.i("OAuth token obtained successfully, login state: $loginState")
                                _state.value = PlexAuthState.Success()
                                AuthCoordinatorSingleton.unregister()
                                break
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error during OAuth polling (continuing...)")
                            // Don't fail immediately - network errors might be transient
                            // Continue polling until timeout or success
                        }

                        // Wait before next poll (uses current polling interval)
                        delay(pollingInterval)
                    }
                }
        }

        /**
         * Called when the browser returns to the app via deep link.
         *
         * This is invoked by [AuthCoordinatorSingleton] when [AuthReturnActivity]
         * receives the `https://auth.chronicleapp.net/callback` Android App Link
         * (or `chronicle://auth/callback` as legacy fallback).
         *
         * **Behavior:**
         * - Reduces polling interval to [EXPEDITED_POLLING_INTERVAL_MS] (200ms)
         * - Triggers immediate polling check after brief delay
         * - Improves perceived responsiveness of auth flow
         *
         * **Note:** This DOES NOT bypass polling - it merely expedites it.
         * The polling mechanism remains the source of truth for authentication status.
         */
        fun onBrowserReturned() {
            Timber.i("Browser returned, expediting polling to ${EXPEDITED_POLLING_INTERVAL_MS}ms")
            pollingInterval = EXPEDITED_POLLING_INTERVAL_MS
        }

        /**
         * Cancels the ongoing authentication flow.
         *
         * **Use Cases:**
         * - User presses back/cancel button
         * - User navigates away from login screen
         * - App needs to abort authentication for any reason
         *
         * **State Transition:** Current state → Cancelled
         */
        fun cancelAuth() {
            Timber.i("Authentication cancelled by user")
            pollingJob?.cancel()
            pollingJob = null
            _state.value = PlexAuthState.Cancelled()
            AuthCoordinatorSingleton.unregister()
        }

        /**
         * Resets the coordinator to Idle state.
         *
         * Cancels any ongoing polling and clears state.
         * Useful for retrying authentication after failure/timeout.
         *
         * **State Transition:** Any state → Idle
         */
        fun reset() {
            Timber.d("Resetting auth coordinator to Idle state")
            pollingJob?.cancel()
            pollingJob = null
            _state.value = PlexAuthState.Idle
            AuthCoordinatorSingleton.unregister()
        }

        /**
         * Disposes of the coordinator and cleans up resources.
         *
         * **Must be called** when the coordinator is no longer needed
         * (e.g., in ViewModel.onCleared() or Fragment.onDestroy()).
         *
         * **Cleanup Actions:**
         * - Cancels polling job
         * - Unregisters from singleton
         * - Does NOT reset state (preserves terminal state for observation)
         */
        fun dispose() {
            Timber.d("Disposing PlexAuthCoordinator")
            pollingJob?.cancel()
            pollingJob = null
            AuthCoordinatorSingleton.unregister()
        }
    }
