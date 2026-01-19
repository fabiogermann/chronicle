package local.oss.chronicle.features.currentlyplaying

import kotlinx.coroutines.ExperimentalCoroutinesApi
import local.oss.chronicle.data.model.*
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.Test

@ExperimentalCoroutinesApi
class CurrentlyPlayingSingletonTest {
    private lateinit var currentlyPlaying: CurrentlyPlayingSingleton
    private var chapterChangeCount = 0
    private var lastChapterChange: Chapter? = null

    private val testBook = Audiobook(
        id = 1,
        source = 1L,
        title = "Test Audiobook",
        chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 3016289L,
                trackId = 1L,
                bookId = 1L,
            ),
            Chapter(
                title = "Chapter 1",
                id = 2L,
                index = 1L,
                startTimeOffset = 3016290L,
                endTimeOffset = 7199999L,
                trackId = 1L,
                bookId = 1L,
            ),
            Chapter(
                title = "Chapter 2",
                id = 3L,
                index = 2L,
                startTimeOffset = 7200000L,
                endTimeOffset = 10000000L,
                trackId = 1L,
                bookId = 1L,
            ),
        ),
    )

    private val testTracks = listOf(
        MediaItemTrack(
            id = 1,
            title = "Test Track 1",
            duration = 10000000L,
            progress = 0L,
        ),
    )

    @Before
    fun setup() {
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
    fun `update sets correct chapter based on track progress at start`() {
        val track = testTracks[0].copy(progress = 0L)
        
        currentlyPlaying.update(
            track = track,
            book = testBook,
            tracks = testTracks,
        )

        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(1L))
    }

    @Test
    fun `update sets correct chapter based on track progress in middle`() {
        val track = testTracks[0].copy(progress = 5000000L)
        
        currentlyPlaying.update(
            track = track,
            book = testBook,
            tracks = testTracks,
        )

        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(2L))
    }

    @Test
    fun `update sets correct chapter when seeking to chapter boundary`() {
        // First set to Opening Credits
        val track1 = testTracks[0].copy(progress = 0L)
        currentlyPlaying.update(
            track = track1,
            book = testBook,
            tracks = testTracks,
        )
        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))

        // Now seek to exactly the start of Chapter 1
        val track2 = testTracks[0].copy(progress = 3016290L)
        currentlyPlaying.update(
            track = track2,
            book = testBook,
            tracks = testTracks,
        )

        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        assertThat(currentlyPlaying.chapter.value.id, `is`(2L))
    }

    @Test
    fun `update triggers chapter change listener when chapter changes`() {
        // Start at Opening Credits
        val track1 = testTracks[0].copy(progress = 0L)
        currentlyPlaying.update(
            track = track1,
            book = testBook,
            tracks = testTracks,
        )
        
        val initialCount = chapterChangeCount
        
        // Seek to Chapter 1
        val track2 = testTracks[0].copy(progress = 3016290L)
        currentlyPlaying.update(
            track = track2,
            book = testBook,
            tracks = testTracks,
        )

        assertThat(chapterChangeCount, `is`(initialCount + 1))
        assertThat(lastChapterChange?.title, `is`("Chapter 1"))
    }

    @Test
    fun `update does not trigger chapter change listener when chapter stays same`() {
        // Start at Chapter 1
        val track1 = testTracks[0].copy(progress = 5000000L)
        currentlyPlaying.update(
            track = track1,
            book = testBook,
            tracks = testTracks,
        )
        
        val initialCount = chapterChangeCount
        
        // Seek within Chapter 1
        val track2 = testTracks[0].copy(progress = 6000000L)
        currentlyPlaying.update(
            track = track2,
            book = testBook,
            tracks = testTracks,
        )

        assertThat(chapterChangeCount, `is`(initialCount))
    }

    @Test
    fun `update with stale progress uses stale value`() {
        // This test verifies the bug: if track.progress is stale,
        // the wrong chapter is detected
        val trackWithStaleProgress = testTracks[0].copy(progress = 0L)
        
        currentlyPlaying.update(
            track = trackWithStaleProgress,
            book = testBook,
            tracks = testTracks,
        )

        // Even though we wanted to seek to Chapter 1, stale progress=0
        // causes Opening Credits to be detected
        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
    }

    @Test
    fun `update with correct progress uses correct value`() {
        // This test verifies the fix: if track.progress is updated correctly,
        // the correct chapter is detected
        val trackWithCorrectProgress = testTracks[0].copy(progress = 3016290L)
        
        currentlyPlaying.update(
            track = trackWithCorrectProgress,
            book = testBook,
            tracks = testTracks,
        )

        // With correct progress=3016290, Chapter 1 is detected
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
    }

    @Test
    fun `update handles empty chapters gracefully`() {
        val bookWithoutChapters = testBook.copy(chapters = emptyList())
        val track = testTracks[0].copy(progress = 5000000L)
        
        // Should use tracks as chapters
        currentlyPlaying.update(
            track = track,
            book = bookWithoutChapters,
            tracks = testTracks,
        )

        // Should not crash, chapter should be set based on track-as-chapter logic
        assertThat(currentlyPlaying.book.value.id, `is`(bookWithoutChapters.id))
    }

    @Test
    fun `update sets book and track values correctly`() {
        val track = testTracks[0].copy(progress = 0L)
        
        currentlyPlaying.update(
            track = track,
            book = testBook,
            tracks = testTracks,
        )

        assertThat(currentlyPlaying.book.value.id, `is`(testBook.id))
        assertThat(currentlyPlaying.book.value.title, `is`(testBook.title))
        assertThat(currentlyPlaying.track.value.id, `is`(track.id))
        assertThat(currentlyPlaying.track.value.progress, `is`(track.progress))
    }

    @Test
    fun `update handles chapter transition correctly`() {
        // Start at end of Opening Credits
        val track1 = testTracks[0].copy(progress = 3016289L)
        currentlyPlaying.update(
            track = track1,
            book = testBook,
            tracks = testTracks,
        )
        assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
        
        // Move to start of Chapter 1
        val track2 = testTracks[0].copy(progress = 3016290L)
        currentlyPlaying.update(
            track = track2,
            book = testBook,
            tracks = testTracks,
        )
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        
        // Move to start of Chapter 2
        val track3 = testTracks[0].copy(progress = 7200000L)
        currentlyPlaying.update(
            track = track3,
            book = testBook,
            tracks = testTracks,
        )
        assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 2"))
    }
}
