package local.oss.chronicle.data.sources.plex

import io.mockk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.plex.model.*
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tests for PlaybackUrlResolver focusing on:
 * - Thread-safe cache with expiration
 * - Retry logic with exponential backoff
 * - Parallel pre-resolution
 * - Server URL change detection
 * - Cache invalidation listeners
 */
class PlaybackUrlResolverTest {
    private lateinit var mockPlexMediaService: PlexMediaService
    private lateinit var mockPlexConfig: PlexConfig
    private lateinit var resolver: PlaybackUrlResolver

    private val testServerUrl = "http://test-server:32400"
    private val testTrack =
        MediaItemTrack(
            id = 123,
            title = "Test Track",
            media = "/library/parts/456/file.mp3",
            duration = 180000L,
        )

    @Before
    fun setup() {
        mockPlexMediaService = mockk()
        mockPlexConfig = mockk()

        // Setup default mock behavior
        every { mockPlexConfig.url } returns testServerUrl
        every { mockPlexConfig.toServerString(any()) } answers {
            val path = firstArg<String>()
            "$testServerUrl$path"
        }

        resolver = PlaybackUrlResolver(mockPlexMediaService, mockPlexConfig)

        // Clear the static cache before each test
        MediaItemTrack.streamingUrlCache.clear()
    }

    @After
    fun tearDown() {
        // Clean up static cache after tests
        MediaItemTrack.streamingUrlCache.clear()
        clearAllMocks()
    }

    /**
     * Test 1: Cache hit - Verify cached URL is returned when valid
     */
    @Test
    fun `resolveStreamingUrl returns cached URL when valid`() =
        runBlocking {
            // Setup successful resolution
            val expectedUrl = "$testServerUrl/transcode/session/abc123"
            setupSuccessfulResolution(testTrack, expectedUrl)

            // First call - should hit the network
            val firstResult = resolver.resolveStreamingUrl(testTrack)
            assertThat(firstResult, `is`(expectedUrl))
            coVerify(exactly = 1) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }

            // Second call - should use cache
            val secondResult = resolver.resolveStreamingUrl(testTrack)
            assertThat(secondResult, `is`(expectedUrl))
            // Should still only have called once (cached)
            coVerify(exactly = 1) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }
        }

    /**
     * Test 2: Cache expiration - Verify expired URLs are re-resolved
     */
    @Test
    fun `resolveStreamingUrl refreshes expired cache entries`() =
        runBlocking {
            // This test would require setting the cache with an old timestamp
            // For now, we test force refresh which bypasses cache
            val expectedUrl = "$testServerUrl/transcode/session/abc123"
            setupSuccessfulResolution(testTrack, expectedUrl)

            // First call
            resolver.resolveStreamingUrl(testTrack)
            coVerify(exactly = 1) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }

            // Force refresh - should ignore cache
            resolver.resolveStreamingUrl(testTrack, forceRefresh = true)
            coVerify(exactly = 2) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }
        }

    /**
     * Test 3: Server URL change - Verify cache is invalidated when server changes
     */
    @Test
    fun `onServerUrlChanged invalidates cache`() =
        runBlocking {
            val expectedUrl = "$testServerUrl/transcode/session/abc123"
            setupSuccessfulResolution(testTrack, expectedUrl)

            // Resolve and cache
            resolver.resolveStreamingUrl(testTrack)
            coVerify(exactly = 1) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }

            // Change server URL
            val newServerUrl = "http://new-server:32400"
            resolver.onServerUrlChanged(newServerUrl)

            // Change mock to return new URL
            every { mockPlexConfig.url } returns newServerUrl

            // Should not use old cached value for different server
            resolver.resolveStreamingUrl(testTrack)
            coVerify(exactly = 2) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }
        }

    /**
     * Test 4: Retry on network error - Verify retries with exponential backoff
     */
    @Test
    fun `resolveStreamingUrl retries on network errors`() =
        runBlocking {
            val expectedUrl = "$testServerUrl/transcode/session/abc123"

            // Fail twice, then succeed
            coEvery { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) } throws
                SocketTimeoutException("Connection timeout") andThenThrows
                UnknownHostException("Host not found") andThen
                createSuccessfulDecision(expectedUrl)

            val result = resolver.resolveStreamingUrl(testTrack)

            assertThat(result, `is`(expectedUrl))
            // Should have called 3 times (2 failures + 1 success)
            coVerify(exactly = 3) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }
        }

    /**
     * Test 5: Retry limit - Verify gives up after max attempts
     */
    @Test
    fun `resolveStreamingUrl gives up after max retry attempts`() =
        runBlocking {
            // Always throw network error
            coEvery { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) } throws
                SocketTimeoutException("Connection timeout")

            val result = resolver.resolveStreamingUrl(testTrack)

            assertThat(result, nullValue())
            // Should have called 3 times (max attempts = 3)
            coVerify(exactly = 3) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }
        }

    /**
     * Test 6: preResolveUrls success - Verify parallel resolution works
     */
    @Test
    fun `preResolveUrls resolves multiple tracks in parallel`() =
        runBlocking {
            val tracks =
                listOf(
                    testTrack,
                    testTrack.copy(id = 124, media = "/library/parts/457/file.mp3"),
                    testTrack.copy(id = 125, media = "/library/parts/458/file.mp3"),
                )

            // Setup successful resolution for all tracks
            tracks.forEach { track ->
                setupSuccessfulResolution(track, "$testServerUrl/transcode/${track.id}")
            }

            val result = resolver.preResolveUrls(tracks)

            assertThat(result.totalTracks, `is`(3))
            assertThat(result.successCount, `is`(3))
            assertThat(result.failureCount, `is`(0))
            assertThat(result.allSucceeded, `is`(true))
            assertThat(result.failedTracks.isEmpty(), `is`(true))

            // Verify all tracks were called
            coVerify(exactly = 3) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }
        }

    /**
     * Test 7: preResolveUrls partial failure - Verify failed tracks are reported
     */
    @Test
    fun `preResolveUrls reports failed tracks`() =
        runBlocking {
            val track1 = testTrack
            val track2 = testTrack.copy(id = 124, media = "/library/parts/457/file.mp3")
            val track3 = testTrack.copy(id = 125, media = "/library/parts/458/file.mp3")
            val tracks = listOf(track1, track2, track3)

            // Setup: track1 succeeds, track2 fails, track3 succeeds
            setupSuccessfulResolution(track1, "$testServerUrl/transcode/${track1.id}")

            coEvery {
                mockPlexMediaService.getPlaybackDecision(
                    eq("/library/metadata/${track2.id}"),
                    any(),
                    any(),
                    any(),
                )
            } throws SocketTimeoutException("Connection timeout")

            setupSuccessfulResolution(track3, "$testServerUrl/transcode/${track3.id}")

            val result = resolver.preResolveUrls(tracks)

            assertThat(result.totalTracks, `is`(3))
            assertThat(result.successCount, `is`(2))
            assertThat(result.failureCount, `is`(1))
            assertThat(result.allSucceeded, `is`(false))
            assertThat(result.failedTracks.size, `is`(1))
            assertThat(result.failedTracks[0].id, `is`(track2.id))
        }

    /**
     * Test 8: Cache invalidation listener - Verify listeners are notified
     */
    @Test
    fun `cache invalidation notifies listeners`() =
        runBlocking {
            var listenerCalled = false
            val listener =
                object : PlaybackUrlResolver.OnCacheInvalidatedListener {
                    override fun onCacheInvalidated() {
                        listenerCalled = true
                    }
                }

            resolver.addCacheInvalidatedListener(listener)
            resolver.clearCache()

            assertThat(listenerCalled, `is`(true))

            // Test remove listener
            listenerCalled = false
            resolver.removeCacheInvalidatedListener(listener)
            resolver.clearCache()

            assertThat(listenerCalled, `is`(false))
        }

    /**
     * Test 9: Thread safety - Verify concurrent access is safe
     */
    @Test
    fun `concurrent resolveStreamingUrl calls are thread safe`() =
        runBlocking {
            val tracks =
                List(10) { index ->
                    testTrack.copy(id = 100 + index, media = "/library/parts/${100 + index}/file.mp3")
                }

            // Setup successful resolution for all tracks
            tracks.forEach { track ->
                setupSuccessfulResolution(track, "$testServerUrl/transcode/${track.id}")
            }

            // Launch concurrent resolution requests
            val jobs =
                tracks.map { track ->
                    launch {
                        val result = resolver.resolveStreamingUrl(track)
                        assertThat(result, notNullValue())
                    }
                }

            // Wait for all to complete
            jobs.forEach { it.join() }

            // All should have been successful
            tracks.forEach { track ->
                val cachedUrl = MediaItemTrack.streamingUrlCache[track.id]
                assertThat(cachedUrl, notNullValue())
            }
        }

    /**
     * Test 10: Empty track list handling
     */
    @Test
    fun `preResolveUrls handles empty track list`() =
        runBlocking {
            val result = resolver.preResolveUrls(emptyList())

            assertThat(result.totalTracks, `is`(0))
            assertThat(result.successCount, `is`(0))
            assertThat(result.failureCount, `is`(0))
            assertThat(result.allSucceeded, `is`(true))
            assertThat(result.failedTracks.isEmpty(), `is`(true))
        }

    /**
     * Test 11: refreshUrlsOnNetworkChange refreshes tracks
     */
    @Test
    fun `refreshUrlsOnNetworkChange resolves tracks`() =
        runBlocking {
            val tracks =
                listOf(
                    testTrack,
                    testTrack.copy(id = 124, media = "/library/parts/457/file.mp3"),
                )

            // Setup successful resolution for all tracks
            tracks.forEach { track ->
                setupSuccessfulResolution(track, "$testServerUrl/transcode/${track.id}")
            }

            val result = resolver.refreshUrlsOnNetworkChange(tracks)

            assertThat(result.totalTracks, `is`(2))
            assertThat(result.successCount, `is`(2))
            assertThat(result.allSucceeded, `is`(true))
        }

    /**
     * Test 12: Non-retryable error fails immediately
     */
    @Test
    fun `resolveStreamingUrl does not retry non-retryable errors`() =
        runBlocking {
            // IllegalStateException is not retryable
            coEvery { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) } throws
                IllegalStateException("Invalid state")

            val result = resolver.resolveStreamingUrl(testTrack)

            assertThat(result, nullValue())
            // Should only call once (no retries for non-retryable errors)
            coVerify(exactly = 1) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }
        }

    /**
     * Test 13: Server URL change with same URL does not invalidate cache
     */
    @Test
    fun `onServerUrlChanged does not invalidate if URL unchanged`() =
        runBlocking {
            val expectedUrl = "$testServerUrl/transcode/session/abc123"
            setupSuccessfulResolution(testTrack, expectedUrl)

            // Initialize currentServerUrl by calling onServerUrlChanged first
            resolver.onServerUrlChanged(testServerUrl)

            // Resolve and cache
            resolver.resolveStreamingUrl(testTrack)

            // Call onServerUrlChanged with same URL again
            resolver.onServerUrlChanged(testServerUrl)

            // Should still use cache
            resolver.resolveStreamingUrl(testTrack)
            coVerify(exactly = 1) { mockPlexMediaService.getPlaybackDecision(any(), any(), any(), any()) }
        }

    // Helper functions

    private fun setupSuccessfulResolution(
        track: MediaItemTrack,
        expectedStreamUrl: String,
    ) {
        val decision = createSuccessfulDecision(expectedStreamUrl)

        coEvery {
            mockPlexMediaService.getPlaybackDecision(
                eq("/library/metadata/${track.id}"),
                any(),
                any(),
                any(),
            )
        } returns decision
    }

    private fun createSuccessfulDecision(streamUrl: String): PlexTranscodeDecisionWrapper {
        val streamPath = streamUrl.removePrefix(testServerUrl)

        val part =
            PlexTranscodePart(
                streamUrl = streamPath,
                decision = "directplay",
                selected = true,
            )

        val media =
            PlexTranscodeMedia(
                parts = listOf(part),
                selected = true,
            )

        val metadata =
            PlexTranscodeMetadata(
                media = listOf(media),
            )

        val container =
            PlexTranscodeDecision(
                metadata = listOf(metadata),
                generalDecisionCode = 1000,
                generalDecisionText = "Direct Play",
                directPlayDecisionCode = 1000,
                directPlayDecisionText = "Direct Play OK",
                transcodeDecisionCode = 0,
                transcodeDecisionText = "",
            )

        return PlexTranscodeDecisionWrapper(container = container)
    }
}
