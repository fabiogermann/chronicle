package local.oss.chronicle.features.currentlyplaying

import android.content.SharedPreferences
import android.support.v4.media.session.MediaControllerCompat
import android.text.format.DateUtils
import android.util.Log
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.*
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.features.player.MediaServiceConnection
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for CurrentlyPlayingViewModel.seekTo() method.
 *
 * This test verifies the fix for the seeking regression bug where in-app seeking
 * was calculating absolute positions instead of chapter-relative positions,
 * causing a double-offset when AudiobookMediaSessionCallback added the chapter offset again.
 *
 * **Expected Behavior:**
 * - seekTo() should send CHAPTER-RELATIVE positions to transportControls.seekTo()
 * - AudiobookMediaSessionCallback.onSeekTo() will then add the chapter offset
 */
@ExperimentalCoroutinesApi
class CurrentlyPlayingViewModelSeekTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: CurrentlyPlayingViewModel
    private lateinit var mockMediaServiceConnection: MediaServiceConnection
    private lateinit var mockTransportControls: MediaControllerCompat.TransportControls
    private lateinit var mockCurrentlyPlaying: CurrentlyPlaying

    private val testChapter =
        Chapter(
            title = "Chapter 1",
            id = 2L,
            index = 1L,
            // Chapter starts at 3.016 seconds
            startTimeOffset = 3016290L,
            // Chapter ends at 7.2 seconds
            endTimeOffset = 7200000L,
            trackId = 1L,
            bookId = 1L,
        )

    private val testTrack =
        MediaItemTrack(
            id = 1,
            title = "Test Track",
            duration = 10000000L,
            progress = 5000000L,
        )

    private val testAudiobook =
        Audiobook(
            id = 1,
            source = 1L,
            title = "Test Audiobook",
            chapters = listOf(testChapter),
        )

    @Before
    fun setup() {
        // Set the main dispatcher for tests
        Dispatchers.setMain(testDispatcher)

        // Mock android.util.Log and DateUtils statically because Android system stuff not automatically mocked
        // for unit tests
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkStatic(DateUtils::class)
        every { DateUtils.formatElapsedTime(any(), any()) } returns "0:00"

        // Mock dependencies
        val mockBookRepository = mockk<IBookRepository>(relaxed = true)
        val mockTrackRepository = mockk<ITrackRepository>(relaxed = true)
        val mockLocalBroadcastManager = mockk<androidx.localbroadcastmanager.content.LocalBroadcastManager>(relaxed = true)
        val mockPrefsRepo = mockk<PrefsRepo>(relaxed = true)
        val mockPlexConfig = mockk<PlexConfig>(relaxed = true)
        val mockSharedPrefs = mockk<SharedPreferences>(relaxed = true)

        // Setup MediaServiceConnection mock
        mockMediaServiceConnection = mockk<MediaServiceConnection>(relaxed = true)
        mockTransportControls = mockk<MediaControllerCompat.TransportControls>(relaxed = true)

        every { mockMediaServiceConnection.transportControls } returns mockTransportControls

        // Setup CurrentlyPlaying mock with StateFlow
        mockCurrentlyPlaying = mockk<CurrentlyPlaying>(relaxed = true)
        every { mockCurrentlyPlaying.chapter } returns MutableStateFlow(testChapter)
        every { mockCurrentlyPlaying.track } returns MutableStateFlow(testTrack)
        every { mockCurrentlyPlaying.book } returns MutableStateFlow(testAudiobook)

        // Setup PlexConfig mock
        every { mockPlexConfig.isConnected } returns MutableLiveData(true)

        // Create ViewModel with mocked dependencies
        viewModel =
            CurrentlyPlayingViewModel(
                bookRepository = mockBookRepository,
                trackRepository = mockTrackRepository,
                localBroadcastManager = mockLocalBroadcastManager,
                mediaServiceConnection = mockMediaServiceConnection,
                prefsRepo = mockPrefsRepo,
                plexConfig = mockPlexConfig,
                currentlyPlaying = mockCurrentlyPlaying,
                sharedPrefs = mockSharedPrefs,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `seekTo sends chapter-relative position at 0 percent`() =
        runTest {
            // When seeking to 0% of chapter
            viewModel.seekTo(percentProgress = 0.0)

            // Then transportControls.seekTo() should be called with 0ms (chapter-relative)
            // NOT with 3016290ms (absolute position)
            verify { mockTransportControls.seekTo(0L) }
        }

    @Test
    fun `seekTo sends chapter-relative position at 50 percent`() =
        runTest {
            // Chapter duration = 7200000 - 3016290 = 4183710ms
            // 50% of chapter = 4183710 * 0.5 = 2091855ms (chapter-relative)
            // Absolute position would be 3016290 + 2091855 = 5108145ms (WRONG - causes double offset)

            // When seeking to 50% of chapter
            viewModel.seekTo(percentProgress = 0.5)

            // Then transportControls.seekTo() should be called with 2091855ms (chapter-relative)
            // NOT with 5108145ms (absolute position)
            verify { mockTransportControls.seekTo(2091855L) }
        }

    @Test
    fun `seekTo sends chapter-relative position at 100 percent`() =
        runTest {
            // Chapter duration = 7200000 - 3016290 = 4183710ms
            // 100% of chapter = 4183710ms (chapter-relative)
            // Absolute position would be 7200000ms (WRONG - causes double offset)

            // When seeking to 100% of chapter
            viewModel.seekTo(percentProgress = 1.0)

            // Then transportControls.seekTo() should be called with 4183710ms (chapter-relative)
            // NOT with 7200000ms (absolute position)
            verify { mockTransportControls.seekTo(4183710L) }
        }

    @Test
    fun `seekTo sends chapter-relative position at 25 percent`() =
        runTest {
            // Chapter duration = 7200000 - 3016290 = 4183710ms
            // 25% of chapter = 4183710 * 0.25 = 1045927ms (chapter-relative)

            // When seeking to 25% of chapter
            viewModel.seekTo(percentProgress = 0.25)

            // Then transportControls.seekTo() should be called with 1045927ms (chapter-relative)
            verify { mockTransportControls.seekTo(1045927L) }
        }

    @Test
    fun `seekTo with different chapter offset`() =
        runTest {
            // Setup a different chapter with different offsets
            val differentChapter =
                Chapter(
                    title = "Chapter 3",
                    id = 3L,
                    index = 2L,
                    // Starts at 10 seconds
                    startTimeOffset = 10000000L,
                    // Ends at 15 seconds
                    endTimeOffset = 15000000L,
                    trackId = 1L,
                    bookId = 1L,
                )

            // Update the mock to return different chapter
            every { mockCurrentlyPlaying.chapter } returns MutableStateFlow(differentChapter)

            // Recreate ViewModel with new chapter
            viewModel =
                CurrentlyPlayingViewModel(
                    bookRepository = mockk(relaxed = true),
                    trackRepository = mockk(relaxed = true),
                    localBroadcastManager = mockk(relaxed = true),
                    mediaServiceConnection = mockMediaServiceConnection,
                    prefsRepo = mockk(relaxed = true),
                    plexConfig =
                        mockk(relaxed = true) {
                            every { isConnected } returns MutableLiveData(true)
                        },
                    currentlyPlaying = mockCurrentlyPlaying,
                    sharedPrefs = mockk(relaxed = true),
                )

            // Chapter duration = 15000000 - 10000000 = 5000000ms
            // 50% of chapter = 5000000 * 0.5 = 2500000ms (chapter-relative)

            // When seeking to 50% of chapter
            viewModel.seekTo(percentProgress = 0.5)

            // Then transportControls.seekTo() should be called with 2500000ms (chapter-relative)
            // NOT with 12500000ms (absolute position)
            verify { mockTransportControls.seekTo(2500000L) }
        }
}
