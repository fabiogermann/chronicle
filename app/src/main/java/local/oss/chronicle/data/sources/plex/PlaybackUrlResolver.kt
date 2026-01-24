package local.oss.chronicle.data.sources.plex

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.plex.model.getStreamUrl
import local.oss.chronicle.data.sources.plex.model.hasPlayableMethod
import local.oss.chronicle.util.RetryConfig
import local.oss.chronicle.util.RetryResult
import local.oss.chronicle.util.withRetry
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves and caches streaming URLs for tracks using Plex's /transcode/universal/decision endpoint.
 *
 * **Changes in PR 3.2 - Playback Robustness Improvements:**
 * - Thread-safe cache with expiration tracking
 * - Automatic retry with exponential backoff
 * - Parallel pre-resolution with concurrency limits
 * - Server URL change detection and cache invalidation
 * - Network change refresh capability
 * - Cache invalidation listener support
 *
 * This solves the bandwidth/permission issues by letting Plex negotiate the best playback method
 * (direct play vs transcode) instead of using direct file URLs.
 *
 * Populates MediaItemTrack.streamingUrlCache which is checked by MediaItemTrack.getTrackSource()
 *
 * See docs/ARCHITECTURE.md for detailed explanation.
 */
@Singleton
class PlaybackUrlResolver @Inject constructor(
    private val plexMediaService: PlexMediaService,
    private val plexConfig: PlexConfig,
) {
    
    /**
     * Cached URL with expiration tracking.
     */
    private data class CachedUrl(
        val url: String,
        val resolvedAt: Long = System.currentTimeMillis(),
        val serverUrl: String // Track which server this was resolved for
    ) {
        fun isExpired(maxAgeMs: Long): Boolean = 
            System.currentTimeMillis() - resolvedAt > maxAgeMs
    }
    
    /** Thread-safe cache with expiration tracking */
    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    
    /** Current server URL for detecting changes */
    private var currentServerUrl: String? = null
    
    /** Listeners for cache invalidation events */
    private val cacheInvalidatedListeners = CopyOnWriteArrayList<OnCacheInvalidatedListener>()
    
    companion object {
        /** Maximum age for cached URLs before they need refresh (5 minutes) */
        const val URL_CACHE_MAX_AGE_MS = 5 * 60 * 1000L
    }
    
    /**
     * Listener for cache invalidation events.
     * Implement this to be notified when the cache is cleared.
     */
    interface OnCacheInvalidatedListener {
        fun onCacheInvalidated()
    }
    
    /**
     * Register a listener for cache invalidation events.
     */
    fun addCacheInvalidatedListener(listener: OnCacheInvalidatedListener) {
        cacheInvalidatedListeners.add(listener)
    }
    
    /**
     * Unregister a cache invalidation listener.
     */
    fun removeCacheInvalidatedListener(listener: OnCacheInvalidatedListener) {
        cacheInvalidatedListeners.remove(listener)
    }
    
    /**
     * Notify all listeners that the cache has been invalidated.
     */
    private fun notifyCacheInvalidated() {
        Timber.d("Notifying ${cacheInvalidatedListeners.size} listeners of cache invalidation")
        cacheInvalidatedListeners.forEach { it.onCacheInvalidated() }
    }
    
    /**
     * Called when the Plex server URL changes.
     * Invalidates all cached URLs since they're server-specific.
     */
    fun onServerUrlChanged(newServerUrl: String) {
        if (currentServerUrl != newServerUrl) {
            Timber.d("Server URL changed from $currentServerUrl to $newServerUrl, invalidating cache")
            currentServerUrl = newServerUrl
            invalidateCache()
        }
    }
    
    /**
     * Invalidates the URL cache and notifies listeners.
     */
    private fun invalidateCache() {
        urlCache.clear()
        MediaItemTrack.streamingUrlCache.clear()
        notifyCacheInvalidated()
    }
    
    /**
     * Resolves the best streaming URL for a track by calling Plex's decision endpoint.
     * Uses retry logic with exponential backoff for network resilience.
     * 
     * @param track The track to resolve
     * @param forceRefresh If true, ignores cache and fetches fresh URL
     * @return The streaming URL to use, or null if unavailable after retries
     */
    suspend fun resolveStreamingUrl(track: MediaItemTrack, forceRefresh: Boolean = false): String? {
        val trackKey = track.key
        
        // Check cache first (unless force refresh)
        if (!forceRefresh) {
            val cached = urlCache[trackKey]
            if (cached != null && 
                !cached.isExpired(URL_CACHE_MAX_AGE_MS) &&
                cached.serverUrl == plexConfig.url) {
                Timber.d("Using cached streaming URL for track ${track.id} (age: ${System.currentTimeMillis() - cached.resolvedAt}ms)")
                return cached.url
            } else if (cached != null && cached.isExpired(URL_CACHE_MAX_AGE_MS)) {
                Timber.d("Cached URL for track ${track.id} expired, refreshing")
            } else if (cached != null && cached.serverUrl != plexConfig.url) {
                Timber.d("Cached URL for track ${track.id} was for different server, refreshing")
            }
        }
        
        // Resolve with retry
        val retryConfig = RetryConfig(
            maxAttempts = 3,
            initialDelayMs = 500L,
            maxDelayMs = 5000L
        )
        
        return when (val result = withRetry(
            config = retryConfig,
            shouldRetry = { error -> isRetryableError(error) },
            onRetry = { attempt, delay, error ->
                Timber.w("URL resolution retry $attempt after ${delay}ms for track ${track.id}: ${error.message}")
            }
        ) { _ ->
            resolveUrlInternal(track)
        }) {
            is RetryResult.Success -> {
                val resolvedUrl = result.value
                // Cache the result
                urlCache[trackKey] = CachedUrl(
                    url = resolvedUrl,
                    serverUrl = plexConfig.url
                )
                // Also update the static cache for backward compatibility
                MediaItemTrack.streamingUrlCache[track.id] = resolvedUrl
                Timber.i("Successfully resolved streaming URL for track ${track.id} on attempt ${result.attemptNumber}")
                resolvedUrl
            }
            is RetryResult.Failure -> {
                Timber.e("URL resolution failed after ${result.attemptsMade} attempts for track ${track.id}: ${result.error.message}")
                null
            }
        }
    }
    
    /**
     * Determines if an error is retryable.
     */
    private fun isRetryableError(error: Throwable): Boolean {
        return when (error) {
            is java.net.UnknownHostException,
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.io.IOException -> true
            else -> false
        }
    }
    
    /**
     * Internal URL resolution - the actual HTTP call.
     * Throws on failure for retry mechanism.
     */
    private suspend fun resolveUrlInternal(track: MediaItemTrack): String {
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
            val errorMsg = "No playable method available for track ${track.id}:\n" +
                "  ${decisionContainer.generalDecisionText}\n" +
                "  ${decisionContainer.directPlayDecisionText}\n" +
                "  ${decisionContainer.transcodeDecisionText}"
            Timber.w(errorMsg)
            throw IllegalStateException(errorMsg)
        }
        
        // Extract the streaming URL
        val streamUrl = decisionContainer.getStreamUrl()
            ?: throw IllegalStateException("Decision succeeded but no stream URL returned for track ${track.id}")
        
        // Convert to full URL with server
        val fullUrl = plexConfig.toServerString(streamUrl)
        Timber.i("Resolved streaming URL for track ${track.id}: $streamUrl")
        return fullUrl
    }
    
    /**
     * Result of batch URL pre-resolution.
     */
    data class PreResolveResult(
        val successCount: Int,
        val failedTracks: List<MediaItemTrack>,
        val totalTracks: Int
    ) {
        val allSucceeded: Boolean get() = failedTracks.isEmpty()
        val failureCount: Int get() = failedTracks.size
    }
    
    /**
     * Pre-resolves URLs for multiple tracks in parallel.
     * Limits concurrency to avoid overwhelming the server.
     * 
     * @param tracks The tracks to pre-resolve
     * @param maxConcurrency Maximum concurrent resolution requests
     * @return Result containing success/failure counts and failed tracks
     */
    suspend fun preResolveUrls(
        tracks: List<MediaItemTrack>,
        maxConcurrency: Int = 4
    ): PreResolveResult = coroutineScope {
        if (tracks.isEmpty()) {
            return@coroutineScope PreResolveResult(
                successCount = 0,
                failedTracks = emptyList(),
                totalTracks = 0
            )
        }
        
        Timber.d("Pre-resolving URLs for ${tracks.size} tracks with max concurrency $maxConcurrency")
        val semaphore = Semaphore(maxConcurrency)
        val failedTracks = mutableListOf<MediaItemTrack>()
        
        val jobs = tracks.map { track ->
            async {
                semaphore.withPermit {
                    try {
                        val result = resolveStreamingUrl(track)
                        if (result == null) {
                            synchronized(failedTracks) {
                                failedTracks.add(track)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to pre-resolve URL for track ${track.id}")
                        synchronized(failedTracks) {
                            failedTracks.add(track)
                        }
                    }
                }
            }
        }
        
        jobs.awaitAll()
        
        val result = PreResolveResult(
            successCount = tracks.size - failedTracks.size,
            failedTracks = failedTracks.toList(),
            totalTracks = tracks.size
        )
        
        Timber.i("Pre-resolved ${result.successCount}/${result.totalTracks} streaming URLs (${result.failureCount} failed)")
        return@coroutineScope result
    }
    
    /**
     * Refreshes cached URLs that may have become stale.
     * Call this when network conditions change.
     * 
     * @param tracks The tracks to refresh (typically currently loaded tracks)
     * @return Result containing success/failure counts
     */
    suspend fun refreshUrlsOnNetworkChange(tracks: List<MediaItemTrack>): PreResolveResult {
        Timber.d("Refreshing ${tracks.size} URLs due to network change")
        
        // Invalidate expired URLs
        val iterator = urlCache.entries.iterator()
        var expiredCount = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(URL_CACHE_MAX_AGE_MS)) {
                iterator.remove()
                expiredCount++
            }
        }
        
        if (expiredCount > 0) {
            Timber.d("Removed $expiredCount expired URLs from cache")
        }
        
        // Re-resolve for currently needed tracks (force refresh to bypass cache)
        return preResolveUrls(tracks)
    }
    
    /**
     * Clears the streaming URL cache.
     * Call this when switching books or when URLs may be stale.
     */
    fun clearCache() {
        Timber.d("Clearing streaming URL cache (${urlCache.size} entries)")
        invalidateCache()
    }
}

/**
 * Extension property to get the cache key for a track.
 * Uses track media path as key since ID might not be unique across servers.
 */
private val MediaItemTrack.key: String
    get() = "$id-$media"
