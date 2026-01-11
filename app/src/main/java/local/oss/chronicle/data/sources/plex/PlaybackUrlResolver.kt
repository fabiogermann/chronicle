package local.oss.chronicle.data.sources.plex

import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.plex.model.getStreamUrl
import local.oss.chronicle.data.sources.plex.model.hasPlayableMethod
import timber.log.Timber

/**
 * Resolves and caches streaming URLs for tracks using Plex's /transcode/universal/decision endpoint.
 *
 * This solves the bandwidth/permission issues by letting Plex negotiate the best playback method
 * (direct play vs transcode) instead of using direct file URLs.
 *
 * Populates MediaItemTrack.streamingUrlCache which is checked by MediaItemTrack.getTrackSource()
 *
 * See docs/ARCHITECTURE.md for detailed explanation.
 */
class PlaybackUrlResolver(
    private val plexMediaService: PlexMediaService,
    private val plexConfig: PlexConfig,
) {
    
    /**
     * Resolves the best streaming URL for a track by calling Plex's decision endpoint.
     * Returns null if resolution fails.
     * 
     * @param track The track to resolve
     * @return The streaming URL to use, or null if unavailable
     */
    suspend fun resolveStreamingUrl(track: MediaItemTrack): String? {
        try {
            // Check cache first
            MediaItemTrack.streamingUrlCache[track.id]?.let { cachedUrl ->
                Timber.d("Using cached streaming URL for track ${track.id}")
                return cachedUrl
            }
            
            // Construct the metadata path for the decision endpoint
            val metadataPath = "/library/metadata/${track.id}"
            
            Timber.d("Requesting playback decision for track ${track.id} (${track.title})")
            
            // Call the decision endpoint
            val decision = plexMediaService.getPlaybackDecision(
                path = metadataPath,
                protocol = "http", // Use simple HTTP for progressive download
                musicBitrate = 320, // Request high quality, Plex will adjust if needed
                maxAudioBitrate = 320,
            )
            
            val decisionContainer = decision.container
            
            // Log the decision
            Timber.i(
                "Playback decision for track ${track.id}: " +
                    "general=${decisionContainer.generalDecisionCode}, " +
                    "directPlay=${decisionContainer.directPlayDecisionCode}, " +
                    "transcode=${decisionContainer.transcodeDecisionCode}"
            )
            
            if (!decisionContainer.hasPlayableMethod()) {
                Timber.w(
                    "No playable method available for track ${track.id}:\n" +
                        "  ${decisionContainer.generalDecisionText}\n" +
                        "  ${decisionContainer.directPlayDecisionText}\n" +
                        "  ${decisionContainer.transcodeDecisionText}"
                )
                return null
            }
            
            // Extract the streaming URL
            val streamUrl = decisionContainer.getStreamUrl()
            if (streamUrl == null) {
                Timber.w("Decision succeeded but no stream URL returned for track ${track.id}")
                return null
            }
            
            // Convert to full URL with server and cache it in the static cache
            val fullUrl = plexConfig.toServerString(streamUrl)
            MediaItemTrack.streamingUrlCache[track.id] = fullUrl
            
            Timber.i("Resolved streaming URL for track ${track.id}: $streamUrl")
            return fullUrl
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve streaming URL for track ${track.id}")
            return null
        }
    }
    
    /**
     * Pre-resolves streaming URLs for a list of tracks.
     * This can be called before playback starts to avoid delays.
     * 
     * @param tracks List of tracks to resolve
     * @return Map of track ID to streaming URL (only successful resolutions)
     */
    suspend fun preResolveUrls(tracks: List<MediaItemTrack>): Int {
        var successCount = 0
        
        for (track in tracks) {
            try {
                if (resolveStreamingUrl(track) != null) {
                    successCount++
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to pre-resolve URL for track ${track.id}")
            }
        }
        
        Timber.i("Pre-resolved $successCount/${tracks.size} streaming URLs")
        return successCount
    }
    
    /**
     * Clears the streaming URL cache.
     * Call this when switching books or when URLs may be stale.
     */
    fun clearCache() {
        MediaItemTrack.streamingUrlCache.clear()
        Timber.d("Cleared streaming URL cache")
    }
}
