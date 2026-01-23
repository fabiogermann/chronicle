package local.oss.chronicle.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import local.oss.chronicle.data.model.MediaItemTrack
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Thread-safety tests for TrackListStateManager.
 * Verifies that concurrent access doesn't corrupt state.
 *
 * These tests are critical for PR 2.1 - ensuring the Mutex-based
 * implementation prevents race conditions during concurrent playback operations.
 */
class TrackListStateManagerThreadSafetyTest {

    private lateinit var manager: TrackListStateManager

    private val testTrackList = listOf(
        MediaItemTrack(1, progress = 0, duration = 60000, lastViewedAt = 1),
        MediaItemTrack(2, progress = 0, duration = 60000, lastViewedAt = 2),
        MediaItemTrack(3, progress = 0, duration = 60000, lastViewedAt = 3),
        MediaItemTrack(4, progress = 0, duration = 60000, lastViewedAt = 4),
        MediaItemTrack(5, progress = 0, duration = 60000, lastViewedAt = 5),
    )

    @Before
    fun setUp() {
        manager = TrackListStateManager()
        manager.trackList = testTrackList
    }

    @Test
    fun `concurrent position updates should not corrupt state`() = runBlocking {
        // Setup initial state
        manager.updatePosition(0, 0)

        // Launch many concurrent updates from different "threads"
        val jobs = (0 until 100).map { i ->
            async(Dispatchers.Default) {
                manager.updatePosition(i % 5, (i * 1000).toLong())
            }
        }
        jobs.awaitAll()

        // Verify state is consistent (last write wins, but no corruption)
        val finalState = manager.getCurrentState()
        assertTrue(
            "Track index should be in valid range",
            finalState.currentTrackIndex in 0..4,
        )
        assertTrue(
            "Progress should be non-negative",
            finalState.currentTrackProgress >= 0,
        )
        // Verify we can still read without crashes
        assertNotNull(manager.trackList)
        assertTrue(manager.currentTrackIndex in 0..4)
    }

    @Test
    fun `concurrent track list and position updates should be safe`() = runBlocking {
        val alternateTrackList = listOf(
            MediaItemTrack(10, progress = 0, duration = 30000, lastViewedAt = 1),
            MediaItemTrack(11, progress = 0, duration = 30000, lastViewedAt = 2),
            MediaItemTrack(12, progress = 0, duration = 30000, lastViewedAt = 3),
        )

        // Mix setTrackList and updatePosition calls
        val jobs = (0 until 50).flatMap { i ->
            listOf(
                async(Dispatchers.Default) {
                    if (i % 2 == 0) {
                        manager.setTrackList(testTrackList)
                    } else {
                        manager.setTrackList(alternateTrackList)
                    }
                },
                async(Dispatchers.Default) {
                    // Try to update position (might fail if track list changed)
                    try {
                        val trackIndex = i % 3
                        manager.updatePosition(trackIndex, (i * 500).toLong())
                    } catch (e: IndexOutOfBoundsException) {
                        // Expected - track list might have changed
                    }
                },
            )
        }
        jobs.awaitAll()

        // Verify no exceptions and state remains valid
        val finalState = manager.getCurrentState()
        assertTrue(
            "Track list should not be empty",
            finalState.trackList.isNotEmpty(),
        )
        assertTrue(
            "Track index should be within bounds",
            finalState.currentTrackIndex in 0 until finalState.trackList.size,
        )
    }

    @Test
    fun `concurrent seeking should maintain valid state`() = runBlocking {
        manager.updatePosition(2, 30000) // Start in middle

        // Launch many concurrent seek operations
        val jobs = (0 until 100).map { i ->
            async(Dispatchers.Default) {
                val offset = if (i % 2 == 0) 5000L else -5000L
                manager.seekByRelative(offset)
            }
        }
        jobs.awaitAll()

        // Verify state is consistent
        val finalState = manager.getCurrentState()
        assertTrue(
            "Track index should be in valid range",
            finalState.currentTrackIndex in 0..4,
        )
        assertTrue(
            "Progress should be non-negative",
            finalState.currentTrackProgress >= 0,
        )
        assertTrue(
            "Progress should not exceed track duration",
            finalState.currentTrackProgress <= finalState.trackList[finalState.currentTrackIndex].duration,
        )
    }

    @Test
    fun `withState should provide atomic read`() = runBlocking {
        manager.updatePosition(1, 15000)

        // Read multiple properties atomically
        val (trackIndex, progress, bookPosition) = manager.withState { state ->
            Triple(
                state.currentTrackIndex,
                state.currentTrackProgress,
                state.currentBookPosition,
            )
        }

        // Verify consistency - bookPosition should match trackIndex + progress
        assertTrue("Track index from withState should be valid", trackIndex in 0..4)
        assertTrue("Progress from withState should be non-negative", progress >= 0)
        assertTrue("Book position should be non-negative", bookPosition >= 0)

        // These should all come from the same snapshot
        assertTrue(
            "Book position should be >= progress (since we're not at first track)",
            bookPosition >= progress,
        )
    }

    @Test
    fun `updateState should provide atomic read-modify-write`() = runBlocking {
        manager.updatePosition(2, 30000)

        // Multiple concurrent read-modify-write operations
        val jobs = (0 until 50).map { i ->
            async(Dispatchers.Default) {
                manager.updateState { currentState ->
                    // Increment progress by 1000ms, wrapping to next track if needed
                    val newProgress = currentState.currentTrackProgress + 1000
                    if (newProgress < currentState.trackList[currentState.currentTrackIndex].duration) {
                        currentState.copy(currentTrackProgress = newProgress)
                    } else {
                        currentState // Don't modify if would exceed duration
                    }
                }
            }
        }
        jobs.awaitAll()

        // Verify state is valid
        val finalState = manager.getCurrentState()
        assertTrue(
            "Final progress should not exceed track duration",
            finalState.currentTrackProgress <= finalState.trackList[finalState.currentTrackIndex].duration,
        )
    }

    @Test
    fun `seekToActiveTrack should be thread-safe`() = runBlocking {
        val tracksWithDifferentProgress = listOf(
            MediaItemTrack(1, progress = 5000, duration = 60000, lastViewedAt = 1),
            MediaItemTrack(2, progress = 15000, duration = 60000, lastViewedAt = 5), // Most recent
            MediaItemTrack(3, progress = 10000, duration = 60000, lastViewedAt = 3),
        )

        // Concurrent seekToActiveTrack calls
        val jobs = (0 until 20).map {
            async(Dispatchers.Default) {
                manager.trackList = tracksWithDifferentProgress
                manager.seekToActiveTrack()
            }
        }
        jobs.awaitAll()

        // Should have seeked to track with most recent lastViewedAt
        val finalState = manager.getCurrentState()
        assertTrue(
            "Should be at track index 1 (highest lastViewedAt)",
            finalState.currentTrackIndex == 1,
        )
        assertTrue(
            "Should be at progress 15000",
            finalState.currentTrackProgress == 15000L,
        )
    }

    @Test
    fun `mixed operations should not cause race conditions`() = runBlocking {
        // Simulate real-world scenario: position updates, seeks, and state reads
        val jobs = (0 until 100).map { i ->
            when (i % 4) {
                0 -> async(Dispatchers.Default) {
                    manager.updatePosition(i % 5, (i * 1000).toLong())
                }
                1 -> async(Dispatchers.Default) {
                    manager.seekByRelative(if (i % 2 == 0) 3000L else -3000L)
                }
                2 -> async(Dispatchers.Default) {
                    manager.getCurrentState()
                }
                else -> async(Dispatchers.Default) {
                    manager.withState { state ->
                        state.currentBookPosition // Just read it
                    }
                }
            }
        }
        jobs.awaitAll()

        // Should complete without crashes and maintain valid state
        val finalState = manager.getCurrentState()
        assertTrue("Track index valid", finalState.currentTrackIndex in 0..4)
        assertTrue("Progress non-negative", finalState.currentTrackProgress >= 0)
    }

    @Test
    fun `property accessors should be safe during concurrent suspend calls`() = runBlocking {
        // Launch suspend operations
        val suspendJobs = (0 until 50).map { i ->
            async(Dispatchers.Default) {
                manager.updatePosition(i % 5, (i * 1000).toLong())
            }
        }

        // Meanwhile, read properties from the test thread (synchronous access)
        repeat(100) {
            val trackList = manager.trackList
            val trackIndex = manager.currentTrackIndex
            val progress = manager.currentTrackProgress

            // Verify consistency of synchronous reads
            assertTrue("Track list should not be empty", trackList.isNotEmpty())
            assertTrue("Track index should be in range", trackIndex in 0..4)
            assertTrue("Progress should be non-negative", progress >= 0)
        }

        suspendJobs.awaitAll()

        // Final state should be valid
        assertTrue(manager.currentTrackIndex in 0..4)
    }
}
