package local.oss.chronicle.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.ProviderType

@Dao
interface AccountDao {
    
    // ===== Insert Operations =====
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: Account)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<Account>)
    
    // ===== Update Operations =====
    
    @Update
    suspend fun update(account: Account)
    
    @Query("UPDATE accounts SET lastUsedAt = :timestamp WHERE id = :accountId")
    suspend fun updateLastUsedAt(accountId: String, timestamp: Long)
    
    // ===== Delete Operations =====
    
    @Delete
    suspend fun delete(account: Account)
    
    @Query("DELETE FROM accounts WHERE id = :accountId")
    suspend fun deleteById(accountId: String)
    
    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
    
    // ===== Query Operations =====
    
    @Query("SELECT * FROM accounts WHERE id = :accountId")
    suspend fun getById(accountId: String): Account?
    
    @Query("SELECT * FROM accounts")
    fun getAllAccounts(): Flow<List<Account>>
    
    @Query("SELECT * FROM accounts ORDER BY lastUsedAt DESC")
    fun getAllAccountsOrderedByLastUsed(): Flow<List<Account>>
    
    @Query("SELECT * FROM accounts WHERE providerType = :providerType")
    fun getByProviderType(providerType: ProviderType): Flow<List<Account>>
    
    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getAccountCount(): Int
}
