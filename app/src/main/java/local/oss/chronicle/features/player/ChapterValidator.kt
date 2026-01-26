package local.oss.chronicle.features.player

import local.oss.chronicle.data.model.Chapter
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of validating a position against chapters.
 */
sealed class ValidationResult {
    /**
     * Position is valid and falls within the specified chapter.
     */
    data class Valid(
        val chapter: Chapter,
        val chapterIndex: Int,
        val positionWithinChapter: Long,
    ) : ValidationResult()

    /**
     * Position is before all chapters (e.g., negative or before first chapter starts).
     */
    data class BeforeAllChapters(
        val position: Long,
        val firstChapter: Chapter?,
    ) : ValidationResult()

    /**
     * Position is after all chapters (beyond book duration).
     */
    data class AfterAllChapters(
        val position: Long,
        val lastChapter: Chapter?,
    ) : ValidationResult()

    /**
     * No chapters available.
     */
    data object NoChapters : ValidationResult()
}

/**
 * Validates playback positions against chapter boundaries.
 *
 * Ensures:
 * - Position falls within a valid chapter
 * - Edge cases at chapter boundaries are handled
 * - Provides safe fallbacks for invalid positions
 */
@Singleton
class ChapterValidator
    @Inject
    constructor() {
        companion object {
            /** Tolerance for boundary checking in milliseconds */
            const val BOUNDARY_TOLERANCE_MS = 100L
        }

        /**
         * Validates a book position against the chapter list.
         *
         * @param bookPositionMs Position within the book in milliseconds
         * @param chapters List of chapters (must be sorted by startTimeMs)
         * @param bookDurationMs Total book duration in milliseconds
         * @return ValidationResult indicating position validity
         */
        fun validatePosition(
            bookPositionMs: Long,
            chapters: List<Chapter>,
            bookDurationMs: Long,
        ): ValidationResult {
            // Handle empty chapters
            if (chapters.isEmpty()) {
                return ValidationResult.NoChapters
            }

            // Handle position before first chapter
            val firstChapter = chapters.first()
            if (bookPositionMs < firstChapter.startTimeOffset - BOUNDARY_TOLERANCE_MS) {
                Timber.d("Position $bookPositionMs is before first chapter (${firstChapter.startTimeOffset})")
                return ValidationResult.BeforeAllChapters(bookPositionMs, firstChapter)
            }

            // Handle position after last chapter
            if (bookPositionMs > bookDurationMs + BOUNDARY_TOLERANCE_MS) {
                Timber.d("Position $bookPositionMs is after book duration ($bookDurationMs)")
                return ValidationResult.AfterAllChapters(bookPositionMs, chapters.last())
            }

            // Find the chapter containing this position
            val chapterResult = getChapterForPosition(bookPositionMs, chapters, bookDurationMs)

            return if (chapterResult != null) {
                val (chapter, index) = chapterResult
                val positionWithinChapter = bookPositionMs - chapter.startTimeOffset
                ValidationResult.Valid(chapter, index, positionWithinChapter.coerceAtLeast(0L))
            } else {
                // Fallback - use last chapter if position is at the end
                ValidationResult.AfterAllChapters(bookPositionMs, chapters.last())
            }
        }

        /**
         * Gets the chapter for a given book position.
         *
         * @param bookPositionMs Position within the book in milliseconds
         * @param chapters List of chapters (must be sorted by startTimeMs)
         * @param bookDurationMs Total book duration (used to calculate last chapter duration)
         * @return Pair of (Chapter, index) or null if not found
         */
        fun getChapterForPosition(
            bookPositionMs: Long,
            chapters: List<Chapter>,
            bookDurationMs: Long,
        ): Pair<Chapter, Int>? {
            if (chapters.isEmpty()) return null

            // Find the last chapter that starts at or before this position
            for (i in chapters.indices.reversed()) {
                val chapter = chapters[i]
                if (chapter.startTimeOffset <= bookPositionMs + BOUNDARY_TOLERANCE_MS) {
                    // Verify position is within chapter duration (if we can calculate it)
                    val chapterEndMs =
                        if (i < chapters.lastIndex) {
                            chapters[i + 1].startTimeOffset
                        } else {
                            bookDurationMs
                        }

                    // Position should be within this chapter's range
                    if (bookPositionMs < chapterEndMs + BOUNDARY_TOLERANCE_MS) {
                        return chapter to i
                    }
                }
            }

            return null
        }

        /**
         * Calculates the duration of a specific chapter.
         *
         * @param chapterIndex Index of the chapter
         * @param chapters List of chapters
         * @param bookDurationMs Total book duration
         * @return Chapter duration in milliseconds, or 0 if invalid
         */
        fun getChapterDuration(
            chapterIndex: Int,
            chapters: List<Chapter>,
            bookDurationMs: Long,
        ): Long {
            if (chapters.isEmpty() || chapterIndex !in chapters.indices) return 0L

            val chapter = chapters[chapterIndex]
            val nextChapterStart =
                if (chapterIndex < chapters.lastIndex) {
                    chapters[chapterIndex + 1].startTimeOffset
                } else {
                    bookDurationMs
                }

            return (nextChapterStart - chapter.startTimeOffset).coerceAtLeast(0L)
        }

        /**
         * Checks if a position is at or near a chapter boundary.
         *
         * @param bookPositionMs Position within the book
         * @param chapters List of chapters
         * @param toleranceMs Tolerance for boundary detection
         * @return True if position is near a chapter boundary
         */
        fun isNearChapterBoundary(
            bookPositionMs: Long,
            chapters: List<Chapter>,
            toleranceMs: Long = 1000L,
        ): Boolean {
            return chapters.any { chapter ->
                kotlin.math.abs(bookPositionMs - chapter.startTimeOffset) <= toleranceMs
            }
        }

        /**
         * Clamps a position to valid chapter bounds.
         *
         * @param bookPositionMs Position to clamp
         * @param chapters List of chapters
         * @param bookDurationMs Total book duration
         * @return Clamped position within valid range
         */
        fun clampPosition(
            bookPositionMs: Long,
            chapters: List<Chapter>,
            bookDurationMs: Long,
        ): Long {
            if (chapters.isEmpty()) return bookPositionMs.coerceIn(0L, bookDurationMs)

            val minPosition = chapters.first().startTimeOffset
            val maxPosition = bookDurationMs

            return bookPositionMs.coerceIn(minPosition, maxPosition)
        }
    }
