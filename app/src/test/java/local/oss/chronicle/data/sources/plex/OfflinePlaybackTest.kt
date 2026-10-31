package local.oss.chronicle.data.sources.plex

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.data.sources.plex.model.PlexTranscodeDecision
import local.oss.chronicle.data.sources.plex.model.PlexTranscodeDecisionWrapper
import local.oss.chronicle.data.sources.plex.model.PlexTranscodeMedia
import local.oss.chronicle.data.sources.plex.model.PlexTranscodeMetadata
import local.oss.chronicle.data.sources.plex.model.PlexTranscodePart
import local.oss.chronicle.injection.components.AppComponent
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests for offline playback: downloaded tracks must never require server reachability.
 *
 * The user-visible symptom: users with fully downloaded books cannot play them when off-network.
 * The fix: if a track is cached locally, [PlaybackUrlResolver] must return the local file URI
 * immediately without calling the Plex decision endpoint, connection probe, or auth resolution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class OfflinePlaybackTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockServerConnectionResolver: ServerConnectionResolver
    private lateinit var mockScopedPlexServiceFactory: ScopedPlexServiceFactory
    private lateinit var mockAppComponent: AppComponent
    private lateinit var mockPrefsRepo: PrefsRepo
    private lateinit var playbackUrlResolver: PlaybackUrlResolver

    private val testLibraryId = "plex:library:1"
    private val testServerUrl = "https://server.plex.direct:32400"
    private val testToken = "test-token"

    @Before
    fun setup() {
        mockServerConnectionResolver = mockk()
        mockScopedPlexServiceFactory = mockk()

        // getTrackSource() resolves the cached-file path through the Injector singleton;
        // stub it so the cached branch works in a plain JVM test.
        mockPrefsRepo = mockk()
        every { mockPrefsRepo.cachedMediaDir } returns tempFolder.root
        mockAppComponent = mockk()
        every { mockAppComponent.prefsRepo() } returns mockPrefsRepo
        mockkObject(Injector.Companion)
        every { Injector.get() } returns mockAppComponent

        playbackUrlResolver = PlaybackUrlResolver(mockServerConnectionResolver, mockScopedPlexServiceFactory)
    }

    @After
    fun tearDown() {
        unmockkObject(Injector.Companion)
        MediaItemTrack.streamingUrlCache.clear()
    }

    // ===== Cached track short-circuit tests =====

    @Test
    fun `resolveStreamingUrl returns local file URI for cached track without touching network`() =
        runTest {
            // Given: A cached track (file exists on disk)
            val cachedTrack =
                MediaItemTrack(
                    id = "plex:cached1",
                    parentKey = "plex:book1",
                    libraryId = testLibraryId,
                    title = "Chapter 1",
                    duration = 1800000L,
                    media = "/library/parts/1/1/file.mp3",
                    cached = true,
                )

            // When: Resolving the streaming URL for a cached track
            val result = playbackUrlResolver.resolveStreamingUrl(cachedTrack)

            // Then: The result is the local file URI (from getTrackSource), NOT null
            assertThat(result).isNotNull()
            assertThat(result).startsWith("file:")
            assertThat(result).contains(cachedTrack.getCachedFileName())

            // And: The network layer is NEVER touched
            coVerify(exactly = 0) { mockServerConnectionResolver.resolve(any()) }
            verify(exactly = 0) { mockScopedPlexServiceFactory.getOrCreateService(any()) }
        }

    @Test
    fun `resolveStreamingUrl does not populate streamingUrlCache for cached tracks`() =
        runTest {
            // Given: A cached track
            val cachedTrack =
                MediaItemTrack(
                    id = "plex:cached2",
                    parentKey = "plex:book1",
                    libraryId = testLibraryId,
                    title = "Chapter 2",
                    duration = 1800000L,
                    media = "/library/parts/2/1/file.mp3",
                    cached = true,
                )

            // When: Resolving the streaming URL
            playbackUrlResolver.resolveStreamingUrl(cachedTrack)

            // Then: streamingUrlCache is NOT populated (cached tracks don't need server URLs)
            assertThat(MediaItemTrack.streamingUrlCache).doesNotContainKey(cachedTrack.id)

            // Cleanup
            MediaItemTrack.streamingUrlCache.clear()
        }

    @Test
    fun `preResolveUrls skips cached tracks and only resolves streaming tracks`() =
        runTest {
            // Given: A mix of cached and streaming tracks
            val cachedTrack =
                MediaItemTrack(
                    id = "plex:cached3",
                    parentKey = "plex:book1",
                    libraryId = testLibraryId,
                    title = "Cached Chapter",
                    duration = 1800000L,
                    media = "/library/parts/3/1/file.mp3",
                    cached = true,
                )
            val streamingTrack =
                MediaItemTrack(
                    id = "plex:stream1",
                    parentKey = "plex:book1",
                    libraryId = testLibraryId,
                    title = "Streaming Chapter",
                    duration = 1800000L,
                    media = "/library/parts/4/1/file.mp3",
                    cached = false,
                )

            // Setup mocks for the streaming track only
            val mockService = mockk<PlexMediaService>()
            val mockDecision = mockk<PlexTranscodeDecisionWrapper>()
            val mockContainer = mockk<PlexTranscodeDecision>()
            val mockMetadata = mockk<PlexTranscodeMetadata>()
            val mockMedia = mockk<PlexTranscodeMedia>()
            val mockPart = mockk<PlexTranscodePart>()
            every { mockPart.streamUrl } returns "/video/:/transcode/universal/start.m3u8?path=/library/parts/4/1/file.mp3"
            every { mockPart.selected } returns true
            every { mockMedia.parts } returns listOf(mockPart)
            every { mockMedia.selected } returns true
            every { mockMetadata.media } returns listOf(mockMedia)
            every { mockContainer.metadata } returns listOf(mockMetadata)
            every { mockContainer.generalDecisionCode } returns 1000
            every { mockContainer.directPlayDecisionCode } returns 1000
            every { mockContainer.transcodeDecisionCode } returns 0
            every { mockDecision.container } returns mockContainer
            coEvery { mockService.getPlaybackDecision(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockDecision
            every { mockScopedPlexServiceFactory.getOrCreateService(any()) } returns mockService
            coEvery { mockServerConnectionResolver.resolve(testLibraryId) } returns
                ServerConnection(testServerUrl, testToken)

            // When: Pre-resolving URLs for both tracks
            val result = playbackUrlResolver.preResolveUrls(listOf(cachedTrack, streamingTrack))

            // Then: Only the streaming track was resolved via network.
            // resolve() is called twice: once for the decision endpoint, once to cache the result.
            coVerify(exactly = 2) { mockServerConnectionResolver.resolve(testLibraryId) }
            verify(exactly = 1) { mockScopedPlexServiceFactory.getOrCreateService(any()) }

            // And: The result counts the streaming track as resolved
            assertThat(result.successCount).isEqualTo(1)
            assertThat(result.failedTracks).isEmpty()
            assertThat(result.totalTracks).isEqualTo(1) // Only streaming track counts

            // And: streamingUrlCache only has the streaming track
            assertThat(MediaItemTrack.streamingUrlCache).containsKey(streamingTrack.id)
            assertThat(MediaItemTrack.streamingUrlCache).doesNotContainKey(cachedTrack.id)

            // Cleanup
            MediaItemTrack.streamingUrlCache.clear()
        }

    @Test
    fun `preResolveUrls returns empty result when all tracks are cached`() =
        runTest {
            // Given: Only cached tracks
            val cachedTracks =
                listOf(
                    MediaItemTrack(
                        id = "plex:cached4",
                        parentKey = "plex:book1",
                        libraryId = testLibraryId,
                        title = "Chapter 1",
                        duration = 1800000L,
                        media = "/library/parts/4/1/file.mp3",
                        cached = true,
                    ),
                    MediaItemTrack(
                        id = "plex:cached5",
                        parentKey = "plex:book1",
                        libraryId = testLibraryId,
                        title = "Chapter 2",
                        duration = 1800000L,
                        media = "/library/parts/5/1/file.mp3",
                        cached = true,
                    ),
                )

            // When: Pre-resolving URLs
            val result = playbackUrlResolver.preResolveUrls(cachedTracks)

            // Then: No network calls at all
            coVerify(exactly = 0) { mockServerConnectionResolver.resolve(any()) }
            verify(exactly = 0) { mockScopedPlexServiceFactory.getOrCreateService(any()) }

            // And: Result indicates nothing to resolve
            assertThat(result.successCount).isEqualTo(0)
            assertThat(result.failedTracks).isEmpty()
            assertThat(result.totalTracks).isEqualTo(0)
        }

    @Test
    fun `resolveStreamingUrl still calls network for non-cached track`() =
        runTest {
            // Given: A non-cached (streaming) track
            val streamingTrack =
                MediaItemTrack(
                    id = "plex:stream2",
                    parentKey = "plex:book1",
                    libraryId = testLibraryId,
                    title = "Streaming Chapter",
                    duration = 1800000L,
                    media = "/library/parts/6/1/file.mp3",
                    cached = false,
                )

            // Setup mocks for successful resolution
            val mockService = mockk<PlexMediaService>()
            val mockDecision = mockk<PlexTranscodeDecisionWrapper>()
            val mockContainer = mockk<PlexTranscodeDecision>()
            val mockMetadata = mockk<PlexTranscodeMetadata>()
            val mockMedia = mockk<PlexTranscodeMedia>()
            val mockPart = mockk<PlexTranscodePart>()
            every { mockPart.streamUrl } returns "/video/:/transcode/universal/start.m3u8?path=/library/parts/6/1/file.mp3"
            every { mockPart.selected } returns true
            every { mockMedia.parts } returns listOf(mockPart)
            every { mockMedia.selected } returns true
            every { mockMetadata.media } returns listOf(mockMedia)
            every { mockContainer.metadata } returns listOf(mockMetadata)
            every { mockContainer.generalDecisionCode } returns 1000
            every { mockContainer.directPlayDecisionCode } returns 1000
            every { mockContainer.transcodeDecisionCode } returns 0
            every { mockDecision.container } returns mockContainer
            coEvery { mockService.getPlaybackDecision(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockDecision
            every { mockScopedPlexServiceFactory.getOrCreateService(any()) } returns mockService
            coEvery { mockServerConnectionResolver.resolve(testLibraryId) } returns
                ServerConnection(testServerUrl, testToken)

            // When: Resolving the streaming URL
            val result = playbackUrlResolver.resolveStreamingUrl(streamingTrack)

            // Then: Network was called and URL was resolved
            assertThat(result).isNotNull()
            assertThat(result).contains("https://server.plex.direct:32400")
            coVerify { mockServerConnectionResolver.resolve(testLibraryId) }
            verify { mockScopedPlexServiceFactory.getOrCreateService(any()) }

            // Cleanup
            MediaItemTrack.streamingUrlCache.clear()
        }
}
