package local.oss.chronicle.features.login

import android.net.Uri
import androidx.lifecycle.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.model.OAuthResponse
import local.oss.chronicle.features.auth.PlexAuthCoordinator
import local.oss.chronicle.features.auth.PlexAuthState
import local.oss.chronicle.util.Event
import local.oss.chronicle.util.postEvent
import javax.inject.Inject

class LoginViewModel(
    private val plexLoginRepo: IPlexLoginRepo,
    private val plexPrefsRepo: PlexPrefsRepo,
) : ViewModel() {
    class Factory
        @Inject
        constructor(
            private val plexLoginRepo: IPlexLoginRepo,
            private val plexPrefsRepo: PlexPrefsRepo,
        ) : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
                    return LoginViewModel(plexLoginRepo, plexPrefsRepo) as T
                }
                throw IllegalArgumentException("Unknown ViewHolder class")
            }
        }

    // Legacy WebView-based OAuth flow (kept for reference, not used with Chrome Custom Tabs)
    private var _authEvent = MutableLiveData<Event<OAuthResponse?>>()
    val authEvent: LiveData<Event<OAuthResponse?>>
        get() = _authEvent

    private var _errorEvent = MutableLiveData<Event<String>>()
    val errorEvent: LiveData<Event<String>>
        get() = _errorEvent

    private var hasLaunched = false

    val isLoading =
        plexLoginRepo.loginEvent.map { loginState ->
            return@map loginState.peekContent() == IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS
        }

    // Chrome Custom Tabs OAuth coordinator
    private var authCoordinator: PlexAuthCoordinator? = null

    /**
     * Starts the Chrome Custom Tabs OAuth flow using PlexAuthCoordinator.
     *
     * This replaces the WebView-based flow (loginWithOAuth).
     * Returns a StateFlow that emits PlexAuthState updates.
     */
    suspend fun startChromeCustomTabsAuth(): StateFlow<PlexAuthState> {
        // Create coordinator if not exists (use viewModelScope for lifecycle-aware coroutines)
        if (authCoordinator == null) {
            authCoordinator = PlexAuthCoordinator(plexLoginRepo, viewModelScope)
        }

        // Start authentication flow
        return authCoordinator!!.startAuth()
    }

    /**
     * Creates the non-strong PIN for manual linking at plex.tv/link and switches
     * polling to it. Returns the Polling state carrying the 4-char code, or null
     * when no flow is active or PIN creation failed.
     */
    suspend fun requestManualLinkCode(): PlexAuthState.Polling? = authCoordinator?.requestManualLinkCode()

    /**
     * Resumes polling of a PIN persisted in [local.oss.chronicle.data.sources.plex.PlexPrefsRepo.oAuthTempId]
     * from before a process restart. If the OS killed the app mid-OAuth (the user
     * comes back from the browser via the callback deep link and the coordinator
     * is gone), the persisted PIN may already be linked — polling it completes
     * login without another browser round-trip (the reported login loop).
     */
    fun resumeAuthWithPersistedPin(pinId: Long): StateFlow<PlexAuthState> {
        if (authCoordinator == null) {
            authCoordinator = PlexAuthCoordinator(plexLoginRepo, viewModelScope)
        }
        return authCoordinator!!.resumeAuth(pinId)
    }

    /**
     * The PIN id persisted from a previous login attempt (survives process death),
     * or -1 when there is none. -1L is also what the repo writes when a PIN 404s.
     */
    fun persistedOAuthPinId(): Long = plexPrefsRepo.oAuthTempId

    /**
     * Cancels the ongoing Chrome Custom Tabs authentication.
     */
    fun cancelAuth() {
        authCoordinator?.cancelAuth()
    }

    /**
     * Resets the coordinator to allow retry after failure/timeout.
     */
    fun resetAuth() {
        authCoordinator?.reset()
    }

    // Legacy WebView-based OAuth flow (preserved but not used with new Chrome Custom Tabs flow)
    fun loginWithOAuth() {
        viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
            try {
                val pin = plexLoginRepo.postOAuthPin()
                if (pin != null) {
                    _authEvent.postEvent(pin)
                } else {
                    _errorEvent.postEvent("Login failed: Unable to connect to Plex servers. Please check your internet connection.")
                }
            } catch (e: Exception) {
                _errorEvent.postEvent("Login failed: ${e.message}")
                timber.log.Timber.e(e, "OAuth login failed")
            }
        }
    }

    fun makeOAuthLoginUrl(
        id: String,
        code: String,
    ): Uri = plexLoginRepo.makeOAuthUrl(id, code)

    /** Whether the custom tab has been launched to login */
    fun setLaunched(b: Boolean) {
        hasLaunched = b
    }

    fun checkForAccess() {
        if (hasLaunched) {
            viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
                // Check for access, if the login repo gains access, then our observer in
                // MainActivity will handle navigation
                plexLoginRepo.checkForOAuthAccessToken()
            }
        }
    }

    override fun onCleared() {
        authCoordinator?.dispose()
        super.onCleared()
    }
}
