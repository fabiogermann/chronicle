package local.oss.chronicle.data.sources.plex

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import local.oss.chronicle.data.model.PlexLibrary
import local.oss.chronicle.data.model.ServerModel
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo.LoginState
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.*
import local.oss.chronicle.data.sources.plex.PlexInterceptor.Companion.PLATFORM
import local.oss.chronicle.data.sources.plex.PlexInterceptor.Companion.PRODUCT
import local.oss.chronicle.data.sources.plex.model.OAuthResponse
import local.oss.chronicle.data.sources.plex.model.PlexUser
import local.oss.chronicle.data.sources.plex.model.UsersResponse
import local.oss.chronicle.features.account.AccountManager
import local.oss.chronicle.util.Event
import local.oss.chronicle.util.postEvent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface IPlexLoginRepo {
    /** POST the OAuth pin to start OAuth login process, and retrieve a default response */
    suspend fun postOAuthPin(): OAuthResponse?

    /**
     * Chooses a user, updates the auth token to match the user in the [PlexConfig], sets
     * [PlexPrefsRepo.user] to [responseUser], changes [loginEvent] to [LOGGED_IN_NO_SERVER_CHOSEN]
     *
     * [responseUser] must have a valid auth token
     */
    fun chooseUser(responseUser: PlexUser)

    /**
     * Chooses a server, sets it in the [PlexPrefsRepo], changes [loginEvent] to
     * [LOGGED_IN_NO_LIBRARY_CHOSEN]
     */
    fun chooseServer(serverModel: ServerModel)

    /** Chooses a library, sets it in the [PlexPrefsRepo], changes state to [LOGGED_IN_FULLY] */
    fun chooseLibrary(plexLibrary: PlexLibrary)

    /**
     * Determines the current [LoginState] based on information stored in [PlexPrefsRepo] and
     * updates the [loginEvent] to reflect that
     */
    fun determineLoginState()

    val loginEvent: LiveData<Event<LoginState>>

    enum class LoginState {
        NOT_LOGGED_IN,
        FAILED_TO_LOG_IN,
        LOGGED_IN_NO_USER_CHOSEN,
        LOGGED_IN_NO_SERVER_CHOSEN,
        LOGGED_IN_NO_LIBRARY_CHOSEN,
        LOGGED_IN_FULLY,
        AWAITING_LOGIN_RESULTS,
    }

    fun makeOAuthUrl(
        clientId: String,
        code: String,
    ): Uri

    suspend fun checkForOAuthAccessToken()
}

/**
 * Responsible for querying network w/r/t network data, configuring network to use login data once
 * on login succeeds, and for saving login info via [PlexPrefsRepo] on success
 */
@Singleton
class PlexLoginRepo
    @Inject
    constructor(
        private val plexPrefsRepo: PlexPrefsRepo,
        private val plexLoginService: PlexLoginService,
        private val plexConfig: PlexConfig,
        private val accountManager: AccountManager,
    ) : IPlexLoginRepo {
        private var _loginState = MutableLiveData<Event<LoginState>>()
        override val loginEvent: LiveData<Event<LoginState>>
            get() = _loginState

        // Coroutine scope for database operations (uses SupervisorJob to prevent cancellation cascading)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        override suspend fun postOAuthPin(): OAuthResponse? {
            return try {
                _loginState.postEvent(AWAITING_LOGIN_RESULTS)
                val pin = plexLoginService.postAuthPin()
                plexPrefsRepo.oAuthTempId = pin.id
                pin
            } catch (e: Throwable) {
                Timber.e("Failed to log in: $e")
                _loginState.postEvent(FAILED_TO_LOG_IN)
                null
            }
        }

        override fun chooseUser(responseUser: PlexUser) {
            plexPrefsRepo.user = responseUser
            _loginState.postEvent(LOGGED_IN_NO_SERVER_CHOSEN)
        }

        override fun makeOAuthUrl(
            clientId: String,
            code: String,
        ): Uri {
            // Keep [ and ] characters for readability, replace with escaped chars below
            return Uri.parse(
                (
                    "https://app.plex.tv/auth#?code=$code" +
                        "&context[device][product]=$PRODUCT" +
                        "&context[device][environment]=bundled" +
                        "&context[device][layout]=desktop" +
                        "&context[device][platform]=$PLATFORM" +
                        "&context[device][device]=$PRODUCT" +
                        "&clientID=$clientId"
                )
                    .replace("[", "%5B")
                    .replace("]", "%5D"),
            )
        }

        override suspend fun checkForOAuthAccessToken() {
            val authToken =
                try {
                    plexLoginService.getAuthPin(plexPrefsRepo.oAuthTempId).authToken ?: ""
                } catch (t: Throwable) {
                    plexPrefsRepo.oAuthTempId = -1L
                    Timber.i("Failed to get OAuth access token: ${t.message}")
                    ""
                }
            if (authToken.isNotEmpty()) {
                plexPrefsRepo.accountAuthToken = authToken

                // now check if we should show user screen:
                try {
                    val userResponse: UsersResponse = plexLoginService.getUsersForAccount()
                    if (userResponse.users.size == 1) {
                        // if there is only one user, there's no need to choose it
                        chooseUser(userResponse.users[0])
                    } else {
                        // now we proceed to choose user
                        _loginState.postEvent(LOGGED_IN_NO_USER_CHOSEN)
                    }
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to load users, cannot proceed to profile")
                }
            }
        }

        override fun chooseServer(serverModel: ServerModel) {
            Timber.i("User chose server: $serverModel")
            Timber.d(
                "URL_DEBUG: User selected server '${serverModel.name}' with ${serverModel.connections.size} connections: ${serverModel.connections.map { "${it.uri} (local=${it.local})" }}",
            )
            plexConfig.setPotentialConnections(serverModel.connections)
            plexPrefsRepo.server = serverModel
            _loginState.postEvent(LOGGED_IN_NO_LIBRARY_CHOSEN)
        }

        override fun chooseLibrary(plexLibrary: PlexLibrary) {
            Timber.i("User chose library: $plexLibrary")
            plexPrefsRepo.library = plexLibrary
            
            // Save account to multi-account database
            val user = plexPrefsRepo.user
            val server = plexPrefsRepo.server
            val accountToken = plexPrefsRepo.accountAuthToken
            
            if (user != null && server != null && accountToken.isNotEmpty()) {
                scope.launch {
                    try {
                        val userToken = user.authToken?.takeIf { it.isNotEmpty() } ?: accountToken
                        accountManager.addPlexAccountWithLibrary(
                            userUuid = user.uuid,
                            username = user.username ?: user.title,
                            userThumb = user.thumb.takeIf { it.isNotEmpty() } ?: "",
                            serverId = server.serverId,
                            serverName = server.name,
                            libraryId = plexLibrary.id,
                            libraryName = plexLibrary.name,
                            libraryType = "artist",
                            userAuthToken = userToken,
                            serverAccessToken = server.accessToken
                        )
                        Timber.i("Account saved to database: ${user.uuid}, library: ${plexLibrary.id}")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to save account to database")
                    }
                }
            } else {
                Timber.w("Cannot save account: user=$user, server=$server, token=${accountToken.isNotEmpty()}")
            }
            
            _loginState.postEvent(LOGGED_IN_FULLY)
        }

        init {
            determineLoginState()
        }

        override fun determineLoginState() {
            val token = plexPrefsRepo.accountAuthToken
            val user: PlexUser? = plexPrefsRepo.user
            val server: ServerModel? = plexPrefsRepo.server
            val library: PlexLibrary? = plexPrefsRepo.library
            Timber.i(
                """Login state: token = $token,
                    |user token = ${user?.authToken},
                    |server token = ${server?.accessToken},
                    |library = ${library?.name}
                """.trimMargin(),
            )
            _loginState.postEvent(
                when {
                    token.isEmpty() -> NOT_LOGGED_IN
                    server != null && library != null -> LOGGED_IN_FULLY // Migrating from v0.41, impossible otherwise
                    user == null -> LOGGED_IN_NO_USER_CHOSEN
                    server == null -> LOGGED_IN_NO_SERVER_CHOSEN
                    library == null -> LOGGED_IN_NO_LIBRARY_CHOSEN
                    else -> {
                        Timber.i("Fully logged in branch, awaiting server checks")
                        LOGGED_IN_FULLY
                    }
                },
            )
        }
    }
