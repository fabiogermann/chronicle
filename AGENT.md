# AGENT.md - AI Agent Reference for Chronicle Android Project

This document helps AI agents understand and work effectively with the Chronicle Epilogue Android codebase.

## 1. Project Overview

Chronicle Epilogue is an Android audiobook player that integrates with Plex Media Server. It allows users to stream and download audiobooks hosted on their Plex server, with features like adjustable playback speed, sleep timer, chapter navigation, and offline playback.

**Key Details:**
- **Language:** Kotlin
- **Platform:** Android (min SDK 26, target SDK 34)
- **Build System:** Gradle with Kotlin DSL
- **License:** GPLv3 (code) + All Rights Reserved (branding assets)

For complete project information, see [`README.md`](README.md).

## 2. Architecture

Chronicle follows a **layered MVVM architecture** with clear separation of concerns:

- **Presentation Layer:** Fragments + ViewModels (per feature)
- **Domain Layer:** Business logic in repositories and use cases
- **Data Layer:** Room databases + Plex API integration
- **Service Layer:** [`MediaPlayerService`](app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) with ExoPlayer for background audio playback

### Key Architectural Patterns

1. **MVVM (Model-View-ViewModel):** Each feature module uses this pattern with AndroidX Lifecycle components
2. **Repository Pattern:** Single source of truth combining local (Room) and remote (Plex API) data
3. **MediaBrowserService:** For background audio playback and media controls via Media3
4. **Dependency Injection:** Dagger 2 with 3-component hierarchy:
   - [`AppComponent`](app/src/main/java/local/oss/chronicle/injection/components/AppComponent.kt) (@Singleton) - Application-wide dependencies
   - [`ActivityComponent`](app/src/main/java/local/oss/chronicle/injection/components/ActivityComponent.kt) (@ActivityScope) - Activity-scoped dependencies  
   - [`ServiceComponent`](app/src/main/java/local/oss/chronicle/injection/components/ServiceComponent.kt) (@ServiceScope) - MediaPlayerService dependencies

**For detailed architecture diagrams and patterns, see:**
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - Main architecture overview and index
- [`docs/architecture/layers.md`](docs/architecture/layers.md) - Detailed layer architecture
- [`docs/architecture/dependency-injection.md`](docs/architecture/dependency-injection.md) - Dagger 2 DI setup
- [`docs/architecture/patterns.md`](docs/architecture/patterns.md) - Architectural patterns
- [`docs/architecture/plex-integration.md`](docs/architecture/plex-integration.md) - Plex-specific implementation
- [`docs/DATA_LAYER.md`](docs/DATA_LAYER.md) - Database and repository patterns
- [`docs/FEATURES.md`](docs/FEATURES.md) - Feature-specific architecture

## 3. Code Structure

The codebase is organized by feature and layer:

```
app/src/main/java/local/oss/chronicle/
├── application/              # App initialization, MainActivity, DI Injector
│   ├── ChronicleApplication.kt
│   ├── MainActivity.kt
│   ├── Injector.kt
│   └── Constants.kt
│
├── data/
│   ├── local/               # Room databases, DAOs, Repositories
│   │   ├── BookDatabase.kt
│   │   ├── BookRepository.kt
│   │   ├── ChapterDatabase.kt
│   │   ├── ChapterRepository.kt
│   │   ├── CollectionsDatabase.kt
│   │   ├── CollectionsRepository.kt
│   │   ├── TrackDatabase.kt
│   │   ├── TrackRepository.kt
│   │   └── LibrarySyncRepository.kt
│   │
│   ├── model/               # Domain models (data classes)
│   │   ├── Audiobook.kt
│   │   ├── Chapter.kt
│   │   ├── Collection.kt
│   │   ├── MediaItemTrack.kt
│   │   └── PlexLibrary.kt
│   │
│   └── sources/
│       ├── plex/            # Plex API integration (Retrofit + OkHttp)
│       │   ├── PlexService.kt
│       │   ├── PlexMediaSource.kt
│       │   ├── PlexMediaRepository.kt
│       │   ├── PlexLoginRepo.kt
│       │   ├── PlexConfig.kt
│       │   ├── PlexInterceptor.kt
│       │   ├── PlaybackUrlResolver.kt
│       │   └── model/       # Plex-specific models (Moshi JSON)
│       └── local/           # Local media source
│
├── features/                # Feature modules (UI + ViewModels)
│   ├── account/            # Account and library management
│   │   ├── AccountListFragment.kt
│   │   ├── AccountListViewModel.kt
│   │   ├── AccountListAdapter.kt
│   │   ├── AccountWithLibraries.kt
│   │   ├── AccountManager.kt
│   │   ├── ActiveLibraryProvider.kt
│   │   ├── CredentialManager.kt
│   │   ├── LegacyAccountMigration.kt
│   │   ├── LibrarySelectorBottomSheet.kt
│   │   └── LibrarySelectorAdapter.kt
│   ├── login/              # OAuth, server/user/library selection
│   ├── home/               # Recently listened, recently added
│   ├── library/            # Full audiobook library with search
│   ├── bookdetails/        # Audiobook details, chapters
│   ├── collections/        # Plex collections browsing
│   ├── currentlyplaying/   # Full-screen player UI
│   ├── player/             # MediaPlayerService, ExoPlayer (Media3)
│   ├── search/             # Search functionality
│   ├── settings/           # App preferences
│   └── download/           # Download management (Fetch library)
│
├── injection/              # Dagger 2 DI setup
│   ├── components/         # AppComponent, ActivityComponent, ServiceComponent
│   ├── modules/            # AppModule, ActivityModule, ServiceModule
│   └── scopes/             # Custom scopes (@ActivityScope, @ServiceScope)
│
├── navigation/             # Navigation utilities (Jetpack Navigation)
│   └── Navigator.kt
│
├── util/                   # Extension functions, utilities
│   ├── ErrorHandling.kt        # ChronicleError sealed class for structured errors
│   ├── RetryHandler.kt         # withRetry() with exponential backoff
│   ├── NetworkMonitor.kt       # Real-time network connectivity monitoring
│   └── ScopedCoroutines.kt     # Lifecycle-aware coroutine management
│
└── views/                  # Custom views, binding adapters (Data Binding)
    ├── BindingAdapters.kt
    ├── ChronicleDraweeView.kt  # Fresco image view
    └── ModalBottomSheetSpeedChooser.kt
```

## 4. Development Commands

### Build Commands
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore configuration)
./gradlew assembleRelease

# Clean build
./gradlew clean
```

### Testing Commands
```bash
# Run unit tests
./gradlew test

# Run tests with coverage
./gradlew testDebugUnitTest

# Run specific test class
./gradlew :app:testDebugUnitTest --tests "local.oss.chronicle.features.player.TrackListStateManagerTest"
```

## 5. Code Style

Chronicle follows the [Kotlin Style Guide](https://developer.android.com/kotlin/style-guide) enforced by **Ktlint**.

**Key conventions:**
- 4 spaces for indentation
- Max line length: 120 characters
- Curly braces on same line
- Use trailing commas in multi-line declarations
- Prefer `val` over `var` when possible
- Use explicit types for public APIs

**Pre-commit hooks** automatically run [`ktlintCheck`](pre-commit) to prevent style violations.

For complete contribution guidelines, see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## 6. Key Technical Details

### 6.1 Plex API Integration

Chronicle interacts with **two separate Plex endpoints**:

1. **plex.tv** - Authentication and account management (Retrofit)
   - OAuth flow
   - User profile information
   - Server discovery

2. **Plex Media Server** - Content delivery (user's server URL, Retrofit)
   - Library browsing
   - Metadata fetching
   - Audio streaming

**Critical Headers** (handled by [`PlexInterceptor`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt)):
- `X-Plex-Token` - Authentication token (all requests)
- `X-Plex-Client-Identifier` - Unique device ID
- **`X-Plex-Client-Profile-Extra`** - **CRITICAL for playback** - Tells Plex which formats the app supports

For implementation details, see [`PlexInterceptor.kt`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt).

### 6.2 Audio Playback Architecture

The player uses **Media3 (ExoPlayer)** with:
- [`MediaPlayerService`](app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) - Foreground service for background playback
- [`AudiobookMediaSessionCallback`](app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) - Handles play/pause/seek commands
- [`PlaybackStateController`](app/src/main/java/local/oss/chronicle/features/player/PlaybackStateController.kt) - **Single source of truth** for playback state
- [`PlaybackState`](app/src/main/java/local/oss/chronicle/features/player/PlaybackState.kt) - Immutable playback state data class
- [`TrackListStateManager`](app/src/main/java/local/oss/chronicle/features/player/TrackListStateManager.kt) - Manages playlist state and chapter detection (Mutex-protected)
- [`SeekHandler`](app/src/main/java/local/oss/chronicle/features/player/SeekHandler.kt) - Atomic seek operations with timeout
- [`ChapterValidator`](app/src/main/java/local/oss/chronicle/features/player/ChapterValidator.kt) - Validates positions against chapter bounds
- [`PlaybackUrlResolver`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlaybackUrlResolver.kt) - Resolves streaming URLs with retry logic and caching

All media playback follows Android's MediaSession/MediaBrowser API.

### 6.3 Playback State Management

Chronicle uses a centralized state management pattern with [`PlaybackStateController`](app/src/main/java/local/oss/chronicle/features/player/PlaybackStateController.kt):

- **Immutable State**: Updates create new `PlaybackState` instances via `copy()`
- **Thread Safety**: All updates go through `Mutex.withLock {}`
- **Reactive**: State exposed via `StateFlow` for observation
- **Debounced Persistence**: Database writes debounced (3 seconds) to reduce I/O
- **StateFlow → LiveData Bridge**: [`CurrentlyPlayingSingleton`](app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) converts StateFlow to LiveData for UI

See [`docs/architecture/patterns.md`](docs/architecture/patterns.md) for detailed patterns.

### 6.4 Data Persistence (Room)

Chronicle uses **Room** (AndroidX) for local data:

| Database | Purpose | Key Entities |
|----------|---------|-------------|
| [`BookDatabase`](app/src/main/java/local/oss/chronicle/data/local/BookDatabase.kt) | Audiobook metadata | `Audiobook` |
| [`TrackDatabase`](app/src/main/java/local/oss/chronicle/data/local/TrackDatabase.kt) | Audio file information | `MediaItemTrack` |
| [`ChapterDatabase`](app/src/main/java/local/oss/chronicle/data/local/ChapterDatabase.kt) | Chapter markers | `Chapter` |
| [`CollectionsDatabase`](app/src/main/java/local/oss/chronicle/data/local/CollectionsDatabase.kt) | Plex collections | `Collection` |

**Database migrations** are defined within database class files. Schema versions are stored in [`app/schemas/`](app/schemas/).

### 6.5 Offline Playback

Uses **[Fetch library](https://github.com/tonyofrancis/Fetch)** for downloads:
- [`CachedFileManager`](app/src/main/java/local/oss/chronicle/data/sources/plex/CachedFileManager.kt) - Manages cached audio files (uses ScopedCoroutineManager)
- [`DownloadNotificationWorker`](app/src/main/java/local/oss/chronicle/features/download/DownloadNotificationWorker.kt) - Background download handling (WorkManager)

### 6.6 Important Implementation Notes

- **Authentication tokens expire** - Implement token refresh logic when modifying auth code
- **Chapter detection** is complex - See [`TrackListStateManager`](app/src/main/java/local/oss/chronicle/features/player/TrackListStateManager.kt) for current implementation
- **Playback position syncing** happens via [`PlexSyncScrobbleWorker`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexSyncScrobbleWorker.kt) using WorkManager
- **Media sessions** must be properly released to avoid memory leaks
- **Data Binding** is used throughout for UI binding - see binding adapters in [`views/`](app/src/main/java/local/oss/chronicle/views/) and feature packages

### 6.7 Multi-Account System

Chronicle supports multiple accounts and libraries:

- **AccountDatabase** - Stores Account and Library entities
- **AccountManager** - Coordinates account operations (add, remove, switch)
- **ActiveLibraryProvider** - StateFlow-based current library state
- **CredentialManager** - Encrypted credential storage using AndroidX Security
- **LegacyAccountMigration** - Migrates single-account data on first launch

**ID Format**: Content IDs use prefixed strings:
- Audiobooks/Tracks: `"plex:{ratingKey}"` (e.g., `"plex:12345"`)
- Libraries: `"plex:library:{sectionId}"` (e.g., `"plex:library:1"`)
- Accounts: `"plex:account:{uuid}"` (e.g., `"plex:account:abc-123"`)

## 7. Documentation Index

### Architecture & Design
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - Main architecture overview and index
- [`docs/architecture/layers.md`](docs/architecture/layers.md) - Detailed layer architecture (Presentation, Domain, Data)
- [`docs/architecture/dependency-injection.md`](docs/architecture/dependency-injection.md) - Dagger 2 DI component hierarchy and modules
- [`docs/architecture/patterns.md`](docs/architecture/patterns.md) - Architectural patterns (Repository, MVVM, State Machines, etc.)
- [`docs/architecture/plex-integration.md`](docs/architecture/plex-integration.md) - Plex API integration details (client profile, headers, OAuth)

### Data Layer
- [`docs/DATA_LAYER.md`](docs/DATA_LAYER.md) - Database and repository patterns
  - Room database schemas
  - Repository implementations
  - Sync mechanisms
  - Data flow diagrams

### Features
- [`docs/FEATURES.md`](docs/FEATURES.md) - Feature documentation index
- [`docs/features/login.md`](docs/features/login.md) - Login/OAuth flow documentation
- [`docs/features/library.md`](docs/features/library.md) - Library browsing documentation
- [`docs/features/playback.md`](docs/features/playback.md) - Media playback documentation
- [`docs/features/downloads.md`](docs/features/downloads.md) - Download management documentation
- [`docs/features/android-auto.md`](docs/features/android-auto.md) - Android Auto integration documentation
- [`docs/features/settings.md`](docs/features/settings.md) - Settings/preferences documentation
- [`docs/features/account-ui-design.md`](docs/features/account-ui-design.md) - Multi-account UI design

### API Integration
- [`docs/API_FLOWS.md`](docs/API_FLOWS.md) - Plex API integration details
  - Authentication flows
  - Endpoint documentation
  - Request/response formats
  - Error handling

### Specific Topics
- [`docs/PLEX_LOGIN_AUTO_CLOSE.md`](docs/PLEX_LOGIN_AUTO_CLOSE.md) - OAuth popup auto-close implementation
- [`docs/example-query-responses/`](docs/example-query-responses/) - Real API response examples
  - [`oauth-flow.md`](docs/example-query-responses/oauth-flow.md) - OAuth flow examples
  - [`request-album-info.md`](docs/example-query-responses/request-album-info.md) - Album/audiobook metadata
  - [`request_track_info.md`](docs/example-query-responses/request_track_info.md) - Track information
  - [`request-collections-info.md`](docs/example-query-responses/request-collections-info.md) - Collections data
  - [`managed_users.md`](docs/example-query-responses/managed_users.md) - Managed user accounts

### Project Management
- [`README.md`](README.md) - Project overview, features, links
- [`CONTRIBUTING.md`](CONTRIBUTING.md) - Contribution guidelines, code style, building
- [`CHANGELOG.md`](CHANGELOG.md) - Version history and changes
- [`PRIVACY.md`](PRIVACY.md) - Privacy policy

## 8. Common Tasks

### 8.1 Adding a New Feature

**Technology:** Kotlin, MVVM with AndroidX Lifecycle, Jetpack Navigation, Data Binding, Dagger 2

**General Strategy:**
Start by understanding the feature scope and user requirements. Research the existing codebase for similar patterns to maintain consistency. Design the data model and any required API changes first before touching UI code. Implement in layers following the architecture: data layer (models, DAOs, API calls) → domain/repository layer (business logic) → presentation layer (Fragment, ViewModel, layouts). Write tests alongside implementation to validate behavior as you build. Document the feature in [`docs/`](docs/) when complete, including architecture diagrams for complex flows.

1. **Create feature package** in [`features/`](app/src/main/java/local/oss/chronicle/features/)
   - Structure: Fragment + ViewModel + Adapter (for RecyclerView) + BindingAdapters (for Data Binding)
   - Reference example: [`features/library/`](app/src/main/java/local/oss/chronicle/features/library/)

2. **Create layout** in `app/src/main/res/layout/` using Android Data Binding

3. **Add navigation destination** in `app/src/main/res/navigation/nav_graph.xml` (Jetpack Navigation)

4. **Setup dependency injection:**
   - Add injection method to [`ActivityComponent`](app/src/main/java/local/oss/chronicle/injection/components/ActivityComponent.kt)
   - Inject in Fragment's `onAttach()` - see [`SettingsFragment`](app/src/main/java/local/oss/chronicle/features/settings/SettingsFragment.kt) for pattern

5. **Follow MVVM pattern:**
   - Fragment: UI rendering only
   - ViewModel: State management with LiveData or Flow
   - Repository: Data access abstraction

6. **Document:**
   - Architecture: update corresponding documentation, split to new file if necessary for readability
   - Feature: describe the feature in a dedicated file in [`docs/features/`](docs/features/)
   - Tests: ensure unit tests have been written and run successfully

### 8.2 Modifying Plex API Calls

**Technology:** Retrofit 2, OkHttp3, Moshi (JSON parsing)

**General Strategy:**
First capture real API responses using a proxy or logging to understand the actual data structure. Understand the existing Plex data flow by reviewing [`PlexMediaRepository.kt`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexMediaRepository.kt) and related code. Create model classes that match actual API responses, not assumptions. Handle error cases and edge cases (network failures, malformed responses, authentication issues). Always save example responses in [`docs/example-query-responses/`](docs/example-query-responses/) for future reference and testing.

1. **Define endpoint** in [`PlexService.kt`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexService.kt) interface using Retrofit annotations

2. **Create/update model classes** in [`data/sources/plex/model/`](app/src/main/java/local/oss/chronicle/data/sources/plex/model/) with Moshi annotations

3. **Update repository** in [`PlexMediaRepository.kt`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexMediaRepository.kt)

4. **Save example responses** in [`docs/example-query-responses/`](docs/example-query-responses/)

5. **Headers handled by** [`PlexInterceptor`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt) - modify if needed for new endpoints

### 8.3 Adding Database Entities

**Technology:** Room (AndroidX), Kotlin Coroutines

**General Strategy:**
Plan the schema changes carefully, considering upgrade paths for existing users who already have data. Always write migrations for existing users - never use destructive migrations in production. Consider data relationships and foreign keys to maintain referential integrity. Test migrations with existing database files from previous versions to ensure data is preserved correctly. Remember that database changes are permanent once users upgrade.

1. **Create entity** in [`data/model/`](app/src/main/java/local/oss/chronicle/data/model/) with Room `@Entity` annotation
   - Reference: See existing entities like [`Audiobook.kt`](app/src/main/java/local/oss/chronicle/data/model/Audiobook.kt)

2. **Create DAO interface** in appropriate database file with Room `@Dao` annotation
   - Reference: See DAOs in [`BookDatabase.kt`](app/src/main/java/local/oss/chronicle/data/local/BookDatabase.kt)

3. **Increment database version** in `@Database` annotation

4. **Add migration** in database class extending `Migration`
   - Reference: See migrations in existing database files

5. **Update repository** to use new DAO

6. **Schema auto-generated** on build to [`app/schemas/`](app/schemas/)

### 8.4 Testing

**Technology:** JUnit 4, Mockito, Kotlin Coroutines Test

**General Strategy:**
Test-driven development is encouraged - write tests before or during implementation, not after. Unit test business logic in isolation from Android framework dependencies. Mock external dependencies (API calls, database access) to ensure tests are fast and deterministic. Write tests that document expected behavior and edge cases. Use real-world scenarios for integration tests to validate complex flows. Tests serve as living documentation of how code should behave.

**Test location:** [`app/src/test/java/local/oss/chronicle/`](app/src/test/java/local/oss/chronicle/)

**Reference examples:**
- [`TrackListStateManagerTest.kt`](app/src/test/java/local/oss/chronicle/features/player/TrackListStateManagerTest.kt) - Player state management testing
- [`ChapterDetectionRealWorldTest.kt`](app/src/test/java/local/oss/chronicle/features/player/ChapterDetectionRealWorldTest.kt) - Complex chapter detection logic
- [`AudiobookDetailsViewModelTest.kt`](app/src/test/java/local/oss/chronicle/features/bookdetails/AudiobookDetailsViewModelTest.kt) - ViewModel testing with Mockito

**Testing approach:**
- Mock repositories and dependencies with Mockito
- Use `@RunWith(MockitoJUnitRunner::class)` for tests
- Test ViewModels independently of Android framework
- Use Kotlin coroutines test utilities for async code

### 8.5 Adding Dependencies

**Technology:** Gradle Kotlin DSL

**General Strategy:**
Evaluate necessity first - prefer using existing solutions already in the project over adding new dependencies. Check license compatibility with GPLv3 to ensure legal compliance. Consider impact on app size (APK bloat) and method count. Verify Android compatibility and that it supports the project's minimum SDK (26). Research the library's maintenance status, community support, and security track record before adding.

1. **Edit** [`app/build.gradle.kts`](app/build.gradle.kts) in the `dependencies` block

2. **Sync project** with Gradle files

3. **For Dagger-provided dependencies:**
   - Add provider method in appropriate module ([`AppModule`](app/src/main/java/local/oss/chronicle/injection/modules/AppModule.kt), [`ActivityModule`](app/src/main/java/local/oss/chronicle/injection/modules/ActivityModule.kt), or [`ServiceModule`](app/src/main/java/local/oss/chronicle/injection/modules/ServiceModule.kt))
   - Add to component if needed

### 8.6 Writing Documentation

**Technology:** Markdown, Mermaid diagrams

**General Strategy:**
Document as you develop, not after - it's easier to document while the context is fresh in your mind. Keep [`AGENT.md`](AGENT.md) updated for AI agent context whenever adding new patterns or architectural decisions. Use relative paths to reference code files so links remain valid as the project evolves. Include diagrams for complex flows using Mermaid syntax in markdown for visual clarity. Document API responses in [`docs/example-query-responses/`](docs/example-query-responses/) as reference material for debugging and testing. Update [`CHANGELOG.md`](CHANGELOG.md) for user-facing changes so users understand what's new or fixed.

1. **Choose appropriate documentation location:**
   - Architecture changes → [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
   - Feature documentation → new file in [`docs/features/`](docs/features/)
   - API integration → [`docs/API_FLOWS.md`](docs/API_FLOWS.md)
   - AI agent context → [`AGENT.md`](AGENT.md)
   - User-facing changes → [`CHANGELOG.md`](CHANGELOG.md)

2. **Use consistent formatting:**
   - Link to code files with backticks and relative paths: \[`FileName.kt`\](path/to/FileName.kt)
   - Use Mermaid for architecture diagrams, sequence diagrams, and flowcharts
   - Include code examples where helpful

3. **Document API responses:**
   - Save real API responses in [`docs/example-query-responses/`](docs/example-query-responses/)
   - Include request details (endpoint, headers, parameters)
   - Document edge cases and error responses

4. **Update AGENT.md for AI agents:**
   - Add new patterns to appropriate sections
   - Document technology choices and rationale
   - Include troubleshooting tips for common issues

### 8.7 Making Architecture Changes

**Technology:** MVVM, Dagger 2, Room, Retrofit

**General Strategy:**
Propose changes in [`docs/architecture/`](docs/architecture/) first before implementing to ensure alignment with project goals. Consider impact on existing features - architecture changes can have far-reaching effects on the codebase. Plan an incremental migration path that allows gradual transition without breaking existing functionality. Update [`ARCHITECTURE.md`](docs/ARCHITECTURE.md) after changes are complete to keep documentation current. Ensure the Dagger DI component hierarchy is maintained to avoid circular dependencies or scope violations.

1. **Document the proposal:**
   - Create proposal document in [`docs/architecture/`](docs/architecture/)
   - Explain problem being solved and proposed solution
   - Identify affected components and features
   - Include migration plan for existing code

2. **Consider dependencies:**
   - Map out which components depend on what's being changed
   - Ensure Dagger component hierarchy remains valid:
     - `AppComponent` (@Singleton) → Application-wide
     - `ActivityComponent` (@ActivityScope) → Activity-scoped
     - `ServiceComponent` (@ServiceScope) → Service-scoped
   - Check for circular dependencies

3. **Plan incremental migration:**
   - Break changes into small, reviewable steps
   - Maintain backward compatibility during transition
   - Test existing features after each step
   - Document migration steps for team awareness

4. **Update documentation:**
   - Update [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
   - Update affected feature documentation in [`docs/FEATURES.md`](docs/FEATURES.md)
   - Update [`AGENT.md`](AGENT.md) with new patterns
   - Add diagrams showing new architecture

5. **Testing:**
   - Write tests for new architecture components
   - Verify existing tests still pass
   - Add integration tests for cross-layer interactions

## 9. Troubleshooting Common Issues

### General Strategy
For reported and confirmed bugs a test recreating the scenario is required. The approach is to have the test fail (as expected with the bug present) and defining the desired state criteria before attempting a fix. Once the fix has been applied and the test passes it is encouraged to still test manually to verify that the issue has been resolved.

### Build Issues
- **Ktlint failures:** Run `./gradlew ktlintFormat` before committing
- **Missing keystore:** Copy [`keystore.properties.example`](keystore.properties.example) to `keystore.properties` for release builds
- **Room schema errors:** Delete `app/schemas/` and rebuild to regenerate

### Runtime Issues
- **Playback fails:** Check `X-Plex-Client-Profile-Extra` header in [`PlexInterceptor`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt)
- **Authentication errors:** Verify token is valid and server URL is correct in [`PlexConfig`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexConfig.kt)
- **Database crashes:** Check for missing migrations between schema versions
- **Chapter detection issues:** See [`TrackListStateManager`](app/src/main/java/local/oss/chronicle/features/player/TrackListStateManager.kt) implementation

### Plex-Specific Issues
- **401 Unauthorized:** Token expired or invalid - trigger re-authentication
- **Media not playing:** Ensure server supports audiobook formats (mp3, m4a, m4b)
- **Managed users:** Switching users requires re-authentication flow - see [`docs/example-query-responses/managed_users.md`](docs/example-query-responses/managed_users.md)

---

**Last Updated:** 2026-01-28
**Project Version:** Check [`CHANGELOG.md`](CHANGELOG.md) for current version
