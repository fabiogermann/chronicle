package local.oss.chronicle.features.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.BookRepository
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.local.TrackRepository
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.ProviderType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central coordinator for account management operations.
 *
 * Provides high-level operations like adding/removing accounts,
 * switching libraries, and managing account lifecycle.
 */
@Singleton
class AccountManager @Inject constructor(
    private val accountRepository: AccountRepository,
    private val libraryRepository: LibraryRepository,
    private val activeLibraryProvider: ActiveLibraryProvider,
    private val credentialManager: CredentialManager,
    private val bookRepository: BookRepository,
    private val trackRepository: TrackRepository
) {

    // ===== Account Operations =====

    /**
     * Add a new Plex account after OAuth authentication.
     *
     * @param displayName User's display name
     * @param avatarUrl User's avatar URL (optional)
     * @param authToken The Plex authentication token
     * @return The created Account
     */
    suspend fun addPlexAccount(
        displayName: String,
        avatarUrl: String?,
        authToken: String
    ): Account {
        val accountId = generateAccountId(ProviderType.PLEX)
        val now = System.currentTimeMillis()

        val account = Account(
            id = accountId,
            providerType = ProviderType.PLEX,
            displayName = displayName,
            avatarUrl = avatarUrl,
            credentials = "", // Stored separately for security
            createdAt = now,
            lastUsedAt = now
        )

        // Store credentials securely
        credentialManager.storeCredentials(accountId, authToken)

        // Save account to database
        accountRepository.addAccount(account)

        return account
    }

    /**
     * Remove an account and all associated data.
     * This cascades: Account → Libraries → Books/Tracks for those libraries.
     */
    suspend fun removeAccount(accountId: String) {
        // Get all libraries for this account before deletion
        val libraries = libraryRepository.getLibrariesForAccount(accountId).first()

        // Delete data for each library
        libraries.forEach { library ->
            deleteLibraryData(library.id)
        }

        // Delete credentials
        credentialManager.deleteCredentials(accountId)

        // Delete account (libraries cascade via FK)
        accountRepository.removeAccountById(accountId)

        // If active library was in this account, clear it
        if (activeLibraryProvider.currentLibrary.value?.accountId == accountId) {
            activeLibraryProvider.clearLibrary()
        }
    }

    /**
     * Get all accounts.
     */
    fun getAllAccounts(): Flow<List<Account>> = accountRepository.getAllAccounts()

    /**
     * Get accounts ordered by last used (most recent first).
     */
    fun getAccountsOrderedByLastUsed(): Flow<List<Account>> =
        accountRepository.getAllAccountsOrderedByLastUsed()

    /**
     * Get the authentication token for an account.
     */
    fun getAccountToken(accountId: String): String? =
        credentialManager.getCredentials(accountId)

    /**
     * Check if any accounts exist.
     */
    suspend fun hasAccounts(): Boolean = accountRepository.hasAccounts()

    // ===== Library Operations =====

    /**
     * Add libraries for an account (typically after server discovery).
     */
    suspend fun addLibraries(accountId: String, libraries: List<Library>) {
        libraryRepository.addLibraries(libraries.map { it.copy(accountId = accountId) })
    }

    /**
     * Get all libraries for an account.
     */
    fun getLibrariesForAccount(accountId: String): Flow<List<Library>> =
        libraryRepository.getLibrariesForAccount(accountId)

    /**
     * Switch to a different library.
     * Updates last used timestamp for the account.
     */
    suspend fun switchToLibrary(libraryId: String) {
        val library = libraryRepository.getLibraryById(libraryId)
            ?: throw IllegalArgumentException("Library not found: $libraryId")

        // Update account last used
        accountRepository.updateLastUsed(library.accountId, System.currentTimeMillis())

        // Set as active library
        activeLibraryProvider.switchToLibrary(libraryId)
    }

    /**
     * Get the current active library.
     */
    suspend fun getCurrentLibrary(): Library? = libraryRepository.getActiveLibrary()

    /**
     * Refresh libraries for an account from the server.
     * Call this to sync available libraries after login or periodically.
     */
    suspend fun refreshLibraries(accountId: String, discoveredLibraries: List<Library>) {
        // Get existing libraries
        val existingLibraries = libraryRepository.getLibrariesForAccount(accountId).first()
        val existingIds = existingLibraries.map { it.id }.toSet()
        val discoveredIds = discoveredLibraries.map { it.id }.toSet()

        // Add new libraries
        val newLibraries = discoveredLibraries.filter { it.id !in existingIds }
        if (newLibraries.isNotEmpty()) {
            libraryRepository.addLibraries(newLibraries)
        }

        // Remove libraries that no longer exist on server
        existingLibraries.filter { it.id !in discoveredIds }.forEach { library ->
            deleteLibraryData(library.id)
            libraryRepository.removeLibraryById(library.id)
        }

        // Update existing libraries (name, item count, etc.)
        discoveredLibraries.filter { it.id in existingIds }.forEach { library ->
            libraryRepository.updateLibrary(library)
        }
    }

    // ===== Private Helpers =====

    /**
     * Delete all data associated with a library.
     */
    private suspend fun deleteLibraryData(libraryId: String) {
        // Delete books and tracks for this library
        bookRepository.deleteByLibraryId(libraryId)
        trackRepository.deleteByLibraryId(libraryId)
    }

    private fun generateAccountId(providerType: ProviderType): String {
        val prefix = when (providerType) {
            ProviderType.PLEX -> "plex"
            ProviderType.AUDIOBOOKSHELF -> "abs"
        }
        return "$prefix:account:${UUID.randomUUID()}"
    }
}
