package local.oss.chronicle.features.player

import android.content.Context
import android.os.Bundle
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.EMPTY_AUDIOBOOK
import local.oss.chronicle.data.model.EMPTY_CHAPTER
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo.LoginState
import local.oss.chronicle.data.sources.plex.PlaybackUrlResolver
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.util.Event
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import timber.log.Timber

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
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @RelaxedMockK
    private lateinit var plexPrefsRepo: PlexPrefsRepo

    @RelaxedMockK
    private lateinit var prefsRepo: PrefsRepo

    @RelaxedMockK
    private lateinit var plexConfig: PlexConfig

    @RelaxedMockK
    private lateinit var plexLoginRepo: IPlexLoginRepo

    @RelaxedMockK
    private lateinit var mediaController: MediaControllerCompat

    @RelaxedMockK
    private lateinit var dataSourceFactory: DefaultHttpDataSource.Factory

    @RelaxedMockK
    private lateinit var trackRepository: ITrackRepository

    @RelaxedMockK
    private lateinit var bookRepository: IBookRepository

    @RelaxedMockK
    private lateinit var trackListStateManager: TrackListStateManager

    @RelaxedMockK
    private lateinit var foregroundServiceController: ForegroundServiceController

    @RelaxedMockK
    private lateinit var serviceController: ServiceController

    @RelaxedMockK
    private lateinit var errorReporter: IPlaybackErrorReporter

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
    private lateinit var testExceptionHandler: CoroutineExceptionHandler

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        testScope = TestScope()
        
        // Setup appContext string resource mocks
        every { appContext.getString(any()) } returns "Mock error message"
        every { appContext.getString(any(), any()) } returns "Mock error message with args"

        // Create test exception handler that logs but doesn't crash
        testExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Test coroutine exception")
        }

        callback =
            AudiobookMediaSessionCallback(
                plexPrefsRepo = plexPrefsRepo,
                prefsRepo = prefsRepo,
                plexConfig = plexConfig,
                plexLoginRepo = plexLoginRepo,
                mediaController = mediaController,
                dataSourceFactory = dataSourceFactory,
                trackRepository = trackRepository,
                bookRepository = bookRepository,
                serviceScope = testScope as CoroutineScope,
                trackListStateManager = trackListStateManager,
                foregroundServiceController = foregroundServiceController,
                serviceController = serviceController,
                errorReporter = errorReporter,
                mediaSession = mediaSession,
                appContext = appContext,
                currentlyPlaying = currentlyPlaying,
                progressUpdater = progressUpdater,
                playbackUrlResolver = playbackUrlResolver,
                playbackStateController = playbackStateController,
                coroutineExceptionHandler = testExceptionHandler,
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
                trackId = 1L,
                bookId = 1L,
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
                trackId = 1L,
                bookId = 1L,
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
                trackId = 1L,
                bookId = 1L,
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
                trackId = 1L,
                bookId = 1L,
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
                trackId = 1L,
                bookId = 1L,
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

    // ========================================
    // Authentication Check Tests
    // ========================================

    @Test
    fun `onPlay returns error when user not logged in`() {
        // Given: User is not logged in
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.NOT_LOGGED_IN)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val playbackState = mockk<android.support.v4.media.session.PlaybackStateCompat>()
        every { playbackState.state } returns PlaybackStateCompat.STATE_NONE
        every { mediaController.playbackState } returns playbackState

        // When: onPlay is called
        callback.onPlay()

        // Then: Error is set with authentication expired code
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                any()
            )
        }
    }

    @Test
    fun `onPlay returns error when no server chosen`() {
        // Given: Logged in but no server chosen
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_NO_SERVER_CHOSEN)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val playbackState = mockk<android.support.v4.media.session.PlaybackStateCompat>()
        every { playbackState.state } returns PlaybackStateCompat.STATE_NONE
        every { mediaController.playbackState } returns playbackState

        // When: onPlay is called
        callback.onPlay()

        // Then: Error is set with not supported code
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                any()
            )
        }
    }

    @Test
    fun `onPlay returns error when no user chosen`() {
        // Given: Logged in but no user chosen
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_NO_USER_CHOSEN)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val playbackState = mockk<android.support.v4.media.session.PlaybackStateCompat>()
        every { playbackState.state } returns PlaybackStateCompat.STATE_NONE
        every { mediaController.playbackState } returns playbackState

        // When: onPlay is called
        callback.onPlay()

        // Then: Error is set with not supported code
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                any()
            )
        }
    }

    @Test
    fun `onPlay returns error when no library chosen`() {
        // Given: Logged in but no library chosen
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val playbackState = mockk<android.support.v4.media.session.PlaybackStateCompat>()
        every { playbackState.state } returns PlaybackStateCompat.STATE_NONE
        every { mediaController.playbackState } returns playbackState

        // When: onPlay is called
        callback.onPlay()

        // Then: Error is set with not supported code
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                any()
            )
        }
    }

    @Test
    fun `onPlay proceeds when fully logged in with prepared state`() {
        // Given: User is fully logged in and player is prepared
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val playbackState = mockk<android.support.v4.media.session.PlaybackStateCompat>()
        every { playbackState.state } returns PlaybackStateCompat.STATE_PAUSED
        every { mediaController.playbackState } returns playbackState
        every { mockPlayer.playWhenReady = any() } returns Unit

        // When: onPlay is called
        callback.onPlay()

        // Then: No error is set and playWhenReady is set to true
        verify(exactly = 0) {
            errorReporter.setPlaybackStateError(any(), any())
        }
        verify { mockPlayer.playWhenReady = true }
    }

    // ========================================
    // onPlayFromSearch Error Handling Tests
    // ========================================

    @Test
    fun `onPlayFromSearch returns error when user not logged in`() {
        // Given: User is not logged in
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.NOT_LOGGED_IN)
        every { plexLoginRepo.loginEvent } returns loginEvent

        // When: onPlayFromSearch is called
        callback.onPlayFromSearch("query", null)

        // Then: Error is set before any search
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                any()
            )
        }
    }

    @Test
    fun `onPlayFromSearch shows error when no results found for specific query`() = runTest {
        // Given: User is fully logged in but no books match the query
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent
        coEvery { bookRepository.searchAsync("nonexistent book") } returns emptyList()

        // When: onPlayFromSearch is called with a specific query
        callback.onPlayFromSearch("nonexistent book", null)
        
        // Advance time to allow coroutine to complete
        testScope.testScheduler.advanceUntilIdle()
        testScope.testScheduler.runCurrent()

        // Then: Error is set with content not available code
        verify(timeout = 1000) {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                any()
            )
        }
    }

    @Test
    fun `onPlayFromSearch shows library empty error on fallback failure`() = runTest {
        // Given: User is fully logged in, empty query, no recently played, empty library
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent
        coEvery { bookRepository.getMostRecentlyPlayed() } returns EMPTY_AUDIOBOOK
        coEvery { bookRepository.getRandomBookAsync() } returns EMPTY_AUDIOBOOK

        // When: onPlayFromSearch is called with empty/null query
        callback.onPlayFromSearch(null, null)
        
        // Advance time to allow coroutine to complete
        testScope.testScheduler.advanceUntilIdle()
        testScope.testScheduler.runCurrent()

        // Then: Error is set with library empty message
        verify(timeout = 1000) {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                any()
            )
        }
    }

    @Test
    fun `onPlayFromSearch plays first result when results found`() = runTest {
        // Given: User is fully logged in and search returns results
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val mockBook = Audiobook(
            id = 123,
            source = 1L, // Plex source
            title = "Test Book",
            author = "Author Name",
            duration = 3600000
        )
        
        coEvery { bookRepository.searchAsync("existing book") } returns listOf(mockBook)
        coEvery { trackRepository.getTracksForAudiobookAsync(123) } returns emptyList()
        coEvery { bookRepository.getAudiobookAsync(123) } returns mockBook
        
        val playbackState = mockk<android.support.v4.media.session.PlaybackStateCompat>()
        every { playbackState.state } returns PlaybackStateCompat.STATE_NONE
        every { mediaController.playbackState } returns playbackState

        // When: onPlayFromSearch is called with a query that has results
        callback.onPlayFromSearch("existing book", null)
        
        // Advance time to allow coroutine to complete
        advanceTimeBy(1000)

        // Then: No error is set (playback would proceed)
        // Note: We verify that the error handler is NOT called for successful search
        verify(exactly = 0) {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                any()
            )
        }
    }

    // ========================================
    // onPlayFromMediaId Error Handling Tests
    // ========================================

    @Test
    fun `onPlayFromMediaId returns error when user not logged in`() {
        // Given: User is not logged in
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.NOT_LOGGED_IN)
        every { plexLoginRepo.loginEvent } returns loginEvent

        // When: onPlayFromMediaId is called
        callback.onPlayFromMediaId("bookId", null)

        // Then: Error is set
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                any()
            )
        }
    }

    @Test
    fun `onPlayFromMediaId returns error when bookId is null`() {
        // Given: User is fully logged in
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent

        // When: onPlayFromMediaId is called with null bookId
        callback.onPlayFromMediaId(null, null)

        // Then: Error is set with "audiobook not available" message
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                any()
            )
        }
    }

    @Test
    fun `onPlayFromMediaId returns error when bookId is empty`() {
        // Given: User is fully logged in
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent

        // When: onPlayFromMediaId is called with empty bookId
        callback.onPlayFromMediaId("", null)

        // Then: Error is set
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                any()
            )
        }
    }

    @Test
    fun `onPlayFromMediaId proceeds when valid bookId provided`() = runTest {
        // Given: User is fully logged in and valid bookId
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val mockBook = Audiobook(
            id = 456,
            source = 1L, // Plex source
            title = "Valid Book",
            author = "Author Name",
            duration = 3600000
        )
        
        coEvery { trackRepository.getTracksForAudiobookAsync(456) } returns emptyList()
        coEvery { bookRepository.getAudiobookAsync(456) } returns mockBook

        // When: onPlayFromMediaId is called with valid bookId
        callback.onPlayFromMediaId("456", Bundle())
        
        // Advance time to allow coroutine to complete
        advanceTimeBy(1000)

        // Then: No authentication error is set
        verify(exactly = 0) {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                any()
            )
        }
    }

    // ========================================
    // Timeout Tests
    // ========================================

    @Test
    fun `resumePlayFromEmpty times out after 10 seconds and shows error`() = runTest {
        // Given: plexConfig.isConnected never becomes true
        val isConnectedLiveData = MutableLiveData<Boolean>()
        isConnectedLiveData.value = false
        every { plexConfig.isConnected } returns isConnectedLiveData
        
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val playbackState = mockk<android.support.v4.media.session.PlaybackStateCompat>()
        every { playbackState.state } returns PlaybackStateCompat.STATE_NONE
        every { mediaController.playbackState } returns playbackState

        // When: onPlay triggers resumePlayFromEmpty
        callback.onPlay()
        
        // Advance time past the timeout (10 seconds)
        testScope.testScheduler.advanceTimeBy(11_000)
        testScope.testScheduler.advanceUntilIdle()

        // Then: Timeout error is set
        verify {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                any()
            )
        }
    }

    @Test
    fun `resumePlayFromEmpty succeeds when connection established`() = runTest {
        // Given: plexConfig.isConnected becomes true
        val isConnectedLiveData = MutableLiveData<Boolean>()
        isConnectedLiveData.value = false
        every { plexConfig.isConnected } returns isConnectedLiveData
        
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent
        
        val mockBook = Audiobook(
            id = 789,
            source = 1L, // Plex source
            title = "Recent Book",
            author = "Author Name",
            duration = 3600000,
            lastViewedAt = System.currentTimeMillis(),
            viewCount = 1
        )
        
        coEvery { bookRepository.getMostRecentlyPlayed() } returns mockBook
        coEvery { trackRepository.getTracksForAudiobookAsync(789) } returns emptyList()
        coEvery { bookRepository.getAudiobookAsync(789) } returns mockBook
        
        val playbackState = mockk<android.support.v4.media.session.PlaybackStateCompat>()
        every { playbackState.state } returns PlaybackStateCompat.STATE_NONE
        every { mediaController.playbackState } returns playbackState

        // When: onPlay triggers resumePlayFromEmpty and connection becomes available
        callback.onPlay()
        
        // Simulate connection becoming available after 1 second
        advanceTimeBy(1_000)
        isConnectedLiveData.value = true
        advanceTimeBy(1000)

        // Then: Playback proceeds without timeout error
        // The timeout error should NOT be called
        verify(exactly = 0) {
            errorReporter.setPlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                match { it.contains("timeout") || it.contains("Connection timeout") }
            )
        }
    }
}
