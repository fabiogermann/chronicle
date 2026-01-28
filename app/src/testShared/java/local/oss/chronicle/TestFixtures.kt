package local.oss.chronicle

import local.oss.chronicle.data.model.Chapter

/**
 * Helper functions for creating test data
 */
object TestFixtures {
    /**
     * Create a test Chapter with sensible defaults for testing.
     * Uses String IDs as per the ID migration.
     */
    fun createTestChapter(
        title: String = "Test Chapter",
        id: Long = 1L,
        index: Long = 1L,
        discNumber: Int = 1,
        startTimeOffset: Long = 0L,
        endTimeOffset: Long = 60_000L,
        downloaded: Boolean = false,
        trackId: String = "plex:1",
        bookId: String = "plex:1",
    ): Chapter {
        return Chapter(
            title = title,
            id = id,
            index = index,
            discNumber = discNumber,
            startTimeOffset = startTimeOffset,
            endTimeOffset = endTimeOffset,
            downloaded = downloaded,
            trackId = trackId,
            bookId = bookId,
        )
    }
}
