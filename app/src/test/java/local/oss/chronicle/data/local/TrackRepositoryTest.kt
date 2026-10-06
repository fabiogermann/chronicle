package local.oss.chronicle.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.ScopedPlexServiceFactory
import local.oss.chronicle.data.sources.plex.ServerConnectionResolver
import local.oss.chronicle.data.sources.plex.model.Media
import local.oss.chronicle.data.sources.plex.model.Part
import local.oss.chronicle.data.sources.plex.model.PlexDirectory
import local.oss.chronicle.data.sources.plex.model.PlexMediaContainer
import local.oss.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for library-aware TrackRepository functionality.
 *
 * Verifies that TrackRepository correctly resolves server connections per library
 * and tags tracks with the appropriate libraryId, fixing the issue where all tracks
 * were fetched from the active library's server regardless of which library they belonged to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class TrackRepositoryTest {
    @Mock
    private lateinit var mockTrackDao: TrackDao

    @Mock
    private lateinit var mockPrefsRepo: PrefsRepo

    @Mock
    private lateinit var mockPlexMediaService: PlexMediaService

    @Mock
    private lateinit var mockPlexPrefs: PlexPrefsRepo

    @Mock
    private lateinit var mockScopedPlexServiceFactory: ScopedPlexServiceFactory

    @Mock
    private lateinit var mockServerConnectionResolver: ServerConnectionResolver

    @Mock
    private lateinit var mockScopedService: PlexMediaService

    @Mock
    private lateinit var mockBookRepository: IBookRepository

    private lateinit var trackRepository: TrackRepository

    private val testBookId = "plex:12345"
    private val testBookIdNumeric = 12345
    private val testLibraryIdA = "plex:library:1"
    private val testLibraryIdB = "plex:library:2"
    private val testServerUrlA = "https://serverA.plex.direct:32400"
    private val testServerUrlB = "https://serverB.plex.direct:32400"
    private val testTokenA = "token-library-A"
    private val testTokenB = "token-library-B"

    @Before
    fun setup() {
        Mockito.lenient().`when`(mockPrefsRepo.offlineMode).thenReturn(false)

        trackRepository =
            TrackRepository(
                trackDao = mockTrackDao,
                prefsRepo = mockPrefsRepo,
                plexMediaService = mockPlexMediaService,
                plexPrefs = mockPlexPrefs,
                scopedPlexServiceFactory = mockScopedPlexServiceFactory,
                serverConnectionResolver = mockServerConnectionResolver,
                bookRepository = mockBookRepository,
            )
    }

    /**
     * TEST: fetchNetworkTracksForBook uses library-specific server connection
     *
     * This is the core fix: when fetching tracks for a book from library B,
     * TrackRepository should resolve library B's server connection and use it,
     * NOT the active library's server.
     */
    @Test
    fun `fetchNetworkTracksForBook uses library-specific server connection`() =
        runTest {
            // Given: Library B has its own server URL and token
            val connectionB = ServerConnection(testServerUrlB, testTokenB)
            whenever(mockServerConnectionResolver.resolve(testLibraryIdB)).thenReturn(connectionB)
            whenever(mockScopedPlexServiceFactory.getOrCreateService(connectionB)).thenReturn(mockScopedService)

            // Mock the API response
            val mockTrack = createMockPlexDirectory(ratingKey = 101, title = "Track 1")
            val mockResponse = createMockPlexResponse(listOf(mockTrack))
            whenever(mockScopedService.retrieveTracksForAlbum(testBookIdNumeric)).thenReturn(mockResponse)

            // When: Fetching tracks for a book in library B
            val tracks = trackRepository.fetchNetworkTracksForBook(testBookId, testLibraryIdB)

            // Then: Should use library B's scoped service (not the global plexMediaService)
            verify(mockServerConnectionResolver).resolve(testLibraryIdB)
            verify(mockScopedPlexServiceFactory).getOrCreateService(connectionB)
            verify(mockScopedService).retrieveTracksForAlbum(testBookIdNumeric)

            // And: Tracks should be tagged with library B's ID
            assertThat(tracks).hasSize(1)
            assertThat(tracks[0].libraryId).isEqualTo(testLibraryIdB)
            assertThat(tracks[0].id).isEqualTo("plex:101")
        }

    /**
     * TEST: loadTracksForAudiobook uses library-specific server connection
     *
     * Verifies that loadTracksForAudiobook (the main entry point) correctly
     * resolves and uses library-specific connections.
     */
    @Test
    fun `loadTracksForAudiobook uses library-specific server connection`() =
        runTest {
            // Given: Library A has connection info
            val connectionA = ServerConnection(testServerUrlA, testTokenA)
            whenever(mockServerConnectionResolver.resolve(testLibraryIdA)).thenReturn(connectionA)
            whenever(mockScopedPlexServiceFactory.getOrCreateService(connectionA)).thenReturn(mockScopedService)

            // Mock track data
            val mockTrack = createMockPlexDirectory(ratingKey = 202, title = "Track 2")
            val mockResponse = createMockPlexResponse(listOf(mockTrack))
            whenever(mockScopedService.retrieveTracksForAlbum(testBookIdNumeric)).thenReturn(mockResponse)

            // Mock DAO
            whenever(mockTrackDao.getAllTracksAsync()).thenReturn(emptyList())

            // When: Loading tracks for book in library A
            val result = trackRepository.loadTracksForAudiobook(testBookId, testLibraryIdA, forceUseNetwork = false)

            // Then: Should succeed and use library A's connection
            assertThat(result.isOk).isTrue()
            verify(mockServerConnectionResolver).resolve(testLibraryIdA)
            verify(mockScopedPlexServiceFactory).getOrCreateService(connectionA)

            // And: Should insert tracks tagged with library A
            verify(mockTrackDao).insertAll(any())
        }

    /**
     * TEST: Tracks from library B don't get tagged with library A's ID
     *
     * This tests the bug scenario: a book from library B should NOT get
     * its tracks tagged with library A's ID even if library A is active.
     */
    @Test
    fun `tracks from library B are not tagged with active library A ID`() =
        runTest {
            // Given: Library B connection
            val connectionB = ServerConnection(testServerUrlB, testTokenB)
            whenever(mockServerConnectionResolver.resolve(testLibraryIdB)).thenReturn(connectionB)
            whenever(mockScopedPlexServiceFactory.getOrCreateService(connectionB)).thenReturn(mockScopedService)

            // Mock tracks response
            val mockTrack1 = createMockPlexDirectory(ratingKey = 301, title = "Track 1")
            val mockTrack2 = createMockPlexDirectory(ratingKey = 302, title = "Track 2")
            val mockResponse = createMockPlexResponse(listOf(mockTrack1, mockTrack2))
            whenever(mockScopedService.retrieveTracksForAlbum(testBookIdNumeric)).thenReturn(mockResponse)

            // When: Fetching tracks for book in library B
            val tracks = trackRepository.fetchNetworkTracksForBook(testBookId, testLibraryIdB)

            // Then: All tracks should have library B's ID, not library A
            assertThat(tracks).hasSize(2)
            tracks.forEach { track ->
                assertThat(track.libraryId).isEqualTo(testLibraryIdB)
                assertThat(track.libraryId).isNotEqualTo(testLibraryIdA)
            }
        }

    /**
     * TEST: Returns empty list when server connection cannot be resolved
     *
     * Graceful degradation when library is not found in database.
     */
    @Test
    fun `fetchNetworkTracksForBook returns empty list when no valid connection`() =
        runTest {
            // Given: Server connection with missing fields
            val invalidConnection = ServerConnection(null, null)
            whenever(mockServerConnectionResolver.resolve(testLibraryIdB)).thenReturn(invalidConnection)

            // When: Fetching tracks
            val tracks = trackRepository.fetchNetworkTracksForBook(testBookId, testLibraryIdB)

            // Then: Should return empty list (graceful failure)
            assertThat(tracks).isEmpty()
        }

    // ===== Helper methods =====

    /**
     * Creates a mock PlexDirectory (track) for testing.
     */
    private fun createMockPlexDirectory(
        ratingKey: Int,
        title: String,
    ): PlexDirectory {
        val part =
            Part(
                key = "/library/parts/$ratingKey/1/file.mp3",
                size = 5000000L,
            )
        val media = Media(part = listOf(part))

        return PlexDirectory(
            ratingKey = ratingKey.toString(),
            parentRatingKey = testBookIdNumeric,
            title = title,
            parentTitle = "Test Album",
            grandparentTitle = "Test Author",
            index = 1,
            parentIndex = 1,
            duration = 180000L,
            viewOffset = 0L,
            thumb = "/library/thumb/$ratingKey",
            media = listOf(media),
            lastViewedAt = 0L,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Creates a mock PlexMediaContainerWrapper response.
     */
    private fun createMockPlexResponse(tracks: List<PlexDirectory>): PlexMediaContainerWrapper {
        val container =
            PlexMediaContainer(
                size = tracks.size.toLong(),
                totalSize = tracks.size.toLong(),
                offset = 0L,
                metadata = tracks,
            )
        return PlexMediaContainerWrapper(container)
    }
}
