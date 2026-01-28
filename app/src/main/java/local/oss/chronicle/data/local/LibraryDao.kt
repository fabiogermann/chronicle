package local.oss.chronicle.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import local.oss.chronicle.data.model.Library

@Dao
interface LibraryDao {
    // ===== Insert Operations =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(library: Library)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(libraries: List<Library>)

    // ===== Update Operations =====

    @Update
    suspend fun update(library: Library)

    @Query("UPDATE libraries SET lastSyncedAt = :timestamp WHERE id = :libraryId")
    suspend fun updateLastSyncedAt(
        libraryId: String,
        timestamp: Long,
    )

    @Query("UPDATE libraries SET itemCount = :count WHERE id = :libraryId")
    suspend fun updateItemCount(
        libraryId: String,
        count: Int,
    )

    // ===== Delete Operations =====

    @Delete
    suspend fun delete(library: Library)

    @Query("DELETE FROM libraries WHERE id = :libraryId")
    suspend fun deleteById(libraryId: String)

    @Query("DELETE FROM libraries WHERE accountId = :accountId")
    suspend fun deleteByAccountId(accountId: String)

    @Query("DELETE FROM libraries")
    suspend fun deleteAll()

    // ===== Query Operations =====

    @Query("SELECT * FROM libraries WHERE id = :libraryId")
    suspend fun getById(libraryId: String): Library?

    @Query("SELECT * FROM libraries")
    fun getAllLibraries(): Flow<List<Library>>

    @Query("SELECT * FROM libraries WHERE accountId = :accountId")
    fun getLibrariesForAccount(accountId: String): Flow<List<Library>>

    @Query("SELECT * FROM libraries WHERE serverId = :serverId")
    fun getLibrariesByServerId(serverId: String): Flow<List<Library>>

    // ===== Active Library Management =====

    @Query("SELECT * FROM libraries WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveLibrary(): Library?

    @Query("SELECT * FROM libraries WHERE isActive = 1 LIMIT 1")
    fun getActiveLibraryFlow(): Flow<Library?>

    @Query("UPDATE libraries SET isActive = 0")
    suspend fun deactivateAllLibraries()

    /**
     * Sets a library as active, deactivating all others.
     * Uses a transaction to ensure atomicity.
     */
    @Transaction
    suspend fun setActiveLibrary(libraryId: String) {
        deactivateAllLibraries()
        activateLibrary(libraryId)
    }

    @Query("UPDATE libraries SET isActive = 1 WHERE id = :libraryId")
    suspend fun activateLibrary(libraryId: String)
}
