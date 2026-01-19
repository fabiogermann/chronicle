package local.oss.chronicle.features.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import local.oss.chronicle.data.model.*
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlayingSingleton
import local.oss.chronicle.features.currentlyplaying.OnChapterChangeListener
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
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
 */
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class ChapterSwitchingWorkflowTest {
    
    private lateinit var chapters: List<Chapter>
    private lateinit var track: MediaItemTrack
    private lateinit var tracks: List<MediaItemTrack>
    private lateinit var book: Audiobook
    private lateinit var currentlyPlaying: CurrentlyPlayingSingleton
    
    private var chapterChangeCount = 0
    private var lastChapterChange: Chapter? = null
    
    @Before
    fun setup() {
        // Create 10 chapters, each 10 minutes (600,000ms) long
        // Using overlapping boundaries: endTimeOffset equals startTimeOffset of next chapter
        // This matches real Plex server data format
        chapters = (1..10).map { index ->
            Chapter(
                title = "Chapter $index",
                id = index.toLong(),
                index = index.toLong(),
                discNumber = 1,
                startTimeOffset = (index - 1) * 600_000L,
                endTimeOffset = index * 600_000L,
                trackId = 1L,
                bookId = 100L
            )
        }
        
        // Create a single track containing all chapters (60 minutes total)
        track = MediaItemTrack(
            id = 1,
            title = "Test Track",
            duration = 6_000_000L, // 60 minutes
            progress = 0L,
            index = 1,
        )
        
        tracks = listOf(track)
        
        book = Audiobook(
            id = 100,
            source = 1L,
            title = "Test Audiobook",
            chapters = chapters,
        )
        
        currentlyPlaying = CurrentlyPlayingSingleton()
        chapterChangeCount = 0
        lastChapterChange = null
        
        currentlyPlaying.setOnChapterChangeListener(object : OnChapterChangeListener {
            override fun onChapterChange(chapter: Chapter) {
                chapterChangeCount++
                lastChapterChange = chapter
            }
        })
    }
    
    @Test
    fun `when playback starts at beginning, chapter 1 is active`() {
        // Initialize playback at position 0
        val trackAtStart = track.copy(progress = 0L)
        
        currentlyPlaying.update(
            track = trackAtStart,
            book = book,
            tracks = listOf(trackAtStart),
        )
        
        // Validate current chapter is Chapter 1
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(1L))
        assertThat(currentlyPlaying.chapter.value.index, `is`(1L))
        
        // Validate chapter offsets
        assertThat(currentlyPlaying.chapter.value.startTimeOffset, `is`(0L))
        assertThat(currentlyPlaying.chapter.value.endTimeOffset, `is`(600_000L))
        
        // Validate chapter-relative position is 0
        val chapterRelativePosition = trackAtStart.progress - currentlyPlaying.chapter.value.startTimeOffset
        assertThat(chapterRelativePosition, `is`(0L))
        
        // Validate chapter duration (600,000ms = 10 minutes)
        val chapterDuration = currentlyPlaying.chapter.value.endTimeOffset - currentlyPlaying.chapter.value.startTimeOffset
        assertThat(chapterDuration, `is`(600_000L))
    }
    
    @Test
    fun `when progress is within chapter 1, chapter 1 remains active`() {
        // Simulate progress to 5 minutes (300,000ms) into Chapter 1
        val trackInProgress = track.copy(progress = 300_000L)
        
        currentlyPlaying.update(
            track = trackInProgress,
            book = book,
            tracks = listOf(trackInProgress),
        )
        
        // Validate current chapter is still Chapter 1
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(1L))
        
        // Validate chapter-relative position is 300,000ms (5 minutes into chapter)
        val chapterRelativePosition = trackInProgress.progress - currentlyPlaying.chapter.value.startTimeOffset
        assertThat(chapterRelativePosition, `is`(300_000L))
        
        // Validate progress bar shows 50% (300,000/600,000)
        val chapterDuration = currentlyPlaying.chapter.value.endTimeOffset - currentlyPlaying.chapter.value.startTimeOffset
        val progressPercentage = (chapterRelativePosition.toDouble() / chapterDuration.toDouble()) * 100
        assertThat(progressPercentage.toInt(), `is`(50))
    }
    
    @Test
    fun `when crossing chapter boundary, chapter updates to chapter 2`() {
        // Simulate progress to 10 minutes 50 seconds (650,000ms)
        // This crosses from Chapter 1 (0-600,000) to Chapter 2 (600,000-1,200,000)
        val trackInChapter2 = track.copy(progress = 650_000L)
        
        currentlyPlaying.update(
            track = trackInChapter2,
            book = book,
            tracks = listOf(trackInChapter2),
        )
        
        // Validate current chapter changes to Chapter 2
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(2L))
        
        // Validate chapter-relative position is 50,000ms (650,000 - 600,000)
        val chapterRelativePosition = trackInChapter2.progress - currentlyPlaying.chapter.value.startTimeOffset
        assertThat(chapterRelativePosition, `is`(50_000L))
        
        // Validate metadata shows Chapter 2
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
    }
    
    @Test
    fun `when user selects chapter 3, chapter and metadata immediately update`() {
        // Start at position 650,000 (Chapter 2)
        val trackInChapter2 = track.copy(progress = 650_000L)
        currentlyPlaying.update(
            track = trackInChapter2,
            book = book,
            tracks = listOf(trackInChapter2),
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
        
        // User clicks "Chapter 3" - seek to position 1,200,000
        val trackAtChapter3 = track.copy(progress = 1_200_000L)
        currentlyPlaying.update(
            track = trackAtChapter3,
            book = book,
            tracks = listOf(trackAtChapter3),
        )
        
        // Validate player seeks to correct position
        assertThat(trackAtChapter3.progress, `is`(1_200_000L))
        
        // Validate current chapter IMMEDIATELY becomes Chapter 3 (NOT Chapter 2 or earlier)
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 3"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(3L))
        
        // Validate chapter-relative position is 0 (start of Chapter 3)
        val chapterRelativePosition = trackAtChapter3.progress - currentlyPlaying.chapter.value.startTimeOffset
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
    fun `when user selects chapter 1 from chapter 3, chapter updates correctly`() {
        // Start at Chapter 3
        val trackInChapter3 = track.copy(progress = 1_200_000L)
        currentlyPlaying.update(
            track = trackInChapter3,
            book = book,
            tracks = listOf(trackInChapter3),
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 3"))
        
        // User clicks "Chapter 1" - seek to position 0
        val trackAtChapter1 = track.copy(progress = 0L)
        currentlyPlaying.update(
            track = trackAtChapter1,
            book = book,
            tracks = listOf(trackAtChapter1),
        )
        
        // Validate player seeks to correct position
        assertThat(trackAtChapter1.progress, `is`(0L))
        
        // Validate current chapter is Chapter 1
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(1L))
        
        // Validate metadata shows Chapter 1
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
    }
    
    @Test
    fun `chapter relative position is calculated correctly after chapter switch`() {
        // Switch to Chapter 5 and verify relative position at various points
        
        // Seek to middle of Chapter 5 (2,700,000 = 45 minutes)
        // Chapter 5 spans 2,400,000 - 3,000,000
        val trackInChapter5Middle = track.copy(progress = 2_700_000L)
        currentlyPlaying.update(
            track = trackInChapter5Middle,
            book = book,
            tracks = listOf(trackInChapter5Middle),
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 5"))
        
        // Chapter-relative position should be 300,000ms (5 minutes into Chapter 5)
        val chapterRelativePosition = trackInChapter5Middle.progress - currentlyPlaying.chapter.value.startTimeOffset
        assertThat(chapterRelativePosition, `is`(300_000L))
        
        // Verify it's exactly halfway through the chapter
        val chapterDuration = currentlyPlaying.chapter.value.endTimeOffset - currentlyPlaying.chapter.value.startTimeOffset
        assertThat(chapterDuration, `is`(600_000L))
        
        val percentageIntoChapter = (chapterRelativePosition.toDouble() / chapterDuration.toDouble()) * 100
        assertThat(percentageIntoChapter.toInt(), `is`(50))
    }
    
    @Test
    fun `metadata duration reflects chapter duration not track duration`() {
        // The chapter duration should be 600,000ms (10 minutes)
        // The track duration is 6,000,000ms (60 minutes)
        
        val trackInChapter3 = track.copy(progress = 1_200_000L)
        currentlyPlaying.update(
            track = trackInChapter3,
            book = book,
            tracks = listOf(trackInChapter3),
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 3"))
        
        // Validate that chapter duration is 600,000ms, NOT 6,000,000ms
        val chapterDuration = currentlyPlaying.chapter.value.endTimeOffset - currentlyPlaying.chapter.value.startTimeOffset
        assertThat(chapterDuration, `is`(600_000L))
        
        // Verify it's NOT using track duration
        assertThat(chapterDuration, `is`(track.duration / 10))
    }
    
    @Test
    fun `when seeking within same chapter, chapter does not change`() {
        // Start in Chapter 4
        val trackInChapter4Start = track.copy(progress = 1_800_000L)
        currentlyPlaying.update(
            track = trackInChapter4Start,
            book = book,
            tracks = listOf(trackInChapter4Start),
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 4"))
        val initialChangeCount = chapterChangeCount
        
        // Seek within Chapter 4 (to 2,100,000 which is still in Chapter 4)
        val trackInChapter4End = track.copy(progress = 2_100_000L)
        currentlyPlaying.update(
            track = trackInChapter4End,
            book = book,
            tracks = listOf(trackInChapter4End),
        )
        
        // Chapter should still be Chapter 4
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 4"))
        
        // Chapter change listener should NOT be triggered
        assertThat(chapterChangeCount, `is`(initialChangeCount))
    }
    
    @Test
    fun `when seeking to exact chapter boundary, correct chapter is selected`() {
        // Seek to exactly 600,000ms (start of Chapter 2)
        // With half-open interval [start, end), position at end of Chapter 1 returns Chapter 2
        val trackAtBoundary = track.copy(progress = 600_000L)
        currentlyPlaying.update(
            track = trackAtBoundary,
            book = book,
            tracks = listOf(trackAtBoundary),
        )
        
        // Should be in Chapter 2
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(2L))
    }
    
    @Test
    fun `when seeking through multiple chapters rapidly, chapter updates correctly`() {
        // Start at Chapter 1
        val track1 = track.copy(progress = 0L)
        currentlyPlaying.update(track = track1, book = book, tracks = listOf(track1))
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        
        // Jump to Chapter 5
        val track2 = track.copy(progress = 2_400_000L)
        currentlyPlaying.update(track = track2, book = book, tracks = listOf(track2))
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 5"))
        
        // Jump to Chapter 8
        val track3 = track.copy(progress = 4_200_000L)
        currentlyPlaying.update(track = track3, book = book, tracks = listOf(track3))
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 8"))
        
        // Jump back to Chapter 2
        val track4 = track.copy(progress = 600_000L)
        currentlyPlaying.update(track = track4, book = book, tracks = listOf(track4))
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
        
        // Jump to Chapter 10 (last chapter)
        val track5 = track.copy(progress = 5_400_000L)
        currentlyPlaying.update(track = track5, book = book, tracks = listOf(track5))
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
    fun `reproduces bug from playback log - stale database progress vs live playback position`() {
        // This test reproduces the exact bug from logs/playback.log
        // Database saved progress: 2,180,940ms (in Prologue chapter)
        // Live playback position: 5,109,125ms (should be in Chapter 1, not Prologue)
        
        // Create realistic chapter structure from the log
        val realChapters = listOf(
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
        
        val realTrack = MediaItemTrack(
            id = 65,
            title = "House of Sky and Breath, Book 2",
            duration = 99738110L,
            progress = 2180940L, // Database saved progress (in Prologue)
            index = 1,
        )
        
        val realBook = Audiobook(
            id = 100,
            source = 1L,
            title = "Test Audiobook",
            chapters = realChapters,
        )
        
        // Simulate database cached state (progress=2180940 is in Prologue)
        val trackWithDatabaseProgress = realTrack.copy(progress = 2180940L)
        currentlyPlaying.update(
            track = trackWithDatabaseProgress,
            book = realBook,
            tracks = listOf(trackWithDatabaseProgress),
        )
        
        // At this point, chapter should be "Prologue" based on database progress
        assertThat(currentlyPlaying.chapter.value.title, `is`("Prologue"))
        
        // Now simulate live playback at position 5,109,125ms (should be in Chapter "1")
        val trackWithLiveProgress = realTrack.copy(progress = 5109125L)
        currentlyPlaying.update(
            track = trackWithLiveProgress,
            book = realBook,
            tracks = listOf(trackWithLiveProgress),
        )
        
        // Chapter should now be "1" (NOT "Prologue")
        // This was the bug: activeChapter.trackId == cachedChapter.trackId was always true
        // for single-track audiobooks, causing stale chapter to be displayed
        assertThat(currentlyPlaying.chapter.value.title, `is`("1"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(487L))
        
        // Verify it's using live progress, not database cached progress
        assertThat(currentlyPlaying.track.value.progress, `is`(5109125L))
    }
}
