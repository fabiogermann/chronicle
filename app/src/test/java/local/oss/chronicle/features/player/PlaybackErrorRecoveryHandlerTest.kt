package local.oss.chronicle.features.player

import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.MediaSource
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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

        // Reasonable defaults for the player handle
        every { playerHandle.getCurrentMediaItem() } returns mockk<MediaItem>(relaxed = true)
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

    // -- Scenario 6 ----------------------------------------------------------------------------
    @Test
    fun `after successful recovery the player is sought to captured position and playWhenReady restored`() =
        runTest(dispatcher) {
            val item = mockk<MediaItem>(relaxed = true)
            val newSource = mockk<MediaSource>(relaxed = true)
            every { playerHandle.getCurrentMediaItem() } returns item
            every { playerHandle.getCurrentPosition() } returns 67890L
            every { playerHandle.getPlayWhenReady() } returns true
            coEvery { mediaSourceRebuilder.rebuild(item) } returns newSource

            handler.handleError(networkError())
            advanceUntilIdle()

            coVerify(exactly = 1) { mediaSourceRebuilder.rebuild(item) }
            verify(exactly = 1) { playerHandle.setMediaSource(newSource, false) }
            verify(exactly = 1) { playerHandle.seekTo(67890L) }
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
}
