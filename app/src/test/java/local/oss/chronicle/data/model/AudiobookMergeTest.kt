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
        viewCount: Long = 0L,
        viewedLeafCount: Long = 0L,
    ) = Audiobook(
        id = id,
        libraryId = "plex:library:1",
        source = 1L,
        title = "Test Book",
        duration = 7_200_000L,
        lastViewedAt = lastViewedAt,
        progress = progress,
        viewCount = viewCount,
        viewedLeafCount = viewedLeafCount,
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

    @Test
    fun `merge keeps local played status when server copy is newer (mark-as-played sticks)`() {
        // GitHub #16: the user marks the book played. setWatched scrobbles the server AND sets
        // local viewCount=1, but Plex stamps the scrobble with a lastViewedAt NEWER than the
        // local row, so the next library refresh used to take the network branch and reset
        // viewCount to the server's (not-yet-updated or unplayed) value — the book reappeared
        // as unplayed in the "hide played" library view.
        val network = book(lastViewedAt = 1_700_000_000L, viewCount = 0L, viewedLeafCount = 0L)
        val local = book(lastViewedAt = 1_600_000_000_000L, viewCount = 1L, viewedLeafCount = 12L)

        val merged = Audiobook.merge(network = network, local = local)

        // Played status is local-retained (like `favorited`): the server recency still wins for
        // lastViewedAt, but it must NOT clobber the locally-marked played flag.
        assertThat(merged.lastViewedAt).isEqualTo(1_700_000_000_000L)
        assertThat(merged.viewCount).isEqualTo(1L)
        assertThat(merged.viewedLeafCount).isEqualTo(12L)
    }

    @Test
    fun `merge keeps local unplayed status when another device played the book (non-force)`() {
        // Played status does not auto-propagate across devices: if the book was marked played on
        // the tablet (server viewCount=1), the phone (local viewCount=0) keeps it visible until
        // the user does a manual "Always Refresh" (forceNetwork). Documents the local-wins rule.
        val network = book(lastViewedAt = 1_700_000_000L, viewCount = 1L, viewedLeafCount = 12L)
        val local = book(lastViewedAt = 1_600_000_000_000L, viewCount = 0L, viewedLeafCount = 0L)

        val merged = Audiobook.merge(network = network, local = local)

        assertThat(merged.viewCount).isEqualTo(0L)
        assertThat(merged.viewedLeafCount).isEqualTo(0L)
    }

    @Test
    fun `merge with forceNetwork adopts the server's played status (manual Always Refresh)`() {
        // The escape hatch: a manual refresh pulls the played state set on another device.
        val network = book(lastViewedAt = 1_700_000_000L, viewCount = 1L, viewedLeafCount = 12L)
        val local = book(lastViewedAt = 1_800_000_000_000L, viewCount = 0L, viewedLeafCount = 0L)

        val merged = Audiobook.merge(network = network, local = local, forceNetwork = true)

        assertThat(merged.viewCount).isEqualTo(1L)
        assertThat(merged.viewedLeafCount).isEqualTo(12L)
    }
}
