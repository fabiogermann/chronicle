package local.oss.chronicle.features.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Library
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the currently active library.
 * 
 * Provides reactive access to the current library via StateFlow
 * and manages library switching operations.
 */
@Singleton
class ActiveLibraryProvider @Inject constructor(
    private val libraryRepository: LibraryRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    private val _currentLibrary = MutableStateFlow<Library?>(null)
    
    /** Current active library as a StateFlow */
    val currentLibrary: StateFlow<Library?> = _currentLibrary.asStateFlow()
    
    /** ID of the current active library, or null if none */
    val currentLibraryId: String?
        get() = _currentLibrary.value?.id
    
    /**
     * Initialize the provider by subscribing to library changes.
     * Call this once during app startup.
     */
    fun initialize() {
        scope.launch {
            libraryRepository.getActiveLibraryFlow().collect { library ->
                _currentLibrary.value = library
            }
        }
    }
    
    /**
     * Switch to a different library.
     * This will update the database and emit the new library.
     */
    suspend fun switchToLibrary(libraryId: String) {
        libraryRepository.setActiveLibrary(libraryId)
    }
    
    /**
     * Clear the active library selection.
     */
    suspend fun clearLibrary() {
        libraryRepository.clearActiveLibrary()
    }
    
    /**
     * Check if a library is currently active.
     */
    fun hasActiveLibrary(): Boolean = _currentLibrary.value != null
    
    /**
     * Get a library ID or throw exception.
     * Use when library context is required.
     */
    fun requireLibraryId(): String = currentLibraryId 
        ?: throw IllegalStateException("No active library selected")
}
