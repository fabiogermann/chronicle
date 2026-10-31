# Data Layer Documentation

This document details the local data storage, repository patterns, and sync mechanisms in Chronicle.

## Overview

```mermaid
graph TB
    subgraph Repositories
        BR[BookRepository]
        TR[TrackRepository]
        CR[CollectionsRepository]
        LSR[LibrarySyncRepository]
    end
    
    subgraph Local Storage
        BookDB[(BookDatabase)]
        TrackDB[(TrackDatabase)]
        CollDB[(CollectionsDatabase)]
        Prefs[SharedPreferences]
        FileCache[File Cache]
    end
    
    subgraph Remote
        PlexAPI[Plex Media Service]
    end
    
    BR --> BookDB
    BR --> PlexAPI
    TR --> TrackDB
    TR --> PlexAPI
    CR --> CollDB
    CR --> PlexAPI
    LSR --> BR
    LSR --> TR
    LSR --> CR
```

## Room Databases

Chronicle uses three separate Room databases for data persistence.

### 1. BookDatabase

Stores audiobook metadata.

**Schema** ([`BookDatabase.kt`](../app/src/main/java/local/oss/chronicle/data/local/BookDatabase.kt)):

```mermaid
erDiagram
    Audiobook {
        int id PK
        long source
        string title
        string titleSort
        string author
        string thumb
        int parentId
        string genre
        string summary
        int year
        long addedAt
        long updatedAt
        long lastViewedAt
        long duration
        boolean isCached
        long progress
        boolean favorited
        long viewedLeafCount
        long leafCount
        long viewCount
        string chapters
    }
```

**Entity**: [`Audiobook`](../app/src/main/java/local/oss/chronicle/data/model/Audiobook.kt)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Int | Plex ratingKey (PK) |
| `source` | Long | Media source identifier |
| `title` | String | Book title |
| `titleSort` | String | Sortable title |
| `author` | String | Author name |
| `thumb` | String | Thumbnail path |
| `genre` | String | Genre(s) |
| `summary` | String | Description |
| `year` | Int | Publication year |
| `addedAt` | Long | Unix timestamp added to library |
| `updatedAt` | Long | Last metadata update |
| `lastViewedAt` | Long | Last playback timestamp |
| `duration` | Long | Total duration (ms) |
| `isCached` | Boolean | Download status |
| `progress` | Long | Playback progress (ms) |
| `leafCount` | Long | Track count |
| `viewCount` | Long | Times completed |
| `chapters` | List | Embedded chapters (serialized) |

**Key Queries:**

```kotlin
// Get all books, respecting offline mode
@Query("SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY titleSort")
fun getAllRows(offlineModeActive: Boolean): LiveData<List<Audiobook>>

// Recently listened (in progress)
@Query("""
    SELECT * FROM Audiobook 
    WHERE isCached >= :offlineModeActive 
      AND lastViewedAt != 0 
      AND progress > 10000 
      AND progress < duration - 120000 
    ORDER BY lastViewedAt DESC 
    LIMIT :bookCount
""")
fun getRecentlyListened(bookCount: Int, offlineModeActive: Boolean): LiveData<List<Audiobook>>

// Search
@Query("SELECT * FROM Audiobook WHERE (title LIKE :query OR author LIKE :query)")
fun search(query: String, offlineModeActive: Boolean): LiveData<List<Audiobook>>
```

**Migrations:**
- Version 1→2: Initial
- Version 2→3: Added chapters column
- Version 3→4: Added source column
- Version 4→5: Added progress column
- Version 5→6: Added titleSort column
- Version 6→7: Added viewCount column
- Version 7→8: Added year column

### 2. TrackDatabase

Stores individual audio track metadata.

**Schema** ([`TrackDatabase.kt`](../app/src/main/java/local/oss/chronicle/data/local/TrackDatabase.kt)):

```mermaid
erDiagram
    MediaItemTrack {
        int id PK
        string title
        long discNumber
        long trackNumber
        long duration
        long progress
        boolean listened
        string media
        int parentKey
        boolean cached
    }
```

**Entity**: [`MediaItemTrack`](../app/src/main/java/local/oss/chronicle/data/model/MediaItemTrack.kt)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Int | Plex ratingKey (PK) |
| `title` | String | Track title |
| `discNumber` | Long | Disc number |
| `trackNumber` | Long | Track index |
| `duration` | Long | Track duration (ms) |
| `progress` | Long | Playback progress (ms) |
| `listened` | Boolean | Completed flag |
| `media` | String | File path on server |
| `parentKey` | Int | Parent audiobook ID |
| `cached` | Boolean | Local file exists |

**Key Queries:**

```kotlin
// Get tracks for a book, ordered
@Query("""
    SELECT * FROM MediaItemTrack 
    WHERE parentKey = :bookId 
    ORDER BY discNumber, trackNumber
""")
fun getTracksForAudiobook(bookId: Int): LiveData<List<MediaItemTrack>>

// Get cached tracks
@Query("SELECT * FROM MediaItemTrack WHERE cached = 1")
suspend fun getCachedTracks(): List<MediaItemTrack>
```

### 3. CollectionsDatabase

Stores Plex collection metadata.

**Schema** ([`CollectionsDatabase.kt`](../app/src/main/java/local/oss/chronicle/data/local/CollectionsDatabase.kt)):

```mermaid
erDiagram
    Collection {
        int id PK
        string title
        string thumb
        int childCount
    }
```

**Entity**: [`Collection`](../app/src/main/java/local/oss/chronicle/data/model/Collection.kt)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Int | Collection ratingKey (PK) |
| `title` | String | Collection name |
| `thumb` | String | Thumbnail path |
| `childCount` | Int | Number of books |

### 4. AccountDatabase

Stores account and library information for multi-account support.

| Entity | Purpose |
|--------|---------|
| `Account` | User account with provider type, credentials, display name |
| `Library` | Library within an account with server URL, section ID |

**Key relationships:**
- Account has many Libraries (foreign key cascade delete)
- Library is referenced by Audiobook and MediaItemTrack via `libraryId`

**Migrations:**
- BookDatabase v8→v9: Added `libraryId` column, converted `id` from Int to String
- TrackDatabase v6→v7: Added `libraryId` column, converted `id`/`parentKey` from Int to String

## Repository Pattern

Repositories provide a single source of truth combining local and remote data.

### IBookRepository

**Interface** ([`BookRepository.kt`](../app/src/main/java/local/oss/chronicle/data/local/BookRepository.kt)):

```kotlin
interface IBookRepository {
    fun getAllBooks(): LiveData<List<Audiobook>>
    fun getRecentlyAdded(bookCount: Int): LiveData<List<Audiobook>>
    fun getRecentlyListened(bookCount: Int): LiveData<List<Audiobook>>
    fun getBook(bookId: String): LiveData<Audiobook?>
    fun search(query: String): LiveData<List<Audiobook>>
    
    suspend fun refreshDataPaginated()
    suspend fun getAudiobookAsync(bookId: String): Audiobook?
    suspend fun fetchBookAsync(bookId: String, libraryId: String): Audiobook
    suspend fun updateCachedStatus(bookId: String, cached: Boolean)
    suspend fun updateTrackData(bookId: String, progress: Long, duration: Long, trackCount: Int)
    suspend fun setWatched(bookId: String, libraryId: String)
    suspend fun setUnwatched(bookId: String, libraryId: String)
}
```

**Implementation highlights:**

```kotlin
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val serverConnectionResolver: ServerConnectionResolver,
    private val scopedPlexServiceFactory: ScopedPlexServiceFactory,
    private val chapterRepository: IChapterRepository,
    private val plexConfig: PlexConfig,
    private val prefsRepo: PrefsRepo
) : IBookRepository {

    // Library-aware book fetching - uses correct server per library
    override suspend fun fetchBookAsync(bookId: String, libraryId: String): Audiobook {
        val connection = serverConnectionResolver.resolveConnection(libraryId)
        val plexService = scopedPlexServiceFactory.createService(connection)
        
        val extractedId = bookId.removePrefix("plex:")
        val response = plexService.retrieveAlbum(extractedId)
        
        val networkBook = Audiobook.from(response.container.directories.first())
        
        // Delegate chapter loading to ChapterRepository
        chapterRepository.loadChaptersForAudiobook(networkBook.id, libraryId)
        
        return mergeWithLocal(networkBook)
    }
    
    // Library-aware watched/unwatched status updates
    override suspend fun setWatched(bookId: String, libraryId: String) {
        val connection = serverConnectionResolver.resolveConnection(libraryId)
        val plexService = scopedPlexServiceFactory.createService(connection)
        val extractedId = bookId.removePrefix("plex:")
        
        plexService.scrobble(extractedId)
        fetchBookAsync(bookId, libraryId) // Refresh metadata
    }
    
    override suspend fun setUnwatched(bookId: String, libraryId: String) {
        val connection = serverConnectionResolver.resolveConnection(libraryId)
        val plexService = scopedPlexServiceFactory.createService(connection)
        val extractedId = bookId.removePrefix("plex:")
        
        plexService.unscrobble(extractedId)
        fetchBookAsync(bookId, libraryId) // Refresh metadata
    }
}
```

**Key changes for multi-library support:**
- Injects `ServerConnectionResolver` and `ScopedPlexServiceFactory` for library-aware API calls
- `fetchBookAsync()` accepts `libraryId` parameter to route requests to correct server
- Chapter loading delegated to `ChapterRepository` (also library-aware)
- `setWatched()`/`setUnwatched()` now library-aware for accurate progress tracking
- Injects `IChapterRepository` and `ChapterDao` for chapter management

### ITrackRepository

**Interface** ([`TrackRepository.kt`](../app/src/main/java/local/oss/chronicle/data/local/TrackRepository.kt)):

```kotlin
interface ITrackRepository {
    fun getTracksForAudiobook(bookId: String): LiveData<List<MediaItemTrack>>
    
    suspend fun refreshDataPaginated()
    suspend fun getTracksForAudiobookAsync(bookId: String): List<MediaItemTrack>
    suspend fun fetchNetworkTracksForBook(bookId: String, libraryId: String): List<MediaItemTrack>
    suspend fun loadTracksForAudiobook(bookId: String, libraryId: String): List<MediaItemTrack>
    suspend fun updateProgress(trackId: String, position: Long)
    suspend fun updateCachedStatus(trackId: String, cached: Boolean)
    suspend fun getBookIdForTrack(trackId: String): String
}
```

**Implementation highlights:**

```kotlin
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val serverConnectionResolver: ServerConnectionResolver,
    private val scopedPlexServiceFactory: ScopedPlexServiceFactory,
    private val bookRepository: IBookRepository,
    private val prefsRepo: PrefsRepo
) : ITrackRepository {

    // Library-aware track fetching - uses correct server per library
    override suspend fun fetchNetworkTracksForBook(
        bookId: String,
        libraryId: String
    ): List<MediaItemTrack> {
        val connection = serverConnectionResolver.resolveConnection(libraryId)
        val plexService = scopedPlexServiceFactory.createService(connection)
        
        val extractedId = bookId.removePrefix("plex:")
        val response = plexService.retrieveAlbum(extractedId)
        
        return response.container.directories.first().tracks.map { track ->
            MediaItemTrack.from(track, extractedId, libraryId)
        }
    }
    
    // Load tracks with library context
    override suspend fun loadTracksForAudiobook(
        bookId: String,
        libraryId: String
    ): List<MediaItemTrack> {
        val networkTracks = fetchNetworkTracksForBook(bookId, libraryId)
        trackDao.insertAll(networkTracks)
        return networkTracks
    }
}
```

**Key changes for multi-library support:**
- Injects `ServerConnectionResolver` and `ScopedPlexServiceFactory` for library-aware API calls
- `fetchNetworkTracksForBook()` accepts `libraryId` parameter to route requests to correct server
- `loadTracksForAudiobook()` accepts `libraryId` for consistent library-aware loading
- All track IDs updated from `Int` to `String` for prefixed format (`"plex:12345"`)

### ChapterRepository

**Library-aware chapter management** ([`ChapterRepository.kt`](../app/src/main/java/local/oss/chronicle/data/local/ChapterRepository.kt)):

```kotlin
class ChapterRepository @Inject constructor(
    private val chapterDao: ChapterDao,
    private val serverConnectionResolver: ServerConnectionResolver,
    private val scopedPlexServiceFactory: ScopedPlexServiceFactory
) : IChapterRepository {

    override suspend fun loadChapterData(bookId: String, libraryId: String) {
        val connection = serverConnectionResolver.resolveConnection(libraryId)
        val plexService = scopedPlexServiceFactory.createService(connection)
        
        val extractedId = bookId.removePrefix("plex:")
        val response = plexService.retrieveAlbum(extractedId)
        
        val chapters = response.container.directories.first().chapters?.map {
            PlexChapter.toChapter(it, bookId)  // Now accepts bookId parameter
        } ?: emptyList()
        
        chapterDao.insertChapters(bookId, chapters)
    }
}
```

**Key features:**
- Uses library-specific server connections via `ServerConnectionResolver`
- `loadChapterData()` accepts `bookId` parameter to correctly associate chapters with their audiobook
- `PlexChapter.toChapter()` conversion now accepts `bookId` to set the chapter's parent book
- Filters out Plex transition markers when a track also has a meaningful embedded chapter, preventing duplicate `0:00` chapter rows in the UI
- Used by `BookRepository` to delegate chapter loading
- Ensures chapters load from correct Plex server in multi-library setups

### CollectionsRepository

Handles Plex collection syncing ([`CollectionsRepository.kt`](../app/src/main/java/local/oss/chronicle/data/local/CollectionsRepository.kt)):

```kotlin
class CollectionsRepository @Inject constructor(
    private val collectionsDao: CollectionsDao,
    private val plexMediaService: PlexMediaService,
    private val prefsRepo: PrefsRepo
) {
    suspend fun refreshCollectionsPaginated() { ... }
    fun getAllCollections(): LiveData<List<Collection>>
    suspend fun getBooksInCollection(collectionId: Int): List<Audiobook>
}
```

## Data Models

### Audiobook

```kotlin
@Entity
data class Audiobook(
    @PrimaryKey val id: Int,
    val source: Long,
    val title: String = "",
    val titleSort: String = "",
    val author: String = "",
    val thumb: String = "",
    val parentId: Int = -1,
    val genre: String = "",
    val summary: String = "",
    val year: Int = 0,
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastViewedAt: Long = 0L,
    val duration: Long = 0L,
    val isCached: Boolean = false,
    val progress: Long = 0L,
    val favorited: Boolean = false,
    val viewedLeafCount: Long = 0L,
    val leafCount: Long = 0L,
    val viewCount: Long = 0L,
    @TypeConverters(ChapterListConverter::class)
    val chapters: List<Chapter> = emptyList()
)
```

**Merge Logic** - When syncing, some fields come from network, others are local-only:

| Source | Fields |
|--------|--------|
| Network | title, author, thumb, genre, summary, year, addedAt, updatedAt |
| Local | duration, progress, isCached, favorited, chapters, source |
| Conditional | lastViewedAt (newer wins) |

### MediaItemTrack

```kotlin
@Entity
data class MediaItemTrack(
    @PrimaryKey val id: Int,
    val title: String,
    val discNumber: Long,
    val trackNumber: Long,
    val duration: Long,
    val progress: Long,
    val listened: Boolean = false,
    val media: String,
    val parentKey: Int,
    val cached: Boolean = false
) {
    companion object {
        // Static cache for pre-resolved streaming URLs
        val streamingUrlCache = mutableMapOf<Int, String>()
        
        // Regex pattern for cached file names
        val cachedFilePattern = Regex("track_(\\d+)_[a-f0-9]+\\.\\w+")
    }
    
    fun getTrackSource(): String {
        // Check streaming URL cache first
        streamingUrlCache[id]?.let { return it }
        
        // Return cached file path or remote URL
        return if (cached) {
            File(cachedMediaDir, getCachedFileName()).absolutePath
        } else {
            plexConfig.toServerString(media)
        }
    }
    
    fun getCachedFileName(): String {
        // Format: track_{id}_{partHash}.{ext}
        val partHash = media.hashCode().toString(16)
        val extension = media.substringAfterLast(".")
        return "track_${id}_$partHash.$extension"
    }
}
```

### Chapter

```kotlin
@Entity
data class Chapter(
    val title: String,
    @PrimaryKey(autoGenerate = true) val uid: Long = 0L,  // Auto-generated primary key
    val id: Long,                                         // Plex rating key (non-unique)
    val index: Long,
    val discNumber: Int,
    val startTimeOffset: Long,
    val endTimeOffset: Long,
    val downloaded: Boolean,
    val trackId: Long,
    val bookId: String,  // Parent audiobook ID
)
```

**Primary Key Change (v1→v2)**: Changed from `@PrimaryKey id` to `@PrimaryKey(autoGenerate = true) uid` to prevent overwrites when multiple chapters share the same track rating key. The `id` field is retained for Plex identification but is no longer the primary key.

**Database Migration**: ChapterDatabase v1→v2 was a destructive migration (dropped and recreated table) since chapters are transient data that can be refetched from the Plex server.

**Transition Marker Filtering**: Some Plex audiobook responses include tiny boundary markers, often around 40-50 ms, in addition to the real chapter for the same track. Chronicle skips those marker rows when a meaningful chapter exists for that track so chapter navigation and lists do not show duplicate `0:00` entries.

## SharedPreferences

Two preference repositories manage settings:

### PrefsRepo

App settings ([`SharedPreferencesPrefsRepo.kt`](../app/src/main/java/local/oss/chronicle/data/local/SharedPreferencesPrefsRepo.kt)):

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `playback_speed` | Float | 1.0 | Playback speed multiplier |
| `skip_silence` | Boolean | false | Skip silent sections |
| `jump_forward_seconds` | Int | 30 | Forward skip duration |
| `jump_backward_seconds` | Int | 15 | Backward skip duration |
| `pause_on_focus_lost` | Boolean | true | Pause on audio focus loss |
| `shake_to_snooze` | Boolean | true | Extend sleep timer by shaking |
| `offline_mode` | Boolean | false | Show only cached content |
| `allow_auto` | Boolean | true | Enable Android Auto |
| `cached_media_dir` | File | - | Download storage location |

### PlexPrefsRepo

Plex authentication state ([`SharedPreferencesPlexPrefsRepo.kt`](../app/src/main/java/local/oss/chronicle/data/sources/plex/SharedPreferencesPlexPrefsRepo.kt)):

| Key | Type | Description |
|-----|------|-------------|
| `account_auth_token` | String | Plex.tv account token |
| `user` | PlexUser | Current user (JSON) |
| `server` | ServerModel | Selected server (JSON) |
| `library` | PlexLibrary | Selected library (JSON) |
| `oauth_temp_id` | Long | Temporary OAuth PIN ID |

## Caching Strategy

### Memory Cache

- **Streaming URLs**: `MediaItemTrack.streamingUrlCache` - Pre-resolved playback URLs
- **LiveData**: Room returns LiveData for reactive UI updates

### Disk Cache

```mermaid
flowchart TD
    subgraph Room Databases
        Books[Audiobook metadata]
        Tracks[Track metadata]
        Collections[Collection metadata]
    end
    
    subgraph File System
        Audio[Downloaded audio files]
        Images[Fresco image cache]
    end
    
    subgraph SharedPreferences
        Settings[App settings]
        Auth[Plex auth tokens]
    end
```

### Audio File Cache

Downloaded tracks stored in app's external files directory:

```
/storage/emulated/0/Android/data/local.oss.chronicle/files/
├── track_120_a1b2c3d4.m4b
├── track_121_e5f6g7h8.m4b
└── track_122_i9j0k1l2.mp3
```

**File naming**: `track_{trackId}_{partHash}.{extension}`

**Management**: [`CachedFileManager`](../app/src/main/java/local/oss/chronicle/data/sources/plex/CachedFileManager.kt)

## Sync Mechanisms

### Library Sync

```mermaid
sequenceDiagram
    participant UI
    participant LibrarySyncRepo
    participant BookRepo
    participant TrackRepo
    participant CollRepo
    participant Plex
    participant DB
    
    UI->>LibrarySyncRepo: refreshLibrary()
    LibrarySyncRepo->>BookRepo: refreshDataPaginated()
    
    loop Paginated
        BookRepo->>Plex: GET albums
        Plex-->>BookRepo: Album page
        BookRepo->>DB: Merge and insert
    end
    
    LibrarySyncRepo->>TrackRepo: refreshDataPaginated()
    
    loop Paginated
        TrackRepo->>Plex: GET tracks
        Plex-->>TrackRepo: Track page
        TrackRepo->>DB: Insert
    end
    
    Note over LibrarySyncRepo: Calculate book durations
    LibrarySyncRepo->>BookRepo: updateTrackData() for each book
    
    LibrarySyncRepo->>CollRepo: refreshCollectionsPaginated()
```

**Implementation**: [`LibrarySyncRepository`](../app/src/main/java/local/oss/chronicle/data/local/LibrarySyncRepository.kt)

### Progress Sync

Progress syncs bidirectionally:

**Local → Remote**: [`ProgressUpdater`](../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) sends timeline updates

**Remote → Local**: During library sync, `lastViewedAt` and `viewOffset` are merged

**Conflict Resolution**: Last-write-wins by `lastViewedAt` — the more recent copy (after
normalizing Plex's unix seconds to the local DB's epoch milliseconds) provides `progress` and
`lastViewedAt`; network metadata is always taken. Two guards keep the rule safe mid-playback
(see `MediaItemTrack.merge`):

1. **Self-echo / clock skew**: the server copy must be newer by at least
   `SERVER_ADOPTION_MARGIN_MS` (60 s), so this device's own timeline report — stamped by the
   server clock at second resolution, possibly skewed ahead — can never rewind local playback.
2. **Finished elsewhere**: a server copy with zero `viewOffset` (Plex clears it on scrobble) is
   not adopted over local progress, so resume never restarts a file finished on another device.

### Download Sync

```mermaid
flowchart TD
    A[Start Download] --> B[Get tracks for book]
    B --> C[Create download requests]
    C --> D[Enqueue in Fetch]
    D --> E{Download progress}
    E -->|Complete| F[Update track.cached = true]
    E -->|Error| G[Retry or fail]
    F --> H{All tracks done?}
    H -->|Yes| I[Update book.isCached = true]
    H -->|No| E
```

**Refresh on App Start**: [`CachedFileManager.refreshTrackDownloadedStatus()`](../app/src/main/java/local/oss/chronicle/data/sources/plex/CachedFileManager.kt) reconciles DB with file system

## Offline Mode

When `PrefsRepo.offlineMode = true`:

1. **BookDao queries** use `isCached >= :offlineModeActive` filter
2. **UI** only shows downloaded books
3. **Playback** uses local files exclusively

Query pattern:
```kotlin
// offlineModeActive = true: WHERE isCached >= true (only cached)
// offlineModeActive = false: WHERE isCached >= false (all books)
@Query("SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive")
fun getAllRows(offlineModeActive: Boolean): LiveData<List<Audiobook>>
```

## Data Flow Diagrams

### Read Flow (Library Screen)

```mermaid
sequenceDiagram
    participant UI
    participant ViewModel
    participant Repository
    participant DAO
    participant Room
    
    UI->>ViewModel: onCreate()
    ViewModel->>Repository: getAllBooks()
    Repository->>DAO: getAllRows()
    DAO->>Room: Query SQLite
    Room-->>UI: LiveData<List<Audiobook>>
    Note over UI: Observes LiveData
```

### Write Flow (Progress Update)

```mermaid
sequenceDiagram
    participant Player
    participant ProgressUpdater
    participant TrackRepo
    participant BookRepo
    participant Plex
    
    Player->>ProgressUpdater: Position changed
    ProgressUpdater->>TrackRepo: updateProgress()
    ProgressUpdater->>BookRepo: updateProgress()
    ProgressUpdater->>Plex: POST /:/timeline
    Note over ProgressUpdater: Runs every 10 seconds
```

## Type Converters

Room type converters for complex types:

```kotlin
class ChapterListConverter {
    private val moshi = Moshi.Builder().build()
    
    @TypeConverter
    fun fromChapterList(chapters: List<Chapter>): String {
        return moshi.adapter<List<Chapter>>().toJson(chapters)
    }
    
    @TypeConverter
    fun toChapterList(json: String): List<Chapter> {
        return moshi.adapter<List<Chapter>>().fromJson(json) ?: emptyList()
    }
}
```

## Error Handling

| Scenario | Handling |
|----------|----------|
| Network failure during sync | Show toast, retain cached data |
| Database migration failure | Clear and rebuild (destructive) |
| File system full | Cancel downloads, notify user |
| Corrupted cached file | Delete and re-download |
| Stale Plex token | Re-authenticate |
