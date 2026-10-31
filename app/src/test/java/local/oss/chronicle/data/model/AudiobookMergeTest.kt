package local.oss.chronicle.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [Audiobook.merge] — book-level companion of [MediaItemTrack.merge].
 *
 * The book row carries no server-provided [Audiobook.progress] (it is derived from the
 * child tracks), but its [Audiobook.lastViewedAt] decides which book is "most recently
 * played" (BookRepository.getMostRecentlyPlayed) and how far auto-rewind goes. Plex serves
 * the value in unix seconds while the local DB stores epoch milliseconds; comparing them raw
 * would make the local copy win every time and a book played on another device would never
 * surface as recently listened.
 */
class AudiobookMergeTest {
    private fun book(
        id: String = "plex:678",
        lastViewedAt: Long = 0L,
        progress: Long = 0L,
    ) = Audiobook(
        id = id,
        libraryId = "plex:library:1",
        source = 1L,
        title = "Test Book",
        duration = 7_200_000L,
        lastViewedAt = lastViewedAt,
        progress = progress,
    )

    @Test
    fun `merge adopts server lastViewedAt (normalized to ms) when server copy is newer`() {
        // Given: the book was played on another device; Plex stamped it in unix seconds
        val network = book(lastViewedAt = 1_700_000_000L)
        val local = book(lastViewedAt = 1_600_000_000_000L, progress = 5_000_000L)

        val merged = Audiobook.merge(network = network, local = local)

        // Then: the server's newer recency wins, stored in milliseconds like the rest of
        // the DB — so getMostRecentlyPlayed orders it correctly against local-ms books
        assertThat(merged.lastViewedAt).isEqualTo(1_700_000_000_000L)
        // Book progress is derived from the child tracks, so the local aggregate is kept
        // until LibrarySyncRepository.updateTrackData re-derives it from the merged tracks
        assertThat(merged.progress).isEqualTo(5_000_000L)
    }

    @Test
    fun `merge keeps local lastViewedAt when local copy is newer`() {
        val network = book(lastViewedAt = 1_500_000_000L)
        val local = book(lastViewedAt = 1_700_000_000_000L, progress = 5_000_000L)

        val merged = Audiobook.merge(network = network, local = local)

        assertThat(merged.lastViewedAt).isEqualTo(1_700_000_000_000L)
        assertThat(merged.progress).isEqualTo(5_000_000L)
    }

    @Test
    fun `merge with forceNetwork adopts the normalized network recency`() {
        val network = book(lastViewedAt = 1_700_000_000L)
        val local = book(lastViewedAt = 1_800_000_000_000L)

        val merged = Audiobook.merge(network = network, local = local, forceNetwork = true)

        assertThat(merged.lastViewedAt).isEqualTo(1_700_000_000_000L)
    }
}
