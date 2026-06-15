package local.oss.chronicle.data.sources.plex

import android.os.Build
import com.squareup.moshi.Moshi
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.data.model.ServerConnection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating library-scoped PlexMediaService instances.
 *
 * Creates Retrofit instances with library-specific base URLs and auth tokens,
 * using properly configured Moshi from dependency injection to avoid deserialization bugs.
 *
 * Caches instances per (serverUrl, authToken) to avoid recreating Retrofit/OkHttp on every request.
 *
 * This centralizes the scoped service creation pattern previously duplicated in
 * PlaybackUrlResolver and PlexProgressReporter, fixing the bare Moshi bug that
 * prevented startMediaSession() from working.
 *
 * @see PlaybackUrlResolver
 * @see PlexProgressReporter
 * @see ServerConnectionResolver
 */
@Singleton
class ScopedPlexServiceFactory
    @Inject
    constructor(
        private val moshi: Moshi,
        private val plexPrefsRepo: PlexPrefsRepo,
        private val plexConfig: PlexConfig,
    ) {
        /**
         * Cache key combining server URL and auth token.
         * Two connections are considered identical if they have the same URL and token.
         */
        private data class ServiceCacheKey(
            val serverUrl: String,
            val authToken: String,
        )

        /**
         * Thread-safe cache of scoped PlexMediaService instances.
         * ConcurrentHashMap handles concurrent reads/writes from multiple threads.
         */
        private val serviceCache = ConcurrentHashMap<ServiceCacheKey, PlexMediaService>()

        /**
         * Gets or creates a scoped PlexMediaService for the given server connection.
         *
         * Caches instances to avoid recreating Retrofit/OkHttp for the same connection.
         * Thread-safe for concurrent access from workers, services, and UI.
         *
         * @param connection Server connection with URL and auth token
         * @return PlexMediaService configured for this library
         * @throws IllegalStateException if serverUrl or authToken is null
         */
        fun getOrCreateService(connection: ServerConnection): PlexMediaService {
            val serverUrl =
                connection.serverUrl
                    ?: throw IllegalStateException("No server URL in connection")

            // Validate that we're not using a placeholder URL
            if (serverUrl.contains("placeholder.com", ignoreCase = true)) {
                throw IllegalStateException(
                    "Cannot create service with placeholder URL: $serverUrl. " +
                        "Ensure PlexConfig.url is properly set before attempting playback.",
                )
            }

            val authToken =
                connection.authToken
                    ?: throw IllegalStateException("No auth token in connection")

            val cacheKey = ServiceCacheKey(serverUrl, authToken)

            // ConcurrentHashMap.getOrPut is thread-safe
            return serviceCache.getOrPut(cacheKey) {
                createService(serverUrl, authToken)
            }
        }

        /**
         * Clears the service cache.
         * Call when:
         * - User logs out
         * - Auth tokens are invalidated
         * - Server connections change
         */
        fun clearCache() {
            serviceCache.clear()
        }

        /**
         * Creates a new PlexMediaService instance for the given server and token.
         * Uses properly configured Moshi from DI to avoid deserialization bugs.
         *
         * CRITICAL: Uses moshi from DI (with KotlinJsonAdapterFactory) instead of
         * bare MoshiConverterFactory.create() which cannot deserialize Kotlin data classes.
         *
         * @param serverUrl Base URL for the Plex server
         * @param authToken Authentication token for this server
         * @return PlexMediaService configured for this server
         */
        private fun createService(
            serverUrl: String,
            authToken: String,
        ): PlexMediaService {
            // Create OkHttp client with scoped auth interceptor
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(createScopedInterceptor(authToken))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()

            // Create Retrofit with library-specific base URL and DI-provided Moshi
            val retrofit =
                Retrofit.Builder()
                    .baseUrl(serverUrl)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi)) // FIX: Use DI Moshi!
                    .build()

            return retrofit.create(PlexMediaService::class.java)
        }

        /**
         * Creates an OkHttp interceptor with scoped auth token.
         * Headers match PlexInterceptor exactly for proper Plex correlation.
         *
         * CRITICAL: Uses plexPrefsRepo.uuid (NOT plexConfig.sessionIdentifier) for
         * X-Plex-Client-Identifier to ensure consistency with main interceptor and
         * proper play queue → timeline correlation on Plex dashboard.
         *
         * @param authToken The auth token for this specific request
         * @return Interceptor that adds Plex headers to requests
         */
        private fun createScopedInterceptor(authToken: String): Interceptor {
            return Interceptor { chain ->
                val request =
                    chain.request().newBuilder()
                        .header("Accept", "application/json")
                        .header("X-Plex-Platform", "Android")
                        .header("X-Plex-Provides", "player")
                        .header("X-Plex-Client-Identifier", plexPrefsRepo.uuid)
                        .header("X-Plex-Version", BuildConfig.VERSION_NAME)
                        .header("X-Plex-Product", APP_NAME)
                        .header("X-Plex-Platform-Version", Build.VERSION.RELEASE)
                        .header("X-Plex-Session-Identifier", plexConfig.sessionIdentifier)
                        .header("X-Plex-Client-Name", APP_NAME)
                        .header("X-Plex-Device", Build.MODEL)
                        .header("X-Plex-Device-Name", Build.MODEL)
                        .header("X-Plex-Client-Profile-Extra", PlexInterceptor.CLIENT_PROFILE_EXTRA)
                        .header("X-Plex-Token", authToken)
                        .build()

                try {
                    chain.proceed(request)
                } catch (e: java.io.IOException) {
                    Timber.w(e, "Network error in ScopedPlexServiceFactory interceptor")
                    throw e
                }
            }
        }
    }
