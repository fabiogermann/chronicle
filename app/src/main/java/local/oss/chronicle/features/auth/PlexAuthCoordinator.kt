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
        private val ticker: Ticker = Ticker { System.currentTimeMillis() },
    ) {
        /** Time source; fakeable so unit tests can drive wall-clock timeouts. */
        fun interface Ticker {
            fun now(): Long
        }

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
             *
             * Zero: the manual-linking info (plex.tv/link) is harmless and is offered from
             * the very first Polling emission. On devices where Android App Links never
             * verified (e.g. some Vivo/Funtouch builds) the deep link NEVER fires and the
             * user bounces between the frozen browser page and the app — they must not
             * have to wait 20 s, on the right screen at the right moment, to learn there
             * is another way in.
             */
            const val MANUAL_FALLBACK_AFTER_MS = 0L

            /**
             * Timeout once the user has switched to manual linking (plex.tv/link).
             * Entering a code from another device takes far longer than the in-browser
             * OAuth round-trip, so the 2-minute [TIMEOUT_MS] is too short; but the
             * non-strong PIN used for manual linking expires server-side after 900 s,
             * so we stop safely below that.
             */
            const val MANUAL_TIMEOUT_MS = 600_000L

            /**
             * Timeout when resuming polling of a PIN persisted before a process restart.
             * The PIN was already created some time ago and expires server-side after
             * 900–1800 s, so a resumed flow gets one minute: if the user finished auth
             * in the browser the very first poll succeeds; anything else means the PIN
             * is dead and the user should start a fresh flow.
             */
            const val RESUME_TIMEOUT_MS = 60_000L
        }

        /** The kind of PIN backing the current flow. */
        private enum class PinMode {
            /** Strong PIN for the app.plex.tv/auth browser flow (long code). */
            STRONG,

            /** Non-strong PIN for manual linking at plex.tv/link (4-char code). */
            MANUAL,

            /** A PIN persisted before a process restart, resumed via [resumeAuth]. */
            RESUMED,
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
        private var activePinMode = PinMode.STRONG
        private var activePinId: Long = -1L
        private var activePinCode: String = ""

        private fun activeTimeoutMs(): Long =
            when (activePinMode) {
                PinMode.STRONG -> TIMEOUT_MS
                PinMode.MANUAL -> MANUAL_TIMEOUT_MS
                PinMode.RESUMED -> RESUME_TIMEOUT_MS
            }

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
                // Create the STRONG PIN for the app.plex.tv/auth browser flow. Manual
                // linking uses a separate non-strong PIN (see [requestManualLinkCode]) —
                // plex.tv/link rejects the strong PIN's 25-char code.
                val oAuthResponse = plexLoginRepo.postOAuthPin(strong = true)
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
                activePinMode = PinMode.STRONG
                activePinId = oAuthResponse.id
                activePinCode = oAuthResponse.code
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
         * Switches the flow to manual linking at plex.tv/link.
         *
         * The browser OAuth PIN is *strong* — its code is 25 characters, which
         * plex.tv/link does not accept (it wants the 4-character code of a
         * non-strong PIN). So manual linking needs its own PIN: this creates a
         * non-strong PIN, repoints polling at it, and stretches the timeout to
         * [MANUAL_TIMEOUT_MS] (linking from another device takes longer than the
         * in-browser round-trip, but the non-strong PIN expires after 900 s).
         *
         * @return A fresh [PlexAuthState.Polling] carrying the 4-char code to display,
         *   or null if the PIN could not be created (network error) or the flow
         *   already reached a terminal state.
         */
        suspend fun requestManualLinkCode(): PlexAuthState.Polling? {
            if (_state.value.isTerminal()) {
                Timber.w("requestManualLinkCode called in terminal state ${_state.value}, ignoring")
                return null
            }
            val manualPin = plexLoginRepo.postOAuthPin(strong = false)
            if (manualPin == null) {
                Timber.e("Failed to create manual-linking PIN - received null response")
                return null
            }
            Timber.i("Manual-linking PIN created: id=${manualPin.id}, code length=${manualPin.code.length}")

            activePinMode = PinMode.MANUAL
            activePinId = manualPin.id
            activePinCode = manualPin.code
            // Restart the clock: from here the flow has the full MANUAL_TIMEOUT_MS
            // budget (the running poll loop reads startTime live).
            startTime = ticker.now()

            val polling =
                PlexAuthState.Polling(
                    pinId = manualPin.id,
                    elapsedMs = 0L,
                    pinCode = manualPin.code,
                    shouldShowManualFallback = true,
                )
            _state.value = polling
            return polling
        }

        /**
         * Resumes polling a PIN that was persisted before a process restart.
         *
         * If the OS kills the app while the user is authenticating in the browser
         * (aggressive on Vivo/Oppo/Xiaomi), the coordinator and its polling job die,
         * but the PIN id survives in PlexPrefsRepo.oAuthTempId. When the login screen
         * comes up again, polling that PIN lets an already-completed browser auth land
         * without forcing the user through a fresh PIN + browser round-trip (which is
         * the loop u/N3Pi_cs reported).
         *
         * Bounded by [RESUME_TIMEOUT_MS]: a PIN that yields nothing quickly is dead.
         *
         * @param pinId The persisted PIN id to resume polling
         * @return The StateFlow that emits auth state changes
         */
        fun resumeAuth(pinId: Long): StateFlow<PlexAuthState> {
            if (_state.value !is PlexAuthState.Idle) {
                Timber.w("resumeAuth called while state is ${_state.value}, ignoring")
                return state
            }
            Timber.i("Resuming polling of persisted OAuth PIN id=$pinId after process restart")
            AuthCoordinatorSingleton.register(this)

            activePinMode = PinMode.RESUMED
            activePinId = pinId
            activePinCode = ""
            startPolling(pinId, "", pollImmediately = true)
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
         * - Timeout after [activeTimeoutMs] (mode-dependent)
         * - Job cancelled via [cancelAuth] or [dispose]
         *
         * @param pinId The PIN identifier to poll
         * @param pinCode The PIN code shown for manual linking (empty while the browser
         *   flow is in charge; the manual code only exists after [requestManualLinkCode])
         * @param pollImmediately When true the first poll happens right away (used by
         *   [resumeAuth], where the user just returned and the PIN may die any minute)
         */
        private fun startPolling(
            pinId: Long,
            pinCode: String,
            pollImmediately: Boolean = false,
        ) {
            startTime = ticker.now()
            pollingInterval = POLLING_INTERVAL_MS

            pollingJob =
                scope.launch {
                    if (!pollImmediately) {
                        // Give UI time to observe WaitingForUser and launch Chrome Custom Tab
                        delay(POLLING_INTERVAL_MS)
                    }

                    var pollIteration = 0L
                    while (isActive) {
                        pollIteration++
                        val elapsed = ticker.now() - startTime

                        // Check timeout (mode-dependent: browser flow, manual linking,
                        // or resumed-after-process-death)
                        if (elapsed > activeTimeoutMs()) {
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
                                pinId = activePinId,
                                elapsedMs = elapsed,
                                pinCode = activePinCode,
                                shouldShowManualFallback = pollIteration * pollingInterval >= MANUAL_FALLBACK_AFTER_MS,
                            )

                        try {
                            // Poll for token via PlexLoginRepo — the ACTIVE pin (strong for
                            // the browser flow, the non-strong manual pin after the user
                            // switched to plex.tv/link).
                            plexLoginRepo.checkForOAuthAccessToken(activePinId)

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
