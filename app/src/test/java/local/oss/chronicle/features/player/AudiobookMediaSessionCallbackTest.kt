package local.oss.chronicle.features.player

import android.content.Context
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.EMPTY_CHAPTER
import local.oss.chronicle.data.sources.plex.PlaybackUrlResolver
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.Test

/**
 * Tests for AudiobookMediaSessionCallback.onSeekTo() to ensure correct handling
 * of chapter-relative position to absolute track position conversion.
 *
 * **Bug Context:**
 * MediaSession seekbar publishes chapter-relative positions, but onSeekTo() was
 * incorrectly treating them as absolute track positions, causing wrong seeks and crashes.
 */
@ExperimentalCoroutinesApi
class AudiobookMediaSessionCallbackTest {
    @RelaxedMockK
    private lateinit var plexPrefsRepo: PlexPrefsRepo

    @RelaxedMockK
    private lateinit var prefsRepo: PrefsRepo

    @RelaxedMockK
    private lateinit var plexConfig: PlexConfig

    @RelaxedMockK
    private lateinit var mediaController: MediaControllerCompat

    @RelaxedMockK
    private lateinit var dataSourceFactory: DefaultHttpDataSource.Factory

    @MockK
    private lateinit var trackRepository: ITrackRepository

    @MockK
    private lateinit var bookRepository: IBookRepository

    @RelaxedMockK
    private lateinit var trackListStateManager: TrackListStateManager

    @RelaxedMockK
    private lateinit var foregroundServiceController: ForegroundServiceController

    @RelaxedMockK
    private lateinit var serviceController: ServiceController

    @RelaxedMockK
    private lateinit var mediaSession: MediaSessionCompat

    @RelaxedMockK
    private lateinit var appContext: Context

    @MockK
    private lateinit var currentlyPlaying: CurrentlyPlaying

    @RelaxedMockK
    private lateinit var progressUpdater: ProgressUpdater

    @RelaxedMockK
    private lateinit var playbackUrlResolver: PlaybackUrlResolver

    @RelaxedMockK
    private lateinit var playbackStateController: PlaybackStateController

    @MockK
    private lateinit var mockPlayer: Player

    @RelaxedMockK
    private lateinit var defaultPlayer: ExoPlayer

    private lateinit var testScope: TestScope
    private lateinit var callback: AudiobookMediaSessionCallback

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        testScope = TestScope()

        callback =
            AudiobookMediaSessionCallback(
                plexPrefsRepo = plexPrefsRepo,
                prefsRepo = prefsRepo,
                plexConfig = plexConfig,
                mediaController = mediaController,
                dataSourceFactory = dataSourceFactory,
                trackRepository = trackRepository,
                bookRepository = bookRepository,
                serviceScope = testScope as CoroutineScope,
                trackListStateManager = trackListStateManager,
                foregroundServiceController = foregroundServiceController,
                serviceController = serviceController,
                mediaSession = mediaSession,
                appContext = appContext,
                currentlyPlaying = currentlyPlaying,
                progressUpdater = progressUpdater,
                playbackUrlResolver = playbackUrlResolver,
                playbackStateController = playbackStateController,
                defaultPlayer = defaultPlayer,
            )

        // Replace default player with mock for testing
        callback.currentPlayer = mockPlayer
    }

    /**
     * Test: Chapter-relative position conversion to absolute
     *
     * Given: Current chapter starts at 5,197,230ms (absolute track position)
     * When: onSeekTo(1,154,000) is called (chapter-relative from notification)
     * Then: Player should seek to 5,197,230 + 1,154,000 = 6,351,230ms (absolute)
     */
    @Test
    fun `onSeekTo converts chapter-relative position to absolute when chapter data available`() {
        // Given: Chapter starting at 5,197,230ms
        val chapterStartOffset = 5_197_230L
        val chapterEndOffset = 7_000_000L
        val chapter =
            Chapter(
                title = "Chapter 3",
                id = 3L,
                index = 3L,
                startTimeOffset = chapterStartOffset,
                endTimeOffset = chapterEndOffset,
                trackId = "plex:1",
                bookId = "plex:1",
            )

        val chapterFlow = MutableStateFlow(chapter)
        every { currentlyPlaying.chapter } returns chapterFlow
        every { mockPlayer.seekTo(any()) } returns Unit

        // When: Seeking to 1,154,000ms (chapter-relative)
        val chapterRelativePosition = 1_154_000L
        callback.onSeekTo(chapterRelativePosition)

        // Then: Should seek to absolute position (5,197,230 + 1,154,000 = 6,351,230ms)
        val expectedAbsolutePosition = chapterStartOffset + chapterRelativePosition
        verify { mockPlayer.seekTo(expectedAbsolutePosition) }
    }

    /**
     * Test: Fallback behavior when no chapter data available
     *
     * Given: No chapter data (EMPTY_CHAPTER)
     * When: onSeekTo(1,154,000) is called
     * Then: Player should seek to 1,154,000ms as-is (no conversion)
     */
    @Test
    fun `onSeekTo uses position as-is when no chapter data available`() {
        // Given: No chapter data
        val emptyChapterFlow = MutableStateFlow(EMPTY_CHAPTER)
        every { currentlyPlaying.chapter } returns emptyChapterFlow
        every { mockPlayer.seekTo(any()) } returns Unit

        // When: Seeking to position
        val position = 1_154_000L
        callback.onSeekTo(position)

        // Then: Should seek to position as-is (no conversion)
        verify { mockPlayer.seekTo(position) }
    }

    /**
     * Test: Seeking to position 0 (start of chapter)
     *
     * Given: Chapter starts at 2,500,000ms
     * When: onSeekTo(0) is called
     * Then: Player should seek to 2,500,000ms (chapter start)
     */
    @Test
    fun `onSeekTo at position zero seeks to chapter start`() {
        // Given: Chapter starting at 2,500,000ms
        val chapterStartOffset = 2_500_000L
        val chapter =
            Chapter(
                title = "Chapter 1",
                id = 1L,
                index = 1L,
                startTimeOffset = chapterStartOffset,
                endTimeOffset = 5_000_000L,
                trackId = "plex:1",
                bookId = "plex:1",
            )

        val chapterFlow = MutableStateFlow(chapter)
        every { currentlyPlaying.chapter } returns chapterFlow
        every { mockPlayer.seekTo(any()) } returns Unit

        // When: Seeking to position 0 (start of chapter)
        callback.onSeekTo(0L)

        // Then: Should seek to chapter start (2,500,000ms)
        verify { mockPlayer.seekTo(chapterStartOffset) }
    }

    /**
     * Test: Seeking at chapter boundary (near end)
     *
     * Given: Chapter from 1,000,000ms to 3,000,000ms (2s duration)
     * When: onSeekTo(1,999,000) is called (1ms before chapter end, chapter-relative)
     * Then: Player should seek to 2,999,000ms (absolute)
     */
    @Test
    fun `onSeekTo at chapter boundary converts correctly`() {
        // Given: Chapter from 1,000,000ms to 3,000,000ms
        val chapterStartOffset = 1_000_000L
        val chapterEndOffset = 3_000_000L
        val chapter =
            Chapter(
                title = "Short Chapter",
                id = 5L,
                index = 5L,
                startTimeOffset = chapterStartOffset,
                endTimeOffset = chapterEndOffset,
                trackId = "plex:1",
                bookId = "plex:1",
            )

        val chapterFlow = MutableStateFlow(chapter)
        every { currentlyPlaying.chapter } returns chapterFlow
        every { mockPlayer.seekTo(any()) } returns Unit

        // When: Seeking to 1,999,000ms (chapter-relative, near end)
        val chapterRelativePosition = 1_999_000L
        callback.onSeekTo(chapterRelativePosition)

        // Then: Should seek to 2,999,000ms (absolute)
        val expectedAbsolutePosition = chapterStartOffset + chapterRelativePosition
        verify { mockPlayer.seekTo(expectedAbsolutePosition) }
    }

    /**
     * Test: Chapter starting at offset 0 (first chapter case)
     *
     * Given: Chapter starts at 0ms (first chapter of track)
     * When: onSeekTo(500,000) is called
     * Then: Player should seek to 500,000ms (chapter-relative == absolute in this case)
     */
    @Test
    fun `onSeekTo with chapter at offset zero works correctly`() {
        // Given: First chapter starting at 0ms
        val chapter =
            Chapter(
                title = "Introduction",
                id = 1L,
                index = 1L,
                startTimeOffset = 0L,
                endTimeOffset = 1_500_000L,
                trackId = "plex:1",
                bookId = "plex:1",
            )

        val chapterFlow = MutableStateFlow(chapter)
        every { currentlyPlaying.chapter } returns chapterFlow
        every { mockPlayer.seekTo(any()) } returns Unit

        // When: Seeking to 500,000ms (chapter-relative)
        val position = 500_000L
        callback.onSeekTo(position)

        // Then: Should seek to 500,000ms (0 + 500,000 = 500,000)
        verify { mockPlayer.seekTo(position) }
    }

    /**
     * Test: Real-world scenario from bug report
     *
     * Given: Multi-hour audiobook with chapter starting deep into track
     * When: User drags notification seekbar to middle of chapter
     * Then: Seek should be accurate to dragged position, not cause crash
     */
    @Test
    fun `onSeekTo handles real-world multi-hour audiobook scenario`() {
        // Given: Chapter 15 starting 3 hours into a long track (10,800,000ms)
        val chapterStartOffset = 10_800_000L // 3 hours
        val chapterEndOffset = 14_400_000L // 4 hours
        val chapter =
            Chapter(
                title = "Chapter 15",
                id = 15L,
                index = 15L,
                startTimeOffset = chapterStartOffset,
                endTimeOffset = chapterEndOffset,
                trackId = "plex:1",
                bookId = "plex:1",
            )

        val chapterFlow = MutableStateFlow(chapter)
        every { currentlyPlaying.chapter } returns chapterFlow
        every { mockPlayer.seekTo(any()) } returns Unit

        // When: User seeks to middle of chapter (30 minutes in, chapter-relative)
        val chapterRelativePosition = 1_800_000L // 30 minutes
        callback.onSeekTo(chapterRelativePosition)

        // Then: Should seek to correct absolute position (3h30m = 12,600,000ms)
        val expectedAbsolutePosition = 12_600_000L
        verify { mockPlayer.seekTo(expectedAbsolutePosition) }
        assertThat(expectedAbsolutePosition, `is`(chapterStartOffset + chapterRelativePosition))
    }
}
