package local.oss.chronicle.data.model

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Test

class ChapterTest {
    @Test
    fun `getChapterAt returns correct chapter at start offset`() {
        val chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 3016289L,
                trackId = 1L,
            ),
            Chapter(
                title = "Chapter 1",
                id = 2L,
                index = 1L,
                startTimeOffset = 3016290L,
                endTimeOffset = 7200000L,
                trackId = 1L,
            ),
        )

        val chapter = chapters.getChapterAt(trackId = 1L, timeStamp = 3016290L)
        assertThat(chapter.title, `is`("Chapter 1"))
        assertThat(chapter.id, `is`(2L))
    }

    @Test
    fun `getChapterAt returns correct chapter at middle offset`() {
        val chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 3016289L,
                trackId = 1L,
            ),
            Chapter(
                title = "Chapter 1",
                id = 2L,
                index = 1L,
                startTimeOffset = 3016290L,
                endTimeOffset = 7200000L,
                trackId = 1L,
            ),
        )

        val chapter = chapters.getChapterAt(trackId = 1L, timeStamp = 5000000L)
        assertThat(chapter.title, `is`("Chapter 1"))
        assertThat(chapter.id, `is`(2L))
    }

    @Test
    fun `getChapterAt returns correct chapter at end offset`() {
        val chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 3016289L,
                trackId = 1L,
            ),
            Chapter(
                title = "Chapter 1",
                id = 2L,
                index = 1L,
                startTimeOffset = 3016290L,
                endTimeOffset = 7200000L,
                trackId = 1L,
            ),
        )

        val chapter = chapters.getChapterAt(trackId = 1L, timeStamp = 7200000L)
        assertThat(chapter.title, `is`("Chapter 1"))
        assertThat(chapter.id, `is`(2L))
    }

    @Test
    fun `getChapterAt returns first chapter at beginning`() {
        val chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 3016289L,
                trackId = 1L,
            ),
            Chapter(
                title = "Chapter 1",
                id = 2L,
                index = 1L,
                startTimeOffset = 3016290L,
                endTimeOffset = 7200000L,
                trackId = 1L,
            ),
        )

        val chapter = chapters.getChapterAt(trackId = 1L, timeStamp = 0L)
        assertThat(chapter.title, `is`("Opening Credits"))
        assertThat(chapter.id, `is`(1L))
    }

    @Test
    fun `getChapterAt returns correct chapter for multiple tracks`() {
        val chapters = listOf(
            Chapter(
                title = "Chapter 1",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 5000000L,
                trackId = 1L,
            ),
            Chapter(
                title = "Chapter 2",
                id = 2L,
                index = 1L,
                startTimeOffset = 0L,
                endTimeOffset = 5000000L,
                trackId = 2L,
            ),
        )

        // Test track 1
        val chapter1 = chapters.getChapterAt(trackId = 1L, timeStamp = 2000000L)
        assertThat(chapter1.title, `is`("Chapter 1"))
        assertThat(chapter1.trackId, `is`(1L))

        // Test track 2
        val chapter2 = chapters.getChapterAt(trackId = 2L, timeStamp = 2000000L)
        assertThat(chapter2.title, `is`("Chapter 2"))
        assertThat(chapter2.trackId, `is`(2L))
    }

    @Test
    fun `getChapterAt returns EMPTY_CHAPTER when no matching chapter`() {
        val chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 3016289L,
                trackId = 1L,
            ),
        )

        // Time beyond the end of the last chapter
        val chapter = chapters.getChapterAt(trackId = 1L, timeStamp = 10000000L)
        assertThat(chapter, `is`(EMPTY_CHAPTER))
    }

    @Test
    fun `getChapterAt returns EMPTY_CHAPTER for wrong track id`() {
        val chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 3016289L,
                trackId = 1L,
            ),
        )

        val chapter = chapters.getChapterAt(trackId = 999L, timeStamp = 1000000L)
        assertThat(chapter, `is`(EMPTY_CHAPTER))
    }

    @Test
    fun `getChapterAt returns EMPTY_CHAPTER for empty chapter list`() {
        val chapters = emptyList<Chapter>()

        val chapter = chapters.getChapterAt(trackId = 1L, timeStamp = 1000000L)
        assertThat(chapter, `is`(EMPTY_CHAPTER))
    }

    @Test
    fun `getChapterAt handles boundary between chapters correctly`() {
        val chapters = listOf(
            Chapter(
                title = "Opening Credits",
                id = 1L,
                index = 0L,
                startTimeOffset = 0L,
                endTimeOffset = 3016289L,
                trackId = 1L,
            ),
            Chapter(
                title = "Chapter 1",
                id = 2L,
                index = 1L,
                startTimeOffset = 3016290L,
                endTimeOffset = 7200000L,
                trackId = 1L,
            ),
        )

        // Just before boundary (still in Opening Credits)
        val chapterBefore = chapters.getChapterAt(trackId = 1L, timeStamp = 3016289L)
        assertThat(chapterBefore.title, `is`("Opening Credits"))

        // At boundary (now in Chapter 1)
        val chapterAt = chapters.getChapterAt(trackId = 1L, timeStamp = 3016290L)
        assertThat(chapterAt.title, `is`("Chapter 1"))

        // Just after boundary (still in Chapter 1)
        val chapterAfter = chapters.getChapterAt(trackId = 1L, timeStamp = 3016291L)
        assertThat(chapterAfter.title, `is`("Chapter 1"))
    }
}
