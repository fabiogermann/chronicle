package local.oss.chronicle.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [MediaItemTrack.merge] — the read side of cross-device progress sync.
 *
 * Reddit report (u/mjm2705, thread 1u7817s): "if I switch to my tablet, it doesn't
 * recognise the play position from the phone and starts the book again."
 *
 * The expected conflict rule is last-write-wins by timestamp: when the server's
 * viewOffset/lastViewedAt is meaningfully newer than the local copy, the server position
 * must be adopted; when local is newer, local must be kept (and will be reported up by the
 * write side). "Local always wins" means a second device never adopts the first
 * device's position — exactly the reported bug.
 *
 * Note: Plex serves `lastViewedAt` in unix SECONDS while the local DB stores epoch
 * MILLISECONDS, so the comparison must normalize units or the server can never win.
 */
class MediaItemTrackMergeTest {
    private val libraryId = "plex:library:1"

    private fun track(
        id: String = "plex:101",
        progress: Long = 0L,
        lastViewedAt: Long = 0L,
        cached: Boolean = false,
    ) = MediaItemTrack(
        id = id,
        parentKey = "plex:100",
        libraryId = libraryId,
        title = "Chapter 1",
        duration = 3_600_000L,
        media = "/library/parts/101/1/file.m4b",
        progress = progress,
        lastViewedAt = lastViewedAt,
        cached = cached,
    )

    @Test
    fun `merge adopts server progress when server lastViewedAt is newer (ms units)`() {
        // Given: phone played to 30 min and reported up; tablet has local progress 0
        val network = track(progress = 1_800_000L, lastViewedAt = 1_700_000_000_000L)
        val local = track(progress = 0L, lastViewedAt = 0L)

        // When: merging during library refresh
        val merged = MediaItemTrack.merge(network = network, local = local)

        // Then: the server's newer position wins
        assertThat(merged.progress).isEqualTo(1_800_000L)
        assertThat(merged.lastViewedAt).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `merge adopts server progress when server lastViewedAt is in unix seconds`() {
        // Plex actually serves lastViewedAt in seconds (see
        // docs/example-query-responses/request-album-info.md: "lastViewedAt": 1591031072).
        // 1_591_031_072 s == 1_591_031_072_000 ms, which is newer than any sane local ms
        // timestamp written before that instant.
        val network = track(progress = 1_800_000L, lastViewedAt = 1_591_031_072L)
        val local = track(progress = 60_000L, lastViewedAt = 1_591_000_000_000L)

        val merged = MediaItemTrack.merge(network = network, local = local)

        assertThat(merged.progress).isEqualTo(1_800_000L)
        // Stored in milliseconds like every other local timestamp, not Plex's seconds
        assertThat(merged.lastViewedAt).isEqualTo(1_591_031_072_000L)
    }

    @Test
    fun `merge keeps local progress when local lastViewedAt is newer`() {
        // Given: this device played more recently than whatever the server last saw
        val network = track(progress = 1_800_000L, lastViewedAt = 1_700_000_000_000L)
        val local = track(progress = 2_400_000L, lastViewedAt = 1_800_000_000_000L)

        val merged = MediaItemTrack.merge(network = network, local = local)

        assertThat(merged.progress).isEqualTo(2_400_000L)
        assertThat(merged.lastViewedAt).isEqualTo(1_800_000_000_000L)
    }

    @Test
    fun `merge keeps local progress when server copy is only seconds newer (self-echo guard)`() {
        // Given: this device is listening right now. ProgressUpdater stamps local progress
        // every tick with the device clock and reports it to Plex; the server stamps the
        // update with the SERVER clock at second resolution. With the server clock a few
        // seconds ahead, the echoed copy arrives "newer" than local — but it carries an
        // older viewOffset. Adopting it would rewind the listener mid-playback.
        val localNow = 1_700_000_100_000L
        val serverEcho = (localNow + 5_000L) / 1000L // server clock 5s ahead, in Plex seconds
        val network = track(progress = 600_000L, lastViewedAt = serverEcho)
        val local = track(progress = 900_000L, lastViewedAt = localNow)

        val merged = MediaItemTrack.merge(network = network, local = local)

        // Then: local playback state wins; the echo is ignored
        assertThat(merged.progress).isEqualTo(900_000L)
        assertThat(merged.lastViewedAt).isEqualTo(localNow)
    }

    @Test
    fun `merge does not adopt zero server progress for a track with local progress (finished elsewhere)`() {
        // Given: this track was FINISHED on another device. Plex clears viewOffset (0) on
        // scrobble and bumps lastViewedAt, so the server copy is hours newer with progress 0.
        // This device is partway through the same file. Adopting the zero would (a) wipe the
        // local position and (b) make getActiveTrack() — which picks the most-recently-viewed
        // track — restart the finished file instead of moving to the next one.
        val hoursAgoMs = 1_700_000_000_000L
        val network = track(progress = 0L, lastViewedAt = (hoursAgoMs + 3_600_000L) / 1000L)
        val local = track(progress = 1_200_000L, lastViewedAt = hoursAgoMs)

        val merged = MediaItemTrack.merge(network = network, local = local)

        // Then: local progress and recency survive; the finished-elsewhere state still
        // arrives via viewCount from the network copy
        assertThat(merged.progress).isEqualTo(1_200_000L)
        assertThat(merged.lastViewedAt).isEqualTo(hoursAgoMs)
    }

    @Test
    fun `merge adopts newer server progress even when it is zero if local has none`() {
        // Given: neither copy has progress — there is nothing local to protect
        val network = track(progress = 0L, lastViewedAt = 1_700_000_000L)
        val local = track(progress = 0L, lastViewedAt = 0L)

        val merged = MediaItemTrack.merge(network = network, local = local)

        assertThat(merged.progress).isEqualTo(0L)
        assertThat(merged.lastViewedAt).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `getActiveTrack skips a finished-elsewhere track after merge`() {
        // End-to-end guard for the resume decision: track 1 was finished on another device
        // (server: newest lastViewedAt, progress 0) while track 2 was in progress there.
        // After merging both, the resume track must be track 2, not the finished track 1.
        val finishMs = 1_700_010_000_000L
        val track1Network = track(id = "plex:101", progress = 0L, lastViewedAt = finishMs / 1000L)
        val track1Local = track(id = "plex:101", progress = 3_500_000L, lastViewedAt = finishMs - 600_000L)
        val track2Network = track(id = "plex:102", progress = 500_000L, lastViewedAt = finishMs / 1000L)
        val track2Local = track(id = "plex:102", progress = 0L, lastViewedAt = 0L)

        val mergedTracks =
            listOf(
                MediaItemTrack.merge(network = track1Network, local = track1Local),
                MediaItemTrack.merge(network = track2Network, local = track2Local),
            )

        val active = mergedTracks.getActiveTrack()
        assertThat(active.id).isEqualTo("plex:102")
        assertThat(active.progress).isEqualTo(500_000L)
    }

    @Test
    fun `merge always keeps local cached flag and network metadata`() {
        val network = track(progress = 1_800_000L, lastViewedAt = 1_700_000_000_000L)
        val local = track(progress = 0L, lastViewedAt = 0L, cached = true)

        val merged = MediaItemTrack.merge(network = network, local = local)

        // Downloaded status is local-only and must survive the merge either way
        assertThat(merged.cached).isTrue()
        // Metadata still comes from the network copy
        assertThat(merged.title).isEqualTo("Chapter 1")
    }

    @Test
    fun `merge forceUseNetwork always takes the network copy`() {
        val network = track(progress = 1_800_000L, lastViewedAt = 1_000L)
        val local = track(progress = 2_400_000L, lastViewedAt = 2_000L, cached = true)

        val merged = MediaItemTrack.merge(network = network, local = local, forceUseNetwork = true)

        assertThat(merged.progress).isEqualTo(1_800_000L)
        assertThat(merged.lastViewedAt).isEqualTo(1_000L)
        assertThat(merged.cached).isTrue()
    }
}
