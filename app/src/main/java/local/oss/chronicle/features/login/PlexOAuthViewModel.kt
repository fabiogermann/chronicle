package local.oss.chronicle.features.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

class PlexOAuthViewModel(
    private val plexLoginRepo: IPlexLoginRepo,
) : ViewModel() {
    companion object {
        /** Polling interval in milliseconds */
        const val POLLING_INTERVAL_MS = 2000L

        /** Maximum polling duration before timeout - 5 minutes */
        const val POLLING_TIMEOUT_MS = 5 * 60 * 1000L
    }

    sealed class AuthState {
        object Idle : AuthState()

        object Polling : AuthState()

        object Success : AuthState()

        data class Error(val message: String) : AuthState()

        object Timeout : AuthState()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var pollingJob: Job? = null
    private var startTime: Long = 0

    fun startPolling(pinId: Long) {
        if (pollingJob?.isActive == true) {
            Timber.d("Polling already active, ignoring start request")
            return
        }

        startTime = System.currentTimeMillis()
        _authState.value = AuthState.Polling

        pollingJob =
            viewModelScope.launch {
                while (isActive) {
                    // Check for timeout
                    if (System.currentTimeMillis() - startTime > POLLING_TIMEOUT_MS) {
                        Timber.w("OAuth polling timed out after ${POLLING_TIMEOUT_MS}ms")
                        _authState.value = AuthState.Timeout
                        break
                    }

                    try {
                        Timber.d("Polling for OAuth token...")
                        plexLoginRepo.checkForOAuthAccessToken()

                        // Check if login state changed to indicate success
                        val currentState = plexLoginRepo.loginEvent.value?.peekContent()
                        if (currentState != null &&
                            currentState != IPlexLoginRepo.LoginState.NOT_LOGGED_IN &&
                            currentState != IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS &&
                            currentState != IPlexLoginRepo.LoginState.FAILED_TO_LOG_IN
                        ) {
                            Timber.i("OAuth token obtained, login state: $currentState")
                            _authState.value = AuthState.Success
                            break
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error during OAuth polling")
                        // Don't fail immediately, continue polling
                    }

                    delay(POLLING_INTERVAL_MS)
                }
            }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Timber.d("OAuth polling stopped")
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    class Factory
        @Inject
        constructor(
            private val plexLoginRepo: IPlexLoginRepo,
        ) : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(PlexOAuthViewModel::class.java)) {
                    return PlexOAuthViewModel(plexLoginRepo) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
}
