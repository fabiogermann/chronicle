# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.62.0] - 2026-06-15

- chore: bump version to 0.61.2-SNAPSHOT and update CHANGELOG.md (c4b3ad9)
- fix: issue with offline playback (80e353d)
- fix: offline playback (e3815f2)
- build: upgrade to Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10 (fd67e2d)
- fix(test): add missing Dagger bindings and fix migration tests in UITestAppModule (2bd610e)
- Merge pull request #14 from fabiogermann/feat/fix-uitest-dagger-bindings (b95be24)
- feat(connection): persist full connection list per library and re-probe on resolve (270e8f2)
- feat(connection): persist Connection list on login + sync; refresh playback URLs on failover (939c159)
- feat(network): add ConnectionRefreshCoordinator for network-change cache invalidation (f1bdb01)
- feat(network): wire ConnectionRefreshCoordinator into ChronicleApplication.setupNetwork (064c086)
- refactor(player): add PlaybackErrorRecoveryHandler with TDD tests (eb47182)
- feat(player): recover from network playback errors via ConnectionRefreshCoordinator (12a977d)
- feat(connection): parse relay/IPv6/protocol/address/port on Connection model (af30e04)
- feat(connection): deprioritize Plex Relay connections behind direct WAN (d0e6e93)
- chore: added mcp config (f584aa5)
- Merge pull request #17 from fabiogermann/feat/off-network-playback (aff8caf)
- chore: bump version to 0.62.0 (versionCode 63) (7010297)

## [0.61.1] - 2026-03-21

- 01 - phase 1 (e366b7d)
- chore: bump version to 0.60.21-SNAPSHOT and update CHANGELOG.md (9bf2466)
- 01 - phase 2 (8f12432)
- fix: database miogration (e2c3fd4)
- 01 - phase 3 (06ae7bf)
- 01 - phase 4 (1eb4792)
- fix: tests from step 4 (d1b1a66)
- 01 - phase 5 (cb941be)
- 01 - phase 5.3 (48f18b1)
- 01 - phase 5.4 (88cfc48)
- 01 - phase 5.5 (6fee7c6)
- fix: tests from step 6 (42916b7)
- fix: tests from step 6 (14696e8)
- fix: ui issues (96483e5)
- 01 - phase 6 (18dda6c)
- fix: autorefresh after login (4519ecf)
- ci: branch filter adjustment (cbc3cb3)
- fix: ui alignment (5ba30c0)
- fix: tests that has issues from rebase (a6b8b63)
- ci: bump (0fbdcfe)
- ci: bump (18ec9fc)
- fix: client signature check (896cacc)
- ci: bump (80cb898)
- fix: loginflow with sign in with google (2087bf8)
- ci: bump (2ae640b)
- fix: regression of library counts not showing correctly (42bcf1e)
- fix: login of second account (1bb4d5f)
- feat: Phase 1 — Create PlexProgressReporter + Migrate PlexSyncScrobbleWorker to CoroutineWorker (07bcdd4)
- fix: Phase 2: Immediate Pause State Reporting to Plex (b5353ce)
- fix: Fix duration*2 Hack for Accurate Progress Reporting (d269605)
- fix: Phase 4 — Make startMediaSession() Library-Aware (e78f0ea)
- feat: Phase 5 — Add Retry Logic for Timeline API Failures (1b53b2b)
- feat: Phase 6 — Write Comprehensive Tests for Progress Reporting (77da483)
- feat: Phase 7 — Update Documentation for Progress Reporting Overhaul (79f6778)
- fix: some tests and token handling (776f0c0)
- fix: more reporing related items (f1317f5)
- ci: bump (07a1101)
- fix: metadata url retrieval (ea587a1)
- fix: multi account handling (3ed3027)
- ci: bump (3ddf028)
- ci: bump (0c0df5e)
- fix: metadata view in library (2cde23c)
- ci: bump (8594133)
- fix: some crashes and issues (17322fd)
- fix: empty chapter markers (100f218)
- ci: bump (96af946)
- fix: ghost chapters with 0 length (793c4ac)
- ci: bump (99c9c8b)
- Merge branch 'feature/multiaccount' (b08cd1a)

## [0.60.20] - 2026-02-07

- chore: bump version to 0.60.18-SNAPSHOT and update CHANGELOG.md (88441c2)
- fix: review feedback where voice commands have issues (490b631)
- test: roboelectric media tests (5a87f29)
- test: jvm based tests for playback (ea82d0d)
- test: instrumentation test for media playback (956d1a4)
- test: improve failing tests (04a7491)
- feat: make sure that something is played on voicecommand (0a9414f)
- feat: add playback timing logs (d8bb527)
- fix: TTS while waiting for buffer (ec26db9)
- ci: bump (8b952c8)
- fix: labels & descriptions (69519d4)
- feat: hide empty views in android auto (97bbf50)
- play: descriptions (67d19cf)
- play: fix sync between auto and loginstate (a935b6a)
- ci: bump (34d5151)
- fit: attempt for regression bugs (9b0c695)
- ci: bump (fca4e95)
- play: more fixes (39df2e4)
- play: fix refresh auto after login (daddf75)
- play: more fixes for the auto play sync (880e63f)
- ci: bump (4f41bf0)
- docs: for the changes implemented (316e02c)

## [0.60.17] - 2026-01-29

- fix: issue with android auto (2417131)
- ci: bump (7f605cb)
- ci: fix flakey tests (97bc9ea)

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
- **Unified Library View**: See audiobooks from ALL your connected libraries in a single view
  - Library, Home, and Collections screens now aggregate content from all accounts/libraries
  - Search finds audiobooks across all libraries
  - "Recently Listened" and "Recently Added" show books from all libraries
- Automatic changelog generation in GitHub release workflow
- CHANGELOG.md file to track all notable changes to the project

### Changed
- **Progress Reporting to Plex**: Overhauled for accuracy and reliability
  - Migrated `PlexSyncScrobbleWorker` to `CoroutineWorker` for proper async handling
  - Created `PlexProgressReporter` for thread-safe, library-aware Plex API calls
  - Removed `duration × 2` hack — Plex dashboard now shows accurate playback progress
  - Added immediate pause state reporting so Plex "Now Playing" updates immediately (within 500ms)
  - Made `startMediaSession()` library-aware for multi-library setups
  - Added retry logic with exponential backoff for failed API calls (3 attempts + WorkManager retry)
  - Eliminated race condition when reporting progress for books from different libraries
  - Request-scoped Retrofit instances prevent global state mutation during progress reporting
- **OAuth Login**: Replaced WebView with Chrome Custom Tabs for improved security and social login support
  - Enables Google/Facebook login (blocked in WebViews)
  - Platform set to "Web" instead of "Android" to enable social authentication
  - Deep link redirect (`chronicle://auth/callback`) returns user to app automatically
  - Expedited polling (200ms) after browser return for faster completion
  - New auth components: `PlexAuthCoordinator`, `PlexAuthUrlBuilder`, `PlexAuthState`, `AuthReturnActivity`, `AuthCoordinatorSingleton`
  - Hosted redirector page at `auth.chronicleapp.net` for seamless deep link integration
- Library sync now automatically syncs ALL connected libraries
- Playback progress syncs to the correct Plex server based on the audiobook's library
- Removed library switching options from Settings (no longer needed with unified view)
- Removed "Switch Library" menu from Library and Home screens

### Removed
- "Change Library" preference from Settings
- "Change Server" preference from Settings
- "Change User" preference from Settings
- Library selector menu items

### Fixed
- **Fixed:** Chapters appearing duplicated (2-4x) in audiobook details screen due to auto-generated primary key preventing conflict detection on re-sync — `ChapterRepository.loadChapterData()` now uses delete-before-insert pattern to prevent duplicates
- **Fixed:** Chapter and track metadata failing to load for audiobooks from non-active libraries — `BookRepository`, `TrackRepository`, and `ChapterRepository` now use library-specific server connections via `ServerConnectionResolver` and `ScopedPlexServiceFactory`
- **Fixed:** Only last chapter displayed for multi-chapter audiobooks due to primary key conflict — Chapters now use auto-generated `uid` primary key instead of `id` (ChapterDatabase v1→v2 migration)
- **Fixed:** Chapters missing bookId association — `PlexChapter.toChapter()` now accepts `bookId` parameter and `ChapterRepository.loadChapterData()` correctly associates chapters with their audiobook
- **Fixed:** Thumbnail images returning 404 for non-active library books — Added `PlexConfig.makeThumbUriForLibrary()` for library-aware thumbnail URL resolution
- Fixed Plex "Now Playing" dashboard not showing Chronicle as an active streaming client
  - Aligned scoped interceptor headers in `PlexProgressReporter` to use consistent `X-Plex-Client-Identifier` (UUID) across all API calls
  - Added missing `X-Plex-Session-Identifier` header to timeline updates
  - Captured and cached `playQueueItemID` from `POST /playQueues` response for use in timeline updates
  - Updated network progress reporting frequency to every 30 seconds
- Fixed OAuth login getting stuck in infinite loop when transient network errors occur during PIN polling
  - Network errors (DNS failures, timeouts, connection resets) no longer corrupt the OAuth PIN ID
  - Only HTTP 404 errors (PIN expired/not found) now clear the PIN ID as intended
  - Prevents infinite 404 loop that blocks users from completing login
- Fixed playback failure for audiobooks from secondary libraries in multi-account setups
  - Introduced `ServerConnectionResolver` for library-specific server URL and token resolution
  - Updated `PlaybackUrlResolver` to route requests to the correct Plex server per library
  - Updated `PlexHttpDataSourceFactory` to inject library-specific auth tokens
  - Updated `PlexSyncScrobbleWorker` for library-aware progress scrobbling
  - Added `serverUrl` and `authToken` fields to Library entity (database migration)
- Fixed playback failing for audiobooks from non-active libraries due to incorrect metadata path format and wrong auth token in playback decision API calls
  - `PlaybackUrlResolver` now strips `"plex:"` prefix from metadata paths before API calls (prevents HTTP 400 errors)
  - Playback decision API calls now use library-scoped Retrofit instances with correct auth tokens (following `PlexProgressReporter` pattern)
- Fixed HTTP 401 error on Android Auto cold start due to stale auth tokens in ExoPlayer DataSource
- Fixed server connections accumulating over time - server list now refreshes every 24 hours (every startup in debug builds) and replaces stale connections instead of merging
- Added "Strict Auto client validation" toggle in Settings → ETC (defaults to off) to resolve Google Play Store rejection caused by review tools being blocked by client signature validation
- Fixed `PlexAuthUrlBuilder` to use standard Java URL encoding instead of Android Uri.encode() for unit test compatibility

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
