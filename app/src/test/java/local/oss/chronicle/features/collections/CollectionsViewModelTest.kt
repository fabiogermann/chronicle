package local.oss.chronicle.features.collections

import android.content.SharedPreferences
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.local.BookRepository
import local.oss.chronicle.data.local.CollectionsRepository
import local.oss.chronicle.data.local.LibrarySyncRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Collection
import local.oss.chronicle.data.sources.plex.PlexMediaSource
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
 * Tests for [CollectionsViewModel] to verify unified library behavior.
 *
 * These tests verify that the ViewModel correctly aggregates collections from ALL libraries,
 * not just the active library.
 */
@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner.Silent::class)
class CollectionsViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Mock
    private lateinit var prefsRepo: PrefsRepo

    @Mock
    private lateinit var collectionsRepository: CollectionsRepository

    @Mock
    private lateinit var librarySyncRepository: LibrarySyncRepository

    @Mock
    private lateinit var sharedPreferences: SharedPreferences

    @Mock
    private lateinit var bookRepository: BookRepository

    private lateinit var viewModel: CollectionsViewModel

    private val testCollections = listOf(
        Collection(
            id = 1,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Fantasy Series from Library A",
            childCount = 5L,
            thumb = "/library/1/collections/1/thumb",
            childIds = listOf(1, 2, 3, 4, 5),
        ),
        Collection(
            id = 2,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Mystery Series from Library B",
            childCount = 3L,
            thumb = "/library/2/collections/2/thumb",
            childIds = listOf(6, 7, 8),
        ),
        Collection(
            id = 3,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Sci-Fi Collection from Library A",
            childCount = 7L,
            thumb = "/library/1/collections/3/thumb",
            childIds = listOf(9, 10, 11, 12, 13, 14, 15),
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(mainThreadSurrogate)

        // Setup default mock behaviors
        `when`(prefsRepo.libraryBookViewStyle).thenReturn("grid")
        `when`(prefsRepo.offlineMode).thenReturn(false)
        `when`(prefsRepo.isLibrarySortedDescending).thenReturn(true)
        `when`(prefsRepo.bookSortKey).thenReturn("title")
        `when`(prefsRepo.hidePlayedAudiobooks).thenReturn(false)
        `when`(librarySyncRepository.isRefreshing).thenReturn(MutableLiveData(false))

        // Mock SharedPreferences to return null for all keys (will use defaults)
        `when`(sharedPreferences.getString(anyString(), anyString())).thenReturn(null)
        `when`(sharedPreferences.getBoolean(anyString(), anyBoolean())).thenAnswer { invocation ->
            invocation.getArgument<Boolean>(1) // Return the default value
        }
        `when`(sharedPreferences.registerOnSharedPreferenceChangeListener(any())).then { }
        `when`(sharedPreferences.unregisterOnSharedPreferenceChangeListener(any())).then { }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    @Test
    fun `collections from all libraries are aggregated`() {
        runBlocking {
        // Given: Collections from multiple libraries (identifiable by their thumb paths)
        val allCollectionsLiveData = MutableLiveData(testCollections)
        `when`(collectionsRepository.getAllCollections()).thenReturn(allCollectionsLiveData)

        // When: ViewModel is created
        viewModel = CollectionsViewModel(
            prefsRepo,
            librarySyncRepository,
            collectionsRepository,
            sharedPreferences,
            bookRepository,
        )

        // Then: getAllCollections should be called (not filtered by library)
        verify(collectionsRepository).getAllCollections()

        // Verify collections LiveData is initialized
        assertThat(viewModel.collections, notNullValue())
        }
    }

    @Test
    fun `getAllCollections repository method is called without library filter`() {
        runBlocking {
        // Given: Repository configured to return collections from multiple libraries
        val allCollectionsLiveData = MutableLiveData(testCollections)
        `when`(collectionsRepository.getAllCollections()).thenReturn(allCollectionsLiveData)

        // When: ViewModel is created
        viewModel = CollectionsViewModel(
            prefsRepo,
            librarySyncRepository,
            collectionsRepository,
            sharedPreferences,
            bookRepository,
        )

        // Then: Verify getAllCollections is called (aggregates all libraries)
        verify(collectionsRepository, times(1)).getAllCollections()

        // Verify no library-specific methods are called
        verify(collectionsRepository, never()).getCollection(anyInt())
        }
    }

    @Test
    fun `refreshData triggers sync for all libraries`() {
        runBlocking {
        // Given: ViewModel is initialized
        val allCollectionsLiveData = MutableLiveData(testCollections)
        `when`(collectionsRepository.getAllCollections()).thenReturn(allCollectionsLiveData)

        viewModel = CollectionsViewModel(
            prefsRepo,
            librarySyncRepository,
            collectionsRepository,
            sharedPreferences,
            bookRepository,
        )

        // When: User triggers refresh
        viewModel.refreshData()

        // Then: LibrarySyncRepository should sync all libraries (including collections)
        verify(librarySyncRepository).refreshLibrary()
        }
    }

    @Test
    fun `search queries books from all libraries`() {
        runBlocking {
        // Given: ViewModel with collections from multiple libraries
        val allCollectionsLiveData = MutableLiveData(testCollections)
        val searchResultsLiveData = MutableLiveData(
            listOf(
                Audiobook(
                    id = "plex:1",
                    source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                    title = "Fantasy Book",
                    titleSort = "Fantasy Book",
                    author = "Author A",
                    libraryId = "plex:library:1",
                    thumb = "",
                    duration = 3600000L,
                    progress = 0L,
                ),
            ),
        )

        `when`(collectionsRepository.getAllCollections()).thenReturn(allCollectionsLiveData)
        `when`(bookRepository.search(anyString())).thenReturn(searchResultsLiveData)

        viewModel = CollectionsViewModel(
            prefsRepo,
            librarySyncRepository,
            collectionsRepository,
            sharedPreferences,
            bookRepository,
        )

        // When: User searches for a book
        viewModel.search("Fantasy")

        // Then: Search should query all books, not filtered by library
        verify(bookRepository).search("Fantasy")
        }
    }

    @Test
    fun `collections from different libraries are not filtered`() {
        runBlocking {
        // Given: Collections with different thumb paths indicating different libraries
        val mixedCollections = listOf(
            Collection(
                id = 10,
                source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                title = "Collection A",
                childCount = 2L,
                thumb = "/library/1/collections/10/thumb", // Library 1
                childIds = listOf(100, 101),
            ),
            Collection(
                id = 20,
                source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                title = "Collection B",
                childCount = 3L,
                thumb = "/library/2/collections/20/thumb", // Library 2
                childIds = listOf(200, 201, 202),
            ),
            Collection(
                id = 30,
                source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                title = "Collection C",
                childCount = 1L,
                thumb = "/library/3/collections/30/thumb", // Library 3
                childIds = listOf(300),
            ),
        )

        val allCollectionsLiveData = MutableLiveData(mixedCollections)
        `when`(collectionsRepository.getAllCollections()).thenReturn(allCollectionsLiveData)

        // When: ViewModel is created
        viewModel = CollectionsViewModel(
            prefsRepo,
            librarySyncRepository,
            collectionsRepository,
            sharedPreferences,
            bookRepository,
        )

        // Then: All collections should be loaded without filtering
        verify(collectionsRepository).getAllCollections()

        // Verify collections LiveData is initialized
        assertThat(viewModel.collections, notNullValue())
        }
    }

    @Test
    fun `empty search returns empty results without library filter`() {
        runBlocking {
        // Given: ViewModel with collections
        val allCollectionsLiveData = MutableLiveData(testCollections)
        `when`(collectionsRepository.getAllCollections()).thenReturn(allCollectionsLiveData)

        viewModel = CollectionsViewModel(
            prefsRepo,
            librarySyncRepository,
            collectionsRepository,
            sharedPreferences,
            bookRepository,
        )

        // When: User searches with empty query
        viewModel.search("")

        // Then: No search should be performed on the repository
        verify(bookRepository, never()).search(anyString())
        }
    }
}
