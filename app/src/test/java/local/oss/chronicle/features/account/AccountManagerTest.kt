package local.oss.chronicle.features.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.BookRepository
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.local.TrackRepository
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.ProviderType
import local.oss.chronicle.data.sources.plex.ServerConnectionResolver
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AccountManagerTest {
    private lateinit var accountRepository: AccountRepository
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var activeLibraryProvider: ActiveLibraryProvider
    private lateinit var credentialManager: CredentialManager
    private lateinit var bookRepository: BookRepository
    private lateinit var trackRepository: TrackRepository
    private lateinit var serverConnectionResolver: ServerConnectionResolver
    private lateinit var accountManager: AccountManager

    @Before
    fun setup() {
        accountRepository = mock()
        libraryRepository = mock()
        activeLibraryProvider = mock()
        credentialManager = mock()
        bookRepository = mock()
        trackRepository = mock()
        serverConnectionResolver = mock()

        accountManager =
            AccountManager(
                accountRepository,
                libraryRepository,
                activeLibraryProvider,
                credentialManager,
                bookRepository,
                trackRepository,
                serverConnectionResolver,
            )
    }

    @Test
    fun addPlexAccount_createsAccountAndStoresCredentials(): Unit =
        runBlocking {
            // Given
            val displayName = "Test User"
            val avatarUrl = "https://example.com/avatar.png"
            val authToken = "test-auth-token"

            // When
            val account = accountManager.addPlexAccount(displayName, avatarUrl, authToken)

            // Then
            assertThat(account.providerType).isEqualTo(ProviderType.PLEX)
            assertThat(account.displayName).isEqualTo(displayName)
            assertThat(account.avatarUrl).isEqualTo(avatarUrl)
            assertThat(account.id).startsWith("plex:account:")

            // Verify credentials stored
            verify(credentialManager).storeCredentials(eq(account.id), eq(authToken))

            // Verify account saved
            verify(accountRepository).addAccount(account)
        }

    @Test
    fun removeAccount_deletesAccountAndAssociatedData(): Unit =
        runBlocking {
            // Given
            val accountId = "plex:account:test123"
            val library1 =
                Library(
                    id = "plex:library:1",
                    accountId = accountId,
                    serverId = "server1",
                    serverName = "Server 1",
                    name = "Library 1",
                    type = "artist",
                    lastSyncedAt = 0L,
                    itemCount = 0,
                    isActive = false,
                )
            val library2 =
                Library(
                    id = "plex:library:2",
                    accountId = accountId,
                    serverId = "server1",
                    serverName = "Server 1",
                    name = "Library 2",
                    type = "artist",
                    lastSyncedAt = 0L,
                    itemCount = 0,
                    isActive = false,
                )

            whenever(libraryRepository.getLibrariesForAccount(accountId)).thenReturn(
                flowOf(listOf(library1, library2)),
            )
            whenever(activeLibraryProvider.currentLibrary).thenReturn(mock())

            // When
            accountManager.removeAccount(accountId)

            // Then
            verify(bookRepository).deleteByLibraryId(library1.id)
            verify(bookRepository).deleteByLibraryId(library2.id)
            verify(trackRepository).deleteByLibraryId(library1.id)
            verify(trackRepository).deleteByLibraryId(library2.id)
            verify(credentialManager).deleteCredentials(accountId)
            verify(accountRepository).removeAccountById(accountId)
        }

    @Test
    fun switchToLibrary_updatesAccountLastUsedAndSetsActiveLibrary(): Unit =
        runBlocking {
            // Given
            val libraryId = "plex:library:test"
            val accountId = "plex:account:test"
            val library =
                Library(
                    id = libraryId,
                    accountId = accountId,
                    serverId = "server1",
                    serverName = "Server 1",
                    name = "Test Library",
                    type = "artist",
                    lastSyncedAt = 0L,
                    itemCount = 0,
                    isActive = false,
                )

            whenever(libraryRepository.getLibraryById(libraryId)).thenReturn(library)

            // When
            accountManager.switchToLibrary(libraryId)

            // Then
            verify(accountRepository).updateLastUsed(eq(accountId), any())
            verify(activeLibraryProvider).switchToLibrary(libraryId)
        }

    @Test
    fun addLibraries_addsLibrariesWithAccountId(): Unit =
        runBlocking {
            // Given
            val accountId = "plex:account:test"
            val libraries =
                listOf(
                    Library(
                        id = "plex:library:1",
                        accountId = "", // Should be overwritten
                        serverId = "server1",
                        serverName = "Server 1",
                        name = "Library 1",
                        type = "artist",
                        lastSyncedAt = 0L,
                        itemCount = 0,
                        isActive = false,
                    ),
                    Library(
                        id = "plex:library:2",
                        accountId = "", // Should be overwritten
                        serverId = "server1",
                        serverName = "Server 1",
                        name = "Library 2",
                        type = "artist",
                        lastSyncedAt = 0L,
                        itemCount = 0,
                        isActive = false,
                    ),
                )

            // When
            accountManager.addLibraries(accountId, libraries)

            // Then
            verify(libraryRepository).addLibraries(
                argThat { list ->
                    list.all { it.accountId == accountId }
                },
            )
        }

    @Test
    fun refreshLibraries_addsNewLibrariesAndRemovesOldOnes(): Unit =
        runBlocking {
            // Given
            val accountId = "plex:account:test"
            val existingLibraries =
                listOf(
                    Library(
                        id = "plex:library:1",
                        accountId = accountId,
                        serverId = "server1",
                        serverName = "Server 1",
                        name = "Library 1",
                        type = "artist",
                        lastSyncedAt = 0L,
                        itemCount = 0,
                        isActive = false,
                    ),
                    Library(
                        id = "plex:library:2",
                        accountId = accountId,
                        serverId = "server1",
                        serverName = "Server 1",
                        name = "Library 2",
                        type = "artist",
                        lastSyncedAt = 0L,
                        itemCount = 0,
                        isActive = false,
                    ),
                )

            val discoveredLibraries =
                listOf(
                    Library(
                        id = "plex:library:2",
                        accountId = accountId,
                        serverId = "server1",
                        serverName = "Server 1",
                        name = "Library 2 Updated",
                        type = "artist",
                        lastSyncedAt = 0L,
                        itemCount = 5,
                        isActive = false,
                    ),
                    Library(
                        id = "plex:library:3",
                        accountId = accountId,
                        serverId = "server1",
                        serverName = "Server 1",
                        name = "Library 3",
                        type = "artist",
                        lastSyncedAt = 0L,
                        itemCount = 3,
                        isActive = false,
                    ),
                )

            whenever(libraryRepository.getLibrariesForAccount(accountId)).thenReturn(
                flowOf(existingLibraries),
            )

            // When
            accountManager.refreshLibraries(accountId, discoveredLibraries)

            // Then
            // Library 3 should be added (new)
            verify(libraryRepository).addLibraries(
                argThat { list ->
                    list.any { it.id == "plex:library:3" }
                },
            )

            // Library 1 should be removed (not in discovered)
            verify(bookRepository).deleteByLibraryId("plex:library:1")
            verify(trackRepository).deleteByLibraryId("plex:library:1")
            verify(libraryRepository).removeLibraryById("plex:library:1")

            // Library 2 should be updated
            verify(libraryRepository).updateLibrary(
                argThat { library -> library.id == "plex:library:2" && library.name == "Library 2 Updated" },
            )
        }

    @Test
    fun hasAccounts_delegatesToRepository(): Unit =
        runBlocking {
            // Given
            whenever(accountRepository.hasAccounts()).thenReturn(true)

            // When
            val result = accountManager.hasAccounts()

            // Then
            assertThat(result).isTrue()
            verify(accountRepository).hasAccounts()
        }

    @Test
    fun getAccountToken_retrievesFromCredentialManager() {
        // Given
        val accountId = "plex:account:test"
        val token = "test-token"
        whenever(credentialManager.getCredentials(accountId)).thenReturn(token)

        // When
        val result = accountManager.getAccountToken(accountId)

        // Then
        assertThat(result).isEqualTo(token)
        verify(credentialManager).getCredentials(accountId)
    }

    @Test
    fun addPlexAccountWithLibrary_storesServerAccessTokenInLibrary(): Unit =
        runBlocking {
            // Given
            val userUuid = "user-uuid-123"
            val username = "testuser"
            val userThumb = "https://example.com/avatar.png"
            val serverId = "server-id-456"
            val serverName = "Test Server"
            val libraryId = "1"
            val libraryName = "Audiobooks"
            val libraryType = "artist"
            val userAuthToken = "user-oauth-token-abc123"
            val serverAccessToken = "server-access-token-xyz789"
            val serverUrl = "http://192.168.1.100:32400"

            // When
            val (account, library) =
                accountManager.addPlexAccountWithLibrary(
                    userUuid = userUuid,
                    username = username,
                    userThumb = userThumb,
                    serverId = serverId,
                    serverName = serverName,
                    libraryId = libraryId,
                    libraryName = libraryName,
                    libraryType = libraryType,
                    userAuthToken = userAuthToken,
                    serverAccessToken = serverAccessToken,
                    serverUrl = serverUrl,
                )

            // Then - verify Library.authToken uses serverAccessToken, NOT userAuthToken
            assertThat(account.id).isEqualTo("plex:account:$userUuid")
            assertThat(account.displayName).isEqualTo(username)
            assertThat(library.id).isEqualTo("plex:library:$libraryId")
            assertThat(library.serverUrl).isEqualTo(serverUrl)
            assertThat(library.authToken).isEqualTo(serverAccessToken) // ← FIXED: Use server token
            assertThat(library.authToken).isNotEqualTo(userAuthToken) // ← Must not be user token

            // Verify library was saved with serverUrl and correct authToken
            verify(libraryRepository).addLibraries(
                argThat { list ->
                    list.size == 1 &&
                        list[0].serverUrl == serverUrl &&
                        list[0].authToken == serverAccessToken
                },
            )
        }

    @Test
    fun addPlexAccountWithLibrary_persistsFullConnectionListAndChosenUri(): Unit =
        runBlocking {
            // Given: A Plex server with LAN, WAN and relay URIs (as returned by /resources)
            val connections =
                listOf(
                    local.oss.chronicle.data.sources.plex.model.Connection(
                        uri = "http://192.168.1.100:32400",
                        local = true,
                    ),
                    local.oss.chronicle.data.sources.plex.model.Connection(
                        uri = "https://wan.plex.direct:32400",
                        local = false,
                    ),
                    local.oss.chronicle.data.sources.plex.model.Connection(
                        uri = "https://relay.plex.tv:443",
                        local = false,
                    ),
                )
            val chosen = "http://192.168.1.100:32400"

            // When: A new Plex library is added at the end of the login flow
            val (_, library) =
                accountManager.addPlexAccountWithLibrary(
                    userUuid = "user-uuid",
                    username = "tester",
                    userThumb = "",
                    serverId = "server-id",
                    serverName = "Home Server",
                    libraryId = "7",
                    libraryName = "Audiobooks",
                    userAuthToken = "user-token",
                    serverAccessToken = "server-token",
                    serverUrl = chosen,
                    connections = connections,
                )

            // Then: The returned Library carries the full Connection list (so the resolver can
            // re-probe later) plus a hint of which URI was active at login time.
            assertThat(library.connections).isEqualTo(connections)
            assertThat(library.chosenConnectionUri).isEqualTo(chosen)
            assertThat(library.lastConnectionCheckAt).isNotNull()

            // And: It was persisted with the same payload.
            verify(libraryRepository).addLibraries(
                argThat { list ->
                    list.size == 1 &&
                        list[0].connections == connections &&
                        list[0].chosenConnectionUri == chosen &&
                        list[0].lastConnectionCheckAt != null
                },
            )
        }

    @Test
    fun getAllAccounts_delegatesToRepository() {
        // Given
        val accounts =
            listOf(
                Account(
                    id = "plex:account:1",
                    providerType = ProviderType.PLEX,
                    displayName = "User 1",
                    avatarUrl = null,
                    credentials = "",
                    createdAt = 0L,
                    lastUsedAt = 0L,
                ),
            )
        whenever(accountRepository.getAllAccounts()).thenReturn(flowOf(accounts))

        // When
        val result = runBlocking { accountManager.getAllAccounts().first() }

        // Then
        assertThat(result).isEqualTo(accounts)
    }
}
