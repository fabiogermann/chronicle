package local.oss.chronicle.data.sources.plex

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tonyodev.fetch2.Fetch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.MediaItemTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Adoption of pre-v0.61.1 download files ("123.mp3") by the reconcile paths.
 *
 * Bug: up to v0.60.20 track ids were Int and downloads were saved as "<id>.<ext>".
 * TrackDatabase MIGRATION_6_7 (first shipped in v0.61.1) rewrote ids to "plex:<id>" in the
 * DB but nothing renamed the files on disk, so after the upgrade every downloaded book
 * silently became 'not downloaded': refreshTrackDownloadedStatus() parses "123.mp3" as
 * track id "123" (no DB row — orphan-delete branch is commented out, so the file survives)
 * and clears the cached flag of "plex:123" because "plex:123.mp3" does not exist. The book
 * then refuses to play offline ('Cannot play media- not connected to any server!') while
 * the files keep occupying storage.
 *
 * Fix: both reconcile paths adopt legacy files — rename "^\\d+\\.[^.]+$" to
 * "plex:<n>.<ext>" when the target does not exist — before reconciling flags.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LegacyDownloadAdoptionTest {
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
    private val trackId = "plex:123"

    @Before
    fun setUp() {
        fetch = mockk(relaxed = true)
        prefsRepo = mockk(relaxed = true)
        trackRepository = mockk(relaxed = true)
        bookRepository = mockk(relaxed = true)
        plexConfig = mockk(relaxed = true)
        context = mockk(relaxed = true)

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

    private fun track(cached: Boolean) =
        MediaItemTrack(
            id = trackId,
            parentKey = bookId,
            libraryId = "plex:library:1",
            title = "Ch 1",
            media = "/library/parts/1/1/file.mp3",
            cached = cached,
        )

    private fun book(isCached: Boolean) =
        Audiobook(
            id = bookId,
            libraryId = "plex:library:1",
            source = 1L,
            title = "Book",
            author = "A",
            duration = 1000L,
            isCached = isCached,
        )

    @Test
    fun `startup reconcile adopts legacy file and keeps cached flag`() =
        runTest {
            // Legacy download "123.mp3" for track "plex:123" whose flag is still cached=true.
            val legacyFile = tempFolder.newFile("123.mp3")
            legacyFile.writeBytes(ByteArray(10) { 1 })

            coEvery { trackRepository.getCachedTracks() } returns listOf(track(cached = true))
            coEvery { trackRepository.getBookIdForTrack(trackId) } returns bookId
            coEvery { trackRepository.getCachedTrackCountForBookAsync(bookId) } returns 1
            coEvery { trackRepository.getTrackCountForBookAsync(bookId) } returns 1
            coEvery { bookRepository.getAudiobookAsync(bookId) } returns book(isCached = true)

            manager().refreshTrackDownloadedStatus()

            // The file is renamed to the new-format name (FAT/exFAT-safe, no colon)...
            assertFalse("legacy file should be renamed away", legacyFile.exists())
            assertTrue(
                "adopted file should exist under the new name",
                File(tempFolder.root, "plex_123.mp3").exists(),
            )
            // ...and the track's cached flag is NOT cleared
            coVerify(exactly = 0) { trackRepository.updateCachedStatus(trackId, false) }
        }

    @Test
    fun `startup reconcile migrates colon-format file to FAT-safe name and keeps cached flag`() =
        runTest {
            // A download made by v0.61.1-v0.62.x ("plex:123.mp3"). The ':' is illegal on
            // FAT/exFAT, so the canonical on-disk scheme is now "plex_123.mp3". The reconcile
            // must rename it in place and keep the cached flag — no re-download.
            val colonFile = tempFolder.newFile("plex:123.mp3")
            colonFile.writeBytes(ByteArray(10) { 1 })

            coEvery { trackRepository.getCachedTracks() } returns listOf(track(cached = true))
            coEvery { trackRepository.getBookIdForTrack(trackId) } returns bookId
            coEvery { trackRepository.getCachedTrackCountForBookAsync(bookId) } returns 1
            coEvery { trackRepository.getTrackCountForBookAsync(bookId) } returns 1
            coEvery { bookRepository.getAudiobookAsync(bookId) } returns book(isCached = true)

            manager().refreshTrackDownloadedStatus()

            assertFalse("colon-format file should be renamed away", colonFile.exists())
            assertTrue(
                "migrated file should exist under the FAT-safe name",
                File(tempFolder.root, "plex_123.mp3").exists(),
            )
            coVerify(exactly = 0) { trackRepository.updateCachedStatus(trackId, false) }
        }

    @Test
    fun `startup reconcile re-flags track and book when legacy file exists but flags were already cleared`() =
        runTest {
            // The state most upgraded users are actually in: an earlier post-upgrade reconcile
            // already cleared the cached flags (it parsed "123.mp3" as track id "123", found no
            // DB row, and unflagged "plex:123"), while the legacy file itself survived (the
            // orphan-delete branch is commented out) and is still on disk under its old name.
            val legacyFile = tempFolder.newFile("123.mp3")
            legacyFile.writeBytes(ByteArray(10) { 1 })

            coEvery { trackRepository.getCachedTracks() } returns emptyList() // flag already cleared
            // The adopted track id exists in the DB (the update affects a row) so the
            // reconcile adds it to alteredTracks and recomputes the book flag below.
            coEvery { trackRepository.updateCachedStatus(trackId, true) } returns 1
            coEvery { trackRepository.getBookIdForTrack(trackId) } returns bookId
            coEvery { trackRepository.getCachedTrackCountForBookAsync(bookId) } returns 1
            coEvery { trackRepository.getTrackCountForBookAsync(bookId) } returns 1
            coEvery { bookRepository.getAudiobookAsync(bookId) } returns book(isCached = false)
            val updatedBook = slot<Audiobook>()
            coEvery { bookRepository.update(capture(updatedBook)) } returns Unit

            manager().refreshTrackDownloadedStatus()

            coVerify { trackRepository.updateCachedStatus(trackId, true) }
            coVerify { bookRepository.update(any()) }
            assertTrue("book should be re-flagged cached, was: $updatedBook", updatedBook.captured.isCached)
            assertFalse(legacyFile.exists())
            assertTrue(File(tempFolder.root, "plex_123.mp3").exists())
        }

    @Test
    fun `single-book reconcile adopts legacy file and re-flags track and book`() =
        runTest {
            val legacyFile = tempFolder.newFile("123.mp3")
            legacyFile.writeBytes(ByteArray(10) { 1 })

            coEvery { trackRepository.getTracksForAudiobookAsync(bookId) } returns listOf(track(cached = false))
            coEvery { bookRepository.getAudiobookAsync(bookId) } returns book(isCached = false)

            manager().refreshDownloadedStatusForBook(bookId)

            coVerify { trackRepository.updateCachedStatus(trackId, true) }
            coVerify {
                bookRepository.update(
                    match { it.id == bookId && it.isCached },
                )
            }
            assertFalse(legacyFile.exists())
            assertTrue(File(tempFolder.root, "plex_123.mp3").exists())
        }

    @Test
    fun `non-numeric and new-format names are left untouched`() =
        runTest {
            // A FAT-safe new-format file, a non-numeric name and a multi-dot numeric name must
            // not be renamed. Multi-dot names ("123.backup.mp3") are not legacy downloads (the
            // old scheme was "<id>.<ext>", single dot), so they stay as-is.
            val newFormat = tempFolder.newFile("plex_456.mp3")
            val nonNumeric = tempFolder.newFile("audiobook backup.mp3")
            val dotted = tempFolder.newFile("123.backup.mp3")

            coEvery { trackRepository.getCachedTracks() } returns emptyList()

            manager().refreshTrackDownloadedStatus()

            assertTrue(newFormat.exists())
            assertTrue(nonNumeric.exists())
            assertTrue(dotted.exists())
            // No rename of the multi-dot file into "plex_123.backup.mp3"
            assertFalse(File(tempFolder.root, "plex_123.backup.mp3").exists())
        }

    @Test
    fun `cached file name is FAT-safe and round-trips to the track id`() {
        // getCachedFileName must not contain ':' (illegal on FAT/exFAT removable storage), and
        // getTrackIdFromFileName must map it back to the "plex:<n>" track id used in the DB.
        val name = track(cached = false).getCachedFileName()
        assertFalse("cached file name must not contain ':'", name.contains(':'))
        assertTrue("expected plex_123.mp3, was: $name", name == "plex_123.mp3")
        assertTrue(
            MediaItemTrack.getTrackIdFromFileName("plex_123.mp3") == "plex:123",
        )
    }

    @Test
    fun `colon file is not migrated when the FAT-safe target already exists`() =
        runTest {
            // Both a colon-format file and an already-correct FAT-safe file exist. The colon one
            // must NOT overwrite the good target.
            val colonFile = tempFolder.newFile("plex:123.mp3")
            colonFile.writeBytes(ByteArray(10) { 1 })
            val target = tempFolder.newFile("plex_123.mp3")
            target.writeBytes(ByteArray(20) { 2 })

            coEvery { trackRepository.getCachedTracks() } returns listOf(track(cached = true))

            manager().refreshTrackDownloadedStatus()

            assertTrue("colon file must be left in place when target exists", colonFile.exists())
            assertTrue(target.exists())
            assertTrue("target content untouched", target.length() == 20L)
        }

    @Test
    fun `legacy file is not adopted when the new-format target already exists`() =
        runTest {
            // Defensive: never clobber an existing new-format file.
            val legacyFile = tempFolder.newFile("123.mp3")
            legacyFile.writeBytes(ByteArray(10) { 1 })
            val target = tempFolder.newFile("plex_123.mp3")
            target.writeBytes(ByteArray(20) { 2 })

            coEvery { trackRepository.getCachedTracks() } returns listOf(track(cached = true))

            manager().refreshTrackDownloadedStatus()

            assertTrue("legacy file must be left in place when target exists", legacyFile.exists())
            assertTrue(target.exists())
            assertTrue("target content untouched", target.length() == 20L)
        }
}
