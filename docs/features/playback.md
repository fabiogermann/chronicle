# Media Playback

This document covers Chronicle's media playback system, including player architecture, sleep timer, speed control, progress sync, and notification controls.

## Architecture

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

---

## Key Components

| Component | Purpose |
|-----------|---------|
| [`MediaPlayerService`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) | Background service, MediaBrowserService |
| [`AudiobookMediaSessionCallback`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) | Handles play/pause/seek commands |
| [`TrackListStateManager`](../../app/src/main/java/local/oss/chronicle/features/player/TrackListStateManager.kt) | Manages playlist state |
| [`PlaybackUrlResolver`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlaybackUrlResolver.kt) | Resolves streaming URLs via decision endpoint |
| [`ProgressUpdater`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) | Syncs progress to Plex server |

---

## Playback Flow

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

---

## Sleep Timer

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

**Implementation**: [`SleepTimer`](../../app/src/main/java/local/oss/chronicle/features/player/SleepTimer.kt)

---

## Playback Speed Control

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
- [`ModalBottomSheetSpeedChooser`](../../app/src/main/java/local/oss/chronicle/views/ModalBottomSheetSpeedChooser.kt)
- [`PrefsRepo.playbackSpeed`](../../app/src/main/java/local/oss/chronicle/data/local/SharedPreferencesPrefsRepo.kt)

---

## Progress Scrobbling

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

**Implementation**: [`ProgressUpdater`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt)

---

## Notification Controls

Media notification with:
- Play/pause
- Skip forward/back (configurable duration)
- Seek bar (Android 10+)
- Album art
- Title and author

**Implementation**: [`NotificationBuilder`](../../app/src/main/java/local/oss/chronicle/features/player/NotificationBuilder.kt)

---

## Related Documentation

- [Features Index](../FEATURES.md) - Overview of all features
- [Architecture Layers](../architecture/layers.md) - Service layer details
- [Plex Integration](../architecture/plex-integration.md) - Streaming URL resolution
- [Track Info API Response](../example-query-responses/request_track_info.md) - Track metadata examples
