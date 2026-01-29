package local.oss.chronicle.data.local

import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.PlexLibrary
import local.oss.chronicle.data.model.asServer
import local.oss.chronicle.data.model.getProgress
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexLoginService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.data.sources.plex.model.MediaType
import local.oss.chronicle.data.sources.plex.model.getDuration
import local.oss.chronicle.features.account.CredentialManager
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibrarySyncRepository
    @Inject
    constructor(
        private val bookRepository: BookRepository,
        private val trackRepository: TrackRepository,
        private val collectionsRepository: CollectionsRepository,
        private val libraryRepository: LibraryRepository,
        private val accountRepository: AccountRepository,
        private val credentialManager: CredentialManager,
        private val plexConfig: PlexConfig,
        private val plexLoginService: PlexLoginService,
        private val plexPrefsRepo: PlexPrefsRepo,
    ) {
        private var repoJob = Job()
        private val repoScope = CoroutineScope(repoJob + Dispatchers.IO)

        private var _isRefreshing = MutableLiveData<Boolean>()
        val isRefreshing: LiveData<Boolean>
            get() = _isRefreshing

        /**
         * Refreshes data from all libraries across all accounts.
         * Each library is synced sequentially with a small delay to avoid rate limiting.
         * Individual library failures are logged but don't prevent syncing other libraries.
         */
        fun refreshLibrary() {
            Timber.i("refreshLibrary() called")
            repoScope.launch {
                try {
                    Timber.i("refreshLibrary: Starting sync process")
                    _isRefreshing.postValue(true)
                    syncAllLibraries()
                } catch (e: Throwable) {
                    val msg = "Failed to refresh data: ${e.message}"
                    Timber.e(e, "Library sync failed")
                    Toast.makeText(Injector.get().applicationContext(), msg, LENGTH_LONG).show()
                } finally {
                    Timber.i("refreshLibrary: Sync process complete")
                    _isRefreshing.postValue(false)
                }
            }
        }

        /**
         * Syncs all libraries from all accounts.
         * NOTE: Phase 1 implementation limitation - This currently still relies on the global
         * PlexConfig URL. Future phases will include server-switching logic to handle
         * libraries from different servers.
         */
        private suspend fun syncAllLibraries() {
            Timber.d("syncAllLibraries: Fetching all libraries from database")
            val allLibraries = libraryRepository.getAllLibraries().first()

            Timber.i("syncAllLibraries: Retrieved ${allLibraries.size} libraries")
            allLibraries.forEachIndexed { index, library ->
                Timber.d("  Library $index: ${library.name} (ID: ${library.id}, Account: ${library.accountId}, Server: ${library.serverId})")
            }

            if (allLibraries.isEmpty()) {
                Timber.w("No libraries found to sync")
                return
            }

            Timber.i("Starting sync for ${allLibraries.size} libraries")
            var successCount = 0
            var failureCount = 0

            for ((index, library) in allLibraries.withIndex()) {
                try {
                    Timber.i("Syncing library ${index + 1}/${allLibraries.size}: ${library.name} (ID: ${library.id}, Account: ${library.accountId})")
                    syncLibrary(library)
                    successCount++
                    Timber.d("Successfully synced library: ${library.name}")
                    // Small delay between libraries to avoid rate limiting
                    delay(500)
                } catch (e: Exception) {
                    failureCount++
                    Timber.e(e, "Failed to sync library: ${library.name} (ID: ${library.id}, Account: ${library.accountId})")
                    // Continue with next library despite failure
                }
            }

            Timber.i("Library sync complete: $successCount succeeded, $failureCount failed")

            // Update track data after all libraries synced
            updateTrackDataForAllBooks()
        }

        /**
         * Syncs a single library with its own server and account credentials.
         * Configures PlexConfig to use the library's server URL and account's auth token
         * before syncing to ensure correct data is retrieved.
         */
        private suspend fun syncLibrary(library: Library) {
            Timber.d("syncLibrary: Starting sync for library: ${library.name}")
            Timber.d("syncLibrary: Library details - ID: ${library.id}, AccountID: ${library.accountId}, ServerID: ${library.serverId}")
            
            // Get account for this library
            val account = accountRepository.getAccountById(library.accountId)
            if (account == null) {
                Timber.e("syncLibrary: Account not found for library ${library.name} (accountId: ${library.accountId})")
                return
            }
            
            // Get credentials for this account
            val credentialsJson = credentialManager.getCredentials(account.id)
            if (credentialsJson == null) {
                Timber.e("syncLibrary: No credentials found for account ${account.displayName} (id: ${account.id})")
                return
            }
            
            // Parse credentials (stored as JSON with userToken and serverToken)
            val authToken = try {
                val json = JSONObject(credentialsJson)
                json.optString("userToken", credentialsJson) // Fallback to raw string if not JSON
            } catch (e: Exception) {
                // If not JSON, assume it's the raw token
                credentialsJson
            }
            
            // Get server connections and access token from plex.tv
            Timber.d("syncLibrary: Fetching server info for ${library.serverName} (serverId: ${library.serverId})")
            val serverInfo = getServerInfo(library.serverId, authToken)
            if (serverInfo == null) {
                Timber.e("syncLibrary: No server info found for ${library.serverName}")
                return
            }
            
            Timber.i("syncLibrary: Configuring PlexConfig for library '${library.name}' on server '${library.serverName}'")
            Timber.d("syncLibrary: Found ${serverInfo.connections.size} server connections: ${serverInfo.connections.map { "${it.uri} (local=${it.local})" }}")
            Timber.d("syncLibrary: Using server accessToken for API requests")
            
            // Configure PlexConfig with this library's server and the server's access token
            // CRITICAL: Use the server's accessToken (from resources), not the user's auth token
            // This is required for managed users on shared servers
            val connectionSuccess = plexConfig.updateServerForSync(serverInfo.connections, serverInfo.accessToken)
            if (!connectionSuccess) {
                Timber.e("syncLibrary: Failed to connect to server for library ${library.name}")
                return
            }
            
            Timber.i("syncLibrary: Successfully configured PlexConfig - URL: ${plexConfig.url}")
    
            // CRITICAL: Set the library context for repositories to use
            // The repositories use plexPrefsRepo.library to determine which library to sync
            // Extract numeric library ID from "plex:library:{id}" format
            val numericLibraryId = library.id.removePrefix("plex:library:")
            // Use ARTIST type for audiobook libraries (Plex standard)
            val plexLibrary = PlexLibrary(
                name = library.name,
                type = MediaType.ARTIST,  // Plex uses "artist" type for audiobook libraries
                id = numericLibraryId
            )
            val previousLibrary = plexPrefsRepo.library
            val previousServer = plexPrefsRepo.server
            
            try {
                // CRITICAL: Temporarily clear server to force PlexInterceptor to use accountAuthToken
                // This ensures the correct user token is used when syncing libraries
                // from different users on the same server. The interceptor prioritizes
                // server.accessToken over accountAuthToken, so we must clear it to prevent
                // credential leakage between users.
                plexPrefsRepo.server = null
                
                // Temporarily set the current library context
                plexPrefsRepo.library = plexLibrary
                Timber.d("syncLibrary: Set library context to ${library.name} (ID: $numericLibraryId)")
    
                // Now sync with correct credentials AND library context
                Timber.d("syncLibrary: Refreshing books...")
                bookRepository.refreshDataPaginated()
                Timber.d("syncLibrary: Refreshing tracks...")
                trackRepository.refreshDataPaginated()
    
                // Update library sync timestamp
                Timber.d("syncLibrary: Updating sync timestamp...")
                libraryRepository.updateSyncTimestamp(library.id, System.currentTimeMillis())
    
                // Sync collections for this library
                Timber.d("syncLibrary: Refreshing collections...")
                collectionsRepository.refreshCollectionsPaginated()
    
                Timber.i("Successfully synced library: ${library.name} from server: ${library.serverName}")
            } finally {
                // Restore previous server state
                plexPrefsRepo.server = previousServer
                if (previousServer != null) {
                    Timber.d("syncLibrary: Restored previous server: ${previousServer.name}")
                } else {
                    Timber.d("syncLibrary: Cleared server (was null)")
                }
                
                // Restore previous library context (if any)
                plexPrefsRepo.library = previousLibrary
                if (previousLibrary != null) {
                    Timber.d("syncLibrary: Restored previous library context: ${previousLibrary.name}")
                } else {
                    Timber.d("syncLibrary: Cleared library context")
                }
            }
        }
    
        /**
         * Data class to hold server connection info and access token.
         */
        private data class ServerInfo(
            val connections: List<Connection>,
            val accessToken: String,
        )
    
        /**
         * Fetches server info (connections and access token) from plex.tv for a specific server.
         * @param serverId The Plex server's clientIdentifier
         * @param authToken The user's auth token (for plex.tv API)
         * @return ServerInfo containing connections and the server's access token, or null if not found
         */
        private suspend fun getServerInfo(
            serverId: String,
            authToken: String,
        ): ServerInfo? {
            return try {
                withTimeoutOrNull(5000L) {
                    // Temporarily set auth token for the resources call
                    val previousToken = plexPrefsRepo.accountAuthToken
                    plexPrefsRepo.accountAuthToken = authToken
                    
                    try {
                        val servers = plexLoginService.resources()
                            .filter { it.provides.contains("server") }
                            .map { it.asServer() }
                            .filter { it.serverId == serverId }
                        
                        if (servers.isNotEmpty()) {
                            val server = servers.first()
                            ServerInfo(
                                connections = server.connections,
                                accessToken = server.accessToken,
                            )
                        } else {
                            Timber.w("No servers found with serverId: $serverId")
                            null
                        }
                    } finally {
                        // Restore previous token
                        plexPrefsRepo.accountAuthToken = previousToken
                    }
                } ?: run {
                    Timber.w("Timeout fetching server info for $serverId")
                    null
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch server info for $serverId")
                null
            }
        }

        /**
         * Updates track data (progress, duration, count) for all books.
         * This aggregates track information into the book records.
         */
        private suspend fun updateTrackDataForAllBooks() {
            Timber.d("Updating track data for all books")
            // TODO: Loading all data into memory :O
            val audiobooks = bookRepository.getAllBooksAsync()
            val tracks = trackRepository.getAllTracksAsync()

            audiobooks.forEach { book ->
                // TODO: O(n^2) so could be bad for big libs, grouping by tracks first would be O(n)

                // Not necessarily in the right order, but it doesn't matter for updateTrackData
                val tracksInAudiobook = tracks.filter { it.parentKey == book.id }
                bookRepository.updateTrackData(
                    bookId = book.id,
                    bookProgress = tracksInAudiobook.getProgress(),
                    bookDuration = tracksInAudiobook.getDuration(),
                    trackCount = tracksInAudiobook.size,
                )
            }
            Timber.d("Track data update complete")
        }
    }
