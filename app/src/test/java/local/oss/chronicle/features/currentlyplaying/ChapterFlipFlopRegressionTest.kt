package local.oss.chronicle.features.currentlyplaying

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.local.BookRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.*
import local.oss.chronicle.features.player.PlaybackStateController
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression test for Critical Issue C1: Chapter detection uses stale DB progress.
 *
 * **The Bug:**
 * The bug occurred when multiple update sources provided conflicting progress values:
 * - ProgressUpdater: uses current player position (e.g., 16573)
 * - OnMediaChangedCallback: used to read stale DB value (e.g., 0)
 *
 * This caused rapid chapter switching at chapter boundaries (flip-flopping).
 *
 * **The Fix (PR 2.2):**
 * CurrentlyPlayingSingleton now derives all state from PlaybackStateController,
 * which uses ExoPlayer's position as the single source of truth.
 * The deprecated update() method is now a no-op.
 *
 * **These tests verify:**
 * 1. Chapter is derived from controller position, not stale DB data
 * 2. Deprecated update() cannot cause flip-flopping
 * 3. Only controller position updates affect chapter detection
 */
@ExperimentalCoroutinesApi
class ChapterFlipFlopRegressionTest {
    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var playbackStateController: PlaybackStateController
    private lateinit var currentlyPlaying: CurrentlyPlayingSingleton
    private var chapterChangeCount = 0
    private var lastChapterChange: Chapter? = null
    private val chapterChanges = mutableListOf<Chapter>()

    // Recreate the exact scenario from the bug:
    // - Chapter 1 "Opening Credits": [0 - 16573]
    // - Chapter 2 "The Four Houses of Midgard": [16573 - 88282]
    private val testBook = Audiobook(
        id = 1,
        source = 1L,
        title = "Test Audiobook",
        chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 1L,
                startTimeOffset = 0L,
                endTimeOffset = 16573L,
                trackId = 1L,
                bookId = 1L,
            ),
            Chapter(
                title = "The Four Houses of Midgard",
                id = 2L,
                index = 2L,
                startTimeOffset = 16573L,
                endTimeOffset = 88282L,
                trackId = 1L,
                bookId = 1L,
            ),
        ),
    )

    private val testTracks = listOf(
        MediaItemTrack(
            id = 1,
            title = "Test Track 1",
            duration = 88282L,
            progress = 0L,
        ),
    )

    @Before
    fun setup() {
        // Set the main dispatcher for tests
        Dispatchers.setMain(testDispatcher)
        
        // Mock dependencies using MockK
        val mockBookRepository = mockk<BookRepository>(relaxed = true)
        val mockPrefsRepo = mockk<PrefsRepo>(relaxed = true)
        
        // Setup mock behaviors
        coEvery { mockBookRepository.updateProgress(any(), any(), any()) } just Runs
        every { mockPrefsRepo.playbackSpeed = any() } just Runs
        
        playbackStateController = PlaybackStateController(mockBookRepository, mockPrefsRepo)
        currentlyPlaying = CurrentlyPlayingSingleton(playbackStateController)
        
        chapterChangeCount = 0
        lastChapterChange = null
        chapterChanges.clear()
        
        currentlyPlaying.setOnChapterChangeListener(object : OnChapterChangeListener {
            override fun onChapterChange(chapter: Chapter) {
                chapterChangeCount++
                lastChapterChange = chapter
                chapterChanges.add(chapter)
            }
        })
    }
    
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FIXED - deprecated update with stale progress cannot cause flip-flop`() = runTest {
        // Given: Controller has track at position 16573 (chapter 2)
        playbackStateController.loadAudiobook(
            audiobook = testBook,
            tracks = testTracks,
            chapters = testBook.chapters,
            startTrackIndex = 0,
            startPositionMs = 16573L
        )
        
        advanceUntilIdle()
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        val chapterCountAfterLoad = chapterChangeCount

        // When: deprecated update() is called with stale DB progress=0
        // This used to cause flip-flop, but now it's a no-op
        val trackWithStaleProgress = testTracks[0].copy(progress = 0L)
        currentlyPlaying.update(
            track = trackWithStaleProgress,
            book = testBook,
            tracks = testTracks,
        )

        advanceUntilIdle()

        // Then: Chapter remains at chapter 2 (ignores stale DB data)
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.track.value.progress, `is`(16573L))
        
        // No chapter change should have occurred (deprecated update is no-op)
        assertThat(chapterChangeCount, `is`(chapterCountAfterLoad))
    }

    @Test
    fun `FIXED - rapid alternating progress values cannot cause flip-flop`() = runTest {
        // Given: Controller at chapter boundary (16573)
        playbackStateController.loadAudiobook(
            audiobook = testBook,
            tracks = testTracks,
            chapters = testBook.chapters,
            startTrackIndex = 0,
            startPositionMs = 16573L
        )
        
        advanceUntilIdle()
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        val initialChapterChangeCount = chapterChangeCount

        // When: Multiple rapid deprecated update() calls with alternating values
        // This used to cause flip-flopping, but now all are no-ops
        currentlyPlaying.update(
            track = testTracks[0].copy(progress = 16573L),
            book = testBook,
            tracks = testTracks,
        )
        
        currentlyPlaying.update(
            track = testTracks[0].copy(progress = 0L),
            book = testBook,
            tracks = testTracks,
        )
        
        currentlyPlaying.update(
            track = testTracks[0].copy(progress = 16573L),
            book = testBook,
            tracks = testTracks,
        )
        
        currentlyPlaying.update(
            track = testTracks[0].copy(progress = 0L),
            book = testBook,
            tracks = testTracks,
        )

        advanceUntilIdle()

        // Then: Chapter remains stable at chapter 2
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.chapter.value.index, `is`(2L))
        
        // No additional chapter changes (all deprecated updates ignored)
        assertThat(chapterChangeCount, `is`(initialChapterChangeCount))
    }

    @Test
    fun `chapter derived from controller position not from DB`() = runTest {
        // This is the core fix for Critical Issue C1
        
        // Given: Controller at position in chapter 2
        playbackStateController.loadAudiobook(
            audiobook = testBook,
            tracks = testTracks,
            chapters = testBook.chapters,
            startTrackIndex = 0,
            startPositionMs = 50000L // Middle of chapter 2
        )
        
        advanceUntilIdle()
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))

        // When: Database returns stale progress=0 via deprecated update()
        val trackWithStaleDbProgress = testTracks[0].copy(progress = 0L)
        currentlyPlaying.update(
            track = trackWithStaleDbProgress,
            book = testBook,
            tracks = testTracks,
        )

        advanceUntilIdle()

        // Then: Chapter comes from controller (chapter 2), NOT from stale DB (chapter 1)
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.track.value.progress, `is`(50000L)) // Controller position, not DB
    }

    @Test
    fun `only controller updates affect chapter detection`() = runTest {
        // Given: Controller at chapter 1
        playbackStateController.loadAudiobook(
            audiobook = testBook,
            tracks = testTracks,
            chapters = testBook.chapters,
            startTrackIndex = 0,
            startPositionMs = 5000L
        )
        
        advanceUntilIdle()
        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
        val initialChapterCount = chapterChangeCount

        // When: Deprecated update() tries to change chapter
        currentlyPlaying.update(
            track = testTracks[0].copy(progress = 50000L), // Would be chapter 2
            book = testBook,
            tracks = testTracks,
        )

        advanceUntilIdle()

        // Then: Chapter unchanged (deprecated update ignored)
        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
        assertThat(chapterChangeCount, `is`(initialChapterCount))

        // When: Controller updates position to chapter 2
        playbackStateController.updatePosition(0, 50000L)

        advanceUntilIdle()

        // Then: Chapter changes (controller update accepted)
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(chapterChangeCount, `is`(initialChapterCount + 1))
    }

    @Test
    fun `exact chapter boundary handled correctly by controller`() = runTest {
        // Given: Controller at exact chapter boundary
        playbackStateController.loadAudiobook(
            audiobook = testBook,
            tracks = testTracks,
            chapters = testBook.chapters,
            startTrackIndex = 0,
            startPositionMs = 16573L
        )
        
        advanceUntilIdle()
        
        // Then: Should be in chapter 2, not chapter 1
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.chapter.value.index, `is`(2L))
    }

    @Test
    fun `chapter changes are stable across rapid controller updates`() = runTest {
        // Given: Start in chapter 1
        playbackStateController.loadAudiobook(
            audiobook = testBook,
            tracks = testTracks,
            chapters = testBook.chapters,
            startTrackIndex = 0,
            startPositionMs = 5000L
        )
        
        advanceUntilIdle()
        chapterChanges.clear() // Reset tracking
        chapterChangeCount = 0

        // When: Rapid position updates in chapter 1
        for (position in listOf(6000L, 7000L, 8000L, 9000L, 10000L)) {
            playbackStateController.updatePosition(0, position)
            advanceUntilIdle()
        }

        advanceUntilIdle()

        // Then: No chapter changes (all positions in chapter 1)
        assertThat(chapterChangeCount, `is`(0))
        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))

        // When: Update to chapter 2  
        playbackStateController.updatePosition(0, 20000L)
        advanceUntilIdle()

        // Then: Exactly one chapter change
        assertThat(chapterChangeCount, `is`(1))
        assertThat(chapterChanges.size, `is`(1))
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
    }

    @Test
    fun `no spurious chapter changes at boundaries`() = runTest {
        // Given: Start just before boundary
        playbackStateController.loadAudiobook(
            audiobook = testBook,
            tracks = testTracks,
            chapters = testBook.chapters,
            startTrackIndex = 0,
            startPositionMs = 16572L
        )
        
        advanceUntilIdle()
        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
        chapterChanges.clear()
        chapterChangeCount = 0

        // When: Cross boundary once
        playbackStateController.updatePosition(0, 16573L)
        advanceUntilIdle()

        // Then: Exactly one chapter change
        assertThat(chapterChangeCount, `is`(1))
        assertThat(chapterChanges.size, `is`(1))
        
        // When: Continue in chapter 2
        playbackStateController.updatePosition(0, 16574L)
        advanceUntilIdle()
        playbackStateController.updatePosition(0, 16575L)
        advanceUntilIdle()

        // Then: No additional chapter changes
        assertThat(chapterChangeCount, `is`(1))
        assertThat(chapterChanges.size, `is`(1))
    }

    @Test
    fun `controller position is authoritative over deprecated update calls`() = runTest {
        // Given: Controller at position A
        playbackStateController.loadAudiobook(
            audiobook = testBook,
            tracks = testTracks,
            chapters = testBook.chapters,
            startTrackIndex = 0,
            startPositionMs = 30000L
        )
        
        advanceUntilIdle()
        val controllerPosition = currentlyPlaying.track.value.progress
        assertThat(controllerPosition, `is`(30000L))

        // When: Multiple deprecated update() calls with different positions
        currentlyPlaying.update(testTracks[0].copy(progress = 0L), testBook, testTracks)
        advanceUntilIdle()
        currentlyPlaying.update(testTracks[0].copy(progress = 5000L), testBook, testTracks)
        advanceUntilIdle()
        currentlyPlaying.update(testTracks[0].copy(progress = 10000L), testBook, testTracks)
        advanceUntilIdle()

        // Then: Position remains at controller's value
        assertThat(currentlyPlaying.track.value.progress, `is`(30000L))
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
    }
}
