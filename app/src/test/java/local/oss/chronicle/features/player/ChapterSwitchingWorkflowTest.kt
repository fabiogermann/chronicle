package local.oss.chronicle.features.player

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
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlayingSingleton
import local.oss.chronicle.features.currentlyplaying.OnChapterChangeListener
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Comprehensive test for chapter switching workflow, verifying that:
 * 1. Chapters are correctly identified during playback
 * 2. Chapter boundaries are handled correctly
 * 3. Direct chapter selection updates chapter immediately (not stale database progress)
 * 4. Chapter-relative positions are calculated correctly
 * 5. Metadata reflects chapter-specific information
 *
 * **UPDATED FOR PR 2.2**: Tests now use PlaybackStateController directly instead of
 * the deprecated CurrentlyPlayingSingleton.update() method.
 */
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class ChapterSwitchingWorkflowTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var chapters: List<Chapter>
    private lateinit var track: MediaItemTrack
    private lateinit var tracks: List<MediaItemTrack>
    private lateinit var book: Audiobook
    private lateinit var playbackStateController: PlaybackStateController
    private lateinit var currentlyPlaying: CurrentlyPlayingSingleton

    private var chapterChangeCount = 0
    private var lastChapterChange: Chapter? = null

    @Before
    fun setup() {
        // Set the main dispatcher for tests
        Dispatchers.setMain(testDispatcher)

        // Create 10 chapters, each 10 minutes (600,000ms) long
        // Using overlapping boundaries: endTimeOffset equals startTimeOffset of next chapter
        // This matches real Plex server data format
        chapters =
            (1..10).map { index ->
                Chapter(
                    title = "Chapter $index",
                    id = index.toLong(),
                    index = index.toLong(),
                    discNumber = 1,
                    startTimeOffset = (index - 1) * 600_000L,
                    endTimeOffset = index * 600_000L,
                    trackId = 1L,
                    bookId = 100L,
                )
            }

        // Create a single track containing all chapters (60 minutes total)
        track =
            MediaItemTrack(
                id = 1,
                title = "Test Track",
                // 60 minutes
                duration = 6_000_000L,
                progress = 0L,
                index = 1,
            )

        tracks = listOf(track)

        book =
            Audiobook(
                id = 100,
                source = 1L,
                title = "Test Audiobook",
                chapters = chapters,
            )

        // Setup mock dependencies and controller
        val mockBookRepository = mockk<BookRepository>(relaxed = true)
        val mockPrefsRepo = mockk<PrefsRepo>(relaxed = true)

        coEvery { mockBookRepository.updateProgress(any(), any(), any()) } just Runs
        every { mockPrefsRepo.playbackSpeed = any() } just Runs

        playbackStateController = PlaybackStateController(mockBookRepository, mockPrefsRepo)
        currentlyPlaying = CurrentlyPlayingSingleton(playbackStateController)
        chapterChangeCount = 0
        lastChapterChange = null

        currentlyPlaying.setOnChapterChangeListener(
            object : OnChapterChangeListener {
                override fun onChapterChange(chapter: Chapter) {
                    chapterChangeCount++
                    lastChapterChange = chapter
                }
            },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `when playback starts at beginning, chapter 1 is active`() =
        runTest {
            // Initialize playback at position 0 via controller
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            advanceUntilIdle()

            // Validate current chapter is Chapter 1
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(1L))
            assertThat(currentlyPlaying.chapter.value.index, `is`(1L))

            // Validate chapter offsets
            assertThat(currentlyPlaying.chapter.value.startTimeOffset, `is`(0L))
            assertThat(currentlyPlaying.chapter.value.endTimeOffset, `is`(600_000L))

            // Validate chapter-relative position is 0
            val chapterRelativePosition = 0L - currentlyPlaying.chapter.value.startTimeOffset
            assertThat(chapterRelativePosition, `is`(0L))

            // Validate chapter duration (600,000ms = 10 minutes)
            val chapterDuration = currentlyPlaying.chapter.value.endTimeOffset - currentlyPlaying.chapter.value.startTimeOffset
            assertThat(chapterDuration, `is`(600_000L))
        }

    @Test
    fun `when progress is within chapter 1, chapter 1 remains active`() =
        runTest {
            // Simulate progress to 5 minutes (300,000ms) into Chapter 1
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 300_000L,
            )

            advanceUntilIdle()

            // Validate current chapter is still Chapter 1
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(1L))

            // Validate chapter-relative position is 300,000ms (5 minutes into chapter)
            val chapterRelativePosition = 300_000L - currentlyPlaying.chapter.value.startTimeOffset
            assertThat(chapterRelativePosition, `is`(300_000L))

            // Validate progress bar shows 50% (300,000/600,000)
            val chapterDuration = currentlyPlaying.chapter.value.endTimeOffset - currentlyPlaying.chapter.value.startTimeOffset
            val progressPercentage = (chapterRelativePosition.toDouble() / chapterDuration.toDouble()) * 100
            assertThat(progressPercentage.toInt(), `is`(50))
        }

    @Test
    fun `when crossing chapter boundary, chapter updates to chapter 2`() =
        runTest {
            // Simulate progress to 10 minutes 50 seconds (650,000ms)
            // This crosses from Chapter 1 (0-600,000) to Chapter 2 (600,000-1,200,000)
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 650_000L,
            )

            advanceUntilIdle()

            // Validate current chapter changes to Chapter 2
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(2L))

            // Validate chapter-relative position is 50,000ms (650,000 - 600,000)
            val chapterRelativePosition = 650_000L - currentlyPlaying.chapter.value.startTimeOffset
            assertThat(chapterRelativePosition, `is`(50_000L))

            // Validate metadata shows Chapter 2
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
        }

    @Test
    fun `when user selects chapter 3, chapter and metadata immediately update`() =
        runTest {
            // Start at position 650,000 (Chapter 2)
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 650_000L,
            )

            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))

            // User clicks "Chapter 3" - seek to position 1,200,000
            playbackStateController.updatePosition(0, 1_200_000L)
            advanceUntilIdle()

            // Validate current chapter IMMEDIATELY becomes Chapter 3 (NOT Chapter 2 or earlier)
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 3"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(3L))

            // Validate chapter-relative position is 0 (start of Chapter 3)
            val chapterRelativePosition = 1_200_000L - currentlyPlaying.chapter.value.startTimeOffset
            assertThat(chapterRelativePosition, `is`(0L))

            // Validate metadata shows Chapter 3
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 3"))

            // Validate all progress markers reflect Chapter 3
            assertThat(currentlyPlaying.chapter.value.startTimeOffset, `is`(1_200_000L))
            assertThat(currentlyPlaying.chapter.value.endTimeOffset, `is`(1_800_000L))

            // Validate chapter change listener was triggered
            assertThat(lastChapterChange?.title, `is`("Chapter 3"))
        }

    @Test
    fun `when user selects chapter 1 from chapter 3, chapter updates correctly`() =
        runTest {
            // Start at Chapter 3
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 1_200_000L,
            )

            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 3"))

            // User clicks "Chapter 1" - seek to position 0
            playbackStateController.updatePosition(0, 0L)
            advanceUntilIdle()

            // Validate current chapter is Chapter 1
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(1L))

            // Validate metadata shows Chapter 1
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        }

    @Test
    fun `chapter relative position is calculated correctly after chapter switch`() =
        runTest {
            // Switch to Chapter 5 and verify relative position at various points

            // Seek to middle of Chapter 5 (2,700,000 = 45 minutes)
            // Chapter 5 spans 2,400,000 - 3,000,000
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 2_700_000L,
            )

            advanceUntilIdle()

            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 5"))

            // Chapter-relative position should be 300,000ms (5 minutes into Chapter 5)
            val chapterRelativePosition = 2_700_000L - currentlyPlaying.chapter.value.startTimeOffset
            assertThat(chapterRelativePosition, `is`(300_000L))

            // Verify it's exactly halfway through the chapter
            val chapterDuration = currentlyPlaying.chapter.value.endTimeOffset - currentlyPlaying.chapter.value.startTimeOffset
            assertThat(chapterDuration, `is`(600_000L))

            val percentageIntoChapter = (chapterRelativePosition.toDouble() / chapterDuration.toDouble()) * 100
            assertThat(percentageIntoChapter.toInt(), `is`(50))
        }

    @Test
    fun `metadata duration reflects chapter duration not track duration`() =
        runTest {
            // The chapter duration should be 600,000ms (10 minutes)
            // The track duration is 6,000,000ms (60 minutes)

            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 1_200_000L,
            )

            advanceUntilIdle()

            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 3"))

            // Validate that chapter duration is 600,000ms, NOT 6,000,000ms
            val chapterDuration = currentlyPlaying.chapter.value.endTimeOffset - currentlyPlaying.chapter.value.startTimeOffset
            assertThat(chapterDuration, `is`(600_000L))

            // Verify it's NOT using track duration
            assertThat(chapterDuration, `is`(track.duration / 10))
        }

    @Test
    fun `when seeking within same chapter, chapter does not change`() =
        runTest {
            // Start in Chapter 4
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 1_800_000L,
            )

            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 4"))
            val initialChangeCount = chapterChangeCount

            // Seek within Chapter 4 (to 2,100,000 which is still in Chapter 4)
            playbackStateController.updatePosition(0, 2_100_000L)
            advanceUntilIdle()

            // Chapter should still be Chapter 4
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 4"))

            // Chapter change listener should NOT be triggered
            assertThat(chapterChangeCount, `is`(initialChangeCount))
        }

    @Test
    fun `when seeking to exact chapter boundary, correct chapter is selected`() =
        runTest {
            // Seek to exactly 600,000ms (start of Chapter 2)
            // With half-open interval [start, end), position at end of Chapter 1 returns Chapter 2
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 600_000L,
            )

            advanceUntilIdle()

            // Should be in Chapter 2
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(2L))
        }

    @Test
    fun `when seeking through multiple chapters rapidly, chapter updates correctly`() =
        runTest {
            // Start at Chapter 1
            playbackStateController.loadAudiobook(
                audiobook = book,
                tracks = tracks,
                chapters = chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))

            // Jump to Chapter 5
            playbackStateController.updatePosition(0, 2_400_000L)
            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 5"))

            // Jump to Chapter 8
            playbackStateController.updatePosition(0, 4_200_000L)
            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 8"))

            // Jump back to Chapter 2
            playbackStateController.updatePosition(0, 600_000L)
            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))

            // Jump to Chapter 10 (last chapter)
            playbackStateController.updatePosition(0, 5_400_000L)
            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 10"))
        }

    @Test
    fun `getChapterAt function works correctly with test data`() {
        // Verify the underlying getChapterAt function works as expected

        // Test Chapter 1 (0-599,999)
        val chapter1 = chapters.getChapterAt(trackId = 1L, timeStamp = 0L)
        assertThat(chapter1.title, `is`("Chapter 1"))

        val chapter1Mid = chapters.getChapterAt(trackId = 1L, timeStamp = 300_000L)
        assertThat(chapter1Mid.title, `is`("Chapter 1"))

        val chapter1End = chapters.getChapterAt(trackId = 1L, timeStamp = 599_999L)
        assertThat(chapter1End.title, `is`("Chapter 1"))

        // Test Chapter 2 boundary (600,000-1,200,000)
        val chapter2 = chapters.getChapterAt(trackId = 1L, timeStamp = 600_000L)
        assertThat(chapter2.title, `is`("Chapter 2"))

        // Test Chapter 5 (2,400,000-3,000,000)
        val chapter5 = chapters.getChapterAt(trackId = 1L, timeStamp = 2_700_000L)
        assertThat(chapter5.title, `is`("Chapter 5"))

        // Test Chapter 10 (5,400,000-6,000,000)
        val chapter10 = chapters.getChapterAt(trackId = 1L, timeStamp = 5_700_000L)
        assertThat(chapter10.title, `is`("Chapter 10"))
    }

    @Test
    fun `reproduces bug from playback log - stale database progress vs live playback position`() =
        runTest {
            // This test reproduces the exact bug from logs/playback.log
            // Now uses controller API to verify the fix works correctly

            // Create realistic chapter structure from the log
            val realChapters =
                listOf(
                    Chapter(
                        title = "Opening Credits",
                        id = 483L,
                        index = 1L,
                        startTimeOffset = 0L,
                        endTimeOffset = 16573L,
                        trackId = 65L,
                        bookId = 100L,
                    ),
                    Chapter(
                        title = "The Four Houses of Midgard",
                        id = 484L,
                        index = 2L,
                        startTimeOffset = 16573L,
                        endTimeOffset = 88282L,
                        trackId = 65L,
                        bookId = 100L,
                    ),
                    Chapter(
                        title = "Prologue",
                        id = 485L,
                        index = 3L,
                        startTimeOffset = 88282L,
                        endTimeOffset = 3010880L,
                        trackId = 65L,
                        bookId = 100L,
                    ),
                    Chapter(
                        title = "Part I: The Chasm",
                        id = 486L,
                        index = 4L,
                        startTimeOffset = 3010880L,
                        endTimeOffset = 3016290L,
                        trackId = 65L,
                        bookId = 100L,
                    ),
                    Chapter(
                        title = "1",
                        id = 487L,
                        index = 5L,
                        startTimeOffset = 3016290L,
                        endTimeOffset = 5197230L,
                        trackId = 65L,
                        bookId = 100L,
                    ),
                )

            val realTrack =
                MediaItemTrack(
                    id = 65,
                    title = "House of Sky and Breath, Book 2",
                    duration = 99738110L,
                    progress = 0L,
                    index = 1,
                )

            val realBook =
                Audiobook(
                    id = 100,
                    source = 1L,
                    title = "Test Audiobook",
                    chapters = realChapters,
                )

            // Load with live playback position at 5,109,125ms (should be in Chapter "1")
            playbackStateController.loadAudiobook(
                audiobook = realBook,
                tracks = listOf(realTrack),
                chapters = realChapters,
                startTrackIndex = 0,
                startPositionMs = 5109125L,
            )

            advanceUntilIdle()

            // Chapter should be "1" using controller's authoritative position
            assertThat(currentlyPlaying.chapter.value.title, `is`("1"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(487L))

            // Verify it's using controller's position
            assertThat(currentlyPlaying.track.value.progress, `is`(5109125L))
        }
}
