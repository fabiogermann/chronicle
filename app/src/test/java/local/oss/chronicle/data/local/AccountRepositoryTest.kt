package local.oss.chronicle.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.account.AccountTestFixtures
import local.oss.chronicle.data.model.ProviderType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class AccountRepositoryTest {
    @Mock
    private lateinit var accountDao: AccountDao

    private lateinit var repository: AccountRepository

    @Before
    fun setup() {
        repository = AccountRepository(accountDao)
    }

    // ===== Insert Operations =====

    @Test
    fun `addAccount delegates to dao insert`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()

            repository.addAccount(account)

            verify(accountDao).insert(account)
        }

    // ===== Read Operations =====

    @Test
    fun `getAccountById delegates to dao getById`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()
            whenever(accountDao.getById(account.id)).thenReturn(account)

            val result = repository.getAccountById(account.id)

            assertThat(result).isEqualTo(account)
            verify(accountDao).getById(account.id)
        }

    @Test
    fun `getAllAccounts delegates to dao getAllAccounts`() =
        runTest {
            repository.getAllAccounts()

            verify(accountDao).getAllAccounts()
        }

    @Test
    fun `getAllAccountsOrderedByLastUsed delegates to dao`() =
        runTest {
            repository.getAllAccountsOrderedByLastUsed()

            verify(accountDao).getAllAccountsOrderedByLastUsed()
        }

    @Test
    fun `getAccountsByProvider delegates to dao getByProviderType`() =
        runTest {
            val providerType = ProviderType.PLEX

            repository.getAccountsByProvider(providerType)

            verify(accountDao).getByProviderType(providerType)
        }

    // ===== Update Operations =====

    @Test
    fun `updateAccount delegates to dao update`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()

            repository.updateAccount(account)

            verify(accountDao).update(account)
        }

    @Test
    fun `updateLastUsed delegates to dao updateLastUsedAt`() =
        runTest {
            val accountId = "account-123"
            val timestamp = 1234567890L

            repository.updateLastUsed(accountId, timestamp)

            verify(accountDao).updateLastUsedAt(accountId, timestamp)
        }

    // ===== Delete Operations =====

    @Test
    fun `removeAccount delegates to dao delete`() =
        runTest {
            val account = AccountTestFixtures.createPlexAccount()

            repository.removeAccount(account)

            verify(accountDao).delete(account)
        }

    @Test
    fun `removeAccountById delegates to dao deleteById`() =
        runTest {
            val accountId = "account-123"

            repository.removeAccountById(accountId)

            verify(accountDao).deleteById(accountId)
        }

    // ===== Count Operations =====

    @Test
    fun `hasAccounts returns true when accounts exist`() =
        runTest {
            whenever(accountDao.getAccountCount()).thenReturn(1)

            val result = repository.hasAccounts()

            assertThat(result).isTrue()
            verify(accountDao).getAccountCount()
        }

    @Test
    fun `hasAccounts returns false when no accounts exist`() =
        runTest {
            whenever(accountDao.getAccountCount()).thenReturn(0)

            val result = repository.hasAccounts()

            assertThat(result).isFalse()
            verify(accountDao).getAccountCount()
        }

    @Test
    fun `getAccountCount delegates to dao getAccountCount`() =
        runTest {
            whenever(accountDao.getAccountCount()).thenReturn(5)

            val result = repository.getAccountCount()

            assertThat(result).isEqualTo(5)
            verify(accountDao).getAccountCount()
        }
}
