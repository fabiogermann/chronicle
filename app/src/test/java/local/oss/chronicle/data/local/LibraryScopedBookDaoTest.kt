package local.oss.chronicle.data.local

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.model.Audiobook
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for library-scoped BookDao queries.
 * Verifies that books are properly isolated by libraryId.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LibraryScopedBookDaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: BookDatabase
    private lateinit var bookDao: BookDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bookDao = database.bookDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ===== Library Scoping Tests =====

    @Test
    fun `books with same plex id but different libraries are separate`() = runTest {
        // Same Plex ID (12345) but different libraries
        val book1 = createTestAudiobook(
            id = "plex:12345",
            libraryId = "plex:library:1",
            title = "Book from Library 1"
        )
        val book2 = createTestAudiobook(
            id = "plex:12345", // Same Plex ID
            libraryId = "plex:library:2",
            title = "Book from Library 2"
        )
        
        // Note: These would have different compound keys in real implementation
        // For now, test the concept
        bookDao.insertOrUpdate(book1)
        bookDao.insertOrUpdate(book2)
        
        val booksInLib1 = bookDao.getBooksForLibrary("plex:library:1").first()
        val booksInLib2 = bookDao.getBooksForLibrary("plex:library:2").first()
        
        assertThat(booksInLib1).hasSize(1)
        assertThat(booksInLib1[0].title).isEqualTo("Book from Library 1")
        
        assertThat(booksInLib2).hasSize(1)
        assertThat(booksInLib2[0].title).isEqualTo("Book from Library 2")
    }

    @Test
    fun `getAllBooks for library excludes other libraries`() = runTest {
        val lib1Book1 = createTestAudiobook(id = "plex:1", libraryId = "lib1", title = "L1 Book 1")
        val lib1Book2 = createTestAudiobook(id = "plex:2", libraryId = "lib1", title = "L1 Book 2")
        val lib2Book1 = createTestAudiobook(id = "plex:3", libraryId = "lib2", title = "L2 Book 1")
        
        bookDao.insertOrUpdate(lib1Book1)
        bookDao.insertOrUpdate(lib1Book2)
        bookDao.insertOrUpdate(lib2Book1)
        
        val lib1Books = bookDao.getBooksForLibrary("lib1").first()
        assertThat(lib1Books).hasSize(2)
        assertThat(lib1Books.map { it.title }).containsExactly("L1 Book 1", "L1 Book 2")
    }

    @Test
    fun `search only searches within specified library`() = runTest {
        val lib1Book = createTestAudiobook(
            id = "plex:1",
            libraryId = "lib1",
            title = "Harry Potter"
        )
        val lib2Book = createTestAudiobook(
            id = "plex:2",
            libraryId = "lib2",
            title = "Harry Potter" // Same title, different library
        )
        
        bookDao.insertOrUpdate(lib1Book)
        bookDao.insertOrUpdate(lib2Book)
        
        val lib1Results = bookDao.searchBooksInLibrary("lib1", "%Harry%").first()
        assertThat(lib1Results).hasSize(1)
        assertThat(lib1Results[0].libraryId).isEqualTo("lib1")
    }

    @Test
    fun `favorites are library-specific`() = runTest {
        val lib1Book = createTestAudiobook(
            id = "plex:1",
            libraryId = "lib1",
            title = "Book 1",
            favorite = true
        )
        val lib2Book = createTestAudiobook(
            id = "plex:2",
            libraryId = "lib2",
            title = "Book 2",
            favorite = true
        )
        
        bookDao.insertOrUpdate(lib1Book)
        bookDao.insertOrUpdate(lib2Book)
        
        val lib1Favorites = bookDao.getFavoritesForLibrary("lib1").first()
        assertThat(lib1Favorites).hasSize(1)
        assertThat(lib1Favorites[0].id).isEqualTo("plex:1")
    }

    @Test
    fun `recently listened scoped to library`() = runTest {
        val lib1Book = createTestAudiobook(
            id = "plex:1",
            libraryId = "lib1",
            lastListenedAt = 1000L
        )
        val lib2Book = createTestAudiobook(
            id = "plex:2",
            libraryId = "lib2",
            lastListenedAt = 2000L // More recent
        )
        
        bookDao.insertOrUpdate(lib1Book)
        bookDao.insertOrUpdate(lib2Book)
        
        val lib1Recent = bookDao.getRecentlyListenedForLibrary("lib1", limit = 10).first()
        assertThat(lib1Recent).hasSize(1)
        assertThat(lib1Recent[0].id).isEqualTo("plex:1")
    }

    @Test
    fun `deleting books for library preserves other libraries`() = runTest {
        val lib1Book = createTestAudiobook(id = "plex:1", libraryId = "lib1")
        val lib2Book = createTestAudiobook(id = "plex:2", libraryId = "lib2")
        
        bookDao.insertOrUpdate(lib1Book)
        bookDao.insertOrUpdate(lib2Book)
        
        bookDao.deleteByLibraryId("lib1")
        
        val lib1Books = bookDao.getBooksForLibrary("lib1").first()
        val lib2Books = bookDao.getBooksForLibrary("lib2").first()
        
        assertThat(lib1Books).isEmpty()
        assertThat(lib2Books).hasSize(1)
    }

    // ===== Helper Methods =====

    private fun createTestAudiobook(
        id: String,
        libraryId: String,
        title: String = "Test Book",
        author: String = "Test Author",
        favorite: Boolean = false,
        lastListenedAt: Long? = null
    ): Audiobook {
        return Audiobook(
            id = id,
            libraryId = libraryId,
            title = title,
            author = author,
            thumb = null,
            duration = 3600000L,
            addedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            lastListenedAt = lastListenedAt,
            playbackProgress = 0L,
            lastListenedTrackKey = null,
            cachedState = 0,
            favorite = favorite,
            playlistTrackKey = null
        )
    }
}
