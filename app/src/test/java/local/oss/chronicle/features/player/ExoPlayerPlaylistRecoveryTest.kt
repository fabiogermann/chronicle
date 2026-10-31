package local.oss.chronicle.features.player

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the media3 playlist behaviour [PlaybackErrorRecoveryHandler] depends on when it recovers
 * a multi-file book (one [MediaItem] per file) after a network error:
 *
 *  - `setMediaSource(single)` REPLACES the entire playlist: the recovered player would hold
 *    only the current file, playback would END at the next file boundary, and pressing Play
 *    would do nothing — the "audio stops mid-book and won't resume" report.
 *  - `setMediaSources(list, index, position)` keeps every queued item and resumes at the
 *    captured index/position.
 *
 * Runs against a REAL [ExoPlayer] so a media3 upgrade that changes this contract fails here
 * instead of in the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExoPlayerPlaylistRecoveryTest {
    private fun queueItems(count: Int): List<MediaItem> =
        (0 until count).map {
            MediaItem
                .Builder()
                .setMediaId("plex:track:$it")
                .setUri("http://192.168.1.100:32400/library/parts/$it/file.mp3")
                .build()
        }

    private fun sourceFor(item: MediaItem) =
        ProgressiveMediaSource
            .Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(item)

    @Test
    fun `setMediaSource with a single source collapses a 3-item playlist to 1 item`() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        try {
            val items = queueItems(3)
            player.setMediaItems(items)
            player.seekTo(1, 45_000L)
            assertThat(player.mediaItemCount).isEqualTo(3)

            // The pre-fix recovery path: rebuild only the current track and set it alone.
            player.setMediaSource(sourceFor(items[1]), false)

            // This is the masking behaviour the fix must avoid: the whole book is gone.
            assertThat(player.mediaItemCount).isEqualTo(1)
        } finally {
            player.release()
        }
    }

    @Test
    fun `setMediaSources with the full list keeps all 3 items at the captured index and position`() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        try {
            val items = queueItems(3)
            player.setMediaItems(items)
            player.seekTo(1, 45_000L)
            assertThat(player.mediaItemCount).isEqualTo(3)
            assertThat(player.currentMediaItemIndex).isEqualTo(1)

            // The fixed recovery path: rebuild EVERY queued item and restore the full queue.
            player.setMediaSources(items.map(::sourceFor), 1, 45_000L)

            assertThat(player.mediaItemCount).isEqualTo(3)
            assertThat(player.currentMediaItemIndex).isEqualTo(1)
            assertThat(player.currentPosition).isEqualTo(45_000L)
        } finally {
            player.release()
        }
    }
}
