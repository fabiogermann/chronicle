package local.oss.chronicle.features.account

import android.util.Log
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.BookDatabase
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.local.TrackDatabase
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.ProviderType
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Migrates existing single-account data to the new multi-account schema.
 *
 * This runs once on app upgrade to:
 * 1. Create an Account from existing PlexPrefsRepo data
 * 2. Create a Library from the selected library
 * 3. Update all books/tracks with the new libraryId
 */
@Singleton
class LegacyAccountMigration @Inject constructor(
    private val accountRepository: AccountRepository,
    private val libraryRepository: LibraryRepository,
    private val credentialManager: CredentialManager,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val bookDatabase: BookDatabase,
    private val trackDatabase: TrackDatabase
) {

    /**
     * Run the migration. Safe to call multiple times - will skip if already migrated.
     *
     * @return true if migration was performed, false if skipped
     */
    suspend fun migrate(): Boolean {
        // Check if already migrated
        if (accountRepository.hasAccounts()) {
            Log.d(TAG, "Migration skipped: Accounts already exist")
            return false
        }

        // Check if there's legacy data to migrate
        val legacyToken = plexPrefsRepo.accountAuthToken
        if (legacyToken.isNullOrEmpty()) {
            Log.d(TAG, "Migration skipped: No legacy Plex token found")
            return false
        }

        Log.i(TAG, "Starting legacy account migration")

        try {
            // 1. Create Account
            val accountId = "plex:account:${UUID.randomUUID()}"
            val now = System.currentTimeMillis()

            val account = Account(
                id = accountId,
                providerType = ProviderType.PLEX,
                displayName = plexPrefsRepo.user?.title ?: "Plex User",
                avatarUrl = plexPrefsRepo.user?.thumb,
                credentials = "", // Stored separately
                createdAt = now,
                lastUsedAt = now
            )

            // Store credentials
            credentialManager.storeCredentials(accountId, legacyToken)

            // Save account
            accountRepository.addAccount(account)

            // 2. Create Library from selected library
            val selectedLibrary = plexPrefsRepo.library
            val libraryId = if (selectedLibrary != null) {
                "plex:library:${selectedLibrary.id}"
            } else {
                "plex:library:legacy"
            }

            val library = Library(
                id = libraryId,
                accountId = accountId,
                serverId = plexPrefsRepo.server?.serverId ?: "legacy-server",
                serverName = plexPrefsRepo.server?.name ?: "Plex Server",
                name = selectedLibrary?.name ?: "Library",
                type = "artist", // Plex audiobook library type
                lastSyncedAt = now,
                itemCount = 0,
                isActive = true
            )

            libraryRepository.addLibrary(library)

            // 3. Update all existing books and tracks with the new libraryId
            updateExistingDataWithLibraryId(libraryId)

            Log.i(TAG, "Legacy migration complete: Account=$accountId, Library=$libraryId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Legacy migration failed", e)
            throw e
        }
    }

    /**
     * Update all books and tracks that have "legacy:pending" libraryId.
     */
    private suspend fun updateExistingDataWithLibraryId(libraryId: String) {
        // Update books with pending libraryId
        bookDatabase.bookDao.updateLegacyLibraryIds(libraryId)

        // Update tracks with pending libraryId
        trackDatabase.trackDao.updateLegacyLibraryIds(libraryId)

        Log.d(TAG, "Updated existing data with libraryId: $libraryId")
    }

    companion object {
        private const val TAG = "LegacyAccountMigration"
    }
}
