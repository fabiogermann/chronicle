# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.60.11] - 2026-01-18

- chore: bump version to 0.60.10-SNAPSHOT and update CHANGELOG.md (e879e27)
- ci: pages improvements (5cf1a74)
- ci: fix the publish step (de6f978)
- ci: move playstore assets (c7a6ed8)
- docs: update readme (f51820c)
- docs: update readme (ef5159a)
- docs: correct links (9df627a)
- chore: update description (85d2cb4)
- ci: permissions and JVM settings (442fdc1)
- fix: login button disappearing (d0324f0)
- feat: auto close login window after successful login (99e3d28)
- ci: Update build.gradle.kts (834408d)
- docs: update links (d3bc0aa)
- chore: Update versionName to 0.60.11 (da01847)
- ci: fix deploy (904fcbf)

## [0.60.9] - 2026-01-12

- chore: add codeql actions (86b92ad)
- chore: fix CI commit message (4e79b9b)
- docs: add documentation about the project (41dd28f)
- chore: update assets (90a088f)
- fix: android auto toggle moved to settings, default to enable (28e5659)
- fix: crash on onlock (2bee0e6)
- fix: android auto playing (e106039)
- chore: licenses (d6baaff)
- chore: move docs (f6b0041)
- chore: bump version (ea502c3)

## [0.60.6] - 2026-01-09

- Bump version to 0.60.6-SNAPSHOT for next development cycle (030ac77)
- chore: updated documentation and enabled changelogs (ffeee4a)
- feat: platstore publishing (3ec97a2)
- chore: refine app name (e10e5e6)
- chore: add playstore assets (1199a88)
- chore: yaml format of action file (99c9fea)
- chore: bump version (c7fd95d)
- chore: fix the release task (fd722f2)
- chore: fix the update changelog task (c68bb68)

## [Unreleased]

### Added
- Automatic changelog generation in GitHub release workflow
- CHANGELOG.md file to track all notable changes to the project

### Fixed
- Fixed server connections accumulating over time - server list now refreshes every 24 hours (every startup in debug builds) and replaces stale connections instead of merging

### Improved (Playback Robustness)

#### Phase 1: Foundation - New Utility Classes
- **ErrorHandling**: `ChronicleError` sealed class for structured error handling with categories (Network, Playback, Data, Authentication, Configuration)
- **RetryHandler**: `withRetry()` function with exponential backoff, configurable max attempts, initial delay, max delay
- **NetworkMonitor**: `NetworkState` sealed class with StateFlow observation for real-time connectivity monitoring
- **ScopedCoroutineManager**: Replaces GlobalScope with proper lifecycle management

#### Phase 2: Thread Safety - State Management
- **PlaybackState**: New immutable data class for playback state with computed properties via `copy()` pattern
- **PlaybackStateController**: Single source of truth for playback state with StateFlow-based reactive state management and debounced database persistence
- **TrackListStateManager**: Added Mutex protection for thread-safe atomic state updates
- **CurrentlyPlayingSingleton**: Now observes PlaybackStateController, bridges StateFlow to LiveData for UI consumption

#### Phase 3: Network Resilience
- **PlaybackUrlResolver**: Thread-safe URL caching, retry logic for URL resolution using exponential backoff
- **PlexConfig**: Connection retry with exponential backoff, reduced initial timeout for faster failover

#### Phase 4: Error Handling
- **CachedFileManager**: Replaced GlobalScope with ScopedCoroutineManager for proper lifecycle management

#### Phase 5: Playback Service
- **SeekHandler**: New component for atomic seek operations with timeout, prevents seek race conditions
- **ChapterValidator**: New component that validates positions against chapter bounds, prevents invalid chapter transitions
- **CurrentlyPlayingBindingAdapters**: Safe valueTo adapter for Slider to prevent crash with 0 duration

#### Phase 6: Android Auto
- **MediaPlayerService**: Enhanced error handling with `[AndroidAuto]` log tags
- **AudiobookMediaSessionCallback**: MediaSession state sync with PlaybackStateController, defensive null checks in browse tree operations

### Documentation
- Updated architecture documentation with new state management patterns
- Added PlaybackStateController, retry with exponential backoff, and error handling patterns to patterns.md
- Updated playback feature documentation with thread safety and error handling details
- Enhanced Android Auto documentation with error handling and state sync details
- Updated AGENT.md with new components and patterns
