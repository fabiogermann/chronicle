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
                    val libraryId = plexPrefsRepo.library?.id ?: run {
                        Timber.d("CollectionsRepo: no library selected, skipping collections sync")
                        return@withContext
                    }
                    Timber.d("CollectionsRepo: starting collections sync for libraryId=$libraryId")
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
                        val page = response.asCollections()
                        Timber.d("CollectionsRepo: page $i — got ${page.size} collections, totalSize=${response.totalSize} offset=${response.offset} size=${response.size} chaptersLeft=$chaptersLeft")
                        page.forEach { col ->
                            Timber.d("CollectionsRepo:   collection id=${col.id} title='${col.title}' childCount=${col.childCount} sortType=${col.sortType} thumb='${col.thumb}'")
                        }
                        networkCollections.addAll(page)
                        i++
                    }
                    Timber.d("CollectionsRepo: fetched ${networkCollections.size} collections total")
                } catch (t: Throwable) {
                    Timber.e(t, "CollectionsRepo: failed to retrieve collections")
                }
            }

            withContext(Dispatchers.IO) {
                try {
                    val library = plexPrefsRepo.library ?: run {
                        Timber.d("CollectionsRepo: no library for child-id resolution, skipping")
                        return@withContext
                    }
                    val libraryId = "plex:library:${library.id}"
                    Timber.d("CollectionsRepo: resolving child books for ${networkCollections.size} collections (libraryId=$libraryId)")
                    val collectionsWithChildIds =
                        networkCollections.map {
                            val collectionItems =
                                plexMediaService.fetchBooksInCollection(it.id)
                                    .plexMediaContainer
                                    .asAudiobooks(libraryId)

                            // book.id is "plex:<ratingKey>", strip the prefix before converting to Long
                            val childIds = collectionItems.mapNotNull { book ->
                                book.id.removePrefix("plex:").toLongOrNull()
                            }
                            Timber.d("CollectionsRepo:   collection '${it.title}' (id=${it.id}) → ${childIds.size} books: ${collectionItems.map { b -> "'${b.title}'" }}")
                            it.copy(childIds = childIds)
                        }
                    collectionsDao.insertAll(collectionsWithChildIds)
                    Timber.d("CollectionsRepo: inserted/updated ${collectionsWithChildIds.size} collections into DB")
                } catch (t: Throwable) {
                    Timber.e(t, "CollectionsRepo: failed to resolve child books")
                }
            }
        }

        suspend fun clear() {
            collectionsDao.clear()
        }
    }
