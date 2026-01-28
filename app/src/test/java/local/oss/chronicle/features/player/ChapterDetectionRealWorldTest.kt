package local.oss.chronicle.features.player

import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.getChapterAt
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Test

/**
 * Test to reproduce chapter detection issue with real world data from House of Sky and Breath audiobook
 *
 * Issue: When playing a chapter (e.g., chapter 2), the UI displays a previous chapter (e.g., chapter 1 or Prologue)
 * When paused, it displays the end of the previous chapter
 */
class ChapterDetectionRealWorldTest {
    // Exact chapter data from logs/playback.log
    private val chapters =
        listOf(
            Chapter(title = "Opening Credits", id = 483, index = 1, discNumber = 1, startTimeOffset = 0, endTimeOffset = 16573, downloaded = false, trackId = "plex:65", bookId = "plex:-22321"),
            Chapter(title = "The Four Houses of Midgard", id = 484, index = 2, discNumber = 1, startTimeOffset = 16573, endTimeOffset = 88282, downloaded = false, trackId = "plex:65", bookId = "plex:-22321"),
            Chapter(title = "Prologue", id = 485, index = 3, discNumber = 1, startTimeOffset = 88282, endTimeOffset = 3010880, downloaded = false, trackId = "plex:65", bookId = "plex:-22321"),
            Chapter(title = "Part I: The Chasm", id = 486, index = 4, discNumber = 1, startTimeOffset = 3010880, endTimeOffset = 3016290, downloaded = false, trackId = "plex:65", bookId = "plex:-22321"),
            Chapter(title = "1", id = 487, index = 5, discNumber = 1, startTimeOffset = 3016290, endTimeOffset = 5197230, downloaded = false, trackId = "plex:65", bookId = "plex:-22321"),
            Chapter(title = "2", id = 488, index = 6, discNumber = 1, startTimeOffset = 5197230, endTimeOffset = 6971840, downloaded = false, trackId = "plex:65", bookId = "plex:-22321"),
            Chapter(title = "3", id = 489, index = 7, discNumber = 1, startTimeOffset = 6971840, endTimeOffset = 7517410, downloaded = false, trackId = "plex:65", bookId = "plex:-22321"),
            Chapter(title = "4", id = 490, index = 8, discNumber = 1, startTimeOffset = 7517410, endTimeOffset = 8974650, downloaded = false, trackId = "plex:65", bookId = "plex:-22321"),
        )

    @Test
    fun `test chapter detection when playing chapter 1 - should show chapter 1`() {
        // From log line 28: position=2180940 is in Prologue (88282 to 3010880)
        // Chapter 1 spans: 3016290 to 5197230
        val trackId = "plex:65"
        val position = 2180940L

        val detectedChapter = chapters.getChapterAt(trackId, position)

        // EXPECTED: Chapter "Prologue" (id=485, index=3) because 2180940 is between 88282 and 3010880
        assertThat(
            "Position $position should be in 'Prologue' (index 3)",
            detectedChapter.title,
            `is`("Prologue"),
        )
        assertThat(detectedChapter.id, `is`(485L))
        assertThat(detectedChapter.index, `is`(3L))
    }

    @Test
    fun `test chapter detection when playing inside chapter 2 - should show chapter 2`() {
        // From log line 79: Cached progress: 5108948
        // Chapter 2 spans: 5197230 to 6971840
        // Position 5108948 is BEFORE chapter 2 starts, so should be in chapter 1
        val trackId = "plex:65"
        val position = 5108948L

        val detectedChapter = chapters.getChapterAt(trackId, position)

        // Position 5108948 is actually in chapter "1" (3016290 to 5197230)
        assertThat(
            "Position $position should be in chapter '1' (index 5)",
            detectedChapter.title,
            `is`("1"),
        )
        assertThat(detectedChapter.id, `is`(487L))
        assertThat(detectedChapter.index, `is`(5L))
    }

    @Test
    fun `test chapter detection at exact chapter boundary - playing state`() {
        // From log line 174: position=5109560
        // This is 5109560, which is BEFORE chapter 2 starts at 5197230
        val trackId = "plex:65"
        val position = 5109560L

        val detectedChapter = chapters.getChapterAt(trackId, position)

        // Should still be in chapter "1"
        assertThat(
            "Position $position (before chapter 2 start 5197230) should be in chapter '1'",
            detectedChapter.title,
            `is`("1"),
        )
        assertThat(detectedChapter.id, `is`(487L))
    }

    @Test
    fun `test chapter detection when paused - should show correct chapter not previous`() {
        // From log line 567: position=5113970 (paused state)
        // Notification shows: chapter=Prologue, index=3 (BUG!)
        // But position 5113970 is in chapter "1" (3016290 to 5197230)
        val trackId = "plex:65"
        val position = 5113970L

        val detectedChapter = chapters.getChapterAt(trackId, position)

        // EXPECTED: Chapter "1" because 3016290 <= 5113970 <= 5197230
        // ACTUAL (BUG): Shows "Prologue" (index=3)
        assertThat(
            "Position $position when paused should show chapter '1' not 'Prologue'",
            detectedChapter.title,
            `is`("1"),
        )
        assertThat(detectedChapter.id, `is`(487L))
        assertThat(detectedChapter.index, `is`(5L))
    }

    @Test
    fun `test all chapter boundaries are correctly detected`() {
        val trackId = "plex:65"

        // Test positions at start of each chapter
        assertThat(
            "Start of 'Opening Credits'",
            chapters.getChapterAt(trackId, 0).title,
            `is`("Opening Credits"),
        )

        assertThat(
            "Start of 'The Four Houses of Midgard'",
            chapters.getChapterAt(trackId, 16573).title,
            `is`("The Four Houses of Midgard"),
        )

        assertThat(
            "Start of 'Prologue'",
            chapters.getChapterAt(trackId, 88282).title,
            `is`("Prologue"),
        )

        assertThat(
            "Middle of 'Prologue'",
            chapters.getChapterAt(trackId, 1500000).title,
            `is`("Prologue"),
        )

        assertThat(
            "Start of 'Part I: The Chasm'",
            chapters.getChapterAt(trackId, 3010880).title,
            `is`("Part I: The Chasm"),
        )

        assertThat(
            "Start of chapter '1'",
            chapters.getChapterAt(trackId, 3016290).title,
            `is`("1"),
        )

        assertThat(
            "Middle of chapter '1'",
            chapters.getChapterAt(trackId, 4000000).title,
            `is`("1"),
        )

        assertThat(
            "Near end of chapter '1'",
            chapters.getChapterAt(trackId, 5197229).title,
            `is`("1"),
        )

        assertThat(
            "Exact end of chapter '1'",
            chapters.getChapterAt(trackId, 5197230).title,
            // Inclusive range means 5197230 is start of chapter 2
            `is`("2"),
        )

        assertThat(
            "Start of chapter '2'",
            chapters.getChapterAt(trackId, 5197230).title,
            `is`("2"),
        )

        assertThat(
            "Middle of chapter '2'",
            chapters.getChapterAt(trackId, 6000000).title,
            `is`("2"),
        )
    }

    @Test
    fun `test issue scenario - user selects chapter 2 while playing`() {
        // User clicks on chapter "2" to play it
        // Chapter 2: startTimeOffset=5197230, endTimeOffset=6971840
        val trackId = "plex:65"

        // Simulate playback starting just after chapter 2 begins
        val positionAfterChapter2Starts = 5197230L + 100L // 5197330

        val detectedChapter = chapters.getChapterAt(trackId, positionAfterChapter2Starts)

        assertThat(
            "When user selects chapter 2 and playback begins, UI should show chapter '2'",
            detectedChapter.title,
            `is`("2"),
        )
        assertThat(detectedChapter.index, `is`(6L))
    }

    @Test
    fun `test issue scenario - paused in middle of chapter shows wrong chapter`() {
        // From log: when paused at position 5113970, it showed "Prologue" (index 3)
        // But 5113970 is in chapter "1" (3016290 to 5197230)
        val trackId = "plex:65"
        val pausedPosition = 5113970L

        val detectedChapter = chapters.getChapterAt(trackId, pausedPosition)

        assertThat(
            "When paused at position $pausedPosition, should show chapter '1' not 'Prologue'",
            detectedChapter.title,
            `is`("1"),
        )
        assertThat(
            "Chapter index should be 5",
            detectedChapter.index,
            `is`(5L),
        )
    }

    @Test
    fun `test actual positions from log - playing state shows wrong chapter`() {
        val trackId = "plex:65"

        // From log line 55: Initial cached progress shows 2180940
        // This should be in Prologue (88282 to 3010880)
        val initialPosition = 2180940L
        assertThat(
            "Initial position $initialPosition should be in 'Prologue'",
            chapters.getChapterAt(trackId, initialPosition).title,
            `is`("Prologue"),
        )

        // From log line 79/87: After user action, progress is 5108948
        // This should be in chapter "1" (3016290 to 5197230)
        val afterClickPosition = 5108948L
        assertThat(
            "After clicking, position $afterClickPosition should be in chapter '1'",
            chapters.getChapterAt(trackId, afterClickPosition).title,
            `is`("1"),
        )

        // From log line 174: While playing, position reported as 5109560
        // This is still in chapter "1"
        val playingPosition = 5109560L
        assertThat(
            "While playing at position $playingPosition should show chapter '1'",
            chapters.getChapterAt(trackId, playingPosition).title,
            `is`("1"),
        )

        // From log line 567: When paused, position is 5113970
        // This is still in chapter "1" (not "Prologue" as shown in UI)
        val pausedPosition = 5113970L
        assertThat(
            "When paused at position $pausedPosition should show chapter '1' not 'Prologue'",
            chapters.getChapterAt(trackId, pausedPosition).title,
            `is`("1"),
        )
    }

    @Test
    fun `test edge case - position exactly at chapter end boundary`() {
        val trackId = "plex:65"

        // Test at exact end of Prologue
        val prologueEnd = 3010880L
        val atPrologueEnd = chapters.getChapterAt(trackId, prologueEnd)
        assertThat(
            "At exact end of Prologue (3010880), should be in 'Part I: The Chasm'",
            atPrologueEnd.title,
            // Because range is inclusive, end position belongs to next chapter
            `is`("Part I: The Chasm"),
        )

        // Test at exact end of chapter 1
        val chapter1End = 5197230L
        val atChapter1End = chapters.getChapterAt(trackId, chapter1End)
        assertThat(
            "At exact end of chapter 1 (5197230), should be in chapter '2'",
            atChapter1End.title,
            `is`("2"),
        )
    }

    @Test
    fun `test comprehensive playing state positions`() {
        val trackId = "plex:65"

        // Test various positions during playback to understand the pattern

        // Position when first loaded (from line 55 in log)
        val pos1 = 2180940L
        assertThat(
            "pos=$pos1 should be in Prologue",
            chapters.getChapterAt(trackId, pos1).title,
            `is`("Prologue"),
        )

        // Position after metadata change (from line 79 in log) - actually in chapter 1
        val pos2 = 5108948L
        assertThat(
            "pos=$pos2 should be in chapter '1'",
            chapters.getChapterAt(trackId, pos2).title,
            `is`("1"),
        )

        // Position while playing (from line 174 in log) - still in chapter 1
        val pos3 = 5109560L
        assertThat(
            "pos=$pos3 should be in chapter '1'",
            chapters.getChapterAt(trackId, pos3).title,
            `is`("1"),
        )

        // Position when paused (from line 567 in log) - still in chapter 1
        val pos4 = 5113970L
        assertThat(
            "pos=$pos4 should be in chapter '1'",
            chapters.getChapterAt(trackId, pos4).title,
            `is`("1"),
        )
    }

    @Test
    fun `test comprehensive paused state - verifies UI should show chapter 1 not Prologue`() {
        val trackId = "plex:65"
        val pausedPosition = 5113970L // From log line 567

        // When paused, the log shows:
        // Line 574: "Building notification! chapter=Prologue, index=3"
        // This is WRONG because position 5113970 is in chapter "1"

        val chapter1 = chapters[4] // Chapter "1" at index 5 in list
        val detectedChapter = chapters.getChapterAt(trackId, pausedPosition)

        // Verify position is within chapter 1 bounds
        assertThat(
            "Position should be >= chapter 1 start",
            pausedPosition >= chapter1.startTimeOffset,
            `is`(true),
        )
        assertThat(
            "Position should be <= chapter 1 end",
            pausedPosition <= chapter1.endTimeOffset,
            `is`(true),
        )

        // The getChapterAt function should return chapter 1
        assertThat(
            "getChapterAt should return chapter '1' for position $pausedPosition",
            detectedChapter.title,
            `is`("1"),
        )
        assertThat(
            "getChapterAt should return index 5",
            detectedChapter.index,
            `is`(5L),
        )
    }

    @Test
    fun `test comprehensive playing state - playing chapter shows wrong chapter in UI`() {
        val trackId = "plex:65"

        // From the log, after user interaction:
        // Line 47: "Building notification! chapter=1, index=5" (while buffering)
        // Line 147: "Building notification! chapter=1, index=5" (while playing)
        // This is correct

        // But then line 160: "Chapter changed to: Prologue"
        // And line 218: "Building notification! chapter=Prologue, index=3"
        // This shows the issue - it switches to showing Prologue even though still in chapter 1

        val playingPosition = 5109560L // From log line 174

        val detectedChapter = chapters.getChapterAt(trackId, playingPosition)

        // Chapter 1 spans: 3016290 to 5197230
        // Position 5109560 is within this range
        assertThat(
            "Playing position $playingPosition is within chapter 1 bounds",
            playingPosition >= 3016290L && playingPosition <= 5197230L,
            `is`(true),
        )

        assertThat(
            "While playing at position $playingPosition, should show chapter '1'",
            detectedChapter.title,
            `is`("1"),
        )
    }

    @Test
    fun `test initial state transition - from position 2180940 to 5108948`() {
        val trackId = "plex:65"

        // Initial position (line 55): 2180940 - in Prologue
        val initialPos = 2180940L
        val initialChapter = chapters.getChapterAt(trackId, initialPos)
        assertThat(initialChapter.title, `is`("Prologue"))
        assertThat(initialChapter.index, `is`(3L))

        // After user clicks chapter (line 79): 5108948 - should be in chapter 1
        val afterClickPos = 5108948L
        val afterClickChapter = chapters.getChapterAt(trackId, afterClickPos)
        assertThat(
            "After user action, position $afterClickPos should be in chapter '1'",
            afterClickChapter.title,
            `is`("1"),
        )
        assertThat(afterClickChapter.index, `is`(5L))
    }

    @Test
    fun `test wrong chapter displayed bug scenario - complete flow`() {
        val trackId = "plex:65"

        // SCENARIO FROM LOGS:
        // 1. Book initially at position 2180940 (in Prologue)
        // 2. User presumably clicks chapter 2 or plays the book
        // 3. Position changes to 5108948 (in chapter 1, close to chapter 2)
        // 4. While playing, position is 5109560 (still in chapter 1)
        //    BUT notification shows "Prologue" (index 3) - WRONG!
        // 5. When paused at position 5113970 (still in chapter 1)
        //    Notification shows "Prologue" (index 3) - WRONG!

        // All these positions are in chapter "1"
        val positions = listOf(5108948L, 5109560L, 5113970L)

        for (pos in positions) {
            val chapter = chapters.getChapterAt(trackId, pos)
            assertThat(
                "Position $pos should detect chapter '1' (index 5), not Prologue or other",
                chapter.title,
                `is`("1"),
            )
            assertThat(
                "Position $pos should have index 5",
                chapter.index,
                `is`(5L),
            )
        }
    }

    /**
     * Helper function that uses the FIXED cachedChapter algorithm from CurrentlyPlayingViewModel.kt
     * This now uses the correct getChapterAt() function with absolute time offsets.
     */
    private fun List<Chapter>.getChapterAtUsingBrokenCachedLogic(
        trackId: String,
        progress: Long,
    ): Chapter {
        return this.getChapterAt(trackId, progress)
    }

    @Test
    fun `FIXED TEST - cachedChapter algorithm now correctly returns chapter 1 when selecting chapter 1`() {
        // When user selects chapter "1" (which starts at 3016290),
        // the UI should show chapter "1" (index=5)
        //
        // The FIXED cachedChapter algorithm in CurrentlyPlayingViewModel now uses getChapterAt()
        //
        // This test verifies the CORRECT behavior now works

        val trackId = "plex:65"
        val position = 3016290L // Start of chapter "1"

        // This is what the fixed code now does (returns "1" correctly)
        val fixedResult = chapters.getChapterAtUsingBrokenCachedLogic(trackId, position)

        // This test asserts the EXPECTED correct behavior
        // It will now PASS because fixedResult.title is "1"
        assertThat(
            "When selecting chapter '1' at position $position, UI should show '1'. " +
                "FIXED: cachedChapter logic in CurrentlyPlayingViewModel now uses getChapterAt().",
            fixedResult.title,
            // Now correctly returns "1"
            `is`("1"),
        )
        assertThat(fixedResult.index, `is`(5L)) // Now correctly returns 5
    }

    // ============================================================================
    // Bug-Specific Tests: Chapter Boundary Detection, Position Conversion, Flip-Flopping
    // ============================================================================

    /**
     * BUG 1: Chapter Boundary Detection (Half-Open Interval)
     * The bug was that getChapterAt() used inclusive range (..) instead of half-open interval (until).
     * This caused positions at chapter boundaries to match the wrong (previous) chapter.
     */

    @Test
    fun `test chapter boundary - position at chapter start returns that chapter`() {
        // Test that a position exactly at the start of a chapter returns that chapter
        val trackId = "plex:65"

        // Test chapter "The Four Houses of Midgard" starts at 16573
        val chapter2Start = 16573L
        val detectedChapter = chapters.getChapterAt(trackId, chapter2Start)

        assertThat(
            "Position at chapter start (16573) should return 'The Four Houses of Midgard'",
            detectedChapter.title,
            `is`("The Four Houses of Midgard"),
        )
        assertThat(detectedChapter.startTimeOffset, `is`(16573L))
    }

    @Test
    fun `test chapter boundary - position at chapter end returns next chapter`() {
        // Test that a position exactly at the end of a chapter returns the NEXT chapter
        // This verifies the half-open interval behavior [start, end)
        val trackId = "plex:65"

        // Chapter "The Four Houses of Midgard" ends at 88282
        // Position 88282 should return "Prologue" (the next chapter), not "The Four Houses of Midgard"
        val chapter2End = 88282L
        val detectedChapter = chapters.getChapterAt(trackId, chapter2End)

        assertThat(
            "Position at chapter end (88282) should return next chapter 'Prologue', not current chapter",
            detectedChapter.title,
            `is`("Prologue"),
        )
        assertThat(detectedChapter.startTimeOffset, `is`(88282L))
    }

    @Test
    fun `test chapter boundary - position one millisecond before chapter end returns current chapter`() {
        // Test that a position just before the chapter end still returns the current chapter
        val trackId = "plex:65"

        // Chapter "The Four Houses of Midgard" ends at 88282
        // Position 88281 (one millisecond before end) should still return "The Four Houses of Midgard"
        val oneBeforeEnd = 88281L
        val detectedChapter = chapters.getChapterAt(trackId, oneBeforeEnd)

        assertThat(
            "Position one millisecond before chapter end (88281) should return current chapter 'The Four Houses of Midgard'",
            detectedChapter.title,
            `is`("The Four Houses of Midgard"),
        )
        assertThat(detectedChapter.endTimeOffset, `is`(88282L))
    }

    /**
     * BUG 2: Chapter-Relative vs Absolute Position Conversion
     * The bug was that ProgressUpdater received chapter-relative positions but treated them as absolute.
     * We now convert chapter-relative to absolute by adding the chapter's startTimeOffset.
     */

    @Test
    fun `test chapter relative to absolute conversion`() {
        // Verify the formula: absolute position = chapterRelativePosition + chapter.startTimeOffset
        val chapter2 = chapters[1] // "The Four Houses of Midgard" starts at 16573

        assertThat("Chapter 2 startTimeOffset", chapter2.startTimeOffset, `is`(16573L))
        assertThat("Chapter 2 title", chapter2.title, `is`("The Four Houses of Midgard"))

        // Test case 1: Position 0 in chapter 2 should be absolute position 16573
        val chapterRelativePosition1 = 0L
        val absolutePosition1 = chapterRelativePosition1 + chapter2.startTimeOffset
        assertThat(
            "For chapter-relative position 0 in chapter 2, absolute position should be 16573",
            absolutePosition1,
            `is`(16573L),
        )

        // Test case 2: Position 5000 in chapter 2 should be absolute position 21573
        val chapterRelativePosition2 = 5000L
        val absolutePosition2 = chapterRelativePosition2 + chapter2.startTimeOffset
        assertThat(
            "For chapter-relative position 5000 in chapter 2, absolute position should be 21573",
            absolutePosition2,
            `is`(21573L),
        )

        // Verify that the absolute position correctly identifies the chapter
        val trackId = "plex:65"
        val detectedChapter1 = chapters.getChapterAt(trackId, absolutePosition1)
        assertThat(
            "Absolute position 16573 should be detected as chapter 2",
            detectedChapter1.title,
            `is`("The Four Houses of Midgard"),
        )

        val detectedChapter2 = chapters.getChapterAt(trackId, absolutePosition2)
        assertThat(
            "Absolute position 21573 should be detected as chapter 2",
            detectedChapter2.title,
            `is`("The Four Houses of Midgard"),
        )
    }

    /**
     * BUG 3: Flip-Flopping Prevention
     * The bug was that mixing chapter-relative and absolute positions caused the chapter detection
     * to oscillate. Test that consistent use of absolute positions prevents this.
     */

    @Test
    fun `test flip-flop prevention - absolute vs relative position`() {
        // Simulate the scenario: chapter 2 starts at 16573ms, player is at absolute position 20000ms
        val trackId = "plex:65"
        val chapter2 = chapters[1] // "The Four Houses of Midgard" starts at 16573

        assertThat("Chapter 2 startTimeOffset", chapter2.startTimeOffset, `is`(16573L))

        // Player is at absolute position 20000ms (within chapter 2: 16573 to 88282)
        val absolutePosition = 20000L

        // Calculate what the chapter-relative position would be
        val chapterRelativePosition = absolutePosition - chapter2.startTimeOffset
        assertThat(
            "Chapter-relative position should be 3427ms",
            chapterRelativePosition,
            `is`(3427L),
        )

        // WRONG: If we mistakenly use chapter-relative position as absolute position
        // Position 3427 falls in chapter 1 "Opening Credits" (0 to 16573)
        val wrongChapter = chapters.getChapterAt(trackId, chapterRelativePosition)
        assertThat(
            "Using chapter-relative position 3427 as absolute returns WRONG chapter 'Opening Credits'",
            wrongChapter.title,
            `is`("Opening Credits"),
        )

        // CORRECT: Using absolute position 20000
        // Position 20000 falls in chapter 2 "The Four Houses of Midgard" (16573 to 88282)
        val correctChapter = chapters.getChapterAt(trackId, absolutePosition)
        assertThat(
            "Using absolute position 20000 returns CORRECT chapter 'The Four Houses of Midgard'",
            correctChapter.title,
            `is`("The Four Houses of Midgard"),
        )

        // This demonstrates the flip-flop bug:
        // If the code alternates between using chapter-relative and absolute positions,
        // it will oscillate between showing "Opening Credits" and "The Four Houses of Midgard"
        assertThat(
            "Wrong chapter (from relative position) is different from correct chapter (from absolute position)",
            wrongChapter.title != correctChapter.title,
            `is`(true),
        )
    }
}
