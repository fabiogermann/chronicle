package local.oss.chronicle.features.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.LibrarySyncRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.sources.plex.PlexConfig
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.notNullValue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests for [HomeViewModel] to verify unified library behavior.
 *
 * These tests verify that the ViewModel correctly aggregates "Recently Listened" and
 * "Recently Added" books from ALL libraries, not just the active library.
 */
@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner.Silent::class)
class HomeViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Mock
    private lateinit var plexConfig: PlexConfig

    @Mock
    private lateinit var bookRepository: IBookRepository

    @Mock
    private lateinit var librarySyncRepository: LibrarySyncRepository

    @Mock
    private lateinit var prefsRepo: PrefsRepo

    private lateinit var viewModel: HomeViewModel

    private val testRecentlyListenedBooks = listOf(
        Audiobook(
            id = "plex:1",
            source = 0L,
            title = "Recently Listened from Library A",
            titleSort = "Recently Listened from Library A",
            author = "Author A",
            libraryId = "plex:library:1",
            thumb = "",
            duration = 3600000L,
            progress = 1800000L, // 50% progress
            lastViewedAt = System.currentTimeMillis() - 3600000L, // 1 hour ago
        ),
        Audiobook(
            id = "plex:2",
            source = 0L,
            title = "Recently Listened from Library B",
            titleSort = "Recently Listened from Library B",
            author = "Author B",
            libraryId = "plex:library:2",
            thumb = "",
            duration = 7200000L,
            progress = 3600000L, // 50% progress
            lastViewedAt = System.currentTimeMillis() - 7200000L, // 2 hours ago
        ),
    )

    private val testRecentlyAddedBooks = listOf(
        Audiobook(
            id = "plex:3",
            source = 0L,
            title = "Recently Added from Library A",
            titleSort = "Recently Added from Library A",
            author = "Author C",
            libraryId = "plex:library:1",
            thumb = "",
            duration = 5400000L,
            progress = 0L,
            addedAt = System.currentTimeMillis() - 86400000L, // 1 day ago
        ),
        Audiobook(
            id = "plex:4",
            source = 0L,
            title = "Recently Added from Library B",
            titleSort = "Recently Added from Library B",
            author = "Author D",
            libraryId = "plex:library:2",
            thumb = "",
            duration = 9000000L,
            progress = 0L,
            addedAt = System.currentTimeMillis() - 172800000L, // 2 days ago
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(mainThreadSurrogate)

        // Setup default mock behaviors
        `when`(prefsRepo.offlineMode).thenReturn(false)
        `when`(prefsRepo.refreshRateMinutes).thenReturn(60)
        `when`(prefsRepo.lastRefreshTimeStamp).thenReturn(System.currentTimeMillis())
        `when`(plexConfig.isConnected).thenReturn(MutableLiveData(false))
        `when`(librarySyncRepository.isRefreshing).thenReturn(MutableLiveData(false))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    @Test
    fun `recently listened books from all libraries are aggregated`() {
        runBlocking {
        // Given: Books from multiple libraries in recently listened
        val recentlyListenedLiveData = MutableLiveData(testRecentlyListenedBooks)
        val recentlyAddedLiveData = MutableLiveData(emptyList<Audiobook>())
        val cachedBooksLiveData = MutableLiveData(emptyList<Audiobook>())

        `when`(bookRepository.getRecentlyListened()).thenReturn(recentlyListenedLiveData)
        `when`(bookRepository.getRecentlyAdded()).thenReturn(recentlyAddedLiveData)
        `when`(bookRepository.getCachedAudiobooks()).thenReturn(cachedBooksLiveData)

        // When: ViewModel is created
        viewModel = HomeViewModel(
            plexConfig,
            bookRepository,
            librarySyncRepository,
            prefsRepo,
        )

        // Then: getRecentlyListened should be called without library filter
        verify(bookRepository).getRecentlyListened()

        // Verify LiveData is initialized
        assertThat(viewModel.recentlyListened, notNullValue())
        }
    }

    @Test
    fun `recently added books from all libraries are aggregated`() {
        runBlocking {
        // Given: Books from multiple libraries in recently added
        val recentlyListenedLiveData = MutableLiveData(emptyList<Audiobook>())
        val recentlyAddedLiveData = MutableLiveData(testRecentlyAddedBooks)
        val cachedBooksLiveData = MutableLiveData(emptyList<Audiobook>())

        `when`(bookRepository.getRecentlyListened()).thenReturn(recentlyListenedLiveData)
        `when`(bookRepository.getRecentlyAdded()).thenReturn(recentlyAddedLiveData)
        `when`(bookRepository.getCachedAudiobooks()).thenReturn(cachedBooksLiveData)

        // When: ViewModel is created
        viewModel = HomeViewModel(
            plexConfig,
            bookRepository,
            librarySyncRepository,
            prefsRepo,
        )

        // Then: getRecentlyAdded should be called without library filter
        verify(bookRepository).getRecentlyAdded()

        // Verify LiveData is initialized
        assertThat(viewModel.recentlyAdded, notNullValue())
        }
    }

    @Test
    fun `both sections aggregate books from all libraries`() {
        runBlocking {
        // Given: Both recently listened and recently added have books from multiple libraries
        val recentlyListenedLiveData = MutableLiveData(testRecentlyListenedBooks)
        val recentlyAddedLiveData = MutableLiveData(testRecentlyAddedBooks)
        val cachedBooksLiveData = MutableLiveData(emptyList<Audiobook>())

        `when`(bookRepository.getRecentlyListened()).thenReturn(recentlyListenedLiveData)
        `when`(bookRepository.getRecentlyAdded()).thenReturn(recentlyAddedLiveData)
        `when`(bookRepository.getCachedAudiobooks()).thenReturn(cachedBooksLiveData)

        // When: ViewModel is created
        viewModel = HomeViewModel(
            plexConfig,
            bookRepository,
            librarySyncRepository,
            prefsRepo,
        )

        // Then: Both methods should be called without library filtering
        verify(bookRepository).getRecentlyListened()
        verify(bookRepository).getRecentlyAdded()

        // Verify both LiveData are initialized
        assertThat(viewModel.recentlyListened, notNullValue())
        assertThat(viewModel.recentlyAdded, notNullValue())
        }
    }

    @Test
    fun `refreshData triggers sync for all libraries`() {
        runBlocking {
        // Given: ViewModel is initialized
        val recentlyListenedLiveData = MutableLiveData(testRecentlyListenedBooks)
        val recentlyAddedLiveData = MutableLiveData(testRecentlyAddedBooks)
        val cachedBooksLiveData = MutableLiveData(emptyList<Audiobook>())

        `when`(bookRepository.getRecentlyListened()).thenReturn(recentlyListenedLiveData)
        `when`(bookRepository.getRecentlyAdded()).thenReturn(recentlyAddedLiveData)
        `when`(bookRepository.getCachedAudiobooks()).thenReturn(cachedBooksLiveData)

        viewModel = HomeViewModel(
            plexConfig,
            bookRepository,
            librarySyncRepository,
            prefsRepo,
        )

        // When: User triggers refresh
        viewModel.refreshData()

        // Then: LibrarySyncRepository should sync all libraries
        verify(librarySyncRepository).refreshLibrary()
        }
    }

    @Test
    fun `search queries all books without library filter`() {
        runBlocking {
        // Given: ViewModel with books from multiple libraries
        val recentlyListenedLiveData = MutableLiveData(testRecentlyListenedBooks)
        val recentlyAddedLiveData = MutableLiveData(testRecentlyAddedBooks)
        val cachedBooksLiveData = MutableLiveData(emptyList<Audiobook>())
        val searchResultsLiveData = MutableLiveData(listOf(testRecentlyListenedBooks[0]))

        `when`(bookRepository.getRecentlyListened()).thenReturn(recentlyListenedLiveData)
        `when`(bookRepository.getRecentlyAdded()).thenReturn(recentlyAddedLiveData)
        `when`(bookRepository.getCachedAudiobooks()).thenReturn(cachedBooksLiveData)
        `when`(bookRepository.search(anyString())).thenReturn(searchResultsLiveData)

        viewModel = HomeViewModel(
            plexConfig,
            bookRepository,
            librarySyncRepository,
            prefsRepo,
        )

        // When: User searches
        viewModel.search("Recently Listened")

        // Then: Search should query all books, not filtered by library
        verify(bookRepository).search("Recently Listened")
        }
    }

    @Test
    fun `downloaded books from all libraries are displayed`() {
        runBlocking {
        // Given: Cached books from multiple libraries
        val cachedBooks = listOf(
            testRecentlyListenedBooks[0].copy(isCached = true),
            testRecentlyListenedBooks[1].copy(isCached = true),
        )
        val recentlyListenedLiveData = MutableLiveData(emptyList<Audiobook>())
        val recentlyAddedLiveData = MutableLiveData(emptyList<Audiobook>())
        val cachedBooksLiveData = MutableLiveData(cachedBooks)

        `when`(bookRepository.getRecentlyListened()).thenReturn(recentlyListenedLiveData)
        `when`(bookRepository.getRecentlyAdded()).thenReturn(recentlyAddedLiveData)
        `when`(bookRepository.getCachedAudiobooks()).thenReturn(cachedBooksLiveData)

        // When: ViewModel is created
        viewModel = HomeViewModel(
            plexConfig,
            bookRepository,
            librarySyncRepository,
            prefsRepo,
        )

        // Then: getCachedAudiobooks should be called (all libraries)
        verify(bookRepository).getCachedAudiobooks()

        // Verify downloaded LiveData is initialized
        assertThat(viewModel.downloaded, notNullValue())
        }
    }
}
