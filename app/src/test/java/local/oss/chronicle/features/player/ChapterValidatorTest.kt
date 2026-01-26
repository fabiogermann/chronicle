package local.oss.chronicle.features.player

import local.oss.chronicle.data.model.Chapter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChapterValidatorTest {
    private lateinit var validator: ChapterValidator

    // Test data: 3 chapters - Chapter 1: 0-60s, Chapter 2: 60-120s, Chapter 3: 120-180s
    private val testChapters =
        listOf(
            Chapter(id = 1, title = "Chapter 1", startTimeOffset = 0L, endTimeOffset = 60_000L, bookId = 1),
            Chapter(id = 2, title = "Chapter 2", startTimeOffset = 60_000L, endTimeOffset = 120_000L, bookId = 1),
            Chapter(id = 3, title = "Chapter 3", startTimeOffset = 120_000L, endTimeOffset = 180_000L, bookId = 1),
        )
    private val bookDurationMs = 180_000L // 3 minutes

    @Before
    fun setUp() {
        validator = ChapterValidator()
    }

    // validatePosition tests

    @Test
    fun `validatePosition returns NoChapters for empty chapter list`() {
        val result = validator.validatePosition(30_000L, emptyList(), bookDurationMs)
        assertEquals(ValidationResult.NoChapters, result)
    }

    @Test
    fun `validatePosition returns Valid for position in first chapter`() {
        val result = validator.validatePosition(30_000L, testChapters, bookDurationMs)
        assertTrue(result is ValidationResult.Valid)
        with(result as ValidationResult.Valid) {
            assertEquals("Chapter 1", chapter.title)
            assertEquals(0, chapterIndex)
            assertEquals(30_000L, positionWithinChapter)
        }
    }

    @Test
    fun `validatePosition returns Valid for position in middle chapter`() {
        val result = validator.validatePosition(90_000L, testChapters, bookDurationMs)
        assertTrue(result is ValidationResult.Valid)
        with(result as ValidationResult.Valid) {
            assertEquals("Chapter 2", chapter.title)
            assertEquals(1, chapterIndex)
            assertEquals(30_000L, positionWithinChapter)
        }
    }

    @Test
    fun `validatePosition returns Valid for position in last chapter`() {
        val result = validator.validatePosition(150_000L, testChapters, bookDurationMs)
        assertTrue(result is ValidationResult.Valid)
        with(result as ValidationResult.Valid) {
            assertEquals("Chapter 3", chapter.title)
            assertEquals(2, chapterIndex)
        }
    }

    @Test
    fun `validatePosition handles position at chapter boundary`() {
        val result = validator.validatePosition(60_000L, testChapters, bookDurationMs)
        assertTrue(result is ValidationResult.Valid)
        with(result as ValidationResult.Valid) {
            assertEquals("Chapter 2", chapter.title)
            assertEquals(1, chapterIndex)
            assertEquals(0L, positionWithinChapter)
        }
    }

    @Test
    fun `validatePosition returns BeforeAllChapters for negative position`() {
        val result = validator.validatePosition(-1000L, testChapters, bookDurationMs)
        assertTrue(result is ValidationResult.BeforeAllChapters)
    }

    @Test
    fun `validatePosition returns AfterAllChapters for position beyond duration`() {
        val result = validator.validatePosition(200_000L, testChapters, bookDurationMs)
        assertTrue(result is ValidationResult.AfterAllChapters)
    }

    // getChapterForPosition tests

    @Test
    fun `getChapterForPosition returns null for empty chapters`() {
        val result = validator.getChapterForPosition(30_000L, emptyList(), bookDurationMs)
        assertNull(result)
    }

    @Test
    fun `getChapterForPosition returns correct chapter and index`() {
        val result = validator.getChapterForPosition(90_000L, testChapters, bookDurationMs)
        assertNotNull(result)
        assertEquals("Chapter 2", result!!.first.title)
        assertEquals(1, result.second)
    }

    // getChapterDuration tests

    @Test
    fun `getChapterDuration returns correct duration for middle chapter`() {
        val duration = validator.getChapterDuration(1, testChapters, bookDurationMs)
        assertEquals(60_000L, duration) // Chapter 2: 60s to 120s
    }

    @Test
    fun `getChapterDuration returns correct duration for last chapter`() {
        val duration = validator.getChapterDuration(2, testChapters, bookDurationMs)
        assertEquals(60_000L, duration) // Chapter 3: 120s to 180s
    }

    @Test
    fun `getChapterDuration returns 0 for invalid index`() {
        assertEquals(0L, validator.getChapterDuration(-1, testChapters, bookDurationMs))
        assertEquals(0L, validator.getChapterDuration(10, testChapters, bookDurationMs))
    }

    // isNearChapterBoundary tests

    @Test
    fun `isNearChapterBoundary returns true at chapter start`() {
        assertTrue(validator.isNearChapterBoundary(60_500L, testChapters, 1000L))
    }

    @Test
    fun `isNearChapterBoundary returns false away from boundaries`() {
        assertFalse(validator.isNearChapterBoundary(90_000L, testChapters, 1000L))
    }

    // clampPosition tests

    @Test
    fun `clampPosition clamps negative position to first chapter`() {
        val clamped = validator.clampPosition(-1000L, testChapters, bookDurationMs)
        assertEquals(0L, clamped)
    }

    @Test
    fun `clampPosition clamps excessive position to book duration`() {
        val clamped = validator.clampPosition(300_000L, testChapters, bookDurationMs)
        assertEquals(bookDurationMs, clamped)
    }

    @Test
    fun `clampPosition returns valid position unchanged`() {
        val clamped = validator.clampPosition(90_000L, testChapters, bookDurationMs)
        assertEquals(90_000L, clamped)
    }
}
