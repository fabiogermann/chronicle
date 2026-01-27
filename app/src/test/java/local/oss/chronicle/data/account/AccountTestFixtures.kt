package local.oss.chronicle.data.account

import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.ProviderType
import java.util.UUID

/**
 * Test fixtures for Account and Library related tests.
 * Provides consistent test data across all account-related test classes.
 */
object AccountTestFixtures {

    // ===== Account Fixtures =====

    fun createPlexAccount(
        id: String = "plex:account:${UUID.randomUUID()}",
        displayName: String = "Test User",
        avatarUrl: String? = "https://plex.tv/users/avatar.png",
        credentials: String = "encrypted_token_placeholder",
        createdAt: Long = System.currentTimeMillis(),
        lastUsedAt: Long = System.currentTimeMillis(),
    ): Account = Account(
        id = id,
        providerType = ProviderType.PLEX,
        displayName = displayName,
        avatarUrl = avatarUrl,
        credentials = credentials,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
    )

    fun createAudiobookshelfAccount(
        id: String = "abs:account:${UUID.randomUUID()}",
        displayName: String = "ABS User",
        avatarUrl: String? = null,
        credentials: String = "encrypted_token_placeholder",
        createdAt: Long = System.currentTimeMillis(),
        lastUsedAt: Long = System.currentTimeMillis(),
    ): Account = Account(
        id = id,
        providerType = ProviderType.AUDIOBOOKSHELF,
        displayName = displayName,
        avatarUrl = avatarUrl,
        credentials = credentials,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
    )

    // ===== Library Fixtures =====

    fun createPlexLibrary(
        id: String = "plex:library:${(1000..9999).random()}",
        accountId: String,
        serverId: String = "server-${UUID.randomUUID().toString().take(8)}",
        serverName: String = "Test Plex Server",
        name: String = "Audiobooks",
        type: String = "artist", // Plex uses "artist" type for music/audiobooks
        lastSyncedAt: Long? = null,
        itemCount: Int = 0,
        isActive: Boolean = false,
    ): Library = Library(
        id = id,
        accountId = accountId,
        serverId = serverId,
        serverName = serverName,
        name = name,
        type = type,
        lastSyncedAt = lastSyncedAt,
        itemCount = itemCount,
        isActive = isActive,
    )

    fun createAudiobookshelfLibrary(
        id: String = "abs:library:${UUID.randomUUID().toString().take(8)}",
        accountId: String,
        serverId: String = "abs-server-${UUID.randomUUID().toString().take(8)}",
        serverName: String = "My Audiobookshelf",
        name: String = "Audiobooks",
        type: String = "audiobook",
        lastSyncedAt: Long? = null,
        itemCount: Int = 0,
        isActive: Boolean = false,
    ): Library = Library(
        id = id,
        accountId = accountId,
        serverId = serverId,
        serverName = serverName,
        name = name,
        type = type,
        lastSyncedAt = lastSyncedAt,
        itemCount = itemCount,
        isActive = isActive,
    )

    // ===== Composite Fixtures =====

    /**
     * Creates a complete account with associated libraries for testing.
     */
    fun createAccountWithLibraries(
        account: Account = createPlexAccount(),
        libraryCount: Int = 2,
    ): Pair<Account, List<Library>> {
        val libraries = (1..libraryCount).map { index ->
            createPlexLibrary(
                accountId = account.id,
                name = "Library $index",
                isActive = index == 1, // First library is active
            )
        }
        return account to libraries
    }

    // ===== ID Generation Helpers =====

    object IdGenerator {
        fun plexAccountId(): String = "plex:account:${UUID.randomUUID()}"
        fun plexLibraryId(sectionId: Int): String = "plex:library:$sectionId"
        fun plexAudiobookId(ratingKey: Int): String = "plex:$ratingKey"
        fun plexTrackId(ratingKey: Int): String = "plex:$ratingKey"

        fun absAccountId(): String = "abs:account:${UUID.randomUUID()}"
        fun absLibraryId(libraryId: String): String = "abs:library:$libraryId"
        fun absAudiobookId(itemId: String): String = "abs:$itemId"
    }

    // ===== Sample Data for Specific Scenarios =====

    /**
     * Creates scenario data for testing library switching.
     * Returns two accounts, each with two libraries, where one library from each is "active".
     */
    fun createLibrarySwitchingScenario(): Map<String, Any> {
        val account1 = createPlexAccount(displayName = "User One")
        val account2 = createPlexAccount(displayName = "User Two")

        val account1Libraries = listOf(
            createPlexLibrary(accountId = account1.id, name = "Audiobooks A", isActive = true),
            createPlexLibrary(accountId = account1.id, name = "Audiobooks B", isActive = false),
        )

        val account2Libraries = listOf(
            createPlexLibrary(accountId = account2.id, name = "My Books", isActive = false),
            createPlexLibrary(accountId = account2.id, name = "Shared Books", isActive = false),
        )

        return mapOf(
            "accounts" to listOf(account1, account2),
            "libraries" to (account1Libraries + account2Libraries),
            "activeLibrary" to account1Libraries[0],
        )
    }
}
