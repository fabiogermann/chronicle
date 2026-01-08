package local.oss.chronicle.features.player

import android.support.v4.media.MediaMetadataCompat
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.toMediaMetadata
import local.oss.chronicle.data.sources.plex.PlexConfig

fun buildPlaylist(
    tracks: List<MediaItemTrack>,
    plexConfig: PlexConfig,
): List<MediaMetadataCompat> {
    return tracks.map { track -> track.toMediaMetadata(plexConfig) }
}
