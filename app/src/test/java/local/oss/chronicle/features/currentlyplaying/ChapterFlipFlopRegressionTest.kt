package local.oss.chronicle.features.currentlyplaying

import kotlinx.coroutines.ExperimentalCoroutinesApi
import local.oss.chronicle.data.model.*
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.Test

/**
 * Regression test for chapter flip-flopping bug.
 *
 * The bug occurred when multiple update sources provided conflicting progress values:
 * - ProgressUpdater: uses current player position (e.g., 16573)
 * - OnMediaChangedCallback: used to read stale DB value (e.g., 0)
 *
 * This caused rapid chapter switching at chapter boundaries.
 *
 * The fix implements defense-in-depth:
 * 1. OnMediaChangedCallback now uses player position instead of stale DB value
 * 2. CurrentlyPlayingSingleton rejects progress updates that drop to near-zero (stale data signature)
 *    but ALLOWS legitimate backward seeks to non-zero positions
 */
@ExperimentalCoroutinesApi
class ChapterFlipFlopRegressionTest {
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
        currentlyPlaying = CurrentlyPlayingSingleton()
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

    @Test
    fun `update should reject stale progress that drops to near-zero`() {
        // Given: CurrentlyPlayingSingleton has track at position 16573 (chapter 2)
        val trackAtChapterBoundary = testTracks[0].copy(progress = 16573L)
        currentlyPlaying.update(
            track = trackAtChapterBoundary,
            book = testBook,
            tracks = testTracks,
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.track.value.progress, `is`(16573L))
        val chapterCountAfterFirstUpdate = chapterChangeCount

        // When: update() is called with same track but progress=0 (stale DB value)
        // This is the signature of the race condition
        val trackWithStaleProgress = testTracks[0].copy(progress = 0L)
        currentlyPlaying.update(
            track = trackWithStaleProgress,
            book = testBook,
            tracks = testTracks,
        )

        // Then: The update should be rejected, chapter should remain at chapter 2
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.track.value.progress, `is`(16573L))
        assertThat(chapterChangeCount, `is`(chapterCountAfterFirstUpdate))
    }

    @Test
    fun `update should accept progress that advances position`() {
        // Given: CurrentlyPlayingSingleton has track at position 16573
        val trackAtChapterBoundary = testTracks[0].copy(progress = 16573L)
        currentlyPlaying.update(
            track = trackAtChapterBoundary,
            book = testBook,
            tracks = testTracks,
        )
        
        assertThat(currentlyPlaying.track.value.progress, `is`(16573L))

        // When: update() is called with progress=17000
        val trackWithAdvancedProgress = testTracks[0].copy(progress = 17000L)
        currentlyPlaying.update(
            track = trackWithAdvancedProgress,
            book = testBook,
            tracks = testTracks,
        )

        // Then: The update should be accepted
        assertThat(currentlyPlaying.track.value.progress, `is`(17000L))
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
    }

    @Test
    fun `update should accept progress for different track even if lower`() {
        // Given: CurrentlyPlayingSingleton has track A at position 16573
        val trackA = testTracks[0].copy(id = 1, progress = 16573L)
        currentlyPlaying.update(
            track = trackA,
            book = testBook,
            tracks = testTracks,
        )
        
        assertThat(currentlyPlaying.track.value.id, `is`(1))
        assertThat(currentlyPlaying.track.value.progress, `is`(16573L))

        // When: update() is called with track B at progress=0
        val trackB = MediaItemTrack(
            id = 2,
            title = "Test Track 2",
            duration = 50000L,
            progress = 0L,
        )
        val tracksWithB = listOf(trackA, trackB)
        currentlyPlaying.update(
            track = trackB,
            book = testBook,
            tracks = tracksWithB,
        )

        // Then: The update should be accepted (different track)
        assertThat(currentlyPlaying.track.value.id, `is`(2))
        assertThat(currentlyPlaying.track.value.progress, `is`(0L))
    }

    @Test
    fun `chapter should not flip-flop when receiving alternating progress values`() {
        // Given: Chapter boundary at 16573
        val trackAtBoundary = testTracks[0].copy(progress = 16573L)
        currentlyPlaying.update(
            track = trackAtBoundary,
            book = testBook,
            tracks = testTracks,
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        val initialChapterChangeCount = chapterChangeCount

        // When: Rapid updates alternate between progress=16573 and progress=0
        // Simulating the race condition scenario
        val trackAtBoundary2 = testTracks[0].copy(progress = 16573L)
        currentlyPlaying.update(
            track = trackAtBoundary2,
            book = testBook,
            tracks = testTracks,
        )
        
        val trackWithStaleProgress1 = testTracks[0].copy(progress = 0L)
        currentlyPlaying.update(
            track = trackWithStaleProgress1,
            book = testBook,
            tracks = testTracks,
        )
        
        val trackAtBoundary3 = testTracks[0].copy(progress = 16573L)
        currentlyPlaying.update(
            track = trackAtBoundary3,
            book = testBook,
            tracks = testTracks,
        )
        
        val trackWithStaleProgress2 = testTracks[0].copy(progress = 0L)
        currentlyPlaying.update(
            track = trackWithStaleProgress2,
            book = testBook,
            tracks = testTracks,
        )

        // Then: Chapter should stabilize at the correct chapter for 16573, not flip-flop
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.chapter.value.index, `is`(2L))
        
        // No additional chapter changes should have occurred (stale updates were rejected)
        assertThat(chapterChangeCount, `is`(initialChapterChangeCount))
    }

    @Test
    fun `update should allow legitimate backward seeks within same track`() {
        // Given: Track at position 50000 in chapter 2
        val trackInMiddle = testTracks[0].copy(progress = 50000L)
        currentlyPlaying.update(
            track = trackInMiddle,
            book = testBook,
            tracks = testTracks,
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.track.value.progress, `is`(50000L))

        // When: User manually seeks back to 20000 (still in chapter 2)
        val trackAfterSeekBack = testTracks[0].copy(progress = 20000L)
        currentlyPlaying.update(
            track = trackAfterSeekBack,
            book = testBook,
            tracks = testTracks,
        )

        // Then: The seek should be allowed (not near-zero, so not stale data)
        assertThat(currentlyPlaying.track.value.progress, `is`(20000L))
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
    }

    @Test
    fun `update should allow seeking to chapter start while playing`() {
        // Given: Track at position 3022434 (mid-chapter)
        val testChapters = listOf(
            Chapter(
                title = "Chapter 1",
                id = 1L,
                index = 1L,
                startTimeOffset = 0L,
                endTimeOffset = 3016290L,
                trackId = 1L,
                bookId = 1L,
            ),
            Chapter(
                title = "Chapter 2",
                id = 2L,
                index = 2L,
                startTimeOffset = 3016290L,
                endTimeOffset = 4000000L,
                trackId = 1L,
                bookId = 1L,
            ),
        )
        val bookWithChapters = testBook.copy(chapters = testChapters)
        val trackInChapter2 = testTracks[0].copy(progress = 3022434L)
        
        currentlyPlaying.update(
            track = trackInChapter2,
            book = bookWithChapters,
            tracks = testTracks,
        )
        
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
        assertThat(currentlyPlaying.track.value.progress, `is`(3022434L))

        // When: User seeks to chapter start (3016290)
        // This is a legitimate backward seek, NOT stale data
        val trackAtChapterStart = testTracks[0].copy(progress = 3016290L)
        currentlyPlaying.update(
            track = trackAtChapterStart,
            book = bookWithChapters,
            tracks = testTracks,
        )

        // Then: The seek should be allowed
        assertThat(currentlyPlaying.track.value.progress, `is`(3016290L))
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
    }

    @Test
    fun `update should handle exact chapter boundary correctly`() {
        // Given: Track at position exactly at chapter boundary
        val trackAtBoundary = testTracks[0].copy(progress = 16573L)
        currentlyPlaying.update(
            track = trackAtBoundary,
            book = testBook,
            tracks = testTracks,
        )
        
        // Then: Should be in chapter 2, not chapter 1
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.chapter.value.index, `is`(2L))
    }

    @Test
    fun `update should handle progress just before chapter boundary`() {
        // Given: Track at position 1ms before chapter boundary
        val trackBeforeBoundary = testTracks[0].copy(progress = 16572L)
        currentlyPlaying.update(
            track = trackBeforeBoundary,
            book = testBook,
            tracks = testTracks,
        )
        
        // Then: Should be in chapter 1
        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
        assertThat(currentlyPlaying.chapter.value.index, `is`(1L))
    }

    @Test
    fun `update should handle progress just after chapter boundary`() {
        // Given: Track at position 1ms after chapter boundary
        val trackAfterBoundary = testTracks[0].copy(progress = 16574L)
        currentlyPlaying.update(
            track = trackAfterBoundary,
            book = testBook,
            tracks = testTracks,
        )
        
        // Then: Should be in chapter 2
        assertThat(currentlyPlaying.chapter.value.title, `is`("The Four Houses of Midgard"))
        assertThat(currentlyPlaying.chapter.value.index, `is`(2L))
    }
}
