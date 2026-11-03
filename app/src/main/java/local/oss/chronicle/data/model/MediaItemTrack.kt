package local.oss.chronicle.data.model

import android.support.v4.media.MediaMetadataCompat
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import local.oss.chronicle.data.model.MediaItemTrack.Companion.EMPTY_TRACK
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.model.PlexDirectory
import local.oss.chronicle.data.sources.plex.model.getDuration
import local.oss.chronicle.features.player.*
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * A model for an audio track (i.e. a song)
 */
@Entity(indices = [Index(value = ["libraryId"]), Index(value = ["parentKey"])])
data class MediaItemTrack(
    @PrimaryKey
    val id: String = TRACK_NOT_FOUND,
    val parentKey: String = "-1",
    val libraryId: String = "",
    val title: String = "",
    val playQueueItemID: Long = -1,
    val thumb: String? = null,
    val index: Int = 0,
    val discNumber: Int = 1,
    /** The duration of the track in milliseconds */
    val duration: Long = 0L,
    /** Path to the media file in the form "/library/parts/[id]/SOME_NUMBER/file.mp3" */
    val media: String = "",
    val album: String = "",
    val artist: String = "",
    val genre: String = "",
    val cached: Boolean = false,
    val artwork: String? = "",
    val viewCount: Long = 0L,
    val progress: Long = 0L,
    val lastViewedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val size: Long = 0L,
    val source: Long = 0L,
) : Comparable<MediaItemTrack> {
    companion object {
        /**
         * Cache for pre-resolved streaming URLs from Plex's /transcode/universal/decision endpoint.
         * This allows bandwidth-aware playback. Key is track ID, value is the resolved streaming URL.
         *
         * Populated by PlaybackUrlResolver before playback starts.
         * Falls back to direct file URLs if not populated.
         */
        @JvmStatic
        val streamingUrlCache = ConcurrentHashMap<String, String>()

        fun from(
            metadata: MediaMetadataCompat,
            libraryId: String = "",
        ): MediaItemTrack {
            return MediaItemTrack(
                id = metadata.id ?: "unknown",
                libraryId = libraryId,
                title = metadata.title ?: "",
                playQueueItemID = metadata.trackNumber,
                thumb = metadata.artUri.toString(),
                media = metadata.mediaUri.toString(),
                index = metadata.trackNumber.toInt(),
                duration = metadata.duration,
                album = metadata.album ?: "",
                artist = metadata.artist ?: "",
                genre = metadata.genre ?: "",
                artwork = metadata.artUri.toString(),
            )
        }

        val EMPTY_TRACK = MediaItemTrack(TRACK_NOT_FOUND)

        /**
         * The pattern representing a downloaded track on the file system - current format is
         * "plex_123.mp3" (underscore, not colon: FAT/exFAT removable storage rejects ':').
         */
        val cachedFilePattern = Regex("[^.]+\\..+")

        /**
         * Maps a cached download's file name back to the track id used in the DB ("plex:<n>").
         * Handles every on-disk scheme: current "plex_123.mp3", the colon format
         * "plex:123.mp3" (v0.61.1-v0.62.x), and legacy "123.mp3" (pre-v0.61.1).
         */
        fun getTrackIdFromFileName(fileName: String): String {
            val base = fileName.substringBefore('.')
            return when {
                base.startsWith("plex_") -> "plex:${base.removePrefix("plex_")}"
                else -> base
            }
        }

        /**
         * Merges updated local fields with a network copy of the track. Prefers network metadata.
         *
         * Progress conflict rule: **last-write-wins by [lastViewedAt]**. Whichever copy was
         * viewed more recently provides [progress] and [lastViewedAt]. This is what makes
         * cross-device resume work: if another device played the book and reported progress
         * to Plex, this device adopts the server's [viewOffset] on the next sync. When the
         * local copy is newer (e.g. the user is listening right now), local progress is kept,
         * preserving the original protection against sync operations clobbering in-progress
         * playback.
         *
         * Plex serves [lastViewedAt] in unix **seconds** while the local DB stores epoch
         * **milliseconds**, so values are normalized before comparing; a raw comparison would
         * let local timestamps win every time and the server position would never be adopted.
         *
         * Two guards against false "server is newer" positives:
         *
         * 1. **Self-echo / clock skew**: the newest remote [lastViewedAt] is usually this
         *    device's own timeline report echoed back, stamped with the *server* clock at
         *    second resolution. If that clock is even slightly ahead of the device clock, a
         *    plain comparison would adopt our own older `viewOffset` mid-playback and rewind
         *    the listener. The server copy must be at least [SERVER_ADOPTION_MARGIN_MS] newer
         *    to win — enough to reject an echo (seconds of skew), far too small to mask real
         *    progress made on another device.
         * 2. **Finished elsewhere**: Plex clears `viewOffset` when a track is scrobbled, so a
         *    track finished on another device arrives with the newest timestamp and zero
         *    progress. Adopting that would make resume restart the finished file (see
         *    [getActiveTrack], which picks the most-recently-viewed track). A zero-progress
         *    server copy is only adopted when the local copy has no progress of its own.
         *
         * Always retains [cached] field from the local copy (it only exists locally).
         */
        fun merge(
            network: MediaItemTrack,
            local: MediaItemTrack,
            forceUseNetwork: Boolean = false,
        ) = if (forceUseNetwork) {
            Timber.i("Force using network track: $network")
            network.copy(cached = local.cached)
        } else {
            val networkViewedAtMs = plexLastViewedAtToMillis(network.lastViewedAt)
            if (shouldAdoptServerProgress(networkViewedAtMs, network.progress, local)) {
                // Server copy is meaningfully newer: adopt the server's position (cross-device
                // resume), storing the timestamp normalized to milliseconds like the rest of the DB
                network.copy(cached = local.cached, lastViewedAt = networkViewedAtMs)
            } else {
                // Local copy is newer (or same): keep local progress, take network metadata
                network.copy(
                    cached = local.cached,
                    lastViewedAt = local.lastViewedAt,
                    progress = local.progress,
                )
            }
        }

        /**
         * Decides whether the server's progress should replace the local one during [merge].
         * See [merge] for the conflict rule and the two guards.
         */
        internal fun shouldAdoptServerProgress(
            networkViewedAtMs: Long,
            networkProgress: Long,
            local: MediaItemTrack,
        ): Boolean {
            // Guard 2: a track finished elsewhere arrives with viewOffset cleared and the
            // newest lastViewedAt. Zero progress carries no resume information and would make
            // the finished file the resume point (getActiveTrack picks by lastViewedAt), so
            // only adopt it when local has no progress of its own to protect.
            if (networkProgress <= 0L && local.progress > 0L) {
                return false
            }
            // Guard 1: require a margin over the local timestamp so this device's own echoed
            // timeline report (server clock, second resolution, possibly skewed ahead) can
            // never win over fresher local playback state.
            return networkViewedAtMs - local.lastViewedAt > SERVER_ADOPTION_MARGIN_MS
        }

        /**
         * How much newer the server copy must be to win the [merge] conflict. Matches the
         * granularity of Plex's `lastViewedAt` (1 second) with generous headroom for server
         * clock skew, while remaining far too small to mask listening done on another device.
         */
        const val SERVER_ADOPTION_MARGIN_MS: Long = 60_000L

        /**
         * Normalizes a Plex `lastViewedAt` value to epoch milliseconds.
         *
         * Plex serves unix seconds (e.g. 1591031072 in
         * docs/example-query-responses/request-album-info.md); the local DB stores epoch
         * milliseconds. Any value below 10^11 is treated as seconds — epoch milliseconds
         * only crossed 10^11 in the year 5138, and real timestamps (~1.7×10^12 ms in 2025)
         * are far above it.
         */
        internal fun plexLastViewedAtToMillis(lastViewedAt: Long): Long =
            if (lastViewedAt in 1 until 100_000_000_000L) {
                lastViewedAt * 1000L
            } else {
                lastViewedAt
            }

        /** Create a [MediaItemTrack] from a Plex model and an index */
        fun fromPlexModel(
            networkTrack: PlexDirectory,
            libraryId: String,
        ): MediaItemTrack {
            return MediaItemTrack(
                id = "plex:${networkTrack.ratingKey}",
                parentKey = "plex:${networkTrack.parentRatingKey}",
                libraryId = libraryId,
                title = networkTrack.title,
                artist = networkTrack.grandparentTitle,
                thumb = networkTrack.thumb,
                index = networkTrack.index,
                discNumber = networkTrack.parentIndex,
                duration = networkTrack.duration,
                progress = networkTrack.viewOffset,
                media = networkTrack.media[0].part[0].key,
                album = networkTrack.parentTitle,
                lastViewedAt = networkTrack.lastViewedAt,
                updatedAt = networkTrack.updatedAt,
                size = networkTrack.media[0].part[0].size,
            )
        }

        const val PARENT_KEY_PREFIX = "/library/metadata/"
    }

    /**
     * The name of the track when it is written to the file system.
     *
     * Uses "plex_123.mp3" (underscore), not "plex:123.mp3": the ':' in the track id is illegal
     * on FAT/exFAT removable storage, so downloads to an SD card sync location failed at
     * file-creation time. See [getTrackIdFromFileName] for the reverse mapping.
     */
    fun getCachedFileName(): String {
        return "${id.replace(':', '_')}.${File(media).extension}"
    }

    /**
     * Returns the playback URL for this track.
     *
     * **BANDWIDTH-AWARE PLAYBACK**:
     * This function now checks for pre-resolved streaming URLs from Plex's
     * `/transcode/universal/decision` endpoint which:
     * 1. Negotiates direct play vs transcode based on bandwidth limits
     * 2. Creates proper playback sessions with permissions
     * 3. Adapts to bandwidth constraints by transcoding to lower bitrates
     *
     * **How it works**:
     * - If a URL exists in [streamingUrlCache], use that (bandwidth-aware)
     * - Otherwise fall back to direct file URL (may hit bandwidth limits)
     *
     * The cache is populated by PlaybackUrlResolver before playback starts.
     * This allows Plex to handle bandwidth limits gracefully by transcoding when needed
     * instead of rejecting the request.
     *
     * @see local.oss.chronicle.data.sources.plex.PlaybackUrlResolver
     * @see <a href="https://github.com/[repo]/blob/main/docs/ARCHITECTURE.md#bandwidth-handling">ARCHITECTURE.md - Bandwidth Handling</a>
     */
    fun getTrackSource(): String {
        return if (cached) {
            // Use local file if downloaded
            File(Injector.get().prefsRepo().cachedMediaDir, getCachedFileName()).toURI().toString()
        } else {
            // Check for pre-resolved streaming URL (bandwidth-aware)
            streamingUrlCache[id]?.let { resolvedUrl ->
                Timber.d("Using pre-resolved streaming URL for track $id")
                return resolvedUrl
            }

            // Fall back to direct file URL with library-aware server resolution
            // Note: This may trigger bandwidth errors if server has limits
            Timber.d("No pre-resolved URL for track $id, using direct file path from library $libraryId")

            // Use library-specific server URL if available
            val serverUrl =
                try {
                    kotlinx.coroutines.runBlocking {
                        Injector.get().serverConnectionResolver().resolve(libraryId).serverUrl
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to resolve server for library $libraryId, using global PlexConfig")
                    null
                } ?: Injector.get().plexConfig().url

            // Build full URL with library-specific server
            buildServerUrl(serverUrl, media)
        }
    }

    /**
     * Builds a full server URL from base and relative path.
     * Accounts for trailing/leading slashes like PlexConfig.toServerString().
     */
    private fun buildServerUrl(
        baseUrl: String,
        relativePath: String,
    ): String {
        val baseEndsWith = baseUrl.endsWith('/')
        val pathStartsWith = relativePath.startsWith('/')
        return if (baseEndsWith && pathStartsWith) {
            "$baseUrl${relativePath.substring(1)}"
        } else if (!baseEndsWith && !pathStartsWith) {
            "$baseUrl/$relativePath"
        } else {
            "$baseUrl$relativePath"
        }
    }

    /** A string representing the index but padded to [length] characters with zeroes */
    fun paddedIndex(length: Int): String {
        return index.toString().padStart(length, '0')
    }

    override fun compareTo(other: MediaItemTrack): Int {
        val discCompare = discNumber.compareTo(other.discNumber)
        if (discCompare != 0) {
            return discCompare
        }
        return index.compareTo(other.index)
    }
}

/**
 * Returns the timestamp (in ms) corresponding to the start of [track] with respect to the
 * entire playlist
 *
 * IMPORTANT: [MediaItemTrack.duration] is not guaranteed to perfectly match the duration of the
 * track represented, as we don't trust the server and are unable to verify this ourselves, so
 * use with caution
 */
fun List<MediaItemTrack>.getTrackStartTime(track: MediaItemTrack): Long {
    if (isEmpty()) {
        return 0
    }
    // There's a possibility [track] has been edited and [this] has not, so find it again
    val trackInList = find { it.id == track.id } ?: return 0
    val previousTracks = this.subList(0, indexOf(trackInList))
    return previousTracks.map { it.duration }.sum()
}

/**
 * Returns the timestamp (in ms) corresponding to the progress of [track] with respect to the
 * entire playlist
 */
fun List<MediaItemTrack>.getTrackProgressInAudiobook(track: MediaItemTrack): Long {
    if (isEmpty()) {
        return 0
    }
    val previousTracks = this.subList(0, indexOf(track))
    return previousTracks.map { it.duration }.sum() + track.progress
}

/** Returns the track containing the timestamp (as offset from the start of the [List] provided */
fun List<MediaItemTrack>?.getTrackContainingOffset(offset: Long): MediaItemTrack {
    if (isNullOrEmpty()) {
        return EMPTY_TRACK
    }
    this.fold(offset) { acc: Long, track: MediaItemTrack ->
        val tempAcc: Long = acc - track.duration
        if (tempAcc <= 0) {
            return track
        }
        return@fold tempAcc
    }
    return EMPTY_TRACK
}

/**
 * @return the progress of the current track plus the duration of all previous tracks
 */
fun List<MediaItemTrack>.getProgress(): Long {
    if (isEmpty()) {
        return 0
    }
    val currentTrackProgress = getActiveTrack().progress
    val previousTracksDuration = getTrackStartTime(getActiveTrack())
    return currentTrackProgress + previousTracksDuration
}

/**
 * @return progress as percent
 */
fun List<MediaItemTrack>.getProgressPercentage(): Int {
    if (isEmpty() || getDuration() == 0L) {
        return 0
    }
    return ((getProgress() / getDuration().toDouble()) * 100).roundToInt()
}

/**
 * Find the next song in the [List] which has not been completed. Returns the first element
 */
fun List<MediaItemTrack>.getActiveTrack(): MediaItemTrack {
    check(this.isNotEmpty()) { "Cannot get active track of empty list!" }
    return maxByOrNull { it.lastViewedAt } ?: get(0)
}

/** Converts the metadata of a [MediaItemTrack] to a [MediaMetadataCompat]. */
fun MediaItemTrack.toMediaMetadata(plexConfig: PlexConfig): MediaMetadataCompat {
    val metadataBuilder = MediaMetadataCompat.Builder()
    metadataBuilder.id = this.id
    metadataBuilder.title = this.title
    metadataBuilder.displayTitle = this.album
    metadataBuilder.displaySubtitle = this.artist
    metadataBuilder.trackNumber = this.playQueueItemID
    metadataBuilder.mediaUri = getTrackSource()
    metadataBuilder.albumArtUri = plexConfig.makeThumbUri(this.thumb ?: "").toString()
    metadataBuilder.trackNumber = this.index.toLong()
    metadataBuilder.duration = this.duration
    metadataBuilder.album = this.album
    metadataBuilder.artist = this.artist
    metadataBuilder.genre = this.genre
    return metadataBuilder.build()
}

fun List<MediaItemTrack>.asChapterList(bookId: String = NO_AUDIOBOOK_FOUND_ID): List<Chapter> {
    val outList = mutableListOf<Chapter>()
    var cumStartOffset = 0L
    for (track in this) {
        outList.add(track.asChapter(cumStartOffset, bookId))
        cumStartOffset += track.duration
    }
    return outList
}

fun MediaItemTrack.asChapter(
    startOffset: Long,
    bookId: String = NO_AUDIOBOOK_FOUND_ID,
): Chapter {
    return Chapter(
        uid = 0L, // Auto-generated by Room
        title = title,
        id = id.hashCode().toLong(),
        index = index.toLong(),
        discNumber = discNumber,
        startTimeOffset = startOffset,
        endTimeOffset = startOffset + duration,
        downloaded = cached,
        trackId = id,
        bookId = bookId,
    )
}

// Produces an ID unique to a track and source
fun MediaItemTrack.uniqueId(): Int {
    return id.hashCode()
}

val EMPTY_TRACK = MediaItemTrack(id = TRACK_NOT_FOUND)
