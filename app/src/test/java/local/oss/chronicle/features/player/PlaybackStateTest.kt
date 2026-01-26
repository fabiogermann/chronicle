package local.oss.chronicle.features.player

import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.plex.PlexMediaSource
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.nullValue
import org.junit.Test

class PlaybackStateTest {
    // Test data - 3 tracks of 60 seconds each (180 seconds total)
    private val testTracks =
        listOf(
            MediaItemTrack(id = 1, title = "Track 1", duration = 60_000L, index = 1),
            MediaItemTrack(id = 2, title = "Track 2", duration = 60_000L, index = 2),
            MediaItemTrack(id = 3, title = "Track 3", duration = 60_000L, index = 3),
        )

    // Test chapters across the book
    // Chapter 1: 0-90s (tracks 1-2)
    // Chapter 2: 90-150s (track 2-3)
    // Chapter 3: 150-180s (track 3)
    private val testChapters =
        listOf(
            Chapter(
                id = 1,
                title = "Chapter 1",
                index = 1,
                startTimeOffset = 0L,
                endTimeOffset = 90_000L,
                bookId = 100L,
            ),
            Chapter(
                id = 2,
                title = "Chapter 2",
                index = 2,
                startTimeOffset = 90_000L,
                endTimeOffset = 150_000L,
                bookId = 100L,
            ),
            Chapter(
                id = 3,
                title = "Chapter 3",
                index = 3,
                startTimeOffset = 150_000L,
                endTimeOffset = 180_000L,
                bookId = 100L,
            ),
        )

    private val testAudiobook =
        Audiobook(
            id = 100,
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Test Audiobook",
            author = "Test Author",
            duration = 180_000L,
            progress = 30_000L,
        )

    // =====================
    // Empty State Tests
    // =====================

    @Test
    fun `EMPTY state has default values`() {
        val state = PlaybackState.EMPTY

        assertThat(state.audiobook, `is`(nullValue()))
        assertThat(state.tracks, `is`(emptyList()))
        assertThat(state.chapters, `is`(emptyList()))
        assertThat(state.currentTrackIndex, `is`(0))
        assertThat(state.currentTrackPositionMs, `is`(0L))
        assertThat(state.isPlaying, `is`(false))
        assertThat(state.playbackSpeed, `is`(1.0f))
        assertThat(state.hasMedia, `is`(false))
    }

    // =====================
    // Factory Method Tests
    // =====================

    @Test
    fun `fromAudiobook creates state with correct values`() {
        val state =
            PlaybackState.fromAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startTrackIndex = 1,
                startPositionMs = 15_000L,
            )

        assertThat(state.audiobook, `is`(testAudiobook))
        assertThat(state.tracks, `is`(testTracks))
        assertThat(state.chapters, `is`(testChapters))
        assertThat(state.currentTrackIndex, `is`(1))
        assertThat(state.currentTrackPositionMs, `is`(15_000L))
        assertThat(state.playbackSpeed, `is`(1.0f))
        assertThat(state.isPlaying, `is`(false))
        assertThat(state.hasMedia, `is`(true))
    }

    @Test
    fun `fromAudiobook coerces negative startPositionMs to 0`() {
        val state =
            PlaybackState.fromAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startPositionMs = -100L,
            )

        assertThat(state.currentTrackPositionMs, `is`(0L))
    }

    @Test
    fun `fromAudiobook coerces out-of-bounds startTrackIndex`() {
        val state =
            PlaybackState.fromAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startTrackIndex = 99,
            )

        assertThat(state.currentTrackIndex, `is`(2)) // last index
    }

    @Test
    fun `fromAudiobook handles negative startTrackIndex`() {
        val state =
            PlaybackState.fromAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startTrackIndex = -5,
            )

        assertThat(state.currentTrackIndex, `is`(0))
    }

    // =====================
    // Computed Property Tests - Basic
    // =====================

    @Test
    fun `hasMedia is false when audiobook is null`() {
        val state = PlaybackState(audiobook = null, tracks = testTracks)
        assertThat(state.hasMedia, `is`(false))
    }

    @Test
    fun `hasMedia is false when tracks are empty`() {
        val state = PlaybackState(audiobook = testAudiobook, tracks = emptyList())
        assertThat(state.hasMedia, `is`(false))
    }

    @Test
    fun `hasMedia is true when both audiobook and tracks present`() {
        val state = PlaybackState(audiobook = testAudiobook, tracks = testTracks)
        assertThat(state.hasMedia, `is`(true))
    }

    @Test
    fun `currentTrack returns null when tracks are empty`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.currentTrack, `is`(nullValue()))
    }

    @Test
    fun `currentTrack returns null when index out of bounds`() {
        val state = PlaybackState(tracks = testTracks, currentTrackIndex = 99)
        assertThat(state.currentTrack, `is`(nullValue()))
    }

    @Test
    fun `currentTrack returns correct track`() {
        val state = PlaybackState(tracks = testTracks, currentTrackIndex = 1)
        assertThat(state.currentTrack, `is`(testTracks[1]))
    }

    // =====================
    // Book Position Tests
    // =====================

    @Test
    fun `bookPositionMs returns 0 when tracks are empty`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.bookPositionMs, `is`(0L))
    }

    @Test
    fun `bookPositionMs at start of first track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.bookPositionMs, `is`(0L))
    }

    @Test
    fun `bookPositionMs within first track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.bookPositionMs, `is`(30_000L))
    }

    @Test
    fun `bookPositionMs at start of second track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.bookPositionMs, `is`(60_000L))
    }

    @Test
    fun `bookPositionMs within second track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 25_000L,
            )
        assertThat(state.bookPositionMs, `is`(85_000L))
    }

    @Test
    fun `bookPositionMs at start of third track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 2,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.bookPositionMs, `is`(120_000L))
    }

    @Test
    fun `bookPositionMs at end of book`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 2,
                currentTrackPositionMs = 60_000L,
            )
        assertThat(state.bookPositionMs, `is`(180_000L))
    }

    // =====================
    // Duration Tests
    // =====================

    @Test
    fun `bookDurationMs sums all track durations`() {
        val state = PlaybackState(tracks = testTracks)
        assertThat(state.bookDurationMs, `is`(180_000L))
    }

    @Test
    fun `bookDurationMs returns 0 for empty tracks`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.bookDurationMs, `is`(0L))
    }

    @Test
    fun `currentTrackDurationMs returns correct duration`() {
        val state = PlaybackState(tracks = testTracks, currentTrackIndex = 1)
        assertThat(state.currentTrackDurationMs, `is`(60_000L))
    }

    @Test
    fun `currentTrackDurationMs returns 0 when no current track`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.currentTrackDurationMs, `is`(0L))
    }

    // =====================
    // Chapter Detection Tests
    // =====================

    @Test
    fun `currentChapter returns null when chapters are empty`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = emptyList(),
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.currentChapter, `is`(nullValue()))
    }

    @Test
    fun `currentChapter detects first chapter at beginning`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.currentChapter, `is`(testChapters[0]))
    }

    @Test
    fun `currentChapter detects first chapter in middle`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 45_000L,
            )
        assertThat(state.currentChapter, `is`(testChapters[0]))
    }

    @Test
    fun `currentChapter detects second chapter at boundary`() {
        // Position: 90s (start of chapter 2)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                // 60s + 30s = 90s
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.currentChapter, `is`(testChapters[1]))
    }

    @Test
    fun `currentChapter detects third chapter`() {
        // Position: 165s (in chapter 3)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 2,
                // 120s + 45s = 165s
                currentTrackPositionMs = 45_000L,
            )
        assertThat(state.currentChapter, `is`(testChapters[2]))
    }

    @Test
    fun `currentChapterIndex returns -1 when chapters are empty`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = emptyList(),
            )
        assertThat(state.currentChapterIndex, `is`(-1))
    }

    @Test
    fun `currentChapterIndex returns correct index for first chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.currentChapterIndex, `is`(0))
    }

    @Test
    fun `currentChapterIndex returns correct index for second chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                // 100s total
                currentTrackPositionMs = 40_000L,
            )
        assertThat(state.currentChapterIndex, `is`(1))
    }

    @Test
    fun `currentChapterIndex returns correct index for third chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 2,
                // 170s total
                currentTrackPositionMs = 50_000L,
            )
        assertThat(state.currentChapterIndex, `is`(2))
    }

    // =====================
    // Chapter Duration and Position Tests
    // =====================

    @Test
    fun `currentChapterDurationMs returns 0 when no chapters`() {
        val state = PlaybackState(tracks = testTracks, chapters = emptyList())
        assertThat(state.currentChapterDurationMs, `is`(0L))
    }

    @Test
    fun `currentChapterDurationMs calculates first chapter duration`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.currentChapterDurationMs, `is`(90_000L))
    }

    @Test
    fun `currentChapterDurationMs calculates second chapter duration`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 40_000L,
            )
        assertThat(state.currentChapterDurationMs, `is`(60_000L))
    }

    @Test
    fun `currentChapterDurationMs calculates last chapter duration to end of book`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 2,
                currentTrackPositionMs = 50_000L,
            )
        assertThat(state.currentChapterDurationMs, `is`(30_000L))
    }

    @Test
    fun `currentChapterPositionMs returns 0 when no chapters`() {
        val state = PlaybackState(tracks = testTracks, chapters = emptyList())
        assertThat(state.currentChapterPositionMs, `is`(0L))
    }

    @Test
    fun `currentChapterPositionMs calculates position in first chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 45_000L,
            )
        assertThat(state.currentChapterPositionMs, `is`(45_000L))
    }

    @Test
    fun `currentChapterPositionMs calculates position in second chapter`() {
        // Book position: 100s, Chapter 2 starts at 90s, position in chapter: 10s
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 40_000L,
            )
        assertThat(state.currentChapterPositionMs, `is`(10_000L))
    }

    @Test
    fun `currentChapterPositionMs calculates position at chapter boundary`() {
        // Book position: 90s, Chapter 2 starts at 90s, position in chapter: 0s
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.currentChapterPositionMs, `is`(0L))
    }

    // =====================
    // Progress Calculation Tests
    // =====================

    @Test
    fun `bookProgress returns 0 when duration is 0`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.bookProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `bookProgress at start of book`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.bookProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `bookProgress at 50 percent`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                // 90s out of 180s
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.bookProgress.toDouble(), `is`(closeTo(0.5, 0.001)))
    }

    @Test
    fun `bookProgress at end of book`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 2,
                currentTrackPositionMs = 60_000L,
            )
        assertThat(state.bookProgress.toDouble(), `is`(closeTo(1.0, 0.001)))
    }

    @Test
    fun `trackProgress returns 0 when no current track`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.trackProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `trackProgress at start of track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.trackProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `trackProgress at 50 percent of track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.trackProgress.toDouble(), `is`(closeTo(0.5, 0.001)))
    }

    @Test
    fun `trackProgress at end of track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 60_000L,
            )
        assertThat(state.trackProgress.toDouble(), `is`(closeTo(1.0, 0.001)))
    }

    @Test
    fun `chapterProgress returns 0 when no chapters`() {
        val state = PlaybackState(tracks = testTracks, chapters = emptyList())
        assertThat(state.chapterProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `chapterProgress at start of chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                // 90s - start of chapter 2
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.chapterProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `chapterProgress at 50 percent of chapter`() {
        // Chapter 2: 90-150s (60s duration), position at 120s = 50%
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 2,
                // 120s book position
                currentTrackPositionMs = 0L,
            )
        assertThat(state.chapterProgress.toDouble(), `is`(closeTo(0.5, 0.001)))
    }

    @Test
    fun `chapterProgress near end of chapter`() {
        // Chapter 3: 150-180s (30s duration), position at 165s = 15s/30s = 50%
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 2,
                // 165s book position
                currentTrackPositionMs = 45_000L,
            )
        assertThat(state.chapterProgress.toDouble(), `is`(closeTo(0.5, 0.001)))
    }

    // =====================
    // State Update Method Tests
    // =====================

    @Test
    fun `withPosition creates new state with updated position`() {
        val original =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )

        val updated = original.withPosition(trackIndex = 1, positionMs = 25_000L)

        // Original unchanged
        assertThat(original.currentTrackIndex, `is`(0))
        assertThat(original.currentTrackPositionMs, `is`(10_000L))

        // Updated has new values
        assertThat(updated.currentTrackIndex, `is`(1))
        assertThat(updated.currentTrackPositionMs, `is`(25_000L))

        // Other properties unchanged
        assertThat(updated.tracks, `is`(original.tracks))
        assertThat(updated.isPlaying, `is`(original.isPlaying))
    }

    @Test
    fun `withPosition coerces negative position to 0`() {
        val state = PlaybackState(tracks = testTracks)
        val updated = state.withPosition(0, -100L)

        assertThat(updated.currentTrackPositionMs, `is`(0L))
    }

    @Test
    fun `withPosition coerces out-of-bounds track index`() {
        val state = PlaybackState(tracks = testTracks)
        val updated = state.withPosition(999, 0L)

        assertThat(updated.currentTrackIndex, `is`(2)) // last valid index
    }

    @Test
    fun `withPosition updates lastUpdatedAtMs`() {
        val original = PlaybackState(tracks = testTracks, lastUpdatedAtMs = 1000L)
        Thread.sleep(10) // Ensure time progresses
        val updated = original.withPosition(0, 0L)

        assertThat(updated.lastUpdatedAtMs > original.lastUpdatedAtMs, `is`(true))
    }

    @Test
    fun `withPlayingState creates new state with updated playing state`() {
        val original = PlaybackState(tracks = testTracks, isPlaying = false)
        val updated = original.withPlayingState(true)

        assertThat(original.isPlaying, `is`(false))
        assertThat(updated.isPlaying, `is`(true))
    }

    @Test
    fun `withPlayingState updates lastUpdatedAtMs`() {
        val original = PlaybackState(tracks = testTracks, lastUpdatedAtMs = 1000L)
        Thread.sleep(10)
        val updated = original.withPlayingState(true)

        assertThat(updated.lastUpdatedAtMs > original.lastUpdatedAtMs, `is`(true))
    }

    @Test
    fun `withPlaybackSpeed creates new state with updated speed`() {
        val original = PlaybackState(tracks = testTracks, playbackSpeed = 1.0f)
        val updated = original.withPlaybackSpeed(1.5f)

        assertThat(original.playbackSpeed, `is`(1.0f))
        assertThat(updated.playbackSpeed, `is`(1.5f))
    }

    @Test
    fun `withPlaybackSpeed coerces speed below minimum`() {
        val state = PlaybackState(tracks = testTracks)
        val updated = state.withPlaybackSpeed(0.1f)

        assertThat(updated.playbackSpeed, `is`(0.5f)) // minimum
    }

    @Test
    fun `withPlaybackSpeed coerces speed above maximum`() {
        val state = PlaybackState(tracks = testTracks)
        val updated = state.withPlaybackSpeed(5.0f)

        assertThat(updated.playbackSpeed, `is`(3.0f)) // maximum
    }

    @Test
    fun `withPlaybackSpeed updates lastUpdatedAtMs`() {
        val original = PlaybackState(tracks = testTracks, lastUpdatedAtMs = 1000L)
        Thread.sleep(10)
        val updated = original.withPlaybackSpeed(1.5f)

        assertThat(updated.lastUpdatedAtMs > original.lastUpdatedAtMs, `is`(true))
    }

    // =====================
    // hasSignificantPositionChange Tests
    // =====================

    @Test
    fun `hasSignificantPositionChange detects different audiobooks`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 =
            PlaybackState(
                audiobook = testAudiobook.copy(id = 999),
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )

        assertThat(state1.hasSignificantPositionChange(state2), `is`(true))
    }

    @Test
    fun `hasSignificantPositionChange detects different track index`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackIndex = 1)

        assertThat(state1.hasSignificantPositionChange(state2), `is`(true))
    }

    @Test
    fun `hasSignificantPositionChange detects position change above threshold`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 12_000L)

        assertThat(state1.hasSignificantPositionChange(state2, thresholdMs = 1000L), `is`(true))
    }

    @Test
    fun `hasSignificantPositionChange ignores position change below threshold`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 10_500L)

        assertThat(state1.hasSignificantPositionChange(state2, thresholdMs = 1000L), `is`(false))
    }

    @Test
    fun `hasSignificantPositionChange detects negative position change above threshold`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 8_000L)

        assertThat(state1.hasSignificantPositionChange(state2, thresholdMs = 1000L), `is`(true))
    }

    @Test
    fun `hasSignificantPositionChange uses default threshold of 1000ms`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 10_999L)

        // 999ms change - below default threshold
        assertThat(state1.hasSignificantPositionChange(state2), `is`(false))
    }

    @Test
    fun `hasSignificantPositionChange at exact threshold boundary`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 11_000L)

        // Exactly 1000ms change - should be considered significant
        assertThat(state1.hasSignificantPositionChange(state2, thresholdMs = 1000L), `is`(true))
    }

    // =====================
    // Edge Cases Tests
    // =====================

    @Test
    fun `state with empty tracks handles all computed properties gracefully`() {
        val state =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = emptyList(),
                chapters = emptyList(),
            )

        assertThat(state.hasMedia, `is`(false))
        assertThat(state.currentTrack, `is`(nullValue()))
        assertThat(state.currentChapter, `is`(nullValue()))
        assertThat(state.currentChapterIndex, `is`(-1))
        assertThat(state.bookPositionMs, `is`(0L))
        assertThat(state.bookDurationMs, `is`(0L))
        assertThat(state.currentTrackDurationMs, `is`(0L))
        assertThat(state.currentChapterDurationMs, `is`(0L))
        assertThat(state.currentChapterPositionMs, `is`(0L))
        assertThat(state.bookProgress, `is`(0f))
        assertThat(state.trackProgress, `is`(0f))
        assertThat(state.chapterProgress, `is`(0f))
    }

    @Test
    fun `state with null audiobook and empty tracks matches EMPTY`() {
        val state = PlaybackState(audiobook = null, tracks = emptyList())

        assertThat(state.hasMedia, `is`(PlaybackState.EMPTY.hasMedia))
        assertThat(state.currentTrack, `is`(PlaybackState.EMPTY.currentTrack))
        assertThat(state.bookPositionMs, `is`(PlaybackState.EMPTY.bookPositionMs))
    }

    @Test
    fun `toString includes relevant information`() {
        val state =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L,
                isPlaying = true,
            )

        val str = state.toString()
        assertThat(str.contains("Test Audiobook"), `is`(true))
        assertThat(str.contains("track=1"), `is`(true))
        assertThat(str.contains("pos=30000ms"), `is`(true))
        assertThat(str.contains("playing=true"), `is`(true))
    }

    @Test
    fun `toString with null audiobook shows None`() {
        val state = PlaybackState.EMPTY
        val str = state.toString()
        assertThat(str.contains("None"), `is`(true))
    }
}
