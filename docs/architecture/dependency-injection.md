# Dependency Injection

Chronicle uses **Dagger 2** for dependency injection with a hierarchical component structure that provides appropriate scoping for different parts of the application.

For a high-level overview, see the [Architecture Overview](../ARCHITECTURE.md).

## Component Hierarchy

```mermaid
graph TB
    subgraph Components
        AppComponent[@Singleton<br/>AppComponent]
        ActivityComponent[@ActivityScope<br/>ActivityComponent]
        ServiceComponent[@ServiceScope<br/>ServiceComponent]
    end
    
    subgraph Modules
        AppModule[AppModule]
        ActivityModule[ActivityModule]
        ServiceModule[ServiceModule]
    end
    
    AppComponent --> AppModule
    ActivityComponent --> AppComponent
    ActivityComponent --> ActivityModule
    ServiceComponent --> AppComponent
    ServiceComponent --> ServiceModule
```

The component hierarchy follows a parent-child relationship:
- **AppComponent** is the root component with application-wide singleton dependencies
- **ActivityComponent** is a subcomponent of AppComponent for activity-scoped dependencies
- **ServiceComponent** is a subcomponent of AppComponent for MediaPlayerService dependencies

---

## Components

Located in [`injection/components/`](../../app/src/main/java/local/oss/chronicle/injection/components/)

### AppComponent

**Scope:** `@Singleton`  
**File:** [`AppComponent.kt`](../../app/src/main/java/local/oss/chronicle/injection/components/AppComponent.kt)

The root component that provides application-wide dependencies. Created once when the application starts and lives for the entire application lifecycle.

**Key Responsibilities:**
- Database and DAO instances
- Repository singletons
- Plex API services
- Configuration objects
- WorkManager, Moshi, and other utilities

### ActivityComponent

**Scope:** `@ActivityScope`  
**File:** [`ActivityComponent.kt`](../../app/src/main/java/local/oss/chronicle/injection/components/ActivityComponent.kt)

Created for each activity instance. Provides activity-scoped dependencies and allows injection into Fragments.

**Injection Points:**
- All feature Fragments
- MainActivity

### ServiceComponent

**Scope:** `@ServiceScope`  
**File:** [`ServiceComponent.kt`](../../app/src/main/java/local/oss/chronicle/injection/components/ServiceComponent.kt)

Created for the MediaPlayerService. Provides all dependencies needed for audio playback.

**Key Responsibilities:**
- ExoPlayer instance
- MediaSession
- Notification handling
- Playback URL resolution

---

## Modules

Located in [`injection/modules/`](../../app/src/main/java/local/oss/chronicle/injection/modules/)

### AppModule

**File:** [`AppModule.kt`](../../app/src/main/java/local/oss/chronicle/injection/modules/AppModule.kt)

Provides application-wide singleton dependencies:

| Dependency | Description |
|------------|-------------|
| Room DAOs | BookDao, TrackDao, ChapterDao, CollectionsDao |
| Repositories | BookRepository, TrackRepository, ChapterRepository, CollectionsRepository |
| Plex Services | PlexLoginService, PlexMediaService (Retrofit interfaces) |
| PlexConfig | Server configuration and connection state |
| PlexPrefsRepo | Plex authentication token storage |
| Moshi | JSON serialization |
| WorkManager | Background task scheduling |
| Fetch | Download manager |
| SharedPreferences | App preferences storage |

### ActivityModule

**File:** [`ActivityModule.kt`](../../app/src/main/java/local/oss/chronicle/injection/modules/ActivityModule.kt)

Provides activity-scoped dependencies:

| Dependency | Description |
|------------|-------------|
| Activity | The activity instance for context-dependent operations |
| Navigator | Navigation handling |
| MediaServiceConnection | Connection to MediaPlayerService |

### ServiceModule

**File:** [`ServiceModule.kt`](../../app/src/main/java/local/oss/chronicle/injection/modules/ServiceModule.kt)

Provides MediaPlayerService dependencies:

| Dependency | Description |
|------------|-------------|
| ExoPlayer | Media playback engine |
| MediaSession | Android media session for system integration |
| MediaController | Controls media playback |
| NotificationBuilder | Playback notification creation |
| SleepTimer | Sleep timer functionality |
| PlaybackUrlResolver | Resolves streaming URLs with authentication |

---

## Custom Scopes

Located in [`injection/scopes/`](../../app/src/main/java/local/oss/chronicle/injection/scopes/)

### @ActivityScope

**File:** [`ActivityScope.kt`](../../app/src/main/java/local/oss/chronicle/injection/scopes/ActivityScope.kt)

Custom scope annotation for activity-scoped dependencies. Dependencies with this scope are created once per activity instance and shared across all fragments within that activity.

### @ServiceScope

**File:** [`ServiceScope.kt`](../../app/src/main/java/local/oss/chronicle/injection/scopes/ServiceScope.kt)

Custom scope annotation for service-scoped dependencies. Dependencies with this scope are tied to the MediaPlayerService lifecycle.

---

## Injection Points

### Application

[`ChronicleApplication.kt`](../../app/src/main/java/local/oss/chronicle/application/ChronicleApplication.kt) creates and holds the AppComponent:

```kotlin
class ChronicleApplication : Application() {
    lateinit var appComponent: AppComponent

    override fun onCreate() {
        super.onCreate()
        appComponent = DaggerAppComponent.builder()
            .appModule(AppModule(this))
            .build()
    }
}
```

### Injector Utility

[`Injector.kt`](../../app/src/main/java/local/oss/chronicle/application/Injector.kt) provides convenient access to components:

```kotlin
object Injector {
    fun getAppComponent(context: Context): AppComponent {
        return (context.applicationContext as ChronicleApplication).appComponent
    }
    
    fun getActivityComponent(activity: FragmentActivity): ActivityComponent {
        // Creates or retrieves ActivityComponent
    }
    
    fun getServiceComponent(service: Service): ServiceComponent {
        // Creates ServiceComponent for MediaPlayerService
    }
}
```

### Fragment Injection

Fragments inject dependencies in `onAttach()`:

```kotlin
class SettingsFragment : Fragment() {
    @Inject lateinit var plexConfig: PlexConfig
    @Inject lateinit var prefsRepo: PrefsRepo
    
    override fun onAttach(context: Context) {
        super.onAttach(context)
        Injector.getActivityComponent(requireActivity()).inject(this)
    }
}
```

### Service Injection

MediaPlayerService uses ServiceComponent:

```kotlin
class MediaPlayerService : MediaBrowserServiceCompat() {
    @Inject lateinit var exoPlayer: ExoPlayer
    @Inject lateinit var mediaSession: MediaSession
    
    override fun onCreate() {
        super.onCreate()
        Injector.getServiceComponent(this).inject(this)
    }
}
```

---

## Adding New Dependencies

### Step 1: Determine the appropriate scope

| If the dependency is... | Use scope |
|------------------------|-----------|
| Application-wide singleton | `@Singleton` in AppModule |
| Activity-specific | `@ActivityScope` in ActivityModule |
| MediaPlayerService-specific | `@ServiceScope` in ServiceModule |

### Step 2: Add provider method to module

```kotlin
@Module
class AppModule(private val application: Application) {
    
    @Provides
    @Singleton
    fun provideMyNewDependency(): MyNewDependency {
        return MyNewDependency()
    }
}
```

### Step 3: Add injection method to component (if needed)

```kotlin
@ActivityScope
@Subcomponent(modules = [ActivityModule::class])
interface ActivityComponent {
    fun inject(fragment: MyNewFragment)
}
```

### Step 4: Inject in target class

```kotlin
class MyNewFragment : Fragment() {
    @Inject lateinit var myNewDependency: MyNewDependency
    
    override fun onAttach(context: Context) {
        super.onAttach(context)
        Injector.getActivityComponent(requireActivity()).inject(this)
    }
}
```

---

## Related Documentation

- [Architecture Overview](../ARCHITECTURE.md) - High-level architecture diagrams
- [Architecture Layers](layers.md) - Layer descriptions and responsibilities
- [Architectural Patterns](patterns.md) - Key patterns used in Chronicle
- [Plex Integration](plex-integration.md) - Plex-specific implementation details
