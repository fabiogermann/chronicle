package local.oss.chronicle.data.local

import kotlinx.coroutines.flow.Flow
import local.oss.chronicle.data.model.Library
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao
) {
    suspend fun addLibrary(library: Library) = libraryDao.insert(library)
    
    suspend fun addLibraries(libraries: List<Library>) = libraryDao.insertAll(libraries)
    
    suspend fun getLibraryById(libraryId: String): Library? = libraryDao.getById(libraryId)
    
    fun getAllLibraries(): Flow<List<Library>> = libraryDao.getAllLibraries()
    
    fun getLibrariesForAccount(accountId: String): Flow<List<Library>> = 
        libraryDao.getLibrariesForAccount(accountId)
    
    fun getLibrariesByServerId(serverId: String): Flow<List<Library>> = 
        libraryDao.getLibrariesByServerId(serverId)
    
    suspend fun removeLibrary(library: Library) = libraryDao.delete(library)
    
    suspend fun removeLibraryById(libraryId: String) = libraryDao.deleteById(libraryId)
    
    suspend fun updateLibrary(library: Library) = libraryDao.update(library)
    
    // Active Library Management
    suspend fun getActiveLibrary(): Library? = libraryDao.getActiveLibrary()
    
    fun getActiveLibraryFlow(): Flow<Library?> = libraryDao.getActiveLibraryFlow()
    
    suspend fun setActiveLibrary(libraryId: String) = libraryDao.setActiveLibrary(libraryId)
    
    suspend fun clearActiveLibrary() = libraryDao.deactivateAllLibraries()
    
    // Update Operations
    suspend fun updateSyncTimestamp(libraryId: String, timestamp: Long) = 
        libraryDao.updateLastSyncedAt(libraryId, timestamp)
    
    suspend fun updateItemCount(libraryId: String, count: Int) = 
        libraryDao.updateItemCount(libraryId, count)
}
