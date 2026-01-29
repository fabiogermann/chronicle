package local.oss.chronicle.features.library

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
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.LibrarySyncRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.plex.ICachedFileManager
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
 * Tests for [LibraryViewModel] to verify unified library behavior.
 *
 * These tests verify that the ViewModel correctly displays books from ALL libraries,
 * not just the active library.
 */
@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner.Silent::class)
class LibraryViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Mock
    private lateinit var bookRepository: IBookRepository

    @Mock
    private lateinit var trackRepository: ITrackRepository

    @Mock
    private lateinit var prefsRepo: PrefsRepo

    @Mock
    private lateinit var cachedFileManager: ICachedFileManager

    @Mock
    private lateinit var librarySyncRepository: LibrarySyncRepository

    @Mock
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var viewModel: LibraryViewModel

    private val testBooks = listOf(
        Audiobook(
            id = "plex:1",
            source = 0L,
            title = "Book from Library A",
            titleSort = "Book from Library A",
            author = "Author A",
            libraryId = "plex:library:1",
            thumb = "",
            duration = 3600000L,
            progress = 0L,
        ),
        Audiobook(
            id = "plex:2",
            source = 0L,
            title = "Book from Library B",
            titleSort = "Book from Library B",
            author = "Author B",
            libraryId = "plex:library:2",
            thumb = "",
            duration = 7200000L,
            progress = 0L,
        ),
        Audiobook(
            id = "plex:3",
            source = 0L,
            title = "Another Book from Library A",
            titleSort = "Another Book from Library A",
            author = "Author C",
            libraryId = "plex:library:1",
            thumb = "",
            duration = 5400000L,
            progress = 0L,
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
        `when`(trackRepository.getAllTracks()).thenReturn(MutableLiveData(emptyList()))

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
    fun `books from all libraries are displayed`() {
        runBlocking {
            // Given: Books from multiple libraries (library A and library B)
            val allBooksLiveData = MutableLiveData(testBooks)
            `when`(bookRepository.getAllBooks()).thenReturn(allBooksLiveData)

            // When: ViewModel is created
            viewModel = LibraryViewModel(
                bookRepository,
                trackRepository,
                prefsRepo,
                cachedFileManager,
                librarySyncRepository,
                sharedPreferences,
            )

            // Then: Books LiveData should be initialized
            assertThat(viewModel.books, notNullValue())

            // Verify that getAllBooks() was called (not filtered by library)
            verify(bookRepository).getAllBooks()
            verify(bookRepository, never()).search(anyString())
        }
    }

    @Test
    fun `getAllBooks repository method is called without library filter`() {
        runBlocking {
            // Given: Repository returns books from multiple libraries
            val allBooksLiveData = MutableLiveData(testBooks)
            `when`(bookRepository.getAllBooks()).thenReturn(allBooksLiveData)

            // When: ViewModel is created
            viewModel = LibraryViewModel(
                bookRepository,
                trackRepository,
                prefsRepo,
                cachedFileManager,
                librarySyncRepository,
                sharedPreferences,
            )

            // Then: Verify getAllBooks() is called (aggregates all libraries)
            verify(bookRepository, times(1)).getAllBooks()

            // Verify no library-specific filtering methods are called
            verify(bookRepository, never()).getAudiobook(anyString())
        }
    }

    @Test
    fun `search queries all books without library filter`() {
        runBlocking {
            // Given: ViewModel with books from multiple libraries
            val allBooksLiveData = MutableLiveData(testBooks)
            val searchResultsLiveData = MutableLiveData(
                listOf(testBooks[0]), // Book from Library A
            )
            `when`(bookRepository.getAllBooks()).thenReturn(allBooksLiveData)
            `when`(bookRepository.search(anyString())).thenReturn(searchResultsLiveData)

            viewModel = LibraryViewModel(
                bookRepository,
                trackRepository,
                prefsRepo,
                cachedFileManager,
                librarySyncRepository,
                sharedPreferences,
            )

            // When: User searches for a book
            viewModel.search("Book from Library A")

            // Then: Search should query all books, not filtered by library
            verify(bookRepository).search("Book from Library A")
        }
    }

    @Test
    fun `refreshData triggers library sync for all libraries`() {
        runBlocking {
            // Given: ViewModel is initialized
            val allBooksLiveData = MutableLiveData(testBooks)
            `when`(bookRepository.getAllBooks()).thenReturn(allBooksLiveData)

            viewModel = LibraryViewModel(
                bookRepository,
                trackRepository,
                prefsRepo,
                cachedFileManager,
                librarySyncRepository,
                sharedPreferences,
            )

            // When: User triggers refresh
            viewModel.refreshData()

            // Then: LibrarySyncRepository should sync all libraries
            verify(librarySyncRepository).refreshLibrary()
        }
    }

    @Test
    fun `tracks from all libraries are queried`() {
        runBlocking {
            // Given: Tracks from multiple libraries
            val testTracks = listOf(
                MediaItemTrack(
                    id = "plex:101",
                    source = 0L,
                    album = "plex:1",
                    libraryId = "plex:library:1",
                    title = "Track 1",
                    discNumber = 1,
                    index = 1,
                    size = 1024L,
                    duration = 1800000L,
                ),
                MediaItemTrack(
                    id = "plex:102",
                    source = 0L,
                    album = "plex:2",
                    libraryId = "plex:library:2",
                    title = "Track 2",
                    discNumber = 1,
                    index = 1,
                    size = 2048L,
                    duration = 3600000L,
                ),
            )

            val tracksLiveData = MutableLiveData(testTracks)
            val allBooksLiveData = MutableLiveData(testBooks)

            `when`(bookRepository.getAllBooks()).thenReturn(allBooksLiveData)
            `when`(trackRepository.getAllTracks()).thenReturn(tracksLiveData)

            // When: ViewModel is created
            viewModel = LibraryViewModel(
                bookRepository,
                trackRepository,
                prefsRepo,
                cachedFileManager,
                librarySyncRepository,
                sharedPreferences,
            )

            // Then: getAllTracks should be called (not filtered by library)
            verify(trackRepository).getAllTracks()

            // Verify tracks LiveData is initialized
            assertThat(viewModel.tracks, notNullValue())
        }
    }
}
