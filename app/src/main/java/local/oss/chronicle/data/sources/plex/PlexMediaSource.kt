package local.oss.chronicle.data.sources.plex

import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.github.michaelbull.result.Result
import com.tonyodev.fetch2.Request
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.HttpMediaSource
import local.oss.chronicle.data.sources.MediaSource
import okhttp3.ResponseBody
import javax.inject.Inject

/** A [MediaSource] wrapping Plex media server and its media calls via audio libraries */
class PlexMediaSource
    @Inject
    constructor(
        private val plexConfig: PlexConfig,
        private val plexMediaService: PlexMediaService,
        private val plexLoginRepo: IPlexLoginRepo,
        private val appContext: Context,
        defaultDataSourceFactory: DefaultHttpDataSource.Factory,
    ) : HttpMediaSource {
        override val id: Long = MEDIA_SOURCE_ID_PLEX

        companion object {
            const val MEDIA_SOURCE_ID_PLEX: Long = 0L
        }

        override val dataSourceFactory: DefaultDataSource.Factory =
            DefaultDataSource.Factory(
                appContext,
                defaultDataSourceFactory,
            )

        override val isDownloadable: Boolean = true

        override suspend fun fetchAudiobooks(): Result<List<Audiobook>, Throwable> {
            throw UnsupportedOperationException("PlexMediaSource.fetchAudiobooks() not yet implemented")
        }

        override suspend fun fetchTracks(): Result<List<MediaItemTrack>, Throwable> {
            throw UnsupportedOperationException("PlexMediaSource.fetchTracks() not yet implemented")
        }

        override suspend fun fetchAdditionalTrackInfo(): MediaItemTrack {
            throw UnsupportedOperationException("PlexMediaSource.fetchAdditionalTrackInfo() not yet implemented")
        }

        override suspend fun fetchStream(url: String): ResponseBody {
            throw UnsupportedOperationException("PlexMediaSource.fetchStream() not yet implemented")
        }

        override suspend fun updateProgress(
            mediaItemTrack: MediaItemTrack,
            playbackState: String,
        ) {
            throw UnsupportedOperationException("PlexMediaSource.updateProgress() not yet implemented")
        }

        override suspend fun sendMediaSessionStartCommand() {
            throw UnsupportedOperationException("PlexMediaSource.sendMediaSessionStartCommand() not yet implemented")
        }

        override suspend fun isReachable(): Boolean {
            throw UnsupportedOperationException("PlexMediaSource.isReachable() not yet implemented")
        }

        override fun makeDownloadRequest(trackUrl: String): Request {
            throw UnsupportedOperationException("PlexMediaSource.makeDownloadRequest() not yet implemented")
        }

        override fun makeGlideHeaders(): Any? {
            throw UnsupportedOperationException("PlexMediaSource.makeGlideHeaders() not yet implemented")
        }

        override fun toServerString(relativePathForResource: String): String {
            throw UnsupportedOperationException("PlexMediaSource.toServerString() not yet implemented")
        }
    }
