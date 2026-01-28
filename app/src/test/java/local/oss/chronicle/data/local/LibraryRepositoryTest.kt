package local.oss.chronicle.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.account.AccountTestFixtures
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class LibraryRepositoryTest {

    @Mock
    private lateinit var libraryDao: LibraryDao

    private lateinit var repository: LibraryRepository

    @Before
    fun setup() {
        repository = LibraryRepository(libraryDao)
    }

    // ===== Insert Operations =====

    @Test
    fun `addLibrary delegates to dao insert`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123")

        repository.addLibrary(library)

        verify(libraryDao).insert(library)
    }

    @Test
    fun `addLibraries delegates to dao insertAll`() = runTest {
        val libraries = listOf(
            AccountTestFixtures.createPlexLibrary(accountId = "account-123"),
            AccountTestFixtures.createPlexLibrary(accountId = "account-123")
        )

        repository.addLibraries(libraries)

        verify(libraryDao).insertAll(libraries)
    }

    // ===== Read Operations =====

    @Test
    fun `getLibraryById delegates to dao getById`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123")
        whenever(libraryDao.getById(library.id)).thenReturn(library)

        val result = repository.getLibraryById(library.id)

        assertThat(result).isEqualTo(library)
        verify(libraryDao).getById(library.id)
    }

    @Test
    fun `getAllLibraries delegates to dao getAllLibraries`() = runTest {
        repository.getAllLibraries()

        verify(libraryDao).getAllLibraries()
    }

    @Test
    fun `getLibrariesForAccount delegates to dao getLibrariesForAccount`() = runTest {
        val accountId = "account-123"

        repository.getLibrariesForAccount(accountId)

        verify(libraryDao).getLibrariesForAccount(accountId)
    }

    @Test
    fun `getLibrariesByServerId delegates to dao getLibrariesByServerId`() = runTest {
        val serverId = "server-123"

        repository.getLibrariesByServerId(serverId)

        verify(libraryDao).getLibrariesByServerId(serverId)
    }

    // ===== Update Operations =====

    @Test
    fun `updateLibrary delegates to dao update`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123")

        repository.updateLibrary(library)

        verify(libraryDao).update(library)
    }

    @Test
    fun `updateSyncTimestamp delegates to dao updateLastSyncedAt`() = runTest {
        val libraryId = "library-123"
        val timestamp = 1234567890L

        repository.updateSyncTimestamp(libraryId, timestamp)

        verify(libraryDao).updateLastSyncedAt(libraryId, timestamp)
    }

    @Test
    fun `updateItemCount delegates to dao updateItemCount`() = runTest {
        val libraryId = "library-123"
        val count = 42

        repository.updateItemCount(libraryId, count)

        verify(libraryDao).updateItemCount(libraryId, count)
    }

    // ===== Delete Operations =====

    @Test
    fun `removeLibrary delegates to dao delete`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123")

        repository.removeLibrary(library)

        verify(libraryDao).delete(library)
    }

    @Test
    fun `removeLibraryById delegates to dao deleteById`() = runTest {
        val libraryId = "library-123"

        repository.removeLibraryById(libraryId)

        verify(libraryDao).deleteById(libraryId)
    }

    // ===== Active Library Management =====

    @Test
    fun `getActiveLibrary delegates to dao getActiveLibrary`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(
            accountId = "account-123",
            isActive = true
        )
        whenever(libraryDao.getActiveLibrary()).thenReturn(library)

        val result = repository.getActiveLibrary()

        assertThat(result).isEqualTo(library)
        verify(libraryDao).getActiveLibrary()
    }

    @Test
    fun `getActiveLibraryFlow delegates to dao getActiveLibraryFlow`() = runTest {
        repository.getActiveLibraryFlow()

        verify(libraryDao).getActiveLibraryFlow()
    }

    @Test
    fun `setActiveLibrary delegates to dao setActiveLibrary`() = runTest {
        val libraryId = "library-123"

        repository.setActiveLibrary(libraryId)

        verify(libraryDao).setActiveLibrary(libraryId)
    }

    @Test
    fun `clearActiveLibrary delegates to dao deactivateAllLibraries`() = runTest {
        repository.clearActiveLibrary()

        verify(libraryDao).deactivateAllLibraries()
    }
}
