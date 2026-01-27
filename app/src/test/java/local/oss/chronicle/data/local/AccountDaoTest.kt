package local.oss.chronicle.data.local

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.account.AccountTestFixtures
import local.oss.chronicle.data.model.ProviderType
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
class AccountDaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AccountDatabase
    private lateinit var accountDao: AccountDao

    @Before
    fun setup() {
        // Initialize in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AccountDatabase::class.java
        ).allowMainThreadQueries().build()
        accountDao = database.accountDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ===== CRUD Tests =====

    @Test
    fun `insert account and retrieve by id`() = runTest {
        val account = AccountTestFixtures.createPlexAccount()
        accountDao.insert(account)

        val retrieved = accountDao.getById(account.id)
        assertThat(retrieved).isEqualTo(account)
    }

    @Test
    fun `insert multiple accounts and retrieve all`() = runTest {
        val account1 = AccountTestFixtures.createPlexAccount(displayName = "User 1")
        val account2 = AccountTestFixtures.createPlexAccount(displayName = "User 2")
        val account3 = AccountTestFixtures.createAudiobookshelfAccount(displayName = "ABS User")

        accountDao.insert(account1)
        accountDao.insert(account2)
        accountDao.insert(account3)

        val allAccounts = accountDao.getAllAccounts().first()
        assertThat(allAccounts).hasSize(3)
        assertThat(allAccounts.map { it.displayName }).containsExactly("User 1", "User 2", "ABS User")
    }

    @Test
    fun `update account modifies existing record`() = runTest {
        val account = AccountTestFixtures.createPlexAccount(displayName = "Original")
        accountDao.insert(account)

        val updated = account.copy(displayName = "Updated", lastUsedAt = System.currentTimeMillis())
        accountDao.update(updated)

        val retrieved = accountDao.getById(account.id)
        assertThat(retrieved?.displayName).isEqualTo("Updated")
    }

    @Test
    fun `delete account removes record`() = runTest {
        val account = AccountTestFixtures.createPlexAccount()
        accountDao.insert(account)

        accountDao.delete(account)

        val retrieved = accountDao.getById(account.id)
        assertThat(retrieved).isNull()
    }

    @Test
    fun `delete account by id removes record`() = runTest {
        val account = AccountTestFixtures.createPlexAccount()
        accountDao.insert(account)

        accountDao.deleteById(account.id)

        val retrieved = accountDao.getById(account.id)
        assertThat(retrieved).isNull()
    }

    // ===== Query Tests =====

    @Test
    fun `get accounts by provider type filters correctly`() = runTest {
        val plexAccount = AccountTestFixtures.createPlexAccount()
        val absAccount = AccountTestFixtures.createAudiobookshelfAccount()

        accountDao.insert(plexAccount)
        accountDao.insert(absAccount)

        val plexAccounts = accountDao.getByProviderType(ProviderType.PLEX).first()
        assertThat(plexAccounts).hasSize(1)
        assertThat(plexAccounts[0].providerType).isEqualTo(ProviderType.PLEX)

        val absAccounts = accountDao.getByProviderType(ProviderType.AUDIOBOOKSHELF).first()
        assertThat(absAccounts).hasSize(1)
        assertThat(absAccounts[0].providerType).isEqualTo(ProviderType.AUDIOBOOKSHELF)
    }

    @Test
    fun `get account count returns correct number`() = runTest {
        assertThat(accountDao.getAccountCount()).isEqualTo(0)

        accountDao.insert(AccountTestFixtures.createPlexAccount())
        assertThat(accountDao.getAccountCount()).isEqualTo(1)

        accountDao.insert(AccountTestFixtures.createPlexAccount())
        assertThat(accountDao.getAccountCount()).isEqualTo(2)
    }

    @Test
    fun `accounts ordered by last used descending`() = runTest {
        val oldAccount = AccountTestFixtures.createPlexAccount(
            displayName = "Old",
            lastUsedAt = 1000L
        )
        val newAccount = AccountTestFixtures.createPlexAccount(
            displayName = "New",
            lastUsedAt = 3000L
        )
        val middleAccount = AccountTestFixtures.createPlexAccount(
            displayName = "Middle",
            lastUsedAt = 2000L
        )

        accountDao.insert(oldAccount)
        accountDao.insert(newAccount)
        accountDao.insert(middleAccount)

        val accounts = accountDao.getAllAccountsOrderedByLastUsed().first()
        assertThat(accounts.map { it.displayName }).containsExactly("New", "Middle", "Old").inOrder()
    }

    @Test
    fun `update last used timestamp`() = runTest {
        val account = AccountTestFixtures.createPlexAccount(lastUsedAt = 1000L)
        accountDao.insert(account)

        val newTimestamp = 5000L
        accountDao.updateLastUsedAt(account.id, newTimestamp)

        val retrieved = accountDao.getById(account.id)
        assertThat(retrieved?.lastUsedAt).isEqualTo(newTimestamp)
    }

    // ===== Flow Tests =====

    @Test
    fun `getAllAccounts flow emits updates`() = runTest {
        val account = AccountTestFixtures.createPlexAccount()

        // Initial state
        val initial = accountDao.getAllAccounts().first()
        assertThat(initial).isEmpty()

        // After insert
        accountDao.insert(account)
        val afterInsert = accountDao.getAllAccounts().first()
        assertThat(afterInsert).hasSize(1)
    }
}
