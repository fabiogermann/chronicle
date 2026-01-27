package local.oss.chronicle.data.model

/**
 * Supported content provider types.
 * Used to identify which backend system an account connects to.
 */
enum class ProviderType {
    /** Plex Media Server */
    PLEX,
    
    /** Audiobookshelf server (future implementation) */
    AUDIOBOOKSHELF
}
