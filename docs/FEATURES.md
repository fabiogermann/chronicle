# Chronicle Features

This document details all major features of the Chronicle audiobook player.

## Feature Overview

```mermaid
graph TB
    subgraph Authentication
        OAuth[Plex OAuth]
        UserSelect[User Selection]
        ServerSelect[Server Selection]
        LibrarySelect[Library Selection]
    end
    
    subgraph Library Management
        Browse[Library Browsing]
        Search[Search]
        Collections[Collections]
        Filter[Filtering/Sorting]
    end
    
    subgraph Playback
        Player[Media Player]
        SleepTimer[Sleep Timer]
        SpeedControl[Speed Control]
        ChapterNav[Chapter Navigation]
    end
    
    subgraph Offline
        Download[Downloads]
        OfflineMode[Offline Mode]
    end
    
    subgraph Platform
        Auto[Android Auto]
        Widget[Media Widget]
        Notification[Notification Controls]
    end
    
    OAuth --> UserSelect --> ServerSelect --> LibrarySelect
    LibrarySelect --> Browse
    Browse --> Player
    Player --> Download
```

## 1. Plex Authentication

### OAuth Flow

Chronicle uses Plex's OAuth 2.0 PIN-based authentication flow.

```mermaid
sequenceDiagram
    participant User
    participant Chronicle
    participant Plex.tv
    participant Browser
    
    User->>Chronicle: Tap Sign In
    Chronicle->>Plex.tv: POST /api/v2/pins.json
    Plex.tv-->>Chronicle: PIN + code
    Chronicle->>Browser: Open auth URL with code
    User->>Browser: Enter credentials
    Browser->>Plex.tv: Authenticate
    Plex.tv-->>Browser: Redirect/complete
    Chronicle->>Plex.tv: GET /api/v2/pins/id
    Plex.tv-->>Chronicle: authToken
    Chronicle->>Chronicle: Store token
```

**Implementation**: [`PlexLoginRepo`](../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexLoginRepo.kt)

### Key Files
- [`LoginFragment`](../app/src/main/java/local/oss/chronicle/features/login/LoginFragment.kt) - Login UI
- [`LoginViewModel`](../app/src/main/java/local/oss/chronicle/features/login/LoginViewModel.kt) - OAuth state management
- [`PlexLoginService`](../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexService.kt) - API endpoints

## 2. Server/User Selection

### Multi-User Support

Plex accounts can have multiple users (managed users). Chronicle supports user switching:

```mermaid
flowchart TD
    A[Get Users] --> B{Multiple users?}
    B -->|Yes| C[Show User Selection]
    B -->|No| D[Auto-select single user]
    C --> E[User picks profile]
    E --> F{Has PIN?}
    F -->|Yes| G[Enter PIN]
    F -->|No| H[Continue]
    G --> H
    H --> I[Switch user and get token]
```

**Implementation**: 
- [`ChooseUserFragment`](../app/src/main/java/local/oss/chronicle/features/login/ChooseUserFragment.kt)
- [`ChooseUserViewModel`](../app/src/main/java/local/oss/chronicle/features/login/ChooseUserViewModel.kt)

### Server Selection

Users can have multiple Plex servers. Chronicle tests connectivity and selects the best connection:

```mermaid
flowchart TD
    A[Fetch servers from Plex.tv] --> B[Display server list]
    B --> C[User selects server]
    C --> D[Get server connections]
    D --> E[Test connections in parallel]
    E --> F{Local available?}
    F -->|Yes| G[Use local connection]
    F -->|No| H{Remote available?}
    H -->|Yes| I[Use remote connection]
    H -->|No| J{Relay available?}
    J -->|Yes| K[Use relay connection]
    J -->|No| L[Connection failed]
```

**Implementation**: 
- [`ChooseServerFragment`](../app/src/main/java/local/oss/chronicle/features/login/ChooseServerFragment.kt)
- [`PlexConfig.setPotentialConnections()`](../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexConfig.kt)

### Library Selection

Lists Music libraries from the selected server (audiobooks are stored as music in Plex):

**Implementation**: 
- [`ChooseLibraryFragment`](../app/src/main/java/local/oss/chronicle/features/login/ChooseLibraryFragment.kt)
- [`ChooseLibraryViewModel`](../app/src/main/java/local/oss/chronicle/features/login/ChooseLibraryViewModel.kt)

## 3. Library Browsing

### Home Screen

Displays categorized audiobook lists:
- **Recently Listened** - Books with in-progress playback
- **Recently Added** - Newest additions to library

**Implementation**: [`HomeFragment`](../app/src/main/java/local/oss/chronicle/features/home/HomeFragment.kt), [`HomeViewModel`](../app/src/main/java/local/oss/chronicle/features/home/HomeViewModel.kt)

### Library View

Full library with sorting and filtering options:

| Sort Option | Description |
|-------------|-------------|
| Title | Alphabetical by title |
| Author | Alphabetical by author |
| Date Added | Newest first |
| Date Played | Most recently listened |
| Duration | Longest/shortest |
| Year | Publication year |

**Implementation**: [`LibraryFragment`](../app/src/main/java/local/oss/chronicle/features/library/LibraryFragment.kt), [`LibraryViewModel`](../app/src/main/java/local/oss/chronicle/features/library/LibraryViewModel.kt)

### Search

Real-time search across audiobook titles and authors:

**Implementation**: [`AudiobookSearchAdapter`](../app/src/main/java/local/oss/chronicle/features/library/AudiobookSearchAdapter.kt)

## 4. Collections

Plex collections allow grouping audiobooks (e.g., by series):

```mermaid
flowchart LR
    A[Collections List] --> B[Collection Details]
    B --> C[Books in Collection]
    C --> D[Audiobook Details]
```

**Key Files**:
- [`CollectionsFragment`](../app/src/main/java/local/oss/chronicle/features/collections/CollectionsFragment.kt) - Collection list
- [`CollectionDetailsFragment`](../app/src/main/java/local/oss/chronicle/features/collections/CollectionDetailsFragment.kt) - Books in collection
- [`CollectionsRepository`](../app/src/main/java/local/oss/chronicle/data/local/CollectionsRepository.kt) - Data management

## 5. Audiobook Details

Displays comprehensive audiobook information:

- Cover art
- Title, author, year
- Summary/description  
- Genre
- Duration and progress
- Chapter list (for M4B files with embedded chapters)
- Download status

**Implementation**: 
- [`AudiobookDetailsFragment`](../app/src/main/java/local/oss/chronicle/features/bookdetails/AudiobookDetailsFragment.kt)
- [`AudiobookDetailsViewModel`](../app/src/main/java/local/oss/chronicle/features/bookdetails/AudiobookDetailsViewModel.kt)

### Chapter Navigation

For M4B files with embedded chapters:

```mermaid
flowchart TD
    A[Load audiobook] --> B[Fetch track info]
    B --> C[Parse chapter metadata]
    C --> D[Display chapter list]
    D --> E[User taps chapter]
    E --> F[Seek to chapter start]
```

**Implementation**: [`ChapterListAdapter`](../app/src/main/java/local/oss/chronicle/features/bookdetails/ChapterListAdapter.kt)

## 6. Media Playback

### Architecture

```mermaid
graph TB
    subgraph UI Layer
        CPF[CurrentlyPlayingFragment]
        CPV[CurrentlyPlayingViewModel]
        MSC[MediaServiceConnection]
    end
    
    subgraph Service Layer
        MPS[MediaPlayerService]
        MSCallback[MediaSessionCallback]
        ExoPlayer
        MediaSession
    end
    
    subgraph Data Layer
        TLM[TrackListStateManager]
        PUR[PlaybackUrlResolver]
        ProgressUpdater
    end
    
    CPF --> CPV
    CPV --> MSC
    MSC --> MediaSession
    MPS --> ExoPlayer
    MPS --> MediaSession
    MSCallback --> TLM
    TLM --> PUR
    MPS --> ProgressUpdater
```

### Key Components

| Component | Purpose |
|-----------|---------|
| [`MediaPlayerService`](../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) | Background service, MediaBrowserService |
| [`AudiobookMediaSessionCallback`](../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) | Handles play/pause/seek commands |
| [`TrackListStateManager`](../app/src/main/java/local/oss/chronicle/features/player/TrackListStateManager.kt) | Manages playlist state |
| [`PlaybackUrlResolver`](../app/src/main/java/local/oss/chronicle/data/sources/plex/PlaybackUrlResolver.kt) | Resolves streaming URLs via decision endpoint |
| [`ProgressUpdater`](../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) | Syncs progress to Plex server |

### Playback Flow

```mermaid
sequenceDiagram
    participant UI
    participant MediaSession
    participant Callback
    participant TrackManager
    participant UrlResolver
    participant ExoPlayer
    participant Plex
    
    UI->>MediaSession: playFromMediaId
    MediaSession->>Callback: onPlayFromMediaId
    Callback->>TrackManager: loadTracksForBook
    TrackManager-->>Callback: tracks
    Callback->>UrlResolver: preResolveUrls
    UrlResolver->>Plex: GET /transcode/universal/decision
    Plex-->>UrlResolver: streaming URLs
    UrlResolver-->>Callback: resolved URLs
    Callback->>ExoPlayer: setMediaItems
    Callback->>ExoPlayer: prepare and play
    ExoPlayer->>Plex: stream audio
```

## 7. Offline Downloads

Chronicle supports downloading audiobooks for offline playback.

### Download Flow

```mermaid
sequenceDiagram
    participant UI
    participant CachedFileManager
    participant Fetch
    participant PlexConfig
    participant FileSystem
    participant DB
    
    UI->>CachedFileManager: downloadTracks
    CachedFileManager->>DB: getTracksForBook
    DB-->>CachedFileManager: tracks
    loop Each track
        CachedFileManager->>PlexConfig: makeDownloadRequest
        PlexConfig-->>CachedFileManager: Request with auth
        CachedFileManager->>Fetch: enqueue
    end
    Fetch->>FileSystem: Write files
    Fetch-->>CachedFileManager: onComplete
    CachedFileManager->>DB: updateCachedStatus
```

**Key Files**:
- [`CachedFileManager`](../app/src/main/java/local/oss/chronicle/data/sources/plex/CachedFileManager.kt) - Download orchestration
- [`DownloadNotificationWorker`](../app/src/main/java/local/oss/chronicle/features/download/DownloadNotificationWorker.kt) - Progress notifications

### Storage

Downloaded files are stored in app-specific external storage:
- File naming: `{trackId}_{partHash}.{extension}`
- Location: App's external files directory

### Offline Mode

When offline mode is enabled, only downloaded content is shown in the library.

## 8. Sleep Timer

Pauses playback after a specified duration.

```mermaid
stateDiagram-v2
    [*] --> Inactive
    Inactive --> Active: User sets timer
    Active --> Active: Tick every second
    Active --> Inactive: Timer expires / Pause playback
    Active --> Extended: Shake to snooze
    Extended --> Active: Add 5 minutes
    Active --> Inactive: User cancels
```

### Features
- Configurable duration
- Shake-to-snooze (extends timer by 5 minutes)
- Only counts down during active playback

**Implementation**: [`SleepTimer`](../app/src/main/java/local/oss/chronicle/features/player/SleepTimer.kt)

## 9. Playback Speed Control

Supports playback speed adjustment from 0.5x to 3.0x:

| Speed | Description |
|-------|-------------|
| 0.5x | Half speed |
| 0.7x | Slower |
| 1.0x | Normal |
| 1.2x | Slightly faster |
| 1.5x | Fast |
| 1.7x | Faster |
| 2.0x | Double speed |
| 3.0x | Triple speed |

**Additional option**: Skip silence - Automatically skips silent parts of audio

**Implementation**: 
- [`ModalBottomSheetSpeedChooser`](../app/src/main/java/local/oss/chronicle/views/ModalBottomSheetSpeedChooser.kt)
- [`PrefsRepo.playbackSpeed`](../app/src/main/java/local/oss/chronicle/data/local/SharedPreferencesPrefsRepo.kt)

## 10. Settings

Configurable preferences:

| Setting | Description |
|---------|-------------|
| Playback Speed | Default playback speed |
| Skip Silence | Auto-skip quiet parts |
| Skip Forward/Back Duration | Jump duration (10-90 seconds) |
| Pause on Focus Lost | Pause when other audio plays |
| Shake to Snooze | Extend sleep timer by shaking |
| Offline Mode | Show only downloaded content |
| Allow Android Auto | Enable Android Auto support |
| Download Location | Choose storage location |

**Implementation**: 
- [`SettingsFragment`](../app/src/main/java/local/oss/chronicle/features/settings/SettingsFragment.kt)
- [`SettingsViewModel`](../app/src/main/java/local/oss/chronicle/features/settings/SettingsViewModel.kt)
- [`PrefsRepo`](../app/src/main/java/local/oss/chronicle/data/local/SharedPreferencesPrefsRepo.kt)

## 11. Android Auto Support

Chronicle supports Android Auto for in-car playback.

### Media Browser Structure

```
Root
├── Recently Listened
├── Offline (Downloaded)
├── Recently Added
└── Library (All Books)
```

### Features
- Voice search support
- Playback controls
- Book artwork display
- Progress indicators

**Implementation**: 
- [`MediaPlayerService.onGetRoot()`](../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt)
- [`MediaPlayerService.onLoadChildren()`](../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt)
- [`MediaPlayerService.onSearch()`](../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt)
- [`PackageValidator`](../app/src/main/java/local/oss/chronicle/util/PackageValidator.kt) - Validates Auto client

### Configuration
- [`auto_allowed_callers.xml`](../app/src/main/res/xml/auto_allowed_callers.xml) - Allowed Auto clients
- [`automotive_app_desc.xml`](../app/src/main/res/xml/automotive_app_desc.xml) - Auto capabilities

## 12. Progress Scrobbling

Chronicle syncs playback progress to Plex server for:
- Cross-device progress sync
- Continue listening features
- Watch history

### Timeline Updates

```mermaid
sequenceDiagram
    participant Player
    participant ProgressUpdater
    participant Plex
    
    loop Every 10 seconds during playback
        Player->>ProgressUpdater: Current position
        ProgressUpdater->>Plex: POST /:/timeline
        Plex-->>ProgressUpdater: OK
    end
    
    Player->>ProgressUpdater: Playback stopped
    ProgressUpdater->>Plex: Final position update
```

**Implementation**: [`ProgressUpdater`](../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt)

## 13. Notification Controls

Media notification with:
- Play/pause
- Skip forward/back (configurable duration)
- Seek bar (Android 10+)
- Album art
- Title and author

**Implementation**: [`NotificationBuilder`](../app/src/main/java/local/oss/chronicle/features/player/NotificationBuilder.kt)

## Feature Dependencies

```mermaid
graph TD
    Auth[Authentication] --> Server[Server Selection]
    Server --> Library[Library Selection]
    Library --> Browse[Library Browsing]
    Browse --> Details[Book Details]
    Details --> Play[Playback]
    Details --> Download[Downloads]
    Play --> Progress[Progress Sync]
    Play --> Sleep[Sleep Timer]
    Play --> Speed[Speed Control]
    Download --> Offline[Offline Mode]
    Play --> Auto[Android Auto]
    Play --> Notification[Notifications]
```
