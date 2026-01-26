package local.oss.chronicle.features.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the debug information dialog.
 * Gathers and displays connection debugging information.
 */
class DebugInfoViewModel(
    private val plexConfig: PlexConfig,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val plexMediaService: PlexMediaService,
) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    class Factory
        @Inject
        constructor(
            private val plexConfig: PlexConfig,
            private val plexPrefsRepo: PlexPrefsRepo,
            private val plexMediaService: PlexMediaService,
        ) : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(DebugInfoViewModel::class.java)) {
                    return DebugInfoViewModel(
                        plexConfig = plexConfig,
                        plexPrefsRepo = plexPrefsRepo,
                        plexMediaService = plexMediaService,
                    ) as T
                } else {
                    throw IllegalArgumentException(
                        "Cannot instantiate $modelClass from DebugInfoViewModel.Factory",
                    )
                }
            }
        }

    private val _debugInfo = MutableLiveData<DebugConnectionInfo>()
    val debugInfo: LiveData<DebugConnectionInfo> = _debugInfo

    private val _connectionResults = MutableLiveData<List<ConnectionTestResult>>()
    val connectionResults: LiveData<List<ConnectionTestResult>> = _connectionResults

    private val _isTesting = MutableLiveData(false)
    val isTesting: LiveData<Boolean> = _isTesting

    init {
        loadDebugInfo()
    }

    private fun loadDebugInfo() {
        val server = plexPrefsRepo.server
        val activeUrl = plexConfig.url.takeIf { it != PlexConfig.PLACEHOLDER_URL }

        val connections =
            server?.connections?.map { conn ->
                ConnectionTestResult(
                    uri = conn.uri,
                    isLocal = conn.local,
                    status =
                        if (conn.uri == activeUrl) {
                            ConnectionStatus.CONNECTED
                        } else {
                            ConnectionStatus.UNTESTED
                        },
                )
            } ?: emptyList()

        _debugInfo.value =
            DebugConnectionInfo(
                appVersion = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE,
                serverName = server?.name,
                connectionState = plexConfig.connectionState.value ?: PlexConfig.ConnectionState.NOT_CONNECTED,
                activeUrl = activeUrl,
                availableConnections = connections,
            )
        _connectionResults.value = connections
    }

    fun testAllConnections() {
        viewModelScope.launch {
            _isTesting.value = true
            val currentResults = _connectionResults.value?.toMutableList() ?: return@launch

            // Mark all non-connected as testing
            currentResults.forEachIndexed { index, result ->
                if (result.status != ConnectionStatus.CONNECTED) {
                    currentResults[index] = result.copy(status = ConnectionStatus.TESTING)
                }
            }
            _connectionResults.value = currentResults.toList()

            // Test each connection
            currentResults.forEachIndexed { index, result ->
                if (result.status == ConnectionStatus.TESTING) {
                    val newStatus = testConnection(result.uri)
                    currentResults[index] = result.copy(status = newStatus)
                    _connectionResults.value = currentResults.toList()
                }
            }

            _isTesting.value = false
        }
    }

    private suspend fun testConnection(uri: String): ConnectionStatus {
        return try {
            Timber.d("Testing connection to: $uri")
            val response = plexMediaService.checkServer(uri)
            if (response.isSuccessful) {
                Timber.d("Connection test successful: $uri")
                ConnectionStatus.SUCCESSFUL
            } else {
                // Check if this is a WAF/ban/block response by inspecting the body
                val responseBody = response.errorBody()?.string()
                val isBlocked =
                    responseBody?.let { body ->
                        body.contains("banned", ignoreCase = true) ||
                            body.contains("blocked", ignoreCase = true) ||
                            body.contains("You got banned permanently from this server", ignoreCase = true)
                    } ?: false

                if (isBlocked) {
                    Timber.w("Connection potentially blocked by WAF: $uri - ${response.message()}")
                    ConnectionStatus.BLOCKED
                } else {
                    Timber.w("Connection test failed: $uri - ${response.message()}")
                    ConnectionStatus.FAILED
                }
            }
        } catch (e: Exception) {
            Timber.e("Connection test exception: $uri - ${e.message}")
            ConnectionStatus.FAILED
        }
    }
}

/**
 * Complete debug information for display
 */
data class DebugConnectionInfo(
    val appVersion: String,
    val buildNumber: Int,
    val serverName: String?,
    val connectionState: PlexConfig.ConnectionState,
    val activeUrl: String?,
    val availableConnections: List<ConnectionTestResult>,
)

/**
 * Result of testing a connection
 */
data class ConnectionTestResult(
    val uri: String,
    val isLocal: Boolean,
    val status: ConnectionStatus,
)

/**
 * Status of a connection test
 */
enum class ConnectionStatus {
    UNTESTED, // Gray - not yet tested
    CONNECTED, // Green - this is the active connection
    SUCCESSFUL, // Green check - test passed but not active
    FAILED, // Red X - test failed
    BLOCKED, // Yellow warning - potentially blocked by WAF or protection software
    TESTING, // Spinner - currently testing
}
