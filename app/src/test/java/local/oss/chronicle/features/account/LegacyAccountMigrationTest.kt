package local.oss.chronicle.features.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.BookDatabase
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.local.TrackDatabase
import local.oss.chronicle.data.model.PlexLibrary
import local.oss.chronicle.data.model.ProviderType
import local.oss.chronicle.data.model.ServerModel
import local.oss.chronicle.data.sources.plex.FakePlexPrefsRepo
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.data.sources.plex.model.MediaType
import local.oss.chronicle.data.sources.plex.model.PlexUser
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Tests for LegacyAccountMigration.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyAccountMigrationTest {

    private lateinit var accountRepository: AccountRepository
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var credentialManager: CredentialManager
    private lateinit var plexPrefsRepo: FakePlexPrefsRepo
    private lateinit var bookDatabase: BookDatabase
    private lateinit var trackDatabase: TrackDatabase
    private lateinit var migration: LegacyAccountMigration

    @Before
    fun setup() {
        accountRepository = mock()
        libraryRepository = mock()
        credentialManager = mock()
        plexPrefsRepo = FakePlexPrefsRepo()
        bookDatabase = mock()
        trackDatabase = mock()

        migration = LegacyAccountMigration(
            accountRepository,
            libraryRepository,
            credentialManager,
            plexPrefsRepo,
            bookDatabase,
            trackDatabase
        )
    }

    @Test
    fun migrate_skipsWhenAccountsAlreadyExist(): Unit = runBlocking {
        // Given
        whenever(accountRepository.hasAccounts()).thenReturn(true)

        // When
        val result = migration.migrate()

        // Then
        assertThat(result).isFalse()
        verify(accountRepository).hasAccounts()
    }

    @Test
    fun migrate_skipsWhenNoLegacyToken(): Unit = runBlocking {
        // Given
        whenever(accountRepository.hasAccounts()).thenReturn(false)
        plexPrefsRepo.accountAuthToken = ""

        // When
        val result = migration.migrate()

        // Then
        assertThat(result).isFalse()
        verify(accountRepository).hasAccounts()
        verify(accountRepository, never()).addAccount(any())
    }

    @Test
    fun migrate_createsAccountFromLegacyData(): Unit = runBlocking {
        // Given
        val legacyToken = "legacy-auth-token"
        val plexUser = PlexUser(
            id = 123L,
            title = "Test User",
            thumb = "https://example.com/avatar.png",
            username = "testuser"
        )
        val plexLibrary = PlexLibrary(
            name = "Audiobooks",
            type = MediaType.ARTIST,
            id = "456"
        )
        val server = ServerModel(
            name = "My Plex Server",
            connections = listOf(Connection(uri = "http://192.168.1.100:32400", local = true)),
            serverId = "server-id-123",
            accessToken = "server-token",
            owned = true
        )

        whenever(accountRepository.hasAccounts()).thenReturn(false)
        plexPrefsRepo.accountAuthToken = legacyToken
        plexPrefsRepo.user = plexUser
        plexPrefsRepo.library = plexLibrary
        plexPrefsRepo.server = server

        val bookDao = mock<local.oss.chronicle.data.local.BookDao>()
        val trackDao = mock<local.oss.chronicle.data.local.TrackDao>()
        whenever(bookDatabase.bookDao).thenReturn(bookDao)
        whenever(trackDatabase.trackDao).thenReturn(trackDao)

        // When
        val result = migration.migrate()

        // Then
        assertThat(result).isTrue()

        // Verify account was created
        verify(accountRepository).addAccount(
            argThat { account ->
                account.providerType == ProviderType.PLEX &&
                    account.displayName == "Test User" &&
                    account.avatarUrl == "https://example.com/avatar.png" &&
                    account.id.startsWith("plex:account:")
            }
        )

        // Verify credentials were stored
        verify(credentialManager).storeCredentials(
            argThat { accountId -> accountId.startsWith("plex:account:") },
            eq(legacyToken)
        )

        // Verify library was created
        verify(libraryRepository).addLibrary(
            argThat { library ->
                library.id == "plex:library:456" &&
                    library.name == "Audiobooks" &&
                    library.serverName == "My Plex Server" &&
                    library.serverId == "server-id-123" &&
                    library.isActive
            }
        )

        // Verify data migration was triggered
        verify(bookDao).updateLegacyLibraryIds("plex:library:456")
        verify(trackDao).updateLegacyLibraryIds("plex:library:456")
    }

    @Test
    fun migrate_handlesNullUser(): Unit = runBlocking {
        // Given
        val legacyToken = "legacy-auth-token"
        val plexLibrary = PlexLibrary(
            name = "Audiobooks",
            type = MediaType.ARTIST,
            id = "456"
        )

        whenever(accountRepository.hasAccounts()).thenReturn(false)
        plexPrefsRepo.accountAuthToken = legacyToken
        plexPrefsRepo.user = null
        plexPrefsRepo.library = plexLibrary
        plexPrefsRepo.server = null

        val bookDao = mock<local.oss.chronicle.data.local.BookDao>()
        val trackDao = mock<local.oss.chronicle.data.local.TrackDao>()
        whenever(bookDatabase.bookDao).thenReturn(bookDao)
        whenever(trackDatabase.trackDao).thenReturn(trackDao)

        // When
        val result = migration.migrate()

        // Then
        assertThat(result).isTrue()

        // Verify account was created with default name
        verify(accountRepository).addAccount(
            argThat { account ->
                account.displayName == "Plex User" &&
                    account.avatarUrl == null
            }
        )
    }

    @Test
    fun migrate_handlesNullLibrary(): Unit = runBlocking {
        // Given
        val legacyToken = "legacy-auth-token"
        val plexUser = PlexUser(
            id = 123L,
            title = "Test User"
        )

        whenever(accountRepository.hasAccounts()).thenReturn(false)
        plexPrefsRepo.accountAuthToken = legacyToken
        plexPrefsRepo.user = plexUser
        plexPrefsRepo.library = null
        plexPrefsRepo.server = null

        val bookDao = mock<local.oss.chronicle.data.local.BookDao>()
        val trackDao = mock<local.oss.chronicle.data.local.TrackDao>()
        whenever(bookDatabase.bookDao).thenReturn(bookDao)
        whenever(trackDatabase.trackDao).thenReturn(trackDao)

        // When
        val result = migration.migrate()

        // Then
        assertThat(result).isTrue()

        // Verify library was created with default values
        verify(libraryRepository).addLibrary(
            argThat { library ->
                library.id == "plex:library:legacy" &&
                    library.name == "Library" &&
                    library.serverName == "Plex Server" &&
                    library.serverId == "legacy-server"
            }
        )

        // Verify data migration was triggered with legacy library ID
        verify(bookDao).updateLegacyLibraryIds("plex:library:legacy")
        verify(trackDao).updateLegacyLibraryIds("plex:library:legacy")
    }

    @Test
    fun migrate_throwsExceptionOnFailure(): Unit = runBlocking {
        // Given
        whenever(accountRepository.hasAccounts()).thenReturn(false)
        plexPrefsRepo.accountAuthToken = "token"
        plexPrefsRepo.user = null
        plexPrefsRepo.library = null
        plexPrefsRepo.server = null
        whenever(accountRepository.addAccount(any())).thenThrow(RuntimeException("Database error"))

        // When/Then
        var exceptionThrown = false
        try {
            migration.migrate()
        } catch (e: RuntimeException) {
            exceptionThrown = true
            assertThat(e.message).isEqualTo("Database error")
        }
        assertThat(exceptionThrown).isTrue()
    }
}
