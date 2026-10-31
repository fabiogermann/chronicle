package local.oss.chronicle.data.sources.plex

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PLAYING
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Write-side progress reporting tests: verify the REAL outgoing HTTP request that
 * [PlexProgressReporter.reportProgress] produces.
 *
 * Instead of mocking the Retrofit interface, these tests stand up the real
 * Retrofit/OkHttp stack built by [ScopedPlexServiceFactory] (including the real
 * production header interceptor) and append an in-process capturing interceptor
 * that records the outgoing request and returns a canned 200. The captured request
 * is exactly what Retrofit + the production interceptors would put on the wire.
 *
 * Reddit thread 1u7817s (cross-device resume) depends on the client persisting
 * progress server-side via GET /:/timeline with the parameters Plex needs:
 * ratingKey, key, state, time, duration, plus X-Plex-Token and a stable
 * X-Plex-Client-Identifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ProgressReportingHttpTest {
    private val recordedRequests = CopyOnWriteArrayList<Request>()

    /** Records the request and replies 200 with an empty JSON body, never touching the network. */
    private val capturingInterceptor =
        Interceptor { chain ->
            val request = chain.request()
            recordedRequests.add(request)
            Response
                .Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody(null))
                .build()
        }

    private lateinit var reporter: PlexProgressReporter

    private val serverUrl = "http://plex.test:32400"
    private val authToken = "test-plex-token"
    private val clientUuid = "chronicle-client-uuid-123"

    @Before
    fun setup() {
        recordedRequests.clear()

        val plexPrefsRepo =
            mockk<PlexPrefsRepo> {
                every { uuid } returns clientUuid
            }
        val plexConfig =
            mockk<PlexConfig> {
                every { sessionIdentifier } returns "session-abc"
            }
        val moshi =
            Moshi
                .Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

        // Real factory: real Retrofit, real production header interceptor — plus the
        // capturing interceptor appended so nothing leaves the process.
        val factory =
            object : ScopedPlexServiceFactory(moshi, plexPrefsRepo, plexConfig) {
                override fun createClient(authToken: String): OkHttpClient =
                    super
                        .createClient(authToken)
                        .newBuilder()
                        .addInterceptor(capturingInterceptor)
                        .build()
            }

        reporter =
            PlexProgressReporter(
                plexConfig = plexConfig,
                plexPrefsRepo = plexPrefsRepo,
                serverConnectionResolver = mockk(),
                libraryRepository = mockk<LibraryRepository>(),
                scopedPlexServiceFactory = factory,
            )
    }

    private fun track(
        id: String = "plex:12345",
        duration: Long = 3_600_000L,
        playQueueItemID: Long = -1L,
    ) = MediaItemTrack(
        id = id,
        parentKey = "plex:678",
        libraryId = "plex:library:1",
        title = "Chapter 5",
        duration = duration,
        media = "/library/parts/12345/1/file.m4b",
        playQueueItemID = playQueueItemID,
    )

    private fun book(id: String = "plex:678") =
        Audiobook(
            id = id,
            libraryId = "plex:library:1",
            source = 1L,
            title = "Test Book",
            duration = 7_200_000L,
        )

    @Test
    fun `reportProgress sends GET timeline with parameters Plex needs to persist viewOffset`() =
        runTest {
            // Given: a multi-file audiobook (book = Plex album, tracks = files); we are
            // 90 seconds into track plex:12345 which is 1 hour long
            val track = track()
            val connection = ServerConnection(serverUrl, authToken)

            // When: progress is reported
            reporter.reportProgress(
                connection = connection,
                track = track,
                book = book(),
                tracks = listOf(track),
                trackProgress = 90_000L,
                bookProgress = 5_000_000L,
                playbackState = PLEX_STATE_PLAYING,
            )

            // Then: exactly one timeline request went out, with the right shape
            assertThat(recordedRequests).hasSize(1)
            val request = recordedRequests.single()
            val url = request.url

            assertThat(request.method).isEqualTo("GET")
            assertThat(url.encodedPath).isEqualTo("/:/timeline")

            // Progress is reported against the TRACK ratingKey with a track-relative
            // offset — multi-file books must not report book-level offsets
            assertThat(url.queryParameter("ratingKey")).isEqualTo("12345")
            assertThat(url.queryParameter("time")).isEqualTo("90000")
            assertThat(url.queryParameter("key")).isEqualTo("/library/metadata/12345")
            assertThat(url.queryParameter("duration")).isEqualTo("3600000")
            assertThat(url.queryParameter("state")).isEqualTo("playing")
            assertThat(url.queryParameter("identifier")).isEqualTo("com.plexapp.plugins.library")

            // Required headers for the server to persist and attribute the update
            assertThat(request.header("X-Plex-Token")).isEqualTo(authToken)
            assertThat(request.header("X-Plex-Client-Identifier")).isEqualTo(clientUuid)
            assertThat(request.header("X-Plex-Provides")).isEqualTo("player")

            // Session visibility (Reddit 1r9ce5f: Tautulli / Plex dashboard must be able to
            // show the active session): session id header + playbackTime/playQueueItemID
            assertThat(request.header("X-Plex-Session-Identifier")).isEqualTo("session-abc")
            assertThat(url.queryParameter("playbackTime")).isEqualTo("90000")

            // Product/device headers Plex uses to label the session
            assertThat(request.header("X-Plex-Product")).isNotEmpty()
            assertThat(request.header("X-Plex-Platform")).isEqualTo("Android")
            assertThat(request.header("X-Plex-Device")).isNotEmpty()
            assertThat(request.header("X-Plex-Client-Name")).isNotEmpty()

            // The auth token must travel ONLY in the header, never in the URL where it
            // would be logged by the server, proxies, and Tautulli
            assertThat(url.queryParameter("X-Plex-Token")).isNull()
            assertThat(url.queryParameter("token")).isNull()
            assertThat(url.encodedQuery).doesNotContain(authToken)
        }

    @Test
    fun `reportProgress sends the cached playQueueItemID when available`() =
        runTest {
            // Given: a play queue was created (startMediaSession) and its playQueueItemID
            // was cached for this track — Plex correlates timeline updates into dashboard
            // sessions via this ID (see PlexProgressReporter.playQueueItemCache)
            val track = track(playQueueItemID = 4242L)
            val connection = ServerConnection(serverUrl, authToken)

            reporter.reportProgress(
                connection = connection,
                track = track,
                book = book(),
                tracks = listOf(track),
                trackProgress = 90_000L,
                bookProgress = 5_000_000L,
                playbackState = PLEX_STATE_PLAYING,
            )

            assertThat(recordedRequests.single().url.queryParameter("playQueueItemID"))
                .isEqualTo("4242")
        }

    @Test
    fun `reportProgress reports each track against its own ratingKey`() =
        runTest {
            // Given: the second file of a multi-file book
            val track = track(id = "plex:12346", duration = 1_800_000L)
            val connection = ServerConnection(serverUrl, authToken)

            reporter.reportProgress(
                connection = connection,
                track = track,
                book = book(),
                tracks = listOf(track),
                trackProgress = 42_000L,
                bookProgress = 3_700_000L,
                playbackState = PLEX_STATE_PLAYING,
            )

            val url = recordedRequests.single().url
            assertThat(url.queryParameter("ratingKey")).isEqualTo("12346")
            assertThat(url.queryParameter("time")).isEqualTo("42000")
            assertThat(url.queryParameter("duration")).isEqualTo("1800000")
        }

    @Test
    fun `reportProgress scrobbles the track when it finishes`() =
        runTest {
            // Given: track at its end (within 1s of duration)
            val track = track()
            val connection = ServerConnection(serverUrl, authToken)

            reporter.reportProgress(
                connection = connection,
                track = track,
                book = book(),
                tracks = listOf(track),
                trackProgress = 3_599_500L,
                bookProgress = 5_000_000L,
                playbackState = PLEX_STATE_PLAYING,
            )

            // Then: a /:/scrobble for the track ratingKey followed the timeline update
            val scrobble = recordedRequests.firstOrNull { it.url.encodedPath == "/:/scrobble" }
            assertThat(scrobble).isNotNull()
            assertThat(scrobble!!.url.queryParameter("key")).isEqualTo("12345")
            assertThat(scrobble.url.queryParameter("identifier"))
                .isEqualTo("com.plexapp.plugins.library")
        }
}
