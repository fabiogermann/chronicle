package local.oss.chronicle.data.sources.plex

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tonyodev.fetch2.Fetch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.MediaItemTrack
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the filesystem-vs-database reconcile (phantom "downloaded" books).
 *
 * Bug: a track file deleted after the startup reconcile (OS cache cleaner, storage
 * pressure) leaves `cached=true` in the DB, so the book shows as downloaded and the
 * download button prompts "Uncache?" instead of re-downloading — the reported
 * dead-end. Running [CachedFileManager.refreshTrackDownloadedStatus] must clear the
 * stale flag on both tracks and their parent book, so re-download is possible again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CachedFileManagerReconcileTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fetch: Fetch
    private lateinit var prefsRepo: PrefsRepo
    private lateinit var trackRepository: ITrackRepository
    private lateinit var bookRepository: IBookRepository
    private lateinit var plexConfig: PlexConfig
    private lateinit var context: Context

    private val bookId = "plex:100"
    private val trackId = "plex:t1"

    @Before
    fun setUp() {
        fetch = mockk(relaxed = true)
        prefsRepo = mockk(relaxed = true)
        trackRepository = mockk(relaxed = true)
        bookRepository = mockk(relaxed = true)
        plexConfig = mockk(relaxed = true)
        context = mockk(relaxed = true)

        // Point the cache dir at a real (empty) temp dir — the track file is NOT on disk.
        every { prefsRepo.cachedMediaDir } returns tempFolder.root
    }

    private fun manager(): CachedFileManager =
        CachedFileManager(
            fetch = fetch,
            prefsRepo = prefsRepo,
            trackRepository = trackRepository,
            bookRepository = bookRepository,
            plexConfig = plexConfig,
            applicationContext = context,
        )

    @Test
    fun `book marked cached in DB but file missing on disk reconciles to not cached`() =
        runTest {
            val track =
                MediaItemTrack(
                    id = trackId,
                    parentKey = bookId,
                    libraryId = "plex:library:1",
                    title = "Ch 1",
                    media = "/library/parts/1/1/file.mp3",
                    cached = true, // stale — file was deleted
                )
            val book =
                Audiobook(
                    id = bookId,
                    libraryId = "plex:library:1",
                    source = 1L,
                    title = "Book",
                    author = "A",
                    duration = 1000L,
                    isCached = true, // stale
                )

            coEvery { trackRepository.getCachedTracks() } returns listOf(track)
            coEvery { trackRepository.getBookIdForTrack(trackId) } returns bookId
            coEvery { trackRepository.getCachedTrackCountForBookAsync(bookId) } returns 0
            coEvery { trackRepository.getTrackCountForBookAsync(bookId) } returns 1
            coEvery { bookRepository.getAudiobookAsync(bookId) } returns book

            manager().refreshTrackDownloadedStatus()

            // Track flag cleared
            coVerify { trackRepository.updateCachedStatus(trackId, false) }
            // Book reconciled: isCached recomputed from track counts and persisted
            coVerify {
                bookRepository.update(
                    match { it.id == bookId && !it.isCached },
                )
            }
        }

    @Test
    fun `reconcile leaves genuinely-cached books alone`() =
        runTest {
            // File exists on disk matching the track's cached filename
            val track =
                MediaItemTrack(
                    id = trackId,
                    parentKey = bookId,
                    libraryId = "plex:library:1",
                    title = "Ch 1",
                    media = "/library/parts/1/1/file.mp3",
                    cached = true,
                )
            tempFolder.newFile(track.getCachedFileName()).writeBytes(ByteArray(10) { 1 })

            coEvery { trackRepository.getCachedTracks() } returns listOf(track)

            manager().refreshTrackDownloadedStatus()

            // No status flip to false
            coVerify(exactly = 0) { trackRepository.updateCachedStatus(trackId, false) }
        }

    // -------------------------------------------------------------------------
    // Single-book reconcile — runs when the book-details page loads so a stale
    // flag is cleared without an app restart (the re-download dead-end).
    // -------------------------------------------------------------------------

    @Test
    fun `refreshDownloadedStatusForBook clears stale flag when file is missing`() =
        runTest {
            val track =
                MediaItemTrack(
                    id = trackId,
                    parentKey = bookId,
                    libraryId = "plex:library:1",
                    title = "Ch 1",
                    media = "/library/parts/1/1/file.mp3",
                    cached = true, // stale — file gone
                )
            val book =
                Audiobook(
                    id = bookId,
                    libraryId = "plex:library:1",
                    source = 1L,
                    title = "Book",
                    author = "A",
                    duration = 1000L,
                    isCached = true, // stale
                )

            coEvery { trackRepository.getTracksForAudiobookAsync(bookId) } returns listOf(track)
            coEvery { bookRepository.getAudiobookAsync(bookId) } returns book

            manager().refreshDownloadedStatusForBook(bookId)

            coVerify { trackRepository.updateCachedStatus(trackId, false) }
            coVerify {
                bookRepository.update(
                    match { it.id == bookId && !it.isCached },
                )
            }
        }

    @Test
    fun `refreshDownloadedStatusForBook restores cached flag when file exists but flag was cleared`() =
        runTest {
            // File on disk (e.g. downloaded OK) but DB flag lost (failed-download listener
            // never updated it) — reconcile should mark it cached again so the book
            // reappears in the offline list.
            val track =
                MediaItemTrack(
                    id = trackId,
                    parentKey = bookId,
                    libraryId = "plex:library:1",
                    title = "Ch 1",
                    media = "/library/parts/1/1/file.mp3",
                    cached = false, // stale — file IS on disk
                )
            tempFolder.newFile(track.getCachedFileName()).writeBytes(ByteArray(10) { 1 })
            val book =
                Audiobook(
                    id = bookId,
                    libraryId = "plex:library:1",
                    source = 1L,
                    title = "Book",
                    author = "A",
                    duration = 1000L,
                    isCached = false, // stale
                )

            coEvery { trackRepository.getTracksForAudiobookAsync(bookId) } returns listOf(track)
            coEvery { bookRepository.getAudiobookAsync(bookId) } returns book

            manager().refreshDownloadedStatusForBook(bookId)

            coVerify { trackRepository.updateCachedStatus(trackId, true) }
            coVerify {
                bookRepository.update(
                    match { it.id == bookId && it.isCached },
                )
            }
        }
}
