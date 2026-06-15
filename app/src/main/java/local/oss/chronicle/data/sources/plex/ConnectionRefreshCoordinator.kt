package local.oss.chronicle.data.sources.plex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.util.NetworkMonitor
import local.oss.chronicle.util.NetworkState
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reacts to network changes by invalidating connection / playback URL caches and proactively
 * re-resolving every persisted [local.oss.chronicle.data.model.Library].
 *
 * **Why this exists (fix #2).**
 * The legacy [ChronicleApplication.setupNetwork][local.oss.chronicle.application.ChronicleApplication]
 * callback only handled `onAvailable`/`onLost`, used the deprecated no-retry
 * [PlexConfig.connectToServer], and never invalidated [ServerConnectionResolver] or the
 * [PlaybackUrlResolver] URL cache. As a result, when the user left their home Wi-Fi the app kept
 * trying the LAN URI it picked at login time and playback failed. This coordinator wires the
 * already-existing [NetworkMonitor] (which exposes `onCapabilitiesChanged` + WiFi/cellular
 * transport flags) to a single [refresh] routine that does the right thing.
 *
 * **Refresh routine.**
 * In order:
 *  1. [ServerConnectionResolver.invalidate] (global) — flush in-memory caches and arm a
 *     pending-reprobe marker so the next [ServerConnectionResolver.resolve] re-probes.
 *  2. [PlaybackUrlResolver.clearCache] — already-resolved streaming URLs point at the old server.
 *  3. [PlexConfig.connectToServerWithRetryAndState] — re-establish the legacy global `PlexConfig.url`
 *     with retries (replaces the deprecated no-retry path the old callback used).
 *  4. Iterate every persisted library and call [ServerConnectionResolver.reResolve] so each row's
 *     `chosenConnectionUri` is refreshed eagerly. This avoids waiting until the user hits Play to
 *     discover that the cached choice is dead.
 *
 * **Triggers.**
 *  - [start] launches a long-lived collector on [NetworkMonitor.networkState]:
 *      - If the current snapshot is already [NetworkState.Connected] when [start] runs, fire one
 *        initial refresh ("user opened the app on cellular after killing it overnight").
 *      - Subsequent emissions are filtered for *meaningful* changes (compared against the last
 *        state that fired a refresh) and debounced by [DEBOUNCE_MS] so a single Wi-Fi → Cellular
 *        handover (which produces 3-4 rapid state changes) results in exactly one refresh.
 *  - Fix #3 (player error recovery) calls [refresh] directly via this same instance.
 */
@Singleton
class ConnectionRefreshCoordinator
    @Inject
    constructor(
        private val networkMonitor: NetworkMonitor,
        private val serverConnectionResolver: ServerConnectionResolver,
        private val playbackUrlResolver: PlaybackUrlResolver,
        private val plexConfig: PlexConfig,
        private val plexMediaService: PlexMediaService,
        private val libraryRepository: LibraryRepository,
    ) {
        companion object {
            /**
             * Window over which to coalesce rapid [NetworkState] flapping. WiFi/cellular handovers
             * typically emit 3-4 capability changes within ~500ms; 1s is conservatively past that
             * while still feeling responsive to a real network change.
             */
            const val DEBOUNCE_MS: Long = 1000L
        }

        @Volatile private var started: Boolean = false

        /** The last [NetworkState] that *triggered* a refresh — used to filter no-op emissions. */
        @Volatile private var lastTriggerState: NetworkState? = null

        /**
         * Begin observing [NetworkMonitor.networkState] in [scope] and run the refresh routine
         * on meaningful transitions. Safe to call once during application startup.
         *
         * Also performs a one-shot startup refresh if the network is already
         * [NetworkState.Connected] at start time.
         */
        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        @InternalCoroutinesApi
        fun start(scope: CoroutineScope) {
            if (started) {
                Timber.d("ConnectionRefreshCoordinator.start() called more than once, ignoring")
                return
            }
            started = true

            // Startup refresh: if the network is already up when we boot, the user may have
            // launched on a different network from where they left off — do one refresh up front
            // rather than waiting for the next transport change.
            val initial = networkMonitor.currentState
            if (initial is NetworkState.Connected) {
                Timber.d("ConnectionRefreshCoordinator: initial state is Connected, firing startup refresh")
                lastTriggerState = initial
                scope.launch {
                    runRefresh()
                }
            }

            scope.launch {
                // StateFlow already conflates equal values, so no distinctUntilChanged() is needed
                // (and applying it to a StateFlow is a compile error). The .filter below removes
                // emissions that aren't meaningful network transitions.
                networkMonitor.networkState
                    .debounce(DEBOUNCE_MS)
                    .filter { state -> shouldTriggerRefresh(state) }
                    .onEach { state ->
                        Timber.i(
                            "ConnectionRefreshCoordinator: meaningful network transition $lastTriggerState -> $state, refreshing",
                        )
                        lastTriggerState = state
                    }
                    .collect {
                        runRefresh()
                    }
            }
        }

        /**
         * Public refresh entry point — fix #3 will invoke this from `MediaPlayerService.onPlayerError`
         * after a playback failure to force a fresh probe of every library.
         */
        @InternalCoroutinesApi
        suspend fun refresh() {
            runRefresh()
        }

        /**
         * Decides whether [next] is a "meaningful" change relative to the last state that triggered
         * a refresh.
         *
         * Rules:
         *  - Going from anything to [NetworkState.Connected] with a *different* transport profile is
         *    meaningful (Disconnected→Connected, WiFi→Cellular, etc.).
         *  - Identical Connected→Connected (same transport flags) is *not* meaningful.
         *  - Anything→Disconnected is not meaningful — there's nothing to refresh against.
         *  - First Connected emission after startup-refresh is filtered out (handled by
         *    [lastTriggerState] set by the startup path).
         *  - [NetworkState.Unknown] is never meaningful.
         */
        private fun shouldTriggerRefresh(next: NetworkState): Boolean {
            if (next !is NetworkState.Connected) return false
            val prev = lastTriggerState
            if (prev == null) {
                return true // first ever Connected state
            }
            if (prev !is NetworkState.Connected) {
                return true // Disconnected/Unknown -> Connected
            }
            // Both Connected — only fire if transport/metered flags actually changed.
            return prev != next
        }

        @InternalCoroutinesApi
        private suspend fun runRefresh() {
            Timber.i("ConnectionRefreshCoordinator: running refresh routine")
            // 1) Invalidate the connection resolver so the next resolve() re-probes.
            try {
                serverConnectionResolver.invalidate(null)
            } catch (e: Exception) {
                Timber.e(e, "ConnectionRefreshCoordinator: failed to invalidate ServerConnectionResolver")
            }

            // 2) Clear the playback URL cache; cached streaming URLs reference the old server URL.
            try {
                playbackUrlResolver.clearCache()
            } catch (e: Exception) {
                Timber.e(e, "ConnectionRefreshCoordinator: failed to clear PlaybackUrlResolver cache")
            }

            // 3) Re-establish global PlexConfig.url with the retry-enabled variant.
            try {
                plexConfig.connectToServerWithRetryAndState(plexMediaService)
            } catch (e: Exception) {
                Timber.e(e, "ConnectionRefreshCoordinator: failed to kick connectToServerWithRetryAndState")
            }

            // 4) Proactively re-resolve every persisted library so its chosenConnectionUri is fresh.
            val libraries =
                try {
                    libraryRepository.getAllLibrariesSnapshot()
                } catch (e: Exception) {
                    Timber.e(e, "ConnectionRefreshCoordinator: failed to enumerate libraries; skipping per-library reResolve")
                    emptyList()
                }
            Timber.d("ConnectionRefreshCoordinator: re-resolving ${libraries.size} libraries")
            for (library in libraries) {
                try {
                    serverConnectionResolver.reResolve(library.id)
                } catch (e: Exception) {
                    Timber.e(e, "ConnectionRefreshCoordinator: reResolve failed for libraryId=${library.id}")
                }
            }
        }
    }
