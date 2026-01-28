package local.oss.chronicle.data.local

import kotlinx.coroutines.flow.Flow
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao
) {
    suspend fun addAccount(account: Account) = accountDao.insert(account)
    
    suspend fun getAccountById(accountId: String): Account? = accountDao.getById(accountId)
    
    fun getAllAccounts(): Flow<List<Account>> = accountDao.getAllAccounts()
    
    fun getAllAccountsOrderedByLastUsed(): Flow<List<Account>> = 
        accountDao.getAllAccountsOrderedByLastUsed()
    
    suspend fun removeAccount(account: Account) = accountDao.delete(account)
    
    suspend fun removeAccountById(accountId: String) = accountDao.deleteById(accountId)
    
    suspend fun updateAccount(account: Account) = accountDao.update(account)
    
    suspend fun updateLastUsed(accountId: String, timestamp: Long) = 
        accountDao.updateLastUsedAt(accountId, timestamp)
    
    fun getAccountsByProvider(providerType: ProviderType): Flow<List<Account>> = 
        accountDao.getByProviderType(providerType)
    
    suspend fun hasAccounts(): Boolean = accountDao.getAccountCount() > 0
    
    suspend fun getAccountCount(): Int = accountDao.getAccountCount()
}
