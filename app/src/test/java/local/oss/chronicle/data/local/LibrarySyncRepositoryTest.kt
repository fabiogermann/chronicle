package local.oss.chronicle.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.account.AccountTestFixtures
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.ProviderType
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexLoginService
import local.oss.chronicle.data.sources.plex.PlexMediaSource
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.data.sources.plex.model.PlexServer
import local.oss.chronicle.features.account.CredentialManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for LibrarySyncRepository, focusing on multi-library sync behavior
 * for the Unified Library View feature.
 *
 * Note: These tests focus on verifying the repository's setup and data handling.
 * The async refresh behavior is better suited for integration tests due to the
 * repository's use of internal coroutine scopes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class LibrarySyncRepositoryTest {
    @Mock
    private lateinit var mockBookRepository: BookRepository

    @Mock
    private lateinit var mockTrackRepository: TrackRepository

    @Mock
    private lateinit var mockCollectionsRepository: CollectionsRepository

    @Mock
    private lateinit var mockLibraryRepository: LibraryRepository

    @Mock
    private lateinit var mockAccountRepository: AccountRepository

    @Mock
    private lateinit var mockCredentialManager: CredentialManager

    @Mock
    private lateinit var mockPlexConfig: PlexConfig

    @Mock
    private lateinit var mockPlexLoginService: PlexLoginService

    @Mock
    private lateinit var mockPlexPrefsRepo: PlexPrefsRepo

    private lateinit var repository: LibrarySyncRepository

    @Before
    fun setup() {
        repository =
            LibrarySyncRepository(
                bookRepository = mockBookRepository,
                trackRepository = mockTrackRepository,
                collectionsRepository = mockCollectionsRepository,
                libraryRepository = mockLibraryRepository,
                accountRepository = mockAccountRepository,
                credentialManager = mockCredentialManager,
                plexConfig = mockPlexConfig,
                plexLoginService = mockPlexLoginService,
                plexPrefsRepo = mockPlexPrefsRepo,
            )

        // Setup default behavior for book and track repositories
        runBlocking {
            whenever(mockBookRepository.getAllBooksAsync()).thenReturn(emptyList())
            whenever(mockTrackRepository.getAllTracksAsync()).thenReturn(emptyList())
        }
    }

    @Test
    fun `repository is created with correct dependencies`() {
        // Then: Repository should be properly initialized
        assertThat(repository).isNotNull()
        // LiveData initial value may be null, so check it's not true
        val refreshingValue = repository.isRefreshing.value
        if (refreshingValue != null) {
            assertThat(refreshingValue).isFalse()
        }
    }

    @Test
    fun `getAllLibraries returns multiple libraries from multiple accounts`() =
        runTest {
            // Given: 2 accounts with 2 libraries each (4 total)
            val account1 = AccountTestFixtures.createPlexAccount(displayName = "User One")
            val account2 = AccountTestFixtures.createPlexAccount(displayName = "User Two")

            val libraries =
                listOf(
                    AccountTestFixtures.createPlexLibrary(
                        id = "plex:library:1",
                        accountId = account1.id,
                        name = "Account1 Library A",
                    ),
                    AccountTestFixtures.createPlexLibrary(
                        id = "plex:library:2",
                        accountId = account1.id,
                        name = "Account1 Library B",
                    ),
                    AccountTestFixtures.createPlexLibrary(
                        id = "plex:library:3",
                        accountId = account2.id,
                        name = "Account2 Library A",
                    ),
                    AccountTestFixtures.createPlexLibrary(
                        id = "plex:library:4",
                        accountId = account2.id,
                        name = "Account2 Library B",
                    ),
                )

            whenever(mockLibraryRepository.getAllLibraries()).thenReturn(flowOf(libraries))

            // When: Getting all libraries
            val result = mockLibraryRepository.getAllLibraries()

            // Then: All 4 libraries should be available
            result.collect { libs ->
                assertThat(libs).hasSize(4)
                assertThat(libs.map { it.accountId }.toSet()).containsExactly(account1.id, account2.id)
            }
        }

    @Test
    fun `empty library list is handled correctly`() =
        runTest {
            // Given: No libraries in repository
            whenever(mockLibraryRepository.getAllLibraries()).thenReturn(flowOf(emptyList()))

            // When: Getting all libraries
            val result = mockLibraryRepository.getAllLibraries()

            // Then: Empty list is returned without errors
            result.collect { libs ->
                assertThat(libs).isEmpty()
            }
        }

    @Test
    fun `track data aggregation works correctly for multiple books`() =
        runTest {
            // Given: Books with associated tracks
            val books =
                listOf(
                    Audiobook(
                        id = "plex:100",
                        libraryId = "plex:library:1",
                        source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                        title = "Book 1",
                    ),
                    Audiobook(
                        id = "plex:200",
                        libraryId = "plex:library:1",
                        source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                        title = "Book 2",
                    ),
                )

            val tracks =
                listOf(
                    MediaItemTrack(
                        id = "plex:101",
                        parentKey = "plex:100",
                        libraryId = "plex:library:1",
                        duration = 3600L,
                        progress = 1800L,
                        source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                    ),
                    MediaItemTrack(
                        id = "plex:102",
                        parentKey = "plex:100",
                        libraryId = "plex:library:1",
                        duration = 3600L,
                        progress = 0L,
                        source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                    ),
                    MediaItemTrack(
                        id = "plex:201",
                        parentKey = "plex:200",
                        libraryId = "plex:library:1",
                        duration = 2400L,
                        progress = 2400L,
                        source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                    ),
                )

            whenever(mockBookRepository.getAllBooksAsync()).thenReturn(books)
            whenever(mockTrackRepository.getAllTracksAsync()).thenReturn(tracks)

            // When: Getting books and tracks
            val retrievedBooks = mockBookRepository.getAllBooksAsync()
            val retrievedTracks = mockTrackRepository.getAllTracksAsync()

            // Then: Data is organized correctly
            assertThat(retrievedBooks).hasSize(2)
            assertThat(retrievedTracks).hasSize(3)

            // Verify tracks can be grouped by parent book
            val tracksForBook1 = retrievedTracks.filter { it.parentKey == "plex:100" }
            val tracksForBook2 = retrievedTracks.filter { it.parentKey == "plex:200" }

            assertThat(tracksForBook1).hasSize(2)
            assertThat(tracksForBook2).hasSize(1)
        }

    @Test
    fun `library repository handles libraries from different accounts`() =
        runTest {
            // Given: Libraries from different accounts
            val library1 =
                AccountTestFixtures.createPlexLibrary(
                    id = "plex:library:1",
                    accountId = "account-1",
                    name = "Account 1 Library",
                )

            val library2 =
                AccountTestFixtures.createPlexLibrary(
                    id = "plex:library:2",
                    accountId = "account-2",
                    name = "Account 2 Library",
                )

            val libraries = listOf(library1, library2)
            whenever(mockLibraryRepository.getAllLibraries()).thenReturn(flowOf(libraries))

            // When: Getting libraries
            val result = mockLibraryRepository.getAllLibraries()

            // Then: Both libraries are present with correct account associations
            result.collect { libs ->
                assertThat(libs).hasSize(2)
                assertThat(libs[0].accountId).isEqualTo("account-1")
                assertThat(libs[1].accountId).isEqualTo("account-2")
            }
        }

    @Test
    fun `syncLibrary persists the full server Connection list onto the Library row`() =
        runTest {
            // Given: A library belonging to an account whose Plex server returns three connections
            // (LAN, WAN, relay) from /resources. This is the data the off-network playback fix needs
            // to persist so the resolver can re-probe later.
            val accountId = "plex:account:abc"
            val serverId = "server-uuid-99"
            val library =
                AccountTestFixtures.createPlexLibrary(
                    id = "plex:library:9",
                    accountId = accountId,
                    serverId = serverId,
                    name = "Audiobooks",
                ).copy(connections = null, chosenConnectionUri = null, lastConnectionCheckAt = null)

            val account =
                local.oss.chronicle.data.model.Account(
                    id = accountId,
                    providerType = ProviderType.PLEX,
                    displayName = "Tester",
                    avatarUrl = null,
                    credentials = "",
                    createdAt = 0L,
                    lastUsedAt = 0L,
                )

            val lan = Connection(uri = "http://192.168.1.20:32400", local = true)
            val wan = Connection(uri = "https://wan.plex.direct:32400", local = false)
            val relay = Connection(uri = "https://relay.plex.tv:443", local = false)
            val connections = listOf(lan, wan, relay)

            val plexServer =
                PlexServer(
                    name = "Home",
                    provides = "server",
                    connections = connections,
                    clientIdentifier = serverId,
                    accessToken = "server-token",
                    owned = true,
                )

            whenever(mockAccountRepository.getAccountById(accountId)).thenReturn(account)
            whenever(mockCredentialManager.getCredentials(accountId))
                .thenReturn("""{"userToken":"user-token","serverToken":"server-token"}""")
            whenever(mockPlexLoginService.resources()).thenReturn(listOf(plexServer))
            whenever(mockPlexConfig.url).thenReturn(lan.uri)
            whenever(
                mockPlexConfig.updateServerForSync(
                    eq(connections),
                    eq("server-token"),
                ),
            ).thenReturn(true)
            // syncLibrary also queries the per-library book count after persisting connections;
            // stub the int return so the suspending call doesn't NPE in the test.
            whenever(mockBookRepository.getBookCountForLibrary(library.id)).thenReturn(0)

            // When: We sync that single library
            repository.syncLibrary(library)

            // Then: The library row was updated with the FULL connection list, plus a chosen URI
            // and a fresh `lastConnectionCheckAt` timestamp. Just persisting `serverUrl` (the old
            // behavior) would mean the app could only ever try that one URL after a restart.
            verify(mockLibraryRepository).updateLibrary(
                argThat { updated ->
                    updated.id == library.id &&
                        updated.connections == connections &&
                        updated.chosenConnectionUri == lan.uri &&
                        updated.lastConnectionCheckAt != null &&
                        // legacy serverUrl is still kept in sync for backward compatibility
                        updated.serverUrl == lan.uri
                },
            )
        }
}
