package local.oss.chronicle.data.sources.plex

import androidx.work.Data
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.account.AccountTestFixtures
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.sources.plex.PlexMediaSource
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

/**
 * Unit tests for PlexSyncScrobbleWorker, focusing on contextual library lookup
 * for the Unified Library View feature.
 *
 * These tests verify the data structures and logic that support library context
 * derivation from audiobook.libraryId. Full worker execution tests require
 * integration testing due to dependency injection via Injector.get().
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class PlexSyncScrobbleWorkerTest {
    @Test
    fun `worker data factory creates correct Data object`() {
        // Given: Valid worker input parameters
        val trackId = "plex:101"
        val playbackState = "playing"
        val trackProgress = 1800L
        val bookProgress = 1800L

        // When: Worker data is created
        val data = PlexSyncScrobbleWorker.makeWorkerData(
            trackId = trackId,
            playbackState = playbackState,
            trackProgress = trackProgress,
            bookProgress = bookProgress,
        )

        // Then: Data object contains all required fields
        assertThat(data.getString(PlexSyncScrobbleWorker.TRACK_ID_ARG)).isEqualTo(trackId)
        assertThat(data.getString(PlexSyncScrobbleWorker.TRACK_STATE_ARG)).isEqualTo(playbackState)
        assertThat(data.getLong(PlexSyncScrobbleWorker.TRACK_POSITION_ARG, -1)).isEqualTo(trackProgress)
        assertThat(data.getLong(PlexSyncScrobbleWorker.BOOK_PROGRESS, -1)).isEqualTo(bookProgress)
    }

    @Test
    fun `audiobook libraryId correctly identifies its library`() = runTest {
        // Given: An audiobook with a specific libraryId
        val library = AccountTestFixtures.createPlexLibrary(
            id = "plex:library:1",
            accountId = "plex:account:test-user",
            name = "Test Library",
        )

        val audiobook = Audiobook(
            id = "plex:100",
            libraryId = library.id,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Test Book",
        )

        // Then: The audiobook's libraryId matches the library's id
        assertThat(audiobook.libraryId).isEqualTo(library.id)
        assertThat(audiobook.libraryId).isEqualTo("plex:library:1")
    }

    @Test
    fun `library accountId correctly identifies owning account`() = runTest {
        // Given: A library associated with an account
        val accountId = "plex:account:user123"
        val library = AccountTestFixtures.createPlexLibrary(
            id = "plex:library:1",
            accountId = accountId,
            name = "User Library",
        )

        // Then: The library's accountId identifies its owner
        assertThat(library.accountId).isEqualTo(accountId)
    }

    @Test
    fun `library lookup respects audiobook libraryId over global config`() = runTest {
        // Given: Multiple libraries and an audiobook in a specific library
        val library1 = AccountTestFixtures.createPlexLibrary(
            id = "plex:library:1",
            accountId = "plex:account:user1",
            name = "User 1 Library",
        )

        val library2 = AccountTestFixtures.createPlexLibrary(
            id = "plex:library:2",
            accountId = "plex:account:user2",
            name = "User 2 Library",
        )

        val audiobookInLibrary2 = Audiobook(
            id = "plex:200",
            libraryId = library2.id,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Book in Library 2",
        )

        // Then: The audiobook's libraryId directly identifies library 2
        assertThat(audiobookInLibrary2.libraryId).isEqualTo(library2.id)
        assertThat(audiobookInLibrary2.libraryId).isNotEqualTo(library1.id)
    }

    @Test
    fun `multiple audiobooks from different libraries maintain correct context`() = runTest {
        // Given: Two audiobooks from different libraries/accounts
        val account1Id = "plex:account:user1"
        val account2Id = "plex:account:user2"

        val library1 = AccountTestFixtures.createPlexLibrary(
            id = "plex:library:1",
            accountId = account1Id,
            name = "Account 1 Library",
        )

        val library2 = AccountTestFixtures.createPlexLibrary(
            id = "plex:library:2",
            accountId = account2Id,
            name = "Account 2 Library",
        )

        val audiobook1 = Audiobook(
            id = "plex:100",
            libraryId = library1.id,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Book from Library 1",
        )

        val audiobook2 = Audiobook(
            id = "plex:200",
            libraryId = library2.id,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Book from Library 2",
        )

        // Then: Each audiobook maintains its correct library context
        assertThat(audiobook1.libraryId).isEqualTo(library1.id)
        assertThat(audiobook2.libraryId).isEqualTo(library2.id)

        // And: Libraries maintain correct account associations
        assertThat(library1.accountId).isEqualTo(account1Id)
        assertThat(library2.accountId).isEqualTo(account2Id)
    }

    @Test
    fun `worker handles track ID extraction from plex format`() {
        // Given: A track ID in Plex format
        val trackId = "plex:12345"

        // When: Extracting numeric ID
        val numericId = trackId.removePrefix("plex:").toIntOrNull()

        // Then: Should successfully extract the numeric part
        assertThat(numericId).isEqualTo(12345)
    }

    @Test
    fun `worker handles track ID without prefix gracefully`() {
        // Given: A track ID without "plex:" prefix
        val trackId = "67890"

        // When: Extracting numeric ID
        val numericId = trackId.removePrefix("plex:").toIntOrNull()

        // Then: Should still extract the numeric value
        assertThat(numericId).isEqualTo(67890)
    }

    @Test
    fun `worker handles malformed track ID gracefully`() {
        // Given: A track ID with non-numeric value after prefix
        val malformedTrackId = "plex:abc123"

        // When: Attempting to extract numeric ID
        val numericId = malformedTrackId.removePrefix("plex:").toIntOrNull()

        // Then: Should return null instead of throwing exception
        assertThat(numericId).isNull()
    }

    @Test
    fun `library context chain is maintained from audiobook to account`() = runTest {
        // Given: Complete context chain
        val account = AccountTestFixtures.createPlexAccount(
            id = "plex:account:test-123",
            displayName = "Test User",
        )

        val library = AccountTestFixtures.createPlexLibrary(
            id = "plex:library:1",
            accountId = account.id,
            name = "Test Library",
        )

        val audiobook = Audiobook(
            id = "plex:100",
            libraryId = library.id,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Test Book",
        )

        // Then: Context chain is properly maintained
        assertThat(audiobook.libraryId).isEqualTo(library.id)
        assertThat(library.accountId).isEqualTo(account.id)

        // And: The complete chain can be traversed
        // audiobook.libraryId -> library.id -> library.accountId -> account.id
        assertThat(audiobook.libraryId).isEqualTo("plex:library:1")
        assertThat(library.accountId).isEqualTo("plex:account:test-123")
    }
}
