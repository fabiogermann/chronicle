package local.oss.chronicle.features.player

import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.sources.plex.ConnectionRefreshCoordinator
import local.oss.chronicle.data.sources.plex.PlaybackUrlResolver
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Tests for [PlaybackErrorRecoveryHandler] — the off-network playback recovery seam (fix #3).
 *
 * Given a [PlaybackException], the handler:
 *  - decides whether it is a recoverable network failure (vs. codec/DRM/etc.),
 *  - if recoverable, captures the current player state, runs
 *    [ConnectionRefreshCoordinator.refresh] to flush stale connection caches, then re-prepares
 *    the player with a freshly-built [MediaSource] for the current track,
 *  - limits to [PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS] retries per playback session,
 *  - resets the counter when playback reaches `STATE_READY`,
 *  - always broadcasts the underlying error so existing UI listeners can surface it.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaybackErrorRecoveryHandlerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private lateinit var refreshCoordinator: ConnectionRefreshCoordinator
    private lateinit var playbackUrlResolver: PlaybackUrlResolver
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var playerHandle: PlaybackErrorRecoveryHandler.PlayerHandle
    private lateinit var mediaSourceRebuilder: PlaybackErrorRecoveryHandler.MediaSourceRebuilder
    private lateinit var handler: PlaybackErrorRecoveryHandler

    @Before
    fun setUp() {
        refreshCoordinator = mockk(relaxed = true)
        playbackUrlResolver = mockk(relaxed = true)
        localBroadcastManager = mockk(relaxed = true)
        playerHandle = mockk(relaxed = true)
        mediaSourceRebuilder = mockk(relaxed = true)

        // Reasonable defaults for the player handle. The queue getters must be stubbed too:
        // on a relaxed mock, getCurrentMediaItems() would otherwise return an EMPTY list and
        // every recovery would abort with "no queued media items".
        val defaultItem = mockk<MediaItem>(relaxed = true)
        every { playerHandle.getCurrentMediaItem() } returns defaultItem
        every { playerHandle.getCurrentMediaItems() } returns listOf(defaultItem)
        every { playerHandle.getCurrentMediaItemIndex() } returns 0
        every { playerHandle.getCurrentPosition() } returns 12345L
        every { playerHandle.getPlayWhenReady() } returns true

        // Default rebuilder returns a non-null MediaSource so the happy path proceeds
        coEvery { mediaSourceRebuilder.rebuild(any()) } returns mockk<MediaSource>(relaxed = true)

        handler =
            PlaybackErrorRecoveryHandler(
                connectionRefreshCoordinator = refreshCoordinator,
                playbackUrlResolver = playbackUrlResolver,
                localBroadcastManager = localBroadcastManager,
                scope = scope,
                playerHandle = playerHandle,
                mediaSourceRebuilder = mediaSourceRebuilder,
            )
    }

    private fun networkError(
        code: Int = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        cause: Throwable? = null,
    ): PlaybackException = PlaybackException("network boom", cause, code)

    // -- Scenario 1 ----------------------------------------------------------------------------
    @Test
    fun `network ERROR_CODE_IO_NETWORK_CONNECTION_FAILED triggers refresh exactly once`() =
        runTest(dispatcher) {
            handler.handleError(networkError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            advanceUntilIdle()

            coVerify(exactly = 1) { refreshCoordinator.refresh() }
        }

    // -- Scenario 2 ----------------------------------------------------------------------------
    @Test
    fun `codec ERROR_CODE_DECODER_INIT_FAILED does NOT trigger refresh`() =
        runTest(dispatcher) {
            handler.handleError(networkError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
            advanceUntilIdle()

            coVerify(exactly = 0) { refreshCoordinator.refresh() }
        }

    // -- Scenario 3 ----------------------------------------------------------------------------
    @Test
    fun `IO_UNSPECIFIED with SocketTimeoutException in cause chain triggers refresh`() =
        runTest(dispatcher) {
            val cause = IOException("wrapped", SocketTimeoutException("read timed out"))
            handler.handleError(
                networkError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, cause = cause),
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { refreshCoordinator.refresh() }
        }

    // -- Scenario 4 ----------------------------------------------------------------------------
    @Test
    fun `three consecutive network errors trigger exactly two refresh calls`() =
        runTest(dispatcher) {
            repeat(3) {
                handler.handleError(networkError())
            }
            advanceUntilIdle()

            coVerify(exactly = PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS) {
                refreshCoordinator.refresh()
            }
        }

    // -- Scenario 5 ----------------------------------------------------------------------------
    @Test
    fun `onPlayerReady resets retry counter allowing fresh refresh after exhaustion`() =
        runTest(dispatcher) {
            // Exhaust the budget
            repeat(PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS + 1) {
                handler.handleError(networkError())
            }
            advanceUntilIdle()
            coVerify(exactly = PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS) {
                refreshCoordinator.refresh()
            }

            // Simulate a successful track start
            handler.onPlayerReady()

            // A subsequent network error should trigger refresh again
            handler.handleError(networkError())
            advanceUntilIdle()
            coVerify(exactly = PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS + 1) {
                refreshCoordinator.refresh()
            }
        }

    // -- Scenario 5b: handover must not leave the player permanently dead -----------------------
    // During a Wi-Fi -> cellular handover both recovery attempts can fire and fail inside the
    // transition window, before the track ever reaches STATE_READY, so the onPlayerReady reset
    // never runs. The budget then stays exhausted and every subsequent network error is surfaced
    // as terminal even after connectivity returns — the user is stuck until app restart.
    //
    // The desired behavior: a network change (any refresh of connectivity) re-arms the recovery
    // budget, because the whole reason it was exhausted (a stale network) no longer applies.
    @Test
    fun `budget re-arms after network change so post-handover errors can recover`() =
        runTest(dispatcher) {
            // Exhaust the budget with failures (refresh throws -> recovery fails -> no READY reset)
            coEvery { refreshCoordinator.refresh() } throws IOException("still no route")
            repeat(PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS) {
                handler.handleError(networkError())
            }
            advanceUntilIdle()
            coVerify(exactly = PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS) {
                refreshCoordinator.refresh()
            }

            // Budget is spent: a further error is surfaced as terminal, no refresh attempted
            handler.handleError(networkError())
            advanceUntilIdle()
            coVerify(exactly = PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS) {
                refreshCoordinator.refresh()
            }

            // Network comes back / changes — the budget must be re-armed so the player is not
            // permanently dead. Simulate by telling the handler the network changed.
            handler.onNetworkChanged()

            // Now the next error should attempt recovery again (budget was re-armed).
            coEvery { refreshCoordinator.refresh() } returns Unit
            handler.handleError(networkError())
            advanceUntilIdle()
            coVerify(exactly = PlaybackErrorRecoveryHandler.MAX_RECOVERY_ATTEMPTS + 1) {
                refreshCoordinator.refresh()
            }
        }

    // -- Scenario 6 ----------------------------------------------------------------------------
    @Test
    fun `after successful recovery the full queue is re-set at the captured index and position and playWhenReady restored`() =
        runTest(dispatcher) {
            val item = mockk<MediaItem>(relaxed = true)
            val newSource = mockk<MediaSource>(relaxed = true)
            every { playerHandle.getCurrentMediaItem() } returns item
            every { playerHandle.getCurrentMediaItems() } returns listOf(item)
            every { playerHandle.getCurrentMediaItemIndex() } returns 0
            every { playerHandle.getCurrentPosition() } returns 67890L
            every { playerHandle.getPlayWhenReady() } returns true
            coEvery { mediaSourceRebuilder.rebuild(item) } returns newSource

            handler.handleError(networkError())
            advanceUntilIdle()

            coVerify(exactly = 1) { mediaSourceRebuilder.rebuild(item) }
            verify(exactly = 1) { playerHandle.setMediaSources(listOf(newSource), 0, 67890L) }
            verify(exactly = 1) { playerHandle.setPlayWhenReady(true) }
            verify(exactly = 1) { playerHandle.prepare() }
        }

    // -- Scenario 7 ----------------------------------------------------------------------------
    @Test
    fun `recoverable error broadcasts ACTION_PLAYBACK_ERROR with IS_RECOVERING=true and ACTION_PLAYBACK_RECOVERED on success`() =
        runTest(dispatcher) {
            val intentSlot = mutableListOf<android.content.Intent>()
            every { localBroadcastManager.sendBroadcast(capture(intentSlot)) } returns true

            handler.handleError(networkError())
            advanceUntilIdle()

            // Two broadcasts expected: ERROR (recovering=true) then RECOVERED
            val errorIntent =
                intentSlot.firstOrNull { it.action == MediaPlayerService.ACTION_PLAYBACK_ERROR }
            val recoveredIntent =
                intentSlot.firstOrNull { it.action == MediaPlayerService.ACTION_PLAYBACK_RECOVERED }
            assert(errorIntent != null) { "expected ACTION_PLAYBACK_ERROR broadcast; saw $intentSlot" }
            assert(recoveredIntent != null) { "expected ACTION_PLAYBACK_RECOVERED broadcast; saw $intentSlot" }
            assert(errorIntent!!.getBooleanExtra(MediaPlayerService.EXTRA_IS_RECOVERING, false)) {
                "expected EXTRA_IS_RECOVERING=true on the initial error broadcast"
            }
        }

    @Test
    fun `non-recoverable error broadcasts ACTION_PLAYBACK_ERROR with IS_RECOVERING=false`() =
        runTest(dispatcher) {
            val intentSlot = mutableListOf<android.content.Intent>()
            every { localBroadcastManager.sendBroadcast(capture(intentSlot)) } returns true

            handler.handleError(networkError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
            advanceUntilIdle()

            val errorIntent =
                intentSlot.firstOrNull { it.action == MediaPlayerService.ACTION_PLAYBACK_ERROR }
            assert(errorIntent != null) { "expected ACTION_PLAYBACK_ERROR broadcast" }
            assert(!errorIntent!!.getBooleanExtra(MediaPlayerService.EXTRA_IS_RECOVERING, true)) {
                "expected EXTRA_IS_RECOVERING=false for terminal errors"
            }
            assert(intentSlot.none { it.action == MediaPlayerService.ACTION_PLAYBACK_RECOVERED }) {
                "should not broadcast RECOVERED for terminal errors"
            }
        }

    // -- Scenario 8 ----------------------------------------------------------------------------
    @Test
    fun `when refresh throws the error is re-broadcast with IS_RECOVERING=false`() =
        runTest(dispatcher) {
            val intentSlot = mutableListOf<android.content.Intent>()
            every { localBroadcastManager.sendBroadcast(capture(intentSlot)) } returns true
            coEvery { refreshCoordinator.refresh() } throws RuntimeException("network down")

            handler.handleError(networkError())
            advanceUntilIdle()

            // First broadcast: ERROR with IS_RECOVERING=true; second broadcast (after refresh
            // throws): ERROR with IS_RECOVERING=false. No RECOVERED broadcast.
            val errorBroadcasts =
                intentSlot.filter { it.action == MediaPlayerService.ACTION_PLAYBACK_ERROR }
            assert(errorBroadcasts.size == 2) {
                "expected two error broadcasts (recovering then final); got ${errorBroadcasts.size}: $intentSlot"
            }
            assert(errorBroadcasts.first().getBooleanExtra(MediaPlayerService.EXTRA_IS_RECOVERING, false))
            assert(!errorBroadcasts.last().getBooleanExtra(MediaPlayerService.EXTRA_IS_RECOVERING, true))
            assert(intentSlot.none { it.action == MediaPlayerService.ACTION_PLAYBACK_RECOVERED }) {
                "should not broadcast RECOVERED when refresh fails"
            }
            // Player should NOT have been re-prepared because rebuild was never reached.
            verify(exactly = 0) { playerHandle.prepare() }
        }

    @Test
    fun `when rebuilder returns null the error is re-broadcast with IS_RECOVERING=false`() =
        runTest(dispatcher) {
            val intentSlot = mutableListOf<android.content.Intent>()
            every { localBroadcastManager.sendBroadcast(capture(intentSlot)) } returns true
            coEvery { mediaSourceRebuilder.rebuild(any()) } returns null

            handler.handleError(networkError())
            advanceUntilIdle()

            val errorBroadcasts =
                intentSlot.filter { it.action == MediaPlayerService.ACTION_PLAYBACK_ERROR }
            assert(errorBroadcasts.size == 2)
            assert(!errorBroadcasts.last().getBooleanExtra(MediaPlayerService.EXTRA_IS_RECOVERING, true))
            verify(exactly = 0) { playerHandle.prepare() }
        }

    // -- Scenario 9: a recovery must preserve the WHOLE queue ----------------------------------
    // Audiobooks are multi-file: a real ExoPlayer holds N MediaItems (one per file of the book).
    // Rebuilding only the current track and calling setMediaSource(single) REPLACES the whole
    // queue with that one item, so when the file ends the player goes ENDED and pressing Play
    // does nothing (the "playback stops mid-book and never resumes" report).
    //
    // These tests use a REAL ExoPlayer under Robolectric as the PlayerHandle, so playlist
    // masking semantics (mediaItemCount / currentMediaItemIndex / currentPosition) are the real
    // media3 1.11.1 behaviour, not a mock's guess.
    private inner class ExoPlayerHandle(
        val player: ExoPlayer,
    ) : PlaybackErrorRecoveryHandler.PlayerHandle {
        override fun getCurrentMediaItem(): MediaItem? = if (player.mediaItemCount > 0) player.currentMediaItem else null

        override fun getCurrentPosition(): Long = player.currentPosition

        override fun getPlayWhenReady(): Boolean = player.playWhenReady

        override fun setMediaSource(
            mediaSource: MediaSource,
            resetPosition: Boolean,
        ) {
            player.setMediaSource(mediaSource, resetPosition)
        }

        override fun setMediaSources(
            mediaSources: List<MediaSource>,
            startIndex: Int,
            startPositionMs: Long,
        ) {
            player.setMediaSources(mediaSources, startIndex, startPositionMs)
        }

        override fun getCurrentMediaItems(): List<MediaItem> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

        override fun getCurrentMediaItemIndex(): Int = player.currentMediaItemIndex

        override fun seekTo(positionMs: Long) {
            player.seekTo(positionMs)
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            player.playWhenReady = playWhenReady
        }

        override fun prepare() {
            player.prepare()
        }
    }

    private fun queueItems(count: Int): List<MediaItem> =
        (0 until count).map {
            MediaItem
                .Builder()
                .setMediaId("plex:track:$it")
                .setUri("http://192.168.1.100:32400/library/parts/$it/file.mp3")
                .build()
        }

    @Test
    fun `recovery keeps all N queued tracks at the same index and position (real ExoPlayer)`() =
        runTest(dispatcher) {
            val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
            val player = ExoPlayer.Builder(appContext).build()
            try {
                // Given: a REAL ExoPlayer holding a 3-file book, positioned in the 2nd file.
                player.setMediaItems(queueItems(3))
                player.seekTo(1, 45_000L)
                player.playWhenReady = true
                assertThat(player.mediaItemCount).isEqualTo(3)
                assertThat(player.currentMediaItemIndex).isEqualTo(1)

                val handle = ExoPlayerHandle(player)
                val rebuilder =
                    PlaybackErrorRecoveryHandler.MediaSourceRebuilder { item ->
                        // Mirror the service: build a fresh ProgressiveMediaSource per requested item.
                        if (item == null) {
                            null
                        } else {
                            val ds = DefaultHttpDataSource.Factory()
                            ProgressiveMediaSource
                                .Factory(ds)
                                .createMediaSource(item)
                        }
                    }
                val realHandler =
                    PlaybackErrorRecoveryHandler(
                        connectionRefreshCoordinator = refreshCoordinator,
                        playbackUrlResolver = playbackUrlResolver,
                        localBroadcastManager = localBroadcastManager,
                        scope = scope,
                        playerHandle = handle,
                        mediaSourceRebuilder = rebuilder,
                    )

                // When: a network error is recovered.
                realHandler.handleError(
                    networkError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
                )
                advanceUntilIdle()

                // Then: the player STILL holds all 3 tracks, at the same index and position —
                // the queue was not collapsed to just the current file.
                assertThat(player.mediaItemCount).isEqualTo(3)
                assertThat(player.currentMediaItemIndex).isEqualTo(1)
                assertThat(player.currentPosition).isEqualTo(45_000L)
                assertThat(player.playWhenReady).isTrue()
            } finally {
                player.release()
            }
        }

    @Test
    fun `recovery re-resolves EVERY queued track URL, not just the current one`() =
        runTest(dispatcher) {
            val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
            val player = ExoPlayer.Builder(appContext).build()
            try {
                player.setMediaItems(queueItems(3))
                player.seekTo(0, 10_000L)

                val handle = ExoPlayerHandle(player)
                val rebuilt = mutableListOf<String>()
                val rebuilder =
                    PlaybackErrorRecoveryHandler.MediaSourceRebuilder { item ->
                        if (item == null) {
                            null
                        } else {
                            rebuilt.add(item.mediaId)
                            val ds = DefaultHttpDataSource.Factory()
                            ProgressiveMediaSource
                                .Factory(ds)
                                .createMediaSource(item)
                        }
                    }
                val realHandler =
                    PlaybackErrorRecoveryHandler(
                        connectionRefreshCoordinator = refreshCoordinator,
                        playbackUrlResolver = playbackUrlResolver,
                        localBroadcastManager = localBroadcastManager,
                        scope = scope,
                        playerHandle = handle,
                        mediaSourceRebuilder = rebuilder,
                    )

                realHandler.handleError(networkError())
                advanceUntilIdle()

                // Every queued item must have been rebuilt against the refreshed connection —
                // otherwise the NEXT file in the book still points at the dead LAN URL and
                // playback dies again at the file boundary.
                assertThat(rebuilt)
                    .containsExactly("plex:track:0", "plex:track:1", "plex:track:2")
                    .inOrder()
            } finally {
                player.release()
            }
        }
}
