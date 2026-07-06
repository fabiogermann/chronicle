# Chronicle — Code Review Findings

*Review date: 2026-07-06. Scope: full app audit against Android framework guidelines and best practices (targetSdk 36, minSdk 33, ~31k lines Kotlin). Companion document: [UX-REVIEW.md](UX-REVIEW.md).*

All file references are relative to `app/` unless otherwise noted. Findings are grouped by severity, then by area. Each includes the location, the problem, why it matters, and a proposed fix.

---

## Critical

### C1. `startForeground()` is gated on a coroutine that performs network I/O

**Where:** `src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt:325-328` (onCreate) and `:826-829` (onStartCommand); `NotificationBuilder.kt:249-255`.

Both `onCreate` and `onStartCommand` call `startForeground` from inside `serviceScope.launch(...)`. The coroutine first suspend-builds the notification, and `NotificationBuilder.buildNotification` fetches album art from the Plex server (`plexConfig.getBitmapFromServer(artUri)`) before returning. When the service is started with `startForegroundService()` (media-button restart-from-dead, notification action intents), the platform requires `startForeground()` within ~5 seconds or it throws `ForegroundServiceDidNotStartInTimeException` / ANRs. A slow server art fetch will blow that window. `serviceScope` also uses `Dispatchers.Main` (not `.immediate`), so even the happy path posts to the looper.

**Fix:** Call `startForeground()` synchronously and immediately in `onStartCommand`/`onCreate` with a lightweight placeholder notification built from cached/no artwork, then update the notification asynchronously once the bitmap loads. Never gate the first `startForeground` on suspend/network work.

### C2. No handling of `ForegroundServiceStartNotAllowedException` (API 31+/34+)

**Where:** `MediaPlayerService.kt:325-328`, `:461-468`, `:826-829`; `OnMediaChangedCallback.kt:93-96,103-106`.

On Android 12+ a foreground-service start from the background throws `ForegroundServiceStartNotAllowedException`; Android 14 further restricts `mediaPlayback` FGS starts. None of these call sites are wrapped in try/catch. One path (`prefsListener`, `:461-468`) re-calls `startForeground` from `Dispatchers.IO` merely because the jump-seconds preference changed.

**Fix:** Wrap every `startForeground` in try/catch for `ForegroundServiceStartNotAllowedException` (and `IllegalStateException`); only promote to foreground in response to an allowed trigger; remove the prefs-triggered re-promotion.

### C3. ExoPlayer is never `release()`d

**Where:** `MediaPlayerService.kt:732-802` (onDestroy); `ServiceModule.kt:71-82`.

`onDestroy` calls `exoPlayer.stop()`, `clearMediaItems()`, and `removeListener()`, and releases the *media session* (line 773) — but `exoPlayer.release()` is called nowhere in the codebase (verified). ExoPlayer holds decoders, internal playback threads, and audio-focus registration that are only freed by `release()`. A fresh `ExoPlayer` is created per service instance (`@ServiceScope`), so every service lifecycle leaks a player.

**Fix:** Call `exoPlayer.release()` in `onDestroy` after `removeListener`.

### C4. Release builds silently fall back to debug signing — and CI publishes them

**Where:** `build.gradle.kts:56,70-73`; `.github/workflows/release.yml:48-49,103`.

If `KEYSTORE_FILE`/`keystore.properties` is absent (secret rotation, fork, misconfigured runner), the build stays green, is debug-signed, and `release.yml` publishes it as a **non-draft** GitHub release. Users cannot update over a debug-signed APK and it is trivially re-signable. The pipeline fails open.

**Fix:** Throw a `GradleException` in the `release` build type when no release signing config resolves; fail the CI step when `KEYSTORE_BASE64` is empty instead of warning.

### C5. Auth tokens stored in plaintext and swept into cloud backups

**Where:**
- `injection/modules/AppModule.kt:53` + `data/sources/plex/SharedPreferencesPlexPrefsRepo.kt:59-93,137-171` — account token, server token, and full `PlexUser` JSON in plaintext SharedPreferences.
- `data/model/Library.kt:62-63` + `features/account/AccountManager.kt:145`, `ServerConnectionResolver.kt:235` — server access token also persisted in the unencrypted Room `libraries.authToken` column.
- `src/main/AndroidManifest.xml:21` — `allowBackup="true"` with no `dataExtractionRules` / `fullBackupContent` (no backup rules exist in `res/xml/`).
- `features/account/CredentialManager.kt:27-35` — the `chronicle_credentials` EncryptedSharedPreferences file **is** backed up, but its MasterKey lives in the AndroidKeyStore and is device-bound. After a restore onto a new device the ciphertext is undecryptable and the first read throws (`AEADBadTagException`) — a hard crash for restored users.

With Auto Backup and no exclusion rules, the plaintext prefs, `book_db`, and `chronicle_accounts.db` all get uploaded to Google Drive — Plex auth tokens leave the device.

**Fix:**
1. Route all token persistence through the encrypted credential store; keep only non-secret prefs in the plain file.
2. Do not persist tokens in the `libraries` row; resolve on demand from `CredentialManager` keyed by `accountId`.
3. Add `android:dataExtractionRules` + `android:fullBackupContent` XML excluding the credentials prefs, the accounts DB, and the encrypted-prefs file.
4. Wrap `EncryptedSharedPreferences.create()` in a recovery path that deletes and recreates the file on decryption failure.
5. Note: `androidx.security:security-crypto` is deprecated and pinned at an alpha (`1.1.0-alpha06`) — plan a migration (e.g. DataStore + manually managed Keystore AES/GCM key).

### C6. Abandoned download library (Fetch2) on the core download path

**Where:** `gradle/libs.versions.toml:16,65`; `build.gradle.kts:154`; `AppModule.kt:152`.

All downloads route through tonyofrancis Fetch2 3.3.0 — unmaintained for years, resolved from JitPack (on-demand source builds, unsigned). No upstream security fixes; supply-chain risk on a core feature. Related: `AppModule.kt:146` leaves Fetch's `OkHttpDownloader` commented out, so downloads bypass the app's OkHttp client/interceptors entirely.

**Fix:** Migrate downloads to WorkManager + OkHttp streaming (both already dependencies) or `DownloadManager`. Interim: pin the JitPack coordinate to a commit hash and wire the `OkHttpDownloader`.

---

## High

### H1. Legacy media stack is the root cause of most playback bugs

**Where:** `MediaPlayerService.kt:71-77` and the whole `features/player/` package.

The service is a legacy `MediaBrowserServiceCompat` + `MediaSessionCompat` + hand-rolled notification/FGS stack driving a Media3 ExoPlayer. Media3's `MediaLibraryService`/`MediaLibrarySession` correctly handles — per current guidelines — everything this code gets wrong by hand: `startForeground` timing (C1), `ForegroundServiceStartNotAllowedException` (C2), media-button routing, the media notification (`DefaultMediaNotificationProvider`), and audio-becoming-noisy. Related symptoms:

- Duplicate `MEDIA_BUTTON` handling: the service intent-filter (`AndroidManifest.xml:83`) **and** a standalone `androidx.media.session.MediaButtonReceiver` (`:92-98`). `MediaButtonReceiver.buildMediaButtonPendingIntent` requires exactly one component resolving the action or returns null.
- Legacy `MediaMetadataCompat`/`PlaybackStateCompat` observed in ViewModels (`MainActivityViewModel.kt:3-5`, `CurrentlyPlayingViewModel.kt:8-9`) — two sources of truth for playback state.
- `MediaStyle.setShowCancelButton` / swipe-dismiss logic (`NotificationBuilder.kt:220-227`, `OnMediaChangedCallback.kt:99-110`) is dead code on API 33+ where the system owns media-notification dismissal — i.e. on **all** supported OS versions.

**Fix (strategic):** Migrate to `MediaLibraryService` + `MediaLibrarySession`; delete `NotificationBuilder`, the manual FGS plumbing, and the standalone receiver; standardize UI observation on the Media3 `MediaController` surface. C1–C3 are the tactical stopgaps until then.

### H2. `POST_NOTIFICATIONS` is never requested at runtime

**Where:** declared at `AndroidManifest.xml:13`; zero `requestPermission` calls in the codebase.

minSdk 33 means every user is on Android 13+, where a denied `POST_NOTIFICATIONS` silently suppresses the media notification — playback with no transport controls or lock-screen surface. The lint baseline hides six related `MissingPermission` findings on bare `NotificationManager.notify()` calls (downloads-finished, now-playing, transfer-error).

**Fix:** Request the permission with rationale at/before first playback; guard `notify()` calls with a permission check and degrade gracefully.

### H3. Final progress save is lost on service destroy

**Where:** `MediaPlayerService.kt:748-764`; `ProgressUpdater.kt:195`.

`onDestroy` schedules the final `progressUpdater.updateProgress(...)` and `playbackStateController.clear()` on `serviceScope`, then cancels `serviceJob` a few lines later. The coroutines are cancelled before the DB/network write executes — the "stopped" progress update and final position persist are dropped. Related: `onTaskRemoved` (`:724-730`) stops the player but neither stops the service nor persists progress.

**Fix:** Perform the final flush synchronously (bounded `runBlocking` on `Dispatchers.IO`) or on a non-cancelled singleton scope before cancelling `serviceJob`.

### H4. No wake mode for network streaming

**Where:** `ServiceModule.kt:71-82`.

`ExoPlayer.Builder` never calls `setWakeMode(C.WAKE_MODE_NETWORK)`. With the screen off, CPU/Wi-Fi can sleep and stall streaming. Media3 manages the wake+wifi locks when the wake mode is set.

**Fix:** `setWakeMode(C.WAKE_MODE_NETWORK)` on the builder; add the `WAKE_LOCK` permission.

### H5. ViewModel leak via mismatched observer removal

**Where:** `application/MainActivityViewModel.kt:182` (register), `:254` (remove).

`init` calls `currentlyPlayingBookObserver.observeForever { book -> ... }` with an anonymous lambda; `onCleared()` calls `removeObserver { }` with a **new empty lambda**, which removes nothing. The LiveData belongs to the `@Singleton` `CurrentlyPlaying`, so every finished `MainActivityViewModel` is retained forever and keeps receiving stale updates. (The other two observers in this VM are held in fields and removed correctly.)

**Fix:** Hold the observer in a field and remove that reference:

```kotlin
private val bookObserver = Observer<Audiobook> { book -> ... }
init { currentlyPlayingBookObserver.observeForever(bookObserver) }
override fun onCleared() { currentlyPlayingBookObserver.removeObserver(bookObserver); ... }
```

### H6. `launchMode="singleInstance"` is wrong for this app

**Where:** `AndroidManifest.xml:34`; `application/MainActivity.kt:248-251`.

`singleInstance` forces `MainActivity` to be the only activity in its task, so every child activity (OSS licenses, billing, web links, `AuthReturnActivity`) is pushed into a separate task — breaking back-stack continuity and recents grouping. The OAuth return works only incidentally. Additionally, `onNewIntent` never calls `setIntent(intent)`, so later `getIntent()` reads (e.g. the `SearchManager.QUERY` read in `onCreate`) return stale extras on warm relaunches.

**Fix:** Use `launchMode="singleTask"` (or `standard` + `SINGLE_TOP` flags on notification/deep-link intents). In `onNewIntent`, call `super.onNewIntent(intent); setIntent(intent)` before handling.

### H7. Lint is disabled as a quality gate

**Where:** `build.gradle.kts:18-19`; `lint-baseline.xml` (4,242 lines, ~400 findings).

`abortOnError = false` plus a large baseline means CI never blocks on lint, even for new errors. Real defects hidden in the baseline: `MissingPermission` ×6 (see H2), `UnsafeOptInUsageError` ×17 (unmarked Media3 `@UnstableApi` usage), `ExportedService` ×1, `DataExtractionRules` ×1 (see C5). The baseline is also stale — captured against Gradle 8.13 while the wrapper is on 9.4.1. Bulk noise: `UnusedResources` ×90, `RtlHardcoded` ×23, `ObsoleteSdkInt` ×19.

**Fix:** Set `abortOnError = true`; triage the correctness classes out of the baseline (fix them); annotate Media3 call sites with `@OptIn(UnstableApi::class)`; regenerate the baseline against the current toolchain and shrink it over time.

### H8. Moshi reflection + R8 keep rules — release-only serialization breakage risk

**Where:** `build.gradle.kts:173-174`; `AppModule.kt:228`; `Account.kt:78`; `proguard-rules.pro:53-90`.

Moshi codegen was removed; runtime uses `KotlinJsonAdapterFactory` (reflection). Keep rules cover `data.model.**` and `plex.model.**`, but reflection Moshi serializes *any* Kotlin data class it is handed — DTOs outside those packages (e.g. under `features/**`; `Account.kt` builds its own Moshi) depend on constructor parameter names surviving R8. This is the classic "works in debug, `JsonDataException` in release" failure, and release CI runs only `testDebugUnitTest` (`release.yml:53`) so it would never be caught.

**Fix:** Switch to Moshi **KSP** codegen (`@JsonClass(generateAdapter = true)`), drop the reflection factory and `kotlin-reflect`. If staying on reflection, broaden keeps to every serialized package and add constructor keeps. Either way, add a release-variant smoke test to CI.

### H9. Dead Glide pipeline shipped alongside Fresco

**Where:** `libs.versions.toml:17`; `build.gradle.kts:151`; `ChronicleApplication.kt:122-126`; `PlexMediaSource.kt:74`.

Fresco is the real image stack (initialized at startup, 16 usage sites). Glide's only live references are a `Glide.get(...).clearDiskCache()` run **on every cold start** (marked "TODO: remove") and an unimplemented `makeGlideHeaders()` stub that throws. Two full image pipelines inflate APK size and method count for zero benefit.

**Fix:** Remove the Glide dependency, the startup cache purge (gate behind a one-time flag if needed for existing installs), and the stub method.

---

## Medium

### Playback / threading

- **`runBlocking` on hot paths (ANR risk).** `MediaItemTrack.getTrackSource()` (`data/model/MediaItemTrack.kt:185-191`) blocks on `serverConnectionResolver.resolve()`, which can hit Room and perform network probing on a cold cache — called from the player/main thread (`PlayerExt.kt:40-41`, `MediaPlayerService.kt:1349`). Same pattern in `PlexHttpDataSourceFactory.currentLibraryId` setter (`:54-69`) and the seek paths (`AudiobookMediaSessionCallback.kt:595`, `TrackListStateManager.kt:131-136,189-192`). **Fix:** pre-resolve URLs off the main thread (`PlaybackUrlResolver.preResolveUrls` already exists), make `getTrackSource()` a pure cache read, and make seek paths suspend.
- **`seekTo(-1)` crash on data inconsistency.** `PlayerExt.kt:63-69,107-113`: when a chapter's containing track isn't in the list, `indexOf` returns `-1` and is passed to `seekTo` → `IllegalSeekPositionException`. **Fix:** guard `containingTrackIndex >= 0`; fall back to relative seek.
- **Two sources of truth for the current chapter.** `onSeekTo` uses `currentlyPlaying.chapter.value` (`AudiobookMediaSessionCallback.kt:569-580`) while `buildPlaybackState` uses `playbackStateController.state.value.currentChapter` (`MediaPlayerService.kt:588`); `ProgressUpdater` mixes both. Momentary divergence at chapter boundaries produces wrong seek offsets. **Fix:** single authoritative source (`PlaybackStateController`) for all position math.
- **Progress tick counter: flood + race.** `ProgressUpdater.kt:281-361`: `tickCounter` only increments in `updateLocalProgress`; when `debugOnlyDisableLocalProgressTracking` is on, `0 % 30 == 0` fires a network scrobble every second. Also read/incremented from concurrent IO coroutines without synchronization. **Fix:** increment unconditionally; use `AtomicLong`.
- **`ToneGenerator` never released.** `SleepTimer.kt:66`, `ServiceModule.kt:199-203` — native audio resource leaked per service lifecycle. **Fix:** release in sleep-timer teardown or `onDestroy`.

### Security / networking

- **Plex token as URL query parameter on image/transcode URLs.** `PlexConfig.kt:275-320` (`makeThumbUri*`) — leaks into server/proxy logs, Referer headers, and Fresco's disk cache keys (`AppModule.kt:266-291`). API calls and playback already use the `X-Plex-Token` header. **Fix:** attach the token via an OkHttp header on the image client.
- **Global cleartext with no network security config.** `AndroidManifest.xml:28`; no `res/xml/network_security_config.xml`. LAN Plex servers legitimately need HTTP, but the global flag permits plaintext (and token exposure) to *any* host. **Fix:** `network_security_config.xml` permitting cleartext only for private/LAN ranges; drop the global flag.
- **`updateServerForSync` mutates global token/URL and never restores it.** `PlexConfig.kt:440-463`; callers (`PlexLoginRepo.kt:189-193`, library sync) never restore. In multi-account/multi-server setups the global token points at whichever server synced last → 401s / cross-account requests. **Fix:** restore previous state in a `finally`, or eliminate the global mutation in favor of the existing `ScopedPlexServiceFactory` pattern.
- **Stable device UUID sent as `X-Plex-Session-Identifier`.** `PlexHttpDataSourceFactory.kt:163` uses `plexPrefsRepo.uuid` where the interceptor uses a per-session random id (`PlexConfig.kt:140`). Makes playback sessions persistently correlatable. **Fix:** use `plexConfig.sessionIdentifier`.
- **Exported service without permission gating.** `AndroidManifest.xml:73-85` — required for MediaBrowser/Auto, but verify `auto_allowed_callers.xml` is actually enforced (PackageValidator is present and correct for `onGetRoot`).
- **OAuth callback accepts unauthenticated deep links.** `AuthReturnActivity` (exported, App Link + custom scheme) does no `state`/nonce validation of the returned URI. Blast radius is low (only expedites PIN polling; no token in the redirect), but validate the callback URI shape and ideally a nonce.
- **JitPack is a global repository.** `settings.gradle.kts:17` (and `FAIL_ON_PROJECT_REPOS` commented out at `:13`) — dependency-confusion risk. **Fix:** scope JitPack with `exclusiveContent` to the specific groups; enable `FAIL_ON_PROJECT_REPOS`.

### Architecture / lifecycle

- **Debug DB dump on every production launch.** `ChronicleApplication.kt:115` calls `logAccountsAndLibraries()` unconditionally (verified: no `BuildConfig.DEBUG` guard); it loads **every book** plus all accounts/libraries from Room on each cold start. **Fix:** guard with `BuildConfig.DEBUG` or delete.
- **Predictive back not supported.** No `android:enableOnBackInvokedCallback="true"` in the manifest; the always-enabled `OnBackPressedCallback` uses a re-entrant disable→dispatch→re-enable hack (`MainActivity.kt:158-183`). **Fix:** add the manifest flag; model back interception as state-driven enable/disable.
- **Edge-to-edge done with deprecated/ignored APIs.** `MainActivity.kt:104,115-119` uses `setDecorFitsSystemWindows(false)` (prefer `enableEdgeToEdge()`); `styles.xml:36-37` sets `statusBarColor`/`navigationBarColor`, which API 35+ **ignores**; insets are only applied to the bottom nav and are `CONSUMED` there, starving siblings; sheets/IME insets unhandled. **Fix:** `enableEdgeToEdge()`, remove dead bar-color attrs, per-view inset dispatch.
- **Deprecated `LocalBroadcastManager` for playback/sleep-timer events.** `MainActivity.kt`, `CurrentlyPlayingFragment/ViewModel`, `MediaPlayerService`. **Fix:** replace with a `SharedFlow`/`StateFlow` on an existing DI singleton.
- **No `SavedStateHandle` anywhere.** All transient UI state (mini-player book id, search text, library filter/sort) is in plain `MutableLiveData` — lost on process death. **Fix:** adopt `SavedStateHandle` for identity/selection state.
- **Fragment transactions committed from async observers.** `navigation/Navigator.kt:47-70` and all `show*()` use plain `commit()`; `showDetails()` is invoked after async DB I/O (`MainActivity.kt:267-274`). Window for `IllegalStateException` after `onSaveInstanceState`. **Fix:** `commitAllowingStateLoss()` for event-driven paths or gate on `RESUMED`.
- **Bottom-nav reselection rebuilds fragments.** `MainActivity.kt:134-144` + `Navigator.kt:125-144`: only `showHome()` guards against re-adding; other tabs `clearBackStack()` + `replace()` unconditionally, losing scroll/filter state; no per-tab back stacks. **Fix:** guard all tabs; consider FragmentManager multiple-back-stack APIs or Jetpack Navigation (file's own TODO).
- **KAPT retained solely for data binding.** `build.gradle.kts:9,91,144-146` plus the reflection-based `--add-opens` hack in the root `build.gradle.kts:13-43` that fails silently. **Fix:** migrate to view binding (or Compose), delete KAPT, the root hack, and `-Xallow-unstable-dependencies`.
- **Systemic version pinning.** `libs.versions.toml` pins ~11 libraries below latest with identical "KSP/Dagger compatibility" comments (media3 at 1.5.0 vs ~1.9.x, Dagger 2.52, etc.). One root incompatibility is likely (Dagger↔KSP↔Kotlin or the KAPT path); the blanket freeze blocks security updates. **Fix:** isolate the real constraint, then lift unrelated pins. Also `kotlinStdlib = "2.1.0"` diverges from `kotlin = "2.2.10"` and appears unreferenced — align or delete.
- **No app-bundle/ABI strategy on the GitHub release path.** `release.yml:61` publishes a single universal APK (Fresco + Media3 native libs for all ABIs) while the Play path uses AAB. **Fix:** publish per-ABI splits or the AAB; align the two paths.
- **Overly broad ProGuard keeps.** `proguard-rules.pro:63-64,90,124` keep all of Moshi, `data.model.**`, and **all of media3** `{ *; }` — media3 ships its own consumer rules. **Fix:** drop blanket keeps; rely on library consumer rules.

---

## Low

- `startForeground(id, notification)` with a nullable notification, no null check (`MediaPlayerService.kt:326-327,827-828`).
- Sleep timer ticks in wall-clock seconds regardless of playback speed, and hard-pauses with no fade-out (`SleepTimer.kt:97,137,143`).
- `PlaybackStateController` singleton scope never cancelled (`PlaybackStateController.kt:57`) — acceptable, worth bounding.
- Full auth tokens logged at `Timber.i` in debug builds (`PlexLoginRepo.kt:245-251`) — use the existing `SecurityUtils.hashToken(...)`.
- `Protocol.QUIC` configured on the media OkHttp client (`AppModule.kt:177`) — OkHttp doesn't negotiate QUIC; use `HTTP_2` + `HTTP_1_1`.
- StrictMode thread policy has every `detect*()` commented out — it detects nothing even in debug (`ChronicleApplication.kt:90-99`).
- ViewModels reach into the DI graph via static `Injector.get()` (multiple VMs) — inject dependencies through constructors for testability.
- Fragments use `activityComponent!!` non-null assertions (`HomeFragment.kt:44` et al.) — fragile restoration invariant.
- Meaningless `@ActivityScope` annotation on the Activity class itself (`MainActivity.kt:52`).
- `MainActivity` intent-filter mixes MAIN/LAUNCHER with `MEDIA_PLAY_FROM_SEARCH` in one filter (`AndroidManifest.xml:35-40`) — split into separate filters.
- Log-stripping `-assumenosideeffects` block commented out in `proguard-rules.pro:154-159`.
- `gradle.properties`: `org.gradle.parallel` disabled, `android.nonTransitiveRClass` commented out — both safe wins for a single-module app.
- Release CI runs only `testDebugUnitTest`; the R8-processed release variant is never exercised before publishing (`release.yml:53`).

---

## Verified-correct areas (no action needed)

- Room: real, ordered migrations (book 1→9, accounts 1→3); no destructive fallback; `allowMainThreadQueries` confined to test builders; DAO calls dispatched via `withContext(Dispatchers.IO)`; FKs indexed.
- Release logging is properly gated: OkHttp `HttpLoggingInterceptor` at `Level.NONE` in release; Timber `DebugTree` planted only in debug; StrictMode double-gated.
- No `GlobalScope` in production code; download `BroadcastReceiver` registered `RECEIVER_NOT_EXPORTED`; downloads write to app-scoped external storage (no scoped-storage violations); download requests send the token as a header.
- `PackageValidator` is the standard, correct signature-based caller validation for Android Auto's `onGetRoot`; Auto `onLoadChildren`/`onSearch` correctly `detach()` and run on `Dispatchers.IO`.
- Fragments largely avoid the retained-binding leak (local `val` bindings, `viewLifecycleOwner` used consistently); `observeForever` usages are paired with `removeObserver` everywhere except H5.

---

## Suggested remediation order

1. **Stability & data integrity (this release):** C1–C3 (FGS timing, FGS exception handling, `exoPlayer.release()`), H3 (final progress save), the `seekTo(-1)` guard, H2 (`POST_NOTIFICATIONS` request + guarded `notify()`), H5 (observer leak), the unguarded startup DB dump.
2. **Security & release engineering:** C4 (fail-closed signing), C5 (backup rules + token storage consolidation), network security config, H7 (re-enable lint gate), JitPack scoping.
3. **Strategic refactors:** H1 (Media3 `MediaLibraryService` migration — deletes most hand-rolled service code), C6 (replace Fetch2), KAPT/data-binding → view binding, Moshi KSP codegen (H8), lift the version-pin wall.
4. **UX:** see [UX-REVIEW.md](UX-REVIEW.md).
