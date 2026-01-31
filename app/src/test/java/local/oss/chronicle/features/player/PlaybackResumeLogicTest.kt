package local.oss.chronicle.features.player

import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.local.BookRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.MediaItemTrack
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for resume/playback logic in PlaybackStateController.
 * These tests verify that when Google Assistant sends an ACTION_PLAY command
 * (triggered by "Resume playing my audiobook"), the player correctly resumes from the last position.
 *
 * **Voice Command Scenarios Tested:**
 * - "Resume playing my audiobook" - should resume from last position, not from beginning
 * - Pause/play cycles - position should be preserved across multiple cycles
 * - State transitions - player state should correctly transition between PLAYING and PAUSED
 */
@ExperimentalCoroutinesApi
class PlaybackResumeLogicTest {
    @RelaxedMockK
    private lateinit var bookRepository: BookRepository

    @RelaxedMockK
    private lateinit var prefsRepo: PrefsRepo

    private lateinit var controller: PlaybackStateController

    // Test audiobook data
    private val testAudiobook = Audiobook(
        id = 1,
        source = 1L,
        title = "Test Audiobook",
        author = "Test Author",
        duration = 3600000, // 1 hour
    )

    private val testTracks = listOf(
        MediaItemTrack(
            id = 1,
            duration = 1800000, // 30 minutes
            index = 1,
        ),
        MediaItemTrack(
            id = 2,
            duration = 1800000, // 30 minutes
            index = 2,
        ),
    )

    private val testChapters = listOf(
        Chapter(
            id = 1,
            bookId = 1,
            trackId = 1,
            index = 1,
            title = "Chapter 1",
            startTimeOffset = 0L,
            endTimeOffset = 900000L, // 15 minutes
        ),
        Chapter(
            id = 2,
            bookId = 1,
            trackId = 1,
            index = 2,
            title = "Chapter 2",
            startTimeOffset = 900000L, // 15 minutes
            endTimeOffset = 1800000L, // 30 minutes
        ),
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        // Set up test dispatcher for coroutines
        Dispatchers.setMain(StandardTestDispatcher())
        controller = PlaybackStateController(
            bookRepository = bookRepository,
            prefsRepo = prefsRepo,
        )
    }

    @After
    fun tearDown() {
        // Reset Main dispatcher to the original state
        Dispatchers.resetMain()
    }

    // ========================================
    // Resume Logic Tests
    // ========================================

    /**
     * Voice command: "Resume playing my audiobook"
     * Expected: Playback resumes from last saved position (42000ms), not from 0
     *
     * This test validates the core resume functionality - when the user asks
     * Google Assistant to resume playback, it should continue from where they left off.
     */
    @Test
    fun `resume starts from last saved position`() = runTest {
        // Given: Audiobook loaded at position 42000ms (42 seconds into first track)
        val savedPosition = 42000L
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = savedPosition,
        )

        // When: State is checked after loading
        val state = controller.currentState

        // Then: Position should be at saved position, not at 0
        assertThat(state.currentTrackIndex, `is`(0))
        assertThat(state.currentTrackPositionMs, `is`(savedPosition))
        assertThat(state.bookPositionMs, `is`(savedPosition))
    }

    /**
     * Voice command: "Pause" followed by "Resume"
     * Expected: Position is maintained after pause
     *
     * This validates that pausing doesn't reset the position - a critical
     * requirement for reliable pause/resume behavior.
     */
    @Test
    fun `position is preserved after pause`() = runTest {
        // Given: Playing at position 30000ms
        val playPosition = 30000L
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = playPosition,
        )
        controller.updatePlayingState(true)

        // When: Pause is called
        controller.updatePlayingState(false)

        // Then: Position should still be 30000ms
        val state = controller.currentState
        assertThat(state.currentTrackPositionMs, `is`(playPosition))
        assertThat(state.bookPositionMs, `is`(playPosition))
        assertThat(state.isPlaying, `is`(false))
    }

    /**
     * Voice command: Multiple "Pause" and "Resume" cycles
     * Expected: Position correctly maintained across multiple cycles
     *
     * This tests the robustness of state management across typical user
     * interaction patterns (pause to take a break, resume later, repeat).
     */
    @Test
    fun `multiple pause play cycles maintain correct position`() = runTest {
        // Given: Audiobook loaded and playing at initial position
        val initialPosition = 15000L
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = initialPosition,
        )

        // When: Multiple pause/play/position update cycles
        // Cycle 1: Play -> update position -> pause
        controller.updatePlayingState(true)
        assertThat(controller.currentState.isPlaying, `is`(true))

        controller.updatePosition(0, 25000L)
        assertThat(controller.currentState.currentTrackPositionMs, `is`(25000L))

        controller.updatePlayingState(false)
        assertThat(controller.currentState.isPlaying, `is`(false))
        assertThat(controller.currentState.currentTrackPositionMs, `is`(25000L))

        // Cycle 2: Resume -> update position -> pause
        controller.updatePlayingState(true)
        assertThat(controller.currentState.isPlaying, `is`(true))

        controller.updatePosition(0, 35000L)
        assertThat(controller.currentState.currentTrackPositionMs, `is`(35000L))

        controller.updatePlayingState(false)

        // Then: Final position should be correctly maintained
        val finalState = controller.currentState
        assertThat(finalState.currentTrackPositionMs, `is`(35000L))
        assertThat(finalState.isPlaying, `is`(false))
    }

    /**
     * Voice command: "Resume playing my audiobook" after switching tracks
     * Expected: Resume from correct position in second track
     *
     * This validates multi-track audiobook handling - ensuring resume works
     * correctly even when the saved position is in a later track.
     */
    @Test
    fun `resume starts from correct track when position is in second track`() = runTest {
        // Given: Audiobook loaded starting at track 1, position 120000ms
        // (This is 2 minutes into the second track)
        val savedTrackIndex = 1
        val savedPosition = 120000L
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = savedTrackIndex,
            startPositionMs = savedPosition,
        )

        // When: State is checked
        val state = controller.currentState

        // Then: Should be at track 1 (second track), position 120000ms
        assertThat(state.currentTrackIndex, `is`(savedTrackIndex))
        assertThat(state.currentTrackPositionMs, `is`(savedPosition))
        // Book position = track 0 duration (1800000ms) + track position (120000ms)
        assertThat(state.bookPositionMs, `is`(1800000L + savedPosition))
    }

    // ========================================
    // Position Persistence Tests
    // ========================================

    /**
     * Voice command: "Pause" (triggers state update)
     * Expected: Position is correctly reflected in state for persistence
     *
     * This validates that pausing maintains the correct position in state
     * which will be persisted to database (database write happens asynchronously).
     */
    @Test
    fun `position state is correct when pausing for persistence`() = runTest {
        // Given: Playing at a specific position
        val playPosition = 180000L
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = playPosition,
        )
        controller.updatePlayingState(true)
        assertThat(controller.currentState.isPlaying, `is`(true))

        // When: Pause is triggered
        controller.updatePlayingState(false)

        // Then: State should correctly reflect paused state with position maintained
        val state = controller.currentState
        assertThat(state.isPlaying, `is`(false))
        assertThat(state.currentTrackPositionMs, `is`(playPosition))
        assertThat(state.bookPositionMs, `is`(playPosition))
        assertThat(state.hasMedia, `is`(true))
    }

    /**
     * Voice command: Continuous playback with position updates
     * Expected: Position updates are correctly reflected in state
     *
     * This simulates the normal playback scenario where ExoPlayer
     * continuously updates the position.
     */
    @Test
    fun `position updates are correctly tracked during playback`() = runTest {
        // Given: Audiobook loaded and playing
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = 0L,
        )
        controller.updatePlayingState(true)

        // When: Position is updated multiple times (simulating playback progress)
        val positions = listOf(5000L, 10000L, 15000L, 20000L)
        positions.forEach { position ->
            controller.updatePosition(0, position)
            assertThat(controller.currentState.currentTrackPositionMs, `is`(position))
        }

        // Then: Final position should be the last update
        assertThat(controller.currentState.currentTrackPositionMs, `is`(20000L))
    }

    /**
     * Voice command: Position updates across track boundaries
     * Expected: Track index and position both update correctly
     *
     * This validates correct handling of track transitions during playback.
     */
    @Test
    fun `position updates correctly when crossing track boundary`() = runTest {
        // Given: Audiobook loaded at end of first track
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = 1795000L, // Near end of first track (30min track)
        )

        // When: Position moves to second track
        controller.updatePosition(1, 5000L)

        // Then: State should reflect second track
        val state = controller.currentState
        assertThat(state.currentTrackIndex, `is`(1))
        assertThat(state.currentTrackPositionMs, `is`(5000L))
        // Book position = first track duration + current position
        assertThat(state.bookPositionMs, `is`(1800000L + 5000L))
    }

    /**
     * Voice command: App lifecycle (stop/resume)
     * Expected: Clear and reload preserves ability to resume from saved position
     *
     * This validates the full stop/resume cycle as would happen when the
     * app is backgrounded and resumed.
     */
    @Test
    fun `clear and reload allows resuming from saved position`() = runTest {
        // Given: Playing at a specific position, then cleared
        val savedPosition = 250000L
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = savedPosition,
        )
        controller.updatePlayingState(true)
        controller.updatePlayingState(false) // Pause to persist
        controller.clear()

        // Verify state is cleared
        assertThat(controller.currentState.hasMedia, `is`(false))

        // When: Audiobook is reloaded at saved position
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = savedPosition,
        )

        // Then: Should resume from saved position
        val state = controller.currentState
        assertThat(state.hasMedia, `is`(true))
        assertThat(state.currentTrackPositionMs, `is`(savedPosition))
        assertThat(state.bookPositionMs, `is`(savedPosition))
    }

    // ========================================
    // State Transition Tests
    // ========================================

    /**
     * Voice command: "Resume playing my audiobook" when stopped
     * Expected: State transitions from no media to PLAYING
     *
     * This validates the initial playback start scenario.
     */
    @Test
    fun `state transitions from EMPTY to PLAYING on initial play`() = runTest {
        // Given: No media loaded (EMPTY state)
        assertThat(controller.currentState, `is`(PlaybackState.EMPTY))
        assertThat(controller.currentState.hasMedia, `is`(false))

        // When: Audiobook is loaded and play is triggered
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = 0L,
        )
        controller.updatePlayingState(true)

        // Then: State should be PLAYING
        val state = controller.currentState
        assertThat(state.hasMedia, `is`(true))
        assertThat(state.isPlaying, `is`(true))
    }

    /**
     * Voice command: "Pause"
     * Expected: State transitions from PLAYING to PAUSED
     *
     * This validates the pause command behavior.
     */
    @Test
    fun `state transitions from PLAYING to PAUSED on pause`() = runTest {
        // Given: Audiobook is playing
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = 0L,
        )
        controller.updatePlayingState(true)
        assertThat(controller.currentState.isPlaying, `is`(true))

        // When: Pause is called
        controller.updatePlayingState(false)

        // Then: State should be PAUSED (not playing)
        assertThat(controller.currentState.isPlaying, `is`(false))
        assertThat(controller.currentState.hasMedia, `is`(true)) // Media still loaded
    }

    /**
     * Voice command: "Resume" after pause
     * Expected: State transitions from PAUSED to PLAYING
     *
     * This is the core resume scenario that Google Assistant triggers.
     */
    @Test
    fun `state transitions from PAUSED to PLAYING on resume`() = runTest {
        // Given: Audiobook is paused at a position
        val pausedPosition = 60000L
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = pausedPosition,
        )
        controller.updatePlayingState(true)
        controller.updatePlayingState(false)
        assertThat(controller.currentState.isPlaying, `is`(false))

        // When: Resume is called (simulating ACTION_PLAY from Google Assistant)
        controller.updatePlayingState(true)

        // Then: State should transition to PLAYING
        val state = controller.currentState
        assertThat(state.isPlaying, `is`(true))
        assertThat(state.currentTrackPositionMs, `is`(pausedPosition)) // Position maintained
    }

    /**
     * Voice command: Stop/Clear playback
     * Expected: State clears all media and resets to EMPTY
     *
     * This validates the stop/clear behavior.
     */
    @Test
    fun `state clears to EMPTY when playback is stopped`() = runTest {
        // Given: Audiobook is loaded and playing
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = 100000L,
        )
        controller.updatePlayingState(true)
        assertThat(controller.currentState.hasMedia, `is`(true))

        // When: Clear is called (stop playback completely)
        controller.clear()

        // Then: State should be EMPTY
        val state = controller.currentState
        assertThat(state.hasMedia, `is`(false))
        assertThat(state.isPlaying, `is`(false))
        assertThat(state.audiobook, `is`(null as Audiobook?))
    }

    /**
     * Voice command: State check during playback
     * Expected: State is correctly reported at any time
     *
     * This validates that state can be queried reliably at any point.
     */
    @Test
    fun `state is correctly reported during various playback stages`() = runTest {
        // Stage 1: No media loaded
        var state = controller.currentState
        assertThat(state.hasMedia, `is`(false))
        assertThat(state.isPlaying, `is`(false))

        // Stage 2: Media loaded but not playing
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = 0L,
        )
        state = controller.currentState
        assertThat(state.hasMedia, `is`(true))
        assertThat(state.isPlaying, `is`(false))
        assertThat(state.audiobook?.title, `is`("Test Audiobook"))

        // Stage 3: Playing
        controller.updatePlayingState(true)
        state = controller.currentState
        assertThat(state.hasMedia, `is`(true))
        assertThat(state.isPlaying, `is`(true))

        // Stage 4: Paused
        controller.updatePlayingState(false)
        state = controller.currentState
        assertThat(state.hasMedia, `is`(true))
        assertThat(state.isPlaying, `is`(false))

        // Stage 5: Resumed
        controller.updatePlayingState(true)
        state = controller.currentState
        assertThat(state.hasMedia, `is`(true))
        assertThat(state.isPlaying, `is`(true))
    }

    /**
     * Voice command: Position update without state change
     * Expected: Position updates don't affect playing state
     *
     * This validates that position updates and state changes are independent.
     */
    @Test
    fun `position updates do not affect playing state`() = runTest {
        // Given: Audiobook is playing
        controller.loadAudiobook(
            audiobook = testAudiobook,
            tracks = testTracks,
            chapters = testChapters,
            startTrackIndex = 0,
            startPositionMs = 0L,
        )
        controller.updatePlayingState(true)

        // When: Position is updated multiple times
        controller.updatePosition(0, 10000L)
        assertThat(controller.currentState.isPlaying, `is`(true))

        controller.updatePosition(0, 20000L)
        assertThat(controller.currentState.isPlaying, `is`(true))

        controller.updatePosition(0, 30000L)

        // Then: Playing state should remain true throughout
        assertThat(controller.currentState.isPlaying, `is`(true))
        assertThat(controller.currentState.currentTrackPositionMs, `is`(30000L))
    }
}
