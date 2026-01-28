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

        fun from(metadata: MediaMetadataCompat, libraryId: String = ""): MediaItemTrack {
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

        /** The pattern representing a downloaded track on the file system - now format is "plex:123.mp3" */
        val cachedFilePattern = Regex("[^.]+\\..+")

        fun getTrackIdFromFileName(fileName: String): String {
            return fileName.substringBefore('.')
        }

        /**
         * Merges updated local fields with a network copy of the book. Prefers network metadata,
         * but always preserves local [progress] and [lastViewedAt] to avoid race conditions
         * where sync operations overwrite in-progress playback.
         *
         * Always retains [cached] field from local copy.
         *
         * Note: Local device is the source of truth for playback progress.
         */
        fun merge(
            network: MediaItemTrack,
            local: MediaItemTrack,
            forceUseNetwork: Boolean = false,
        ) = if (forceUseNetwork) {
            Timber.i("Force using network track: $network")
            network.copy(cached = local.cached)
        } else {
            // Always preserve local progress and lastViewedAt to prevent race conditions
            network.copy(
                cached = local.cached,
                lastViewedAt = local.lastViewedAt,
                progress = local.progress,
            )
        }

        /** Create a [MediaItemTrack] from a Plex model and an index */
        fun fromPlexModel(networkTrack: PlexDirectory, libraryId: String): MediaItemTrack {
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

    /** The name of the track when it is written to the file system */
    fun getCachedFileName(): String {
        return "$id.${File(media).extension}"
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
            File(Injector.get().prefsRepo().cachedMediaDir, getCachedFileName()).absolutePath
        } else {
            // Check for pre-resolved streaming URL (bandwidth-aware)
            streamingUrlCache[id]?.let { resolvedUrl ->
                Timber.d("Using pre-resolved streaming URL for track $id")
                return resolvedUrl
            }

            // Fall back to direct file URL
            // Note: This may trigger bandwidth errors if server has limits
            Timber.d("No pre-resolved URL for track $id, using direct file path")
            Injector.get().plexConfig().toServerString(media)
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

fun List<MediaItemTrack>.asChapterList(): List<Chapter> {
    val outList = mutableListOf<Chapter>()
    var cumStartOffset = 0L
    for (track in this) {
        outList.add(track.asChapter(cumStartOffset))
        cumStartOffset += track.duration
    }
    return outList
}

fun MediaItemTrack.asChapter(startOffset: Long): Chapter {
    return Chapter(
        title = title,
        id = id.hashCode().toLong(),
        index = index.toLong(),
        discNumber = discNumber,
        startTimeOffset = startOffset,
        endTimeOffset = startOffset + duration,
        downloaded = cached,
        trackId = id,
    )
}

// Produces an ID unique to a track and source
fun MediaItemTrack.uniqueId(): Int {
    return id.hashCode()
}

val EMPTY_TRACK = MediaItemTrack(id = TRACK_NOT_FOUND)
