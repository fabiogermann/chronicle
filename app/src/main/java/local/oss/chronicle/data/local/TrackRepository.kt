package local.oss.chronicle.data.local

import androidx.lifecycle.LiveData
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import local.oss.chronicle.data.sources.MediaSource
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.model.MediaType
import local.oss.chronicle.data.sources.plex.model.asTrackList
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A repository abstracting all [MediaItemTrack]s from all [MediaSource]s */
interface ITrackRepository {
    /**
     * Load all tracks from the network corresponding to the book with id == [bookId], add them to
     * the local [TrackDatabase], and return the merged results.
     *
     * If [forceUseNetwork] is true, override local copy with the network copy where it makes sense
     */
    suspend fun loadTracksForAudiobook(
        bookId: String,
        forceUseNetwork: Boolean = false,
    ): Result<List<MediaItemTrack>, Throwable>

    /**
     * Update the value of [MediaItemTrack.cached] to [isCached] for a [MediaItemTrack] with
     * [MediaItemTrack.id] == [trackId] in the [TrackDatabase]
     */
    suspend fun updateCachedStatus(
        trackId: String,
        isCached: Boolean,
    ): Int

    /** Return all tracks in the [TrackDatabase]  */
    fun getAllTracks(): LiveData<List<MediaItemTrack>>

    suspend fun getAllTracksAsync(): List<MediaItemTrack>

    /**
     * Return a [LiveData<List<MediaItemTrack>>] containing all [MediaItemTrack]s where
     * [MediaItemTrack.parentKey] == [bookId]
     */
    fun getTracksForAudiobook(bookId: String): LiveData<List<MediaItemTrack>>

    suspend fun getTracksForAudiobookAsync(bookId: String): List<MediaItemTrack>

    /** Update the value of [MediaItemTrack.progress] == [trackProgress] and
     * [MediaItemTrack.lastViewedAt] == [lastViewedAt] for the track where
     * [MediaItemTrack.id] == [trackId]
     */
    suspend fun updateTrackProgress(
        trackProgress: Long,
        trackId: String,
        lastViewedAt: Long,
    )

    /**
     * Return a [MediaItemTrack] where [MediaItemTrack.id] == [id], or null if no such
     * [MediaItemTrack] exists
     */
    suspend fun getTrackAsync(id: String): MediaItemTrack?

    /**
     * Return the [MediaItemTrack.parentKey] for a [MediaItemTrack] where [MediaItemTrack.id] == [trackId]
     */
    suspend fun getBookIdForTrack(trackId: String): String

    /** Remove all [MediaItemTrack] from the [TrackDatabase] */
    suspend fun clear()

    /**
     * Return a [List<MediaItemTrack>] containing all [MediaItemTrack] where [MediaItemTrack.cached] == true
     */
    suspend fun getCachedTracks(): List<MediaItemTrack>

    /** Returns the number of [MediaItemTrack] where [MediaItemTrack.parentKey] == [bookId] */
    suspend fun getTrackCountForBookAsync(bookId: String): Int

    /**
     * Returns the number of [MediaItemTrack] where [MediaItemTrack.parentKey] == [bookId] and
     * [MediaItemTrack.cached] == true
     */
    suspend fun getCachedTrackCountForBookAsync(bookId: String): Int

    /** Sets [MediaItemTrack.cached] to false for all [MediaItemTrack] in [TrackDatabase] */
    suspend fun uncacheAll()

    /**
     * Loads all [MediaItemTrack]s available on the server into the load DB and returns a [List]
     * of them
     */
    suspend fun loadAllTracksAsync(): List<MediaItemTrack>

    /** Fetches all [MediaType.TRACK]s from the server, updates the local db */
    suspend fun refreshData()

    /** Retrieves a track from the local db with [title] as a substring of [MediaItemTrack.title] */
    suspend fun findTrackByTitle(title: String): MediaItemTrack?

    /**
     * Pulls all [MediaItemTrack] with [MediaItemTrack.album] == [bookId] from the network
     *
     * @return a [List<MediaItemTrack>] reflecting tracks returned by the server
     */
    suspend fun fetchNetworkTracksForBook(bookId: String): List<MediaItemTrack>

    /**
     * Loads in new track data from the network, updates the DB and returns the new track data
     */
    suspend fun syncTracksInBook(
        bookId: String,
        forceUseNetwork: Boolean = false,
    ): List<MediaItemTrack>

    /** Marks tracks in book as watched by setting the progress in all to 0 */
    suspend fun markTracksInBookAsWatched(bookId: String)

    companion object {
        /**
         * The value representing the [MediaItemTrack.id] for any track which does not exist in the
         * [TrackDatabase]
         */
        const val TRACK_NOT_FOUND: String = "track-not-found"
    }

    suspend fun refreshDataPaginated()
}

@Singleton
class TrackRepository
    @Inject
    constructor(
        private val trackDao: TrackDao,
        private val prefsRepo: PrefsRepo,
        private val plexMediaService: PlexMediaService,
        private val plexPrefs: PlexPrefsRepo,
    ) : ITrackRepository {
        @Throws(Throwable::class)
        override suspend fun refreshData() {
            if (prefsRepo.offlineMode) {
                return
            }
            loadAllTracksAsync()
        }

        override suspend fun refreshDataPaginated() {
            if (prefsRepo.offlineMode) {
                return
            }
            // TODO: this could possibly exhaust memory, ought have people w/ big libraries try it out
            val networkTracks = mutableListOf<MediaItemTrack>()
            withContext(Dispatchers.IO) {
                try {
                    val library = plexPrefs.library ?: return@withContext
                    val libraryIdNumeric = library.id
                    val libraryId = "plex:library:$libraryIdNumeric"
                    var tracksLeft = 1L
                    // Maximum number of pages of data we fetch. Failsafe in case of bad data from the
                    // server since we don't want infinite loops. This limits us to a maximum 1,000,000
                    // tracks for now
                    val maxIterations = 1000
                    var i = 0
                    while (tracksLeft > 0 && i < maxIterations) {
                        val response =
                            plexMediaService
                                .retrieveTracksPaginated(libraryIdNumeric, i * 100)
                                .plexMediaContainer
                        tracksLeft = response.totalSize - (response.offset + response.size)
                        networkTracks.addAll(response.asTrackList(libraryId))
                        i++
                    }
                } catch (t: Throwable) {
                    Timber.e("Failed to load tracks: $t")
                }

                val localTracks = trackDao.getAllTracksAsync()
                val mergedTracks = mergeNetworkTracks(networkTracks, localTracks)
                trackDao.insertAll(mergedTracks)
            }
        }

        override suspend fun findTrackByTitle(title: String): MediaItemTrack? {
            return withContext(Dispatchers.IO) {
                trackDao.findTrackByTitle(title)
            }
        }

        override suspend fun fetchNetworkTracksForBook(bookId: String): List<MediaItemTrack> {
            return withContext(Dispatchers.IO) {
                // Extract numeric ID from "plex:{id}" format
                val numericId = bookId.removePrefix("plex:").toIntOrNull() ?: return@withContext emptyList()
                val library = plexPrefs.library ?: return@withContext emptyList()
                val libraryId = "plex:library:${library.id}"
                return@withContext plexMediaService.retrieveTracksForAlbum(numericId)
                    .plexMediaContainer
                    .asTrackList(libraryId)
            }
        }

        override suspend fun syncTracksInBook(
            bookId: String,
            forceUseNetwork: Boolean,
        ): List<MediaItemTrack> =
            withContext(Dispatchers.IO) {
                val networkTracks = fetchNetworkTracksForBook(bookId)
                // Re-read local tracks right before merge to minimize race condition window
                // where ProgressUpdater might update progress between initial read and insertAll
                val localTracks = getTracksForAudiobookAsync(bookId)
                val mergedTracks =
                    mergeNetworkTracks(
                        networkTracks = networkTracks,
                        localTracks = localTracks,
                        forcePreferNetwork = forceUseNetwork,
                    )
                trackDao.insertAll(mergedTracks)
                mergedTracks
            }

        override suspend fun markTracksInBookAsWatched(bookId: String) {
            withContext(Dispatchers.IO) {
                val tracks = getTracksForAudiobookAsync(bookId)
                val currentTime = System.currentTimeMillis()
                val updatedTracks =
                    tracks.map {
                        it.copy(progress = 0L, lastViewedAt = currentTime)
                    }
                trackDao.insertAll(updatedTracks)
            }
        }

        override suspend fun loadTracksForAudiobook(
            bookId: String,
            forceUseNetwork: Boolean,
        ): Result<List<MediaItemTrack>, Throwable> {
            return withContext(Dispatchers.IO) {
                val localTracks = trackDao.getAllTracksAsync()
                try {
                    // Extract numeric ID from "plex:{id}" format
                    val numericId = bookId.removePrefix("plex:").toIntOrNull() ?: return@withContext Err(IllegalArgumentException("Invalid book ID format: $bookId"))
                    val library = plexPrefs.library ?: return@withContext Err(IllegalStateException("No library selected"))
                    val libraryId = "plex:library:${library.id}"
                    val networkTracks =
                        plexMediaService.retrieveTracksForAlbum(numericId)
                            .plexMediaContainer
                            .asTrackList(libraryId)
                    val mergedTracks =
                        mergeNetworkTracks(
                            networkTracks = networkTracks,
                            localTracks = localTracks,
                            forcePreferNetwork = forceUseNetwork,
                        )
                    trackDao.insertAll(mergedTracks)
                    Ok(mergedTracks)
                } catch (t: Throwable) {
                    Err(t)
                }
            }
        }

        override suspend fun updateCachedStatus(
            trackId: String,
            isCached: Boolean,
        ): Int {
            return withContext(Dispatchers.IO) {
                trackDao.updateCachedStatus(trackId, isCached)
            }
        }

        override fun getAllTracks(): LiveData<List<MediaItemTrack>> {
            return trackDao.getAllTracks()
        }

        override suspend fun getAllTracksAsync(): List<MediaItemTrack> {
            return withContext(Dispatchers.IO) {
                trackDao.getAllTracksAsync()
            }
        }

        override fun getTracksForAudiobook(bookId: String): LiveData<List<MediaItemTrack>> {
            return trackDao.getTracksForAudiobook(bookId, prefsRepo.offlineMode)
        }

        override suspend fun getTracksForAudiobookAsync(bookId: String): List<MediaItemTrack> {
            return withContext(Dispatchers.IO) {
                trackDao.getTracksForAudiobookAsync(bookId, prefsRepo.offlineMode)
            }
        }

        override suspend fun updateTrackProgress(
            trackProgress: Long,
            trackId: String,
            lastViewedAt: Long,
        ) {
            withContext(Dispatchers.IO) {
                trackDao.updateProgress(
                    trackProgress = trackProgress,
                    trackId = trackId,
                    lastViewedAt = lastViewedAt,
                )
            }
        }

        override suspend fun getTrackAsync(id: String): MediaItemTrack? {
            return trackDao.getTrackAsync(id)
        }

        override suspend fun getBookIdForTrack(trackId: String): String {
            return withContext(Dispatchers.IO) {
                val track = trackDao.getTrackAsync(trackId)
                Timber.i("Track is $track")
                val parentKey = track?.parentKey
                parentKey ?: NO_AUDIOBOOK_FOUND_ID
            }
        }

        override suspend fun clear() {
            withContext(Dispatchers.IO) {
                trackDao.clear()
            }
        }

        override suspend fun getCachedTracks(): List<MediaItemTrack> {
            return withContext(Dispatchers.IO) {
                trackDao.getCachedTracksAsync(isCached = true)
            }
        }

        override suspend fun getTrackCountForBookAsync(bookId: String): Int {
            return withContext(Dispatchers.IO) {
                trackDao.getTrackCountForAudiobookAsync(bookId)
            }
        }

        override suspend fun getCachedTrackCountForBookAsync(bookId: String): Int {
            return withContext(Dispatchers.IO) {
                trackDao.getCachedTrackCountForBookAsync(bookId)
            }
        }

        override suspend fun uncacheAll() {
            withContext(Dispatchers.IO) {
                trackDao.uncacheAll()
            }
        }

        override suspend fun loadAllTracksAsync() =
            withContext(Dispatchers.IO) {
                val localTracks = trackDao.getAllTracksAsync()
                try {
                    val library = Injector.get().plexPrefs().library!!
                    val libraryId = "plex:library:${library.id}"
                    val networkTracks =
                        plexMediaService.retrieveAllTracksInLibrary(library.id)
                            .plexMediaContainer.asTrackList(libraryId)
                    val mergedTracks = mergeNetworkTracks(networkTracks, localTracks)
                    trackDao.insertAll(mergedTracks)
                    return@withContext mergedTracks
                } catch (t: Throwable) {
                    Timber.e("Failed to load tracks: $t")
                    emptyList()
                }
            }

        private data class TrackIdentifier(
            val parentId: String,
            val title: String,
            val duration: Long,
        ) {
            companion object {
                fun from(mediaItemTrack: MediaItemTrack) =
                    TrackIdentifier(
                        parentId = mediaItemTrack.parentKey,
                        title = mediaItemTrack.title,
                        duration = mediaItemTrack.duration,
                    )
            }
        }

        /**
         * Merges a list of tracks from the network into the DB by comparing to local tracks and using
         * using logic [MediaItemTrack.merge] to determine what data to keep from each
         */
        private fun mergeNetworkTracks(
            networkTracks: List<MediaItemTrack>,
            localTracks: List<MediaItemTrack>,
            forcePreferNetwork: Boolean = false,
        ): List<MediaItemTrack> {
            val localTracksMap = localTracks.associateBy { it.id }
            val localTrackIdentifiers = mutableSetOf<TrackIdentifier>()
            localTracks.mapTo(localTrackIdentifiers) { TrackIdentifier.from(it) }
            return networkTracks.map { networkTrack ->
                val localTrack = localTracksMap[networkTrack.id]
                if (localTrack != null) {
                    Timber.i("Local track merge: $localTrack")
                    return@map MediaItemTrack.merge(
                        network = networkTrack,
                        local = localTrack,
                        forceUseNetwork = forcePreferNetwork,
                    )
                }
                val networkTrackIdentifier = TrackIdentifier.from(networkTrack)
                // Check to see if a track has changed ID. Move the local file to represent
                // the new track's ID
                if (networkTrackIdentifier in localTrackIdentifiers) {
                    Timber.e("Moving disappeared track: ${networkTrack.title}")
                    val cachedTrack =
                        localTracks.firstOrNull {
                            networkTrackIdentifier.duration == it.duration &&
                                networkTrackIdentifier.parentId == it.parentKey &&
                                networkTrackIdentifier.title == it.title
                        }
                    if (cachedTrack != null) {
                        val cachedFile =
                            File(prefsRepo.cachedMediaDir, cachedTrack.getCachedFileName())
                        val newFileName =
                            File(prefsRepo.cachedMediaDir, networkTrack.getCachedFileName())
                        try {
                            cachedFile.renameTo(newFileName)
                        } catch (t: Throwable) {
                            Timber.e("Failed to rename downloaded track: $t")
                        }
                    }
                }
                return@map networkTrack
            }
        }

        suspend fun deleteByLibraryId(libraryId: String) {
            withContext(Dispatchers.IO) {
                trackDao.deleteByLibraryId(libraryId)
            }
        }
    }
