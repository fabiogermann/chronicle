# Architectural Patterns

This document describes the key architectural patterns used in Chronicle.

For a high-level overview, see the [Architecture Overview](../ARCHITECTURE.md).

---

## Repository Pattern

Repositories abstract data sources, providing a single source of truth by combining local (Room) and remote (Plex API) data.

### Implementation

```kotlin
class BookRepository(
    private val bookDao: BookDao,
    private val plexService: PlexMediaService,
    private val plexConfig: PlexConfig
) : IBookRepository {
    // Combines local cache with network data
    
    suspend fun getAudiobook(id: String): Audiobook {
        // 1. Check local cache
        val cached = bookDao.getById(id)
        if (cached != null && !isStale(cached)) {
            return cached
        }
        
        // 2. Fetch from network
        val remote = plexService.getAlbum(id)
        
        // 3. Update cache
        bookDao.insert(remote.toAudiobook())
        
        return remote.toAudiobook()
    }
}
```

### Repository Classes

| Repository | Purpose |
|------------|---------|
| [`BookRepository`](../../app/src/main/java/local/oss/chronicle/data/local/BookRepository.kt) | Audiobook metadata access |
| [`TrackRepository`](../../app/src/main/java/local/oss/chronicle/data/local/TrackRepository.kt) | Audio track management |
| [`ChapterRepository`](../../app/src/main/java/local/oss/chronicle/data/local/ChapterRepository.kt) | Chapter marker access |
| [`CollectionsRepository`](../../app/src/main/java/local/oss/chronicle/data/local/CollectionsRepository.kt) | Plex collections access |
| [`PlexMediaRepository`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexMediaRepository.kt) | Remote Plex content access |

---

## MediaBrowserService Architecture

[`MediaPlayerService`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) extends `MediaBrowserServiceCompat` to provide:

- **Background audio playback** - Continues playing when app is backgrounded
- **Android Auto/Automotive support** - Browse and play from car displays
- **Media button handling** - Hardware buttons, Bluetooth controls
- **Lock screen controls** - System media controls integration

### Component Interaction

```mermaid
graph TB
    subgraph MediaPlayerService
        MPS[MediaPlayerService]
        EP[ExoPlayer]
        MS[MediaSession]
        NB[NotificationBuilder]
        TLSM[TrackListStateManager]
    end
    
    subgraph Clients
        UI[App UI]
        Auto[Android Auto]
        BT[Bluetooth Controls]
        Lock[Lock Screen]
    end
    
    MPS --> EP
    MPS --> MS
    MPS --> NB
    MPS --> TLSM
    
    UI --> MPS
    Auto --> MS
    BT --> MS
    Lock --> MS
```

### Key Components

| Component | Purpose |
|-----------|---------|
| [`MediaPlayerService`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) | Main service managing playback lifecycle |
| [`AudiobookMediaSessionCallback`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) | Handles play/pause/seek commands |
| [`TrackListStateManager`](../../app/src/main/java/local/oss/chronicle/features/player/TrackListStateManager.kt) | Manages playlist state and chapter detection |
| [`PlaybackUrlResolver`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlaybackUrlResolver.kt) | Resolves streaming URLs with authentication |
| [`NotificationBuilder`](../../app/src/main/java/local/oss/chronicle/features/player/NotificationBuilder.kt) | Creates playback notifications |

---

## Dual HTTP Client Architecture

Chronicle uses two separate HTTP client configurations for different purposes:

| Client | Purpose | Configuration |
|--------|---------|---------------|
| OkHttp + PlexInterceptor | API calls, metadata | Adds all Plex headers |
| ExoPlayer DefaultHttpDataSource | Audio streaming | Must also include Plex headers |

### Why Two Clients?

1. **API Client (Retrofit + OkHttp)** - Used for JSON API calls to fetch metadata, collections, library content. Configured with Moshi for JSON parsing.

2. **Streaming Client (ExoPlayer)** - Used for audio streaming. ExoPlayer manages its own HTTP connections for efficient media loading with buffering, seeking, and adaptive bitrate support.

### Critical: Both clients must include Plex headers

Both clients must include the `X-Plex-Client-Profile-Extra` header. This header tells Plex which audio formats the app supports and is critical for proper playback.

See [`PlexInterceptor`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt) for header implementation.

---

## State Machines

Chronicle uses state machine patterns for complex state management.

### Connection State Machine

Manages the connection lifecycle to Plex servers:

```mermaid
stateDiagram-v2
    [*] --> NOT_CONNECTED
    NOT_CONNECTED --> CONNECTING: setPotentialConnections
    CONNECTING --> CONNECTED: Connection test success
    CONNECTING --> CONNECTION_FAILED: All connections fail
    CONNECTION_FAILED --> CONNECTING: Retry
    CONNECTED --> CONNECTING: Server change
```

**States:**
- `NOT_CONNECTED` - No server connection configured
- `CONNECTING` - Testing potential connections (local, remote, relay)
- `CONNECTED` - Successfully connected to a Plex server
- `CONNECTION_FAILED` - All connection attempts failed

**Implementation:** [`PlexConfig`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexConfig.kt)

### Login State Machine

Manages the multi-step login flow:

```mermaid
stateDiagram-v2
    [*] --> NOT_LOGGED_IN
    NOT_LOGGED_IN --> AWAITING_LOGIN_RESULTS: Start OAuth
    AWAITING_LOGIN_RESULTS --> FAILED_TO_LOG_IN: OAuth error
    AWAITING_LOGIN_RESULTS --> LOGGED_IN_NO_USER_CHOSEN: Multiple users
    AWAITING_LOGIN_RESULTS --> LOGGED_IN_NO_SERVER_CHOSEN: Single user
    LOGGED_IN_NO_USER_CHOSEN --> LOGGED_IN_NO_SERVER_CHOSEN: User selected
    LOGGED_IN_NO_SERVER_CHOSEN --> LOGGED_IN_NO_LIBRARY_CHOSEN: Server selected
    LOGGED_IN_NO_LIBRARY_CHOSEN --> LOGGED_IN_FULLY: Library selected
```

**States:**
- `NOT_LOGGED_IN` - No authentication
- `AWAITING_LOGIN_RESULTS` - OAuth in progress
- `FAILED_TO_LOG_IN` - Authentication failed
- `LOGGED_IN_NO_USER_CHOSEN` - Authenticated but no user selected (managed users)
- `LOGGED_IN_NO_SERVER_CHOSEN` - User selected but no server chosen
- `LOGGED_IN_NO_LIBRARY_CHOSEN` - Server connected but no library selected
- `LOGGED_IN_FULLY` - Fully authenticated and configured

**Implementation:** [`PlexLoginRepo`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexLoginRepo.kt)

---

## MVVM Pattern

Each feature module follows the Model-View-ViewModel pattern:

```mermaid
graph LR
    subgraph View
        Fragment
        Layout[XML Layout]
    end
    
    subgraph ViewModel
        VM[ViewModel]
        State[UI State]
    end
    
    subgraph Model
        Repo[Repository]
        DB[(Database)]
    end
    
    Fragment --> VM
    VM --> Repo
    Repo --> DB
    VM --> State
    State --> Fragment
    Layout --> Fragment
```

### Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **Fragment** | UI rendering, user input handling, lifecycle management |
| **ViewModel** | UI state management, business logic orchestration, survives configuration changes |
| **Repository** | Data access abstraction, caching, sync logic |
| **Data Binding** | Declarative UI binding in XML layouts |

### Example

```kotlin
// ViewModel
class AudiobookDetailsViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {
    
    private val _uiState = MutableLiveData<AudiobookDetailsState>()
    val uiState: LiveData<AudiobookDetailsState> = _uiState
    
    fun loadAudiobook(id: String) {
        viewModelScope.launch {
            _uiState.value = AudiobookDetailsState.Loading
            try {
                val book = bookRepository.getAudiobook(id)
                val tracks = trackRepository.getTracksForBook(id)
                _uiState.value = AudiobookDetailsState.Success(book, tracks)
            } catch (e: Exception) {
                _uiState.value = AudiobookDetailsState.Error(e.message)
            }
        }
    }
}
```

---

## Observer Pattern with LiveData/Flow

Chronicle uses Android's LiveData and Kotlin Flow for reactive data streams:

### LiveData

Used for UI state that Fragments observe:

```kotlin
// ViewModel
val playbackState: LiveData<PlaybackState> = mediaServiceConnection.playbackState

// Fragment
viewModel.playbackState.observe(viewLifecycleOwner) { state ->
    updatePlaybackUI(state)
}
```

### Flow

Used for continuous data streams from repositories:

```kotlin
// Repository
fun observeAudiobooks(): Flow<List<Audiobook>> = bookDao.observeAll()

// ViewModel
val audiobooks = bookRepository.observeAudiobooks()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

---

## Related Documentation

- [Architecture Overview](../ARCHITECTURE.md) - High-level architecture diagrams
- [Architecture Layers](layers.md) - Layer descriptions and responsibilities
- [Dependency Injection](dependency-injection.md) - Dagger component hierarchy
- [Plex Integration](plex-integration.md) - Plex-specific implementation details
