package local.oss.chronicle.data.local

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import kotlinx.coroutines.*
import local.oss.chronicle.data.model.Collection
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.model.asAudiobooks
import local.oss.chronicle.data.sources.plex.model.asCollections
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionsRepository
    @Inject
    constructor(
        private val plexMediaService: PlexMediaService,
        private val prefsRepo: PrefsRepo,
        private val plexPrefsRepo: PlexPrefsRepo,
        private val collectionsDao: CollectionsDao,
    ) {
        // TODO: handle collections sorting!
        suspend fun getChildIds(collectionId: Int): List<Long> {
            return collectionsDao.getCollectionAsync(collectionId).childIds
        }

        fun getCollection(id: Int): LiveData<Collection?> = collectionsDao.getCollection(id)

        fun getAllCollections(): LiveData<List<Collection>> = collectionsDao.getAllRows()

        fun hasCollections(): LiveData<Boolean> =
            collectionsDao
                .countCollections()
                .map { it > 0 }

        suspend fun refreshCollectionsPaginated() {
            prefsRepo.lastRefreshTimeStamp = System.currentTimeMillis()
            val networkCollections: MutableList<Collection> = mutableListOf()
            withContext(Dispatchers.IO) {
                try {
                    val libraryId = plexPrefsRepo.library?.id ?: return@withContext
                    var chaptersLeft = 1L
                    // Maximum number of pages of data we fetch. Failsafe in case of bad data from the
                    // server since we don't want infinite loops. This limits us to a maximum 1,000,000
                    // collections for now
                    val maxIterations = 5000
                    var i = 0
                    while (chaptersLeft > 0 && i < maxIterations) {
                        val response =
                            plexMediaService
                                .retrieveCollectionsPaginated(libraryId, i * 100)
                                .plexMediaContainer
                        chaptersLeft = response.totalSize - (response.offset + response.size)
                        networkCollections.addAll(response.asCollections())
                        i++
                    }
                } catch (t: Throwable) {
                    Timber.i("Failed to retrieve books: $t")
                }
            }

            withContext(Dispatchers.IO) {
                try {
                    val library = plexPrefsRepo.library ?: return@withContext
                    val libraryId = "plex:library:${library.id}"
                    val collectionsWithChildIds =
                        networkCollections.map {
                            val collectionItems =
                                plexMediaService.fetchBooksInCollection(it.id)
                                    .plexMediaContainer
                                    .asAudiobooks(libraryId)

                            val childIds = collectionItems.map { book -> book.id.toLong() }
                            it.copy(childIds = childIds)
                        }
                    collectionsDao.insertAll(collectionsWithChildIds)
                } catch (t: Throwable) {
                    Timber.i("Failed to retrieve books: $t")
                }
            }
        }

        suspend fun clear() {
            collectionsDao.clear()
        }
    }
