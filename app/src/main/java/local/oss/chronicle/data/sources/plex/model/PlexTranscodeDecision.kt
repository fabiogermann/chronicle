package local.oss.chronicle.data.sources.plex.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from Plex's /music/:/transcode/universal/decision endpoint.
 * 
 * This endpoint negotiates the best playback method (direct play vs transcode) based on:
 * - Client capabilities (from X-Plex-Client-Profile-Extra)
 * - Available bandwidth
 * - Media format
 * 
 * See docs/ARCHITECTURE.md for detailed explanation of why this is needed.
 */
@JsonClass(generateAdapter = true)
data class PlexTranscodeDecisionWrapper(
    @Json(name = "MediaContainer") val container: PlexTranscodeDecision,
)

@JsonClass(generateAdapter = true)
data class PlexTranscodeDecision(
    /** Metadata about the media item */
    @Json(name = "Metadata") val metadata: List<PlexTranscodeMetadata> = emptyList(),
    
    /** General decision code - 1000 = success, 2000 = neither method available */
    @Json(name = "generalDecisionCode") val generalDecisionCode: Int = 0,
    
    /** Direct play decision code - 1000 = OK, 3000/3001 = not available */
    @Json(name = "directPlayDecisionCode") val directPlayDecisionCode: Int = 0,
    
    /** Transcode decision code - 1000/1001 = OK, 4005 = not available */
    @Json(name = "transcodeDecisionCode") val transcodeDecisionCode: Int = 0,
    
    /** Text description of the general decision */
    @Json(name = "generalDecisionText") val generalDecisionText: String = "",
    
    /** Text description of the direct play decision */
    @Json(name = "directPlayDecisionText") val directPlayDecisionText: String = "",
    
    /** Text description of the transcode decision */
    @Json(name = "transcodeDecisionText") val transcodeDecisionText: String = "",
)

@JsonClass(generateAdapter = true)
data class PlexTranscodeMetadata(
    @Json(name = "Media") val media: List<PlexTranscodeMedia> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class PlexTranscodeMedia(
    @Json(name = "Part") val parts: List<PlexTranscodePart> = emptyList(),
    
    /** Whether this media was selected for playback */
    @Json(name = "selected") val selected: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class PlexTranscodePart(
    /** The actual streaming URL to use - may be direct file or transcode session */
    @Json(name = "key") val streamUrl: String = "",
    
    /** Decision for this part: "directplay", "transcode", or "copy" */
    @Json(name = "decision") val decision: String = "",
    
    /** Whether this part was selected for playback */
    @Json(name = "selected") val selected: Boolean = false,
)

/**
 * Gets the best streaming URL from the decision response.
 * Returns null if no valid playback method is available.
 */
fun PlexTranscodeDecision.getStreamUrl(): String? {
    // Find the selected media and part
    return metadata.firstOrNull()
        ?.media?.firstOrNull { it.selected }
        ?.parts?.firstOrNull { it.selected }
        ?.streamUrl
}

/**
 * Checks if direct play is available
 */
fun PlexTranscodeDecision.canDirectPlay(): Boolean {
    return directPlayDecisionCode == 1000
}

/**
 * Checks if transcode is available
 */
fun PlexTranscodeDecision.canTranscode(): Boolean {
    return transcodeDecisionCode == 1000 || transcodeDecisionCode == 1001
}

/**
 * Checks if any playback method is available
 */
fun PlexTranscodeDecision.hasPlayableMethod(): Boolean {
    return generalDecisionCode == 1000 || canDirectPlay() || canTranscode()
}
