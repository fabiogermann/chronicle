package local.oss.chronicle.features.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeekHandlerTest {

    private lateinit var seekHandler: SeekHandler

    @Before
    fun setUp() {
        seekHandler = SeekHandler()
    }

    @Test
    fun `initial state is idle`() {
        assertFalse(seekHandler.isSeeking)
        assertEquals(SeekState.IDLE, seekHandler.seekState.value)
    }

    @Test
    fun `onSeekStart sets seeking state`() {
        seekHandler.onSeekStart(2, 30000L)
        
        assertTrue(seekHandler.isSeeking)
        with(seekHandler.seekState.value) {
            assertTrue(isActive)
            assertEquals(2, targetTrackIndex)
            assertEquals(30000L, targetPositionMs)
        }
    }

    @Test
    fun `onSeekComplete clears seeking state`() {
        seekHandler.onSeekStart(2, 30000L)
        seekHandler.onSeekComplete()
        
        assertFalse(seekHandler.isSeeking)
        assertEquals(SeekState.IDLE, seekHandler.seekState.value)
    }

    @Test
    fun `onSeekCancelled clears seeking state`() {
        seekHandler.onSeekStart(2, 30000L)
        seekHandler.onSeekCancelled()
        
        assertFalse(seekHandler.isSeeking)
    }

    @Test
    fun `shouldProcessStateUpdate returns true when not seeking`() {
        assertTrue(seekHandler.shouldProcessStateUpdate(0, 1000L))
    }

    @Test
    fun `shouldProcessStateUpdate blocks stale updates during seek`() {
        seekHandler.onSeekStart(2, 30000L)
        
        // Report old position - should be blocked
        assertFalse(seekHandler.shouldProcessStateUpdate(0, 1000L))
        
        // Report wrong track - should be blocked
        assertFalse(seekHandler.shouldProcessStateUpdate(1, 30000L))
    }

    @Test
    fun `shouldProcessStateUpdate allows update near target position`() {
        seekHandler.onSeekStart(2, 30000L)
        
        // Report position close to target (within 500ms tolerance)
        assertTrue(seekHandler.shouldProcessStateUpdate(2, 30200L))
        
        // Seek should be auto-completed
        assertFalse(seekHandler.isSeeking)
    }

    @Test
    fun `shouldProcessStateUpdate respects custom tolerance`() {
        seekHandler.onSeekStart(2, 30000L)
        
        // 600ms off with default tolerance (500ms) - should block
        assertFalse(seekHandler.shouldProcessStateUpdate(2, 30600L, toleranceMs = 500L))
        
        // 600ms off with larger tolerance (1000ms) - should allow
        assertTrue(seekHandler.shouldProcessStateUpdate(2, 30600L, toleranceMs = 1000L))
    }

    @Test
    fun `reset clears seek state`() {
        seekHandler.onSeekStart(2, 30000L)
        seekHandler.reset()
        
        assertFalse(seekHandler.isSeeking)
    }

    @Test
    fun `seek timeout is detected`() {
        val state = SeekState(
            isActive = true,
            targetTrackIndex = 0,
            targetPositionMs = 1000L,
            startedAtMs = System.currentTimeMillis() - 6000L // 6 seconds ago
        )
        
        assertTrue(state.hasTimedOut())
    }

    @Test
    fun `non-timed-out seek is not detected as timed out`() {
        val state = SeekState(
            isActive = true,
            targetTrackIndex = 0,
            targetPositionMs = 1000L,
            startedAtMs = System.currentTimeMillis() // Just now
        )
        
        assertFalse(state.hasTimedOut())
    }
}
