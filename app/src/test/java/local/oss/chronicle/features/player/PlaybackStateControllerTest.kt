package local.oss.chronicle.features.player

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.local.BookRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.plex.PlexMediaSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStateControllerTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var bookRepository: BookRepository
    private lateinit var prefsRepo: PrefsRepo
    private lateinit var controller: PlaybackStateController

    // Test data
    private val testTracks =
        listOf(
            MediaItemTrack(id = "plex:1", libraryId = "plex:lib:1", title = "Track 1", duration = 60_000L, index = 1),
            MediaItemTrack(id = "plex:2", libraryId = "plex:lib:1", title = "Track 2", duration = 60_000L, index = 2),
            MediaItemTrack(id = "plex:3", libraryId = "plex:lib:1", title = "Track 3", duration = 60_000L, index = 3),
        )

    private val testChapters =
        listOf(
            Chapter(
                id = 1,
                title = "Chapter 1",
                index = 1,
                startTimeOffset = 0L,
                endTimeOffset = 90_000L,
                trackId = "plex:1",
                bookId = "plex:100",
            ),
            Chapter(
                id = 2,
                title = "Chapter 2",
                index = 2,
                startTimeOffset = 90_000L,
                endTimeOffset = 150_000L,
                trackId = "plex:2",
                bookId = "plex:100",
            ),
            Chapter(
                id = 3,
                title = "Chapter 3",
                index = 3,
                startTimeOffset = 150_000L,
                endTimeOffset = 180_000L,
                trackId = "plex:3",
                bookId = "plex:100",
            ),
        )

    private val testAudiobook =
        Audiobook(
            id = "plex:100",
            libraryId = "plex:lib:1",
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Test Audiobook",
            author = "Test Author",
            duration = 180_000L,
            progress = 0L,
        )

    @Before
    fun setup() {
        // Set the main dispatcher for tests
        Dispatchers.setMain(testDispatcher)

        bookRepository = mockk(relaxed = true)
        prefsRepo = mockk(relaxed = true)

        // Setup default mock behaviors
        coEvery { bookRepository.updateProgress(any(), any(), any()) } just Runs
        every { prefsRepo.playbackSpeed = any() } just Runs

        controller = PlaybackStateController(bookRepository, prefsRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========================
    // Initial State Tests
    // ========================

    @Test
    fun `initial state is EMPTY`() {
        val state = controller.currentState
        assertEquals(PlaybackState.EMPTY, state)
        assertFalse(state.hasMedia)
    }

    @Test
    fun `state flow emits EMPTY on creation`() =
        runTest {
            val initialState = controller.state.first()
            assertEquals(PlaybackState.EMPTY, initialState)
        }

    // ========================
    // loadAudiobook() Tests
    // ========================

    @Test
    fun `loadAudiobook sets state correctly`() =
        runTest {
            controller.loadAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startTrackIndex = 1,
                startPositionMs = 5_000L,
            )

            val state = controller.currentState
            assertEquals(testAudiobook, state.audiobook)
            assertEquals(testTracks, state.tracks)
            assertEquals(testChapters, state.chapters)
            assertEquals(1, state.currentTrackIndex)
            assertEquals(5_000L, state.currentTrackPositionMs)
            assertTrue(state.hasMedia)
        }

    @Test
    fun `loadAudiobook emits state to flow`() =
        runTest {
            controller.loadAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
            )

            val emittedState = controller.state.first()
            assertEquals(testAudiobook, emittedState.audiobook)
            assertTrue(emittedState.hasMedia)
        }

    @Test
    fun `loadAudiobook persists to database immediately`() =
        runTest {
            controller.loadAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startTrackIndex = 0,
                startPositionMs = 10_000L,
            )

            advanceUntilIdle()

            // Verify database update was called
            coVerify {
                bookRepository.updateProgress(
                    testAudiobook.id,
                    any(),
                    10_000L,
                )
            }
        }

    // ========================
    // updatePosition() Tests
    // ========================

    @Test
    fun `updatePosition updates state correctly`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            controller.updatePosition(trackIndex = 1, positionMs = 30_000L)

            val state = controller.currentState
            assertEquals(1, state.currentTrackIndex)
            assertEquals(30_000L, state.currentTrackPositionMs)
        }

    @Test
    fun `updatePosition ignores update when no media loaded`() =
        runTest {
            // Don't load any audiobook
            controller.updatePosition(trackIndex = 1, positionMs = 5_000L)

            val state = controller.currentState
            assertEquals(PlaybackState.EMPTY, state)
        }

    @Test
    fun `updatePosition debounces database writes`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            advanceUntilIdle()

            // Rapid position updates
            controller.updatePosition(0, 1_000L)
            controller.updatePosition(0, 2_000L)
            controller.updatePosition(0, 3_000L)

            // Don't advance time - debounce should prevent additional writes
            coVerify(exactly = 1) {
                bookRepository.updateProgress(any(), any(), any())
            }
        }

    @Test
    fun `updatePosition persists after debounce delay`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            advanceUntilIdle()

            controller.updatePosition(0, 5_000L)

            // Advance past debounce delay
            advanceTimeBy(PlaybackStateController.DB_WRITE_DEBOUNCE_MS + 100)

            coVerify(atLeast = 2) {
                bookRepository.updateProgress(any(), any(), any())
            }
        }

    @Test
    fun `updatePosition skips write if change below threshold`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            advanceUntilIdle()

            // Small change (below MIN_POSITION_CHANGE_FOR_PERSIST_MS)
            controller.updatePosition(0, 500L) // 500ms change

            advanceTimeBy(PlaybackStateController.DB_WRITE_DEBOUNCE_MS + 100)

            // Should still only have initial write from loadAudiobook
            coVerify(exactly = 1) {
                bookRepository.updateProgress(any(), any(), any())
            }
        }

    @Test
    fun `updatePosition writes if change above threshold`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            advanceUntilIdle()

            // Large change (above MIN_POSITION_CHANGE_FOR_PERSIST_MS)
            controller.updatePosition(0, 5_000L) // 5000ms change

            advanceTimeBy(PlaybackStateController.DB_WRITE_DEBOUNCE_MS + 100)

            // Should have write from loadAudiobook + position update
            coVerify(atLeast = 2) {
                bookRepository.updateProgress(any(), any(), any())
            }
        }

    // ========================
    // updatePlayingState() Tests
    // ========================

    @Test
    fun `updatePlayingState updates state correctly`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            controller.updatePlayingState(true)

            assertTrue(controller.currentState.isPlaying)
        }

    @Test
    fun `updatePlayingState persists immediately when pausing`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.updatePosition(0, 10_000L)
            advanceUntilIdle()

            controller.updatePlayingState(false) // Pause
            advanceUntilIdle()

            // Should have immediate persistence on pause
            coVerify(atLeast = 2) {
                bookRepository.updateProgress(any(), any(), any())
            }
        }

    @Test
    fun `updatePlayingState ignores update when no media`() =
        runTest {
            controller.updatePlayingState(true)

            assertFalse(controller.currentState.isPlaying)
        }

    // ========================
    // updatePlaybackSpeed() Tests
    // ========================

    @Test
    fun `updatePlaybackSpeed updates state correctly`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            controller.updatePlaybackSpeed(1.5f)

            assertEquals(1.5f, controller.currentState.playbackSpeed, 0.01f)
        }

    @Test
    fun `updatePlaybackSpeed persists to preferences`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            controller.updatePlaybackSpeed(2.0f)
            advanceUntilIdle()

            coVerify {
                prefsRepo.playbackSpeed = 2.0f
            }
        }

    @Test
    fun `updatePlaybackSpeed ignores update when no media`() =
        runTest {
            controller.updatePlaybackSpeed(1.5f)

            assertEquals(1.0f, controller.currentState.playbackSpeed, 0.01f)
        }

    // ========================
    // clear() Tests
    // ========================

    @Test
    fun `clear resets state to EMPTY`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.updatePosition(1, 15_000L)

            controller.clear()

            assertEquals(PlaybackState.EMPTY, controller.currentState)
            assertFalse(controller.currentState.hasMedia)
        }

    @Test
    fun `clear persists current position before clearing`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.updatePosition(1, 25_000L)
            advanceUntilIdle()

            controller.clear()
            advanceUntilIdle()

            // Should have persistence from loadAudiobook and clear
            coVerify(atLeast = 2) {
                bookRepository.updateProgress(any(), any(), any())
            }
        }

    // ========================
    // Chapter Change Listener Tests
    // ========================

    @Test
    fun `addChapterChangeListener notifies on chapter change`() =
        runTest {
            var notificationCount = 0
            var notifiedPrevious: Chapter? = null
            var notifiedNew: Chapter? = null
            var notifiedIndex = -1

            val listener =
                OnChapterChangeListener { prev, new, idx ->
                    notificationCount++
                    notifiedPrevious = prev
                    notifiedNew = new
                    notifiedIndex = idx
                }

            controller.addChapterChangeListener(listener)
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            // Move from chapter 1 to chapter 2
            controller.updatePosition(1, 30_000L) // 90s - start of chapter 2

            assertTrue(notificationCount > 0)
            assertEquals(testChapters[1], notifiedNew)
            assertEquals(1, notifiedIndex)
        }

    @Test
    fun `removeChapterChangeListener stops notifications`() =
        runTest {
            var notificationCount = 0

            val listener =
                OnChapterChangeListener { _, _, _ ->
                    notificationCount++
                }

            controller.addChapterChangeListener(listener)
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            controller.removeChapterChangeListener(listener)

            // This should not trigger notification
            controller.updatePosition(1, 30_000L)

            // Only notification from loadAudiobook, not from updatePosition
            assertEquals(1, notificationCount)
        }

    @Test
    fun `chapter change listener not notified when chapter unchanged`() =
        runTest {
            var notificationCount = 0

            val listener =
                OnChapterChangeListener { _, _, _ ->
                    notificationCount++
                }

            controller.addChapterChangeListener(listener)
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            notificationCount = 0 // Reset after initial load

            // Move within same chapter
            controller.updatePosition(0, 15_000L)
            controller.updatePosition(0, 30_000L)

            assertEquals(0, notificationCount)
        }

    @Test
    fun `multiple listeners all receive notifications`() =
        runTest {
            val count1 = AtomicInteger(0)
            val count2 = AtomicInteger(0)

            val listener1 = OnChapterChangeListener { _, _, _ -> count1.incrementAndGet() }
            val listener2 = OnChapterChangeListener { _, _, _ -> count2.incrementAndGet() }

            controller.addChapterChangeListener(listener1)
            controller.addChapterChangeListener(listener2)

            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            assertEquals(1, count1.get())
            assertEquals(1, count2.get())
        }

    // ========================
    // Read-Only Accessor Tests
    // ========================

    @Test
    fun `getBookPositionMs returns correct value`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.updatePosition(1, 30_000L) // 60s + 30s = 90s

            assertEquals(90_000L, controller.getBookPositionMs())
        }

    @Test
    fun `getCurrentTrackPosition returns correct pair`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.updatePosition(2, 15_000L)

            val (trackIndex, positionMs) = controller.getCurrentTrackPosition()
            assertEquals(2, trackIndex)
            assertEquals(15_000L, positionMs)
        }

    @Test
    fun `getCurrentChapter returns correct chapter`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.updatePosition(1, 40_000L) // 100s - in chapter 2

            assertEquals(testChapters[1], controller.getCurrentChapter())
        }

    @Test
    fun `getCurrentChapterIndex returns correct index`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.updatePosition(2, 50_000L) // 170s - in chapter 3

            assertEquals(2, controller.getCurrentChapterIndex())
        }

    // ========================
    // Utility Method Tests
    // ========================

    @Test
    fun `updateState allows atomic state transformations`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            controller.updateState { state ->
                state.withPosition(1, 20_000L).withPlayingState(true)
            }

            val state = controller.currentState
            assertEquals(1, state.currentTrackIndex)
            assertEquals(20_000L, state.currentTrackPositionMs)
            assertTrue(state.isPlaying)
        }

    @Test
    fun `withState allows safe state reading`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.updatePosition(1, 10_000L)

            val bookPosition = controller.withState { it.bookPositionMs }

            assertEquals(70_000L, bookPosition) // 60s + 10s
        }

    // ========================
    // Thread Safety Tests
    // ========================

    @Test
    fun `concurrent position updates don't corrupt state`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)

            // Simulate concurrent updates
            controller.updatePosition(0, 10_000L)
            controller.updatePosition(0, 20_000L)
            controller.updatePosition(0, 30_000L)

            // State should reflect last update
            assertEquals(30_000L, controller.currentState.currentTrackPositionMs)
        }

    // ========================
    // Edge Cases
    // ========================

    @Test
    fun `loading new audiobook clears previous state`() =
        runTest {
            val firstBook = testAudiobook.copy(id = "plex:1")
            val secondBook = testAudiobook.copy(id = "plex:2")

            controller.loadAudiobook(firstBook, testTracks, testChapters)
            controller.updatePosition(2, 30_000L)

            controller.loadAudiobook(secondBook, testTracks, testChapters)

            val state = controller.currentState
            assertEquals("plex:2", state.audiobook?.id)
            assertEquals(0, state.currentTrackIndex) // Reset to start
            assertEquals(0L, state.currentTrackPositionMs)
        }

    @Test
    fun `operations on cleared controller are safe`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            controller.clear()

            // These should not throw exceptions
            controller.updatePosition(0, 1000L)
            controller.updatePlayingState(true)
            controller.updatePlaybackSpeed(1.5f)

            assertEquals(PlaybackState.EMPTY, controller.currentState)
        }

    @Test
    fun `database persistence uses correct book position not track position`() =
        runTest {
            controller.loadAudiobook(testAudiobook, testTracks, testChapters)
            advanceUntilIdle()

            // Move to track 2, position 15s
            controller.updatePosition(1, 15_000L)
            advanceTimeBy(PlaybackStateController.DB_WRITE_DEBOUNCE_MS + 100)

            val progressList = mutableListOf<Long>()
            coVerify(atLeast = 2) {
                bookRepository.updateProgress(
                    testAudiobook.id,
                    any(),
                    capture(progressList),
                )
            }

            // Book position should be 60s (track 1) + 15s = 75s
            // The last captured call should have the correct book position
            assertEquals(75_000L, progressList.last())
        }
}
