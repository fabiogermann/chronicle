package local.oss.chronicle.features.currentlyplaying

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
import local.oss.chronicle.features.player.PlaybackStateController
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for CurrentlyPlayingSingleton after PR 2.2 refactoring.
 *
 * **Architecture:** CurrentlyPlayingSingleton now derives state from PlaybackStateController.
 * Tests verify that:
 * 1. State flows properly expose controller state
 * 2. Backward compatibility flows work
 * 3. Chapter change listeners are bridged correctly
 * 4. Deprecated update() is a no-op
 */
@ExperimentalCoroutinesApi
class CurrentlyPlayingSingletonTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var playbackStateController: PlaybackStateController
    private lateinit var currentlyPlaying: CurrentlyPlayingSingleton
    private var chapterChangeCount = 0
    private var lastChapterChange: Chapter? = null

    private val testBook =
        Audiobook(
            id = "plex:1",
            libraryId = "plex:lib:1",
            source = 1L,
            title = "Test Audiobook",
            chapters =
                listOf(
                    Chapter(
                        title = "Opening Credits",
                        id = 1L,
                        index = 0L,
                        startTimeOffset = 0L,
                        endTimeOffset = 3016290L,
                        trackId = "plex:1",
                        bookId = "plex:1",
                    ),
                    Chapter(
                        title = "Chapter 1",
                        id = 2L,
                        index = 1L,
                        startTimeOffset = 3016290L,
                        endTimeOffset = 7200000L,
                        trackId = "plex:1",
                        bookId = "plex:1",
                    ),
                    Chapter(
                        title = "Chapter 2",
                        id = 3L,
                        index = 2L,
                        startTimeOffset = 7200000L,
                        endTimeOffset = 10000000L,
                        trackId = "plex:1",
                        bookId = "plex:1",
                    ),
                ),
        )

    private val testTracks =
        listOf(
            MediaItemTrack(
                id = "plex:1",
                libraryId = "plex:lib:1",
                title = "Test Track 1",
                duration = 10000000L,
                progress = 0L,
            ),
        )

    @Before
    fun setup() {
        // Set the main dispatcher for tests
        Dispatchers.setMain(testDispatcher)

        // Mock dependencies for PlaybackStateController using MockK
        val mockBookRepository = mockk<BookRepository>(relaxed = true)
        val mockPrefsRepo = mockk<PrefsRepo>(relaxed = true)

        // Setup mock behaviors
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
    fun `state exposes PlaybackStateController StateFlow`() =
        runTest {
            // Load audiobook via controller
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            advanceUntilIdle()

            // Verify singleton exposes controller's state
            val state = currentlyPlaying.state.value
            assertThat(state.audiobook?.id, `is`(testBook.id))
            assertThat(state.currentTrack?.id, `is`(testTracks[0].id))
        }

    @Test
    fun `book flow derives from controller state`() =
        runTest {
            // Load audiobook via controller
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            // Give the flow time to update
            advanceUntilIdle()

            // Verify backward compatibility flow
            assertThat(currentlyPlaying.book.value.id, `is`(testBook.id))
            assertThat(currentlyPlaying.book.value.title, `is`(testBook.title))
        }

    @Test
    fun `track flow derives from controller state`() =
        runTest {
            // Load audiobook via controller
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            // Give the flow time to update
            advanceUntilIdle()

            // Verify backward compatibility flow
            assertThat(currentlyPlaying.track.value.id, `is`(testTracks[0].id))
        }

    @Test
    fun `chapter flow derives from controller state - opening credits`() =
        runTest {
            // Load audiobook at position 0 (Opening Credits)
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            // Give the flow time to update
            advanceUntilIdle()

            // Verify chapter is correctly derived from position
            assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(1L))
        }

    @Test
    fun `chapter flow updates when position changes to chapter 1`() =
        runTest {
            // Load audiobook at Opening Credits
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))

            // Update position to Chapter 1
            playbackStateController.updatePosition(0, 3016290L)

            advanceUntilIdle()

            // Verify chapter updated to Chapter 1
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
            assertThat(currentlyPlaying.chapter.value.id, `is`(2L))
        }

    @Test
    fun `chapter change listener is triggered when chapter changes`() =
        runTest {
            // Load audiobook at Opening Credits
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            advanceUntilIdle()
            val initialCount = chapterChangeCount

            // Update position to Chapter 1
            playbackStateController.updatePosition(0, 3016290L)

            advanceUntilIdle()

            // Verify chapter change listener was called
            assertThat(chapterChangeCount, `is`(initialCount + 1))
            assertThat(lastChapterChange?.title, `is`("Chapter 1"))
        }

    @Test
    fun `chapter change listener not triggered when chapter stays same`() =
        runTest {
            // Load audiobook at Chapter 1
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                startPositionMs = 5000000L,
            )

            advanceUntilIdle()
            val initialCount = chapterChangeCount

            // Update position within Chapter 1
            playbackStateController.updatePosition(0, 6000000L)

            advanceUntilIdle()

            // Verify chapter change listener was NOT called
            assertThat(chapterChangeCount, `is`(initialCount))
        }

    @Test
    fun `deprecated update method is a no-op`() =
        runTest {
            // Load audiobook via controller
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                startPositionMs = 0L,
            )

            advanceUntilIdle()
            assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))

            // Call deprecated update() - should be ignored
            val trackWithDifferentProgress = testTracks[0].copy(progress = 5000000L)
            currentlyPlaying.update(
                track = trackWithDifferentProgress,
                book = testBook,
                tracks = testTracks,
            )

            advanceUntilIdle()

            // Verify state was NOT changed by deprecated update()
            // Should still be at Opening Credits, not Chapter 1
            assertThat(currentlyPlaying.chapter.value.title, `is`("Opening Credits"))
        }

    @Test
    fun `chapter derived from controller position not from stale DB data`() =
        runTest {
            // This test verifies the fix for Critical Issue C1
            // Controller is at Chapter 1 position
            playbackStateController.loadAudiobook(
                audiobook = testBook,
                tracks = testTracks,
                chapters = testBook.chapters,
                startTrackIndex = 0,
                // Chapter 1
                startPositionMs = 5000000L,
            )

            advanceUntilIdle()

            // Call deprecated update() with stale progress from DB (Opening Credits)
            val trackWithStaleProgress = testTracks[0].copy(progress = 0L)
            currentlyPlaying.update(
                track = trackWithStaleProgress,
                book = testBook,
                tracks = testTracks,
            )

            advanceUntilIdle()

            // Verify chapter comes from controller (Chapter 1), NOT from stale DB data (Opening Credits)
            assertThat(currentlyPlaying.chapter.value.title, `is`("Chapter 1"))
        }

    @Test
    fun `state handles empty audiobook gracefully`() {
        // Before loading any audiobook
        assertThat(currentlyPlaying.book.value, `is`(EMPTY_AUDIOBOOK))
        assertThat(currentlyPlaying.track.value, `is`(EMPTY_TRACK))
        assertThat(currentlyPlaying.chapter.value, `is`(EMPTY_CHAPTER))
    }
}
