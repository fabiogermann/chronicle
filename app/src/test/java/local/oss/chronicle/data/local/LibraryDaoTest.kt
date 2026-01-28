package local.oss.chronicle.data.local

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.account.AccountTestFixtures
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LibraryDaoTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AccountDatabase
    private lateinit var accountDao: AccountDao
    private lateinit var libraryDao: LibraryDao

    @Before
    fun setup() {
        // Initialize in-memory database for testing
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AccountDatabase::class.java,
            ).allowMainThreadQueries().build()
        accountDao = database.accountDao()
        libraryDao = database.libraryDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ===== CRUD Tests =====

    @Test
    fun `insert library and retrieve by id`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library = AccountTestFixtures.createPlexLibrary(accountId = account.id)
            libraryDao.insert(library)

            val retrieved = libraryDao.getById(library.id)
            assertThat(retrieved).isEqualTo(library)
        }

    @Test
    fun `insert multiple libraries for same account`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library1 = AccountTestFixtures.createPlexLibrary(accountId = account.id, name = "Audiobooks")
            val library2 = AccountTestFixtures.createPlexLibrary(accountId = account.id, name = "Podcasts")

            libraryDao.insert(library1)
            libraryDao.insert(library2)

            val libraries = libraryDao.getLibrariesForAccount(account.id).first()
            assertThat(libraries).hasSize(2)
        }

    @Test
    fun `update library modifies existing record`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library = AccountTestFixtures.createPlexLibrary(accountId = account.id, itemCount = 0)
            libraryDao.insert(library)

            val updated = library.copy(itemCount = 100, lastSyncedAt = System.currentTimeMillis())
            libraryDao.update(updated)

            val retrieved = libraryDao.getById(library.id)
            assertThat(retrieved?.itemCount).isEqualTo(100)
        }

    @Test
    fun `delete library removes record`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library = AccountTestFixtures.createPlexLibrary(accountId = account.id)
            libraryDao.insert(library)

            libraryDao.delete(library)

            val retrieved = libraryDao.getById(library.id)
            assertThat(retrieved).isNull()
        }

    // ===== Foreign Key Tests =====

    @Test
    fun `library requires valid account foreign key`() =
        runTest {
            val library = AccountTestFixtures.createPlexLibrary(accountId = "non-existent-account")

            // This should throw SQLiteConstraintException due to FK violation
            try {
                libraryDao.insert(library)
                // If we get here, FK constraint is not enforced - fail the test
                assertThat(false).isTrue() // Force failure
            } catch (e: Exception) {
                // Expected - FK constraint violation
                assertThat(e).isNotNull()
            }
        }

    @Test
    fun `deleting account cascades to libraries`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library1 = AccountTestFixtures.createPlexLibrary(accountId = account.id, name = "Lib 1")
            val library2 = AccountTestFixtures.createPlexLibrary(accountId = account.id, name = "Lib 2")
            libraryDao.insert(library1)
            libraryDao.insert(library2)

            // Verify libraries exist
            assertThat(libraryDao.getLibrariesForAccount(account.id).first()).hasSize(2)

            // Delete account - should cascade
            accountDao.delete(account)

            // Libraries should be gone
            assertThat(libraryDao.getById(library1.id)).isNull()
            assertThat(libraryDao.getById(library2.id)).isNull()
        }

    // ===== Active Library Tests =====

    @Test
    fun `get active library returns correct library`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val activeLib =
                AccountTestFixtures.createPlexLibrary(
                    accountId = account.id,
                    name = "Active",
                    isActive = true,
                )
            val inactiveLib =
                AccountTestFixtures.createPlexLibrary(
                    accountId = account.id,
                    name = "Inactive",
                    isActive = false,
                )

            libraryDao.insert(activeLib)
            libraryDao.insert(inactiveLib)

            val active = libraryDao.getActiveLibrary()
            assertThat(active?.name).isEqualTo("Active")
        }

    @Test
    fun `only one library can be active at a time`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library1 =
                AccountTestFixtures.createPlexLibrary(
                    accountId = account.id,
                    name = "Lib 1",
                    isActive = true,
                )
            val library2 =
                AccountTestFixtures.createPlexLibrary(
                    accountId = account.id,
                    name = "Lib 2",
                    isActive = false,
                )

            libraryDao.insert(library1)
            libraryDao.insert(library2)

            // Set library2 as active (should deactivate library1)
            libraryDao.setActiveLibrary(library2.id)

            val lib1 = libraryDao.getById(library1.id)
            val lib2 = libraryDao.getById(library2.id)

            assertThat(lib1?.isActive).isFalse()
            assertThat(lib2?.isActive).isTrue()
        }

    @Test
    fun `deactivate all libraries`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library =
                AccountTestFixtures.createPlexLibrary(
                    accountId = account.id,
                    isActive = true,
                )
            libraryDao.insert(library)

            libraryDao.deactivateAllLibraries()

            val retrieved = libraryDao.getById(library.id)
            assertThat(retrieved?.isActive).isFalse()
        }

    // ===== Query Tests =====

    @Test
    fun `get all libraries returns all libraries across accounts`() =
        runTest {
            val account1 = AccountTestFixtures.createPlexAccount(displayName = "User 1")
            val account2 = AccountTestFixtures.createPlexAccount(displayName = "User 2")

            accountDao.insert(account1)
            accountDao.insert(account2)

            libraryDao.insert(AccountTestFixtures.createPlexLibrary(accountId = account1.id))
            libraryDao.insert(AccountTestFixtures.createPlexLibrary(accountId = account1.id))
            libraryDao.insert(AccountTestFixtures.createPlexLibrary(accountId = account2.id))

            val allLibraries = libraryDao.getAllLibraries().first()
            assertThat(allLibraries).hasSize(3)
        }

    @Test
    fun `get libraries for account filters correctly`() =
        runTest {
            val account1 = AccountTestFixtures.createPlexAccount(displayName = "User 1")
            val account2 = AccountTestFixtures.createPlexAccount(displayName = "User 2")

            accountDao.insert(account1)
            accountDao.insert(account2)

            libraryDao.insert(AccountTestFixtures.createPlexLibrary(accountId = account1.id, name = "A1"))
            libraryDao.insert(AccountTestFixtures.createPlexLibrary(accountId = account1.id, name = "A2"))
            libraryDao.insert(AccountTestFixtures.createPlexLibrary(accountId = account2.id, name = "B1"))

            val account1Libs = libraryDao.getLibrariesForAccount(account1.id).first()
            assertThat(account1Libs).hasSize(2)
            assertThat(account1Libs.map { it.name }).containsExactly("A1", "A2")
        }

    @Test
    fun `get libraries by server id`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val serverId = "server-123"
            val lib1 = AccountTestFixtures.createPlexLibrary(accountId = account.id, serverId = serverId)
            val lib2 = AccountTestFixtures.createPlexLibrary(accountId = account.id, serverId = serverId)
            val lib3 = AccountTestFixtures.createPlexLibrary(accountId = account.id, serverId = "other-server")

            libraryDao.insert(lib1)
            libraryDao.insert(lib2)
            libraryDao.insert(lib3)

            val serverLibs = libraryDao.getLibrariesByServerId(serverId).first()
            assertThat(serverLibs).hasSize(2)
        }

    @Test
    fun `update sync timestamp`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library = AccountTestFixtures.createPlexLibrary(accountId = account.id, lastSyncedAt = null)
            libraryDao.insert(library)

            val syncTime = System.currentTimeMillis()
            libraryDao.updateLastSyncedAt(library.id, syncTime)

            val retrieved = libraryDao.getById(library.id)
            assertThat(retrieved?.lastSyncedAt).isEqualTo(syncTime)
        }

    @Test
    fun `update item count`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library = AccountTestFixtures.createPlexLibrary(accountId = account.id, itemCount = 0)
            libraryDao.insert(library)

            libraryDao.updateItemCount(library.id, 42)

            val retrieved = libraryDao.getById(library.id)
            assertThat(retrieved?.itemCount).isEqualTo(42)
        }

    // ===== Flow Tests =====

    @Test
    fun `active library flow emits updates`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            accountDao.insert(account)

            val library =
                AccountTestFixtures.createPlexLibrary(
                    accountId = account.id,
                    isActive = false,
                )
            libraryDao.insert(library)

            // Initially no active library
            val initial = libraryDao.getActiveLibraryFlow().first()
            assertThat(initial).isNull()

            // Activate library
            libraryDao.setActiveLibrary(library.id)
            val afterActivation = libraryDao.getActiveLibraryFlow().first()
            assertThat(afterActivation).isNotNull()
        }
}
