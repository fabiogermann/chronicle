package local.oss.chronicle.application

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import com.bumptech.glide.Glide
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.core.ImagePipelineConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.asServer
import local.oss.chronicle.data.sources.plex.*
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.features.account.LegacyAccountMigration
import local.oss.chronicle.injection.components.AppComponent
import local.oss.chronicle.injection.components.DaggerAppComponent
import local.oss.chronicle.injection.modules.AppModule
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Exposing a ref to the application statically doesn't leak anything because Application is already
// a singleton
@Suppress("LeakingThis")
@Singleton
open class ChronicleApplication : Application() {
    // Instance of the AppComponent that will be used by all the Activities in the project
    val appComponent by lazy {
        initializeComponent()
    }

    init {
        INSTANCE = this
    }

    private var applicationJob = Job()
    private val applicationScope = CoroutineScope(applicationJob + Dispatchers.Main)

    @Inject
    lateinit var plexPrefs: PlexPrefsRepo

    @Inject
    lateinit var plexMediaService: PlexMediaService

    @Inject
    lateinit var plexConfig: PlexConfig

    @Inject
    lateinit var prefsRepo: PrefsRepo

    @Inject
    lateinit var billingManager: ChronicleBillingManager

    @Inject
    lateinit var unhandledExceptionHandler: CoroutineExceptionHandler

    @Inject
    lateinit var cachedFileManager: ICachedFileManager

    @Inject
    lateinit var plexLoginService: PlexLoginService

    @Inject
    lateinit var frescoConfig: ImagePipelineConfig

    @Inject
    lateinit var legacyAccountMigration: LegacyAccountMigration

    override fun onCreate() {
        if (USE_STRICT_MODE && BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
//                    choose which ones you want
//                    .detectDiskReads()
//                    .detectDiskWrites()
//                    .detectNetwork() // or .detectAll() for all detectable problems
                    .penaltyLog()
                    .penaltyDeath()
                    .build(),
            )
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .penaltyDeath()
                    .build(),
            )
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        appComponent.inject(this)
        logAccountsAndLibraries() // DEBUG: Log database state
        setupNetwork(plexPrefs)
        updateDownloadedFileState()
        runLegacyAccountMigration()
        super.onCreate()
        Fresco.initialize(this, frescoConfig)
        // TODO: remove in a future version
        applicationScope.launch {
            withContext(Dispatchers.IO) {
                Glide.get(Injector.get().applicationContext()).clearDiskCache()
            }
        }
    }

    /**
     * Updates the book and track repositories to reflect the true state of downloaded files
     */
    private fun updateDownloadedFileState() {
        applicationScope.launch {
            withContext(Dispatchers.IO) {
                cachedFileManager.refreshTrackDownloadedStatus()
            }
        }
    }

    /**
     * DEBUG: Log all accounts and libraries at startup
     */
    private fun logAccountsAndLibraries() {
        applicationScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val accountRepository = appComponent.accountRepository()
                    val libraryRepository = appComponent.libraryRepository()
                    val bookRepository = appComponent.bookRepos()
                    
                    val accountCount = accountRepository.getAccountCount()
                    Timber.i("DEBUG_STARTUP: Found $accountCount accounts in database")
                    
                    val accounts = accountRepository.getAllAccounts().first()
                    accounts.forEachIndexed { index, account ->
                        Timber.i("DEBUG_STARTUP:   Account ${index + 1}: ${account.displayName} (ID: ${account.id}, Provider: ${account.providerType})")
                    }
                    
                    val libraries = libraryRepository.getAllLibraries().first()
                    Timber.i("DEBUG_STARTUP: Found ${libraries.size} libraries in database")
                    libraries.forEachIndexed { index, library ->
                        Timber.i("DEBUG_STARTUP:   Library ${index + 1}: ${library.name} (ID: ${library.id}, Account: ${library.accountId}, Server: ${library.serverId}, Active: ${library.isActive})")
                    }
                    
                    val bookCount = bookRepository.getBookCount()
                    Timber.i("DEBUG_STARTUP: Found $bookCount books in database")
                    
                    // Log which libraries have books
                    val allBooks = bookRepository.getAllBooksAsync()
                    val booksGroupedByLibrary = allBooks.groupBy { it.libraryId }
                    Timber.i("DEBUG_STARTUP: Books distribution by library:")
                    booksGroupedByLibrary.forEach { (libraryId, books) ->
                        val libraryName = libraries.find { it.id == libraryId }?.name ?: "Unknown"
                        Timber.i("DEBUG_STARTUP:   Library '$libraryName' ($libraryId): ${books.size} books")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "DEBUG_STARTUP: Failed to log accounts/libraries")
                }
            }
        }
    }

    /**
     * Run legacy account migration on first launch.
     * Migrates existing single-account data to the new multi-account schema.
     */
    private fun runLegacyAccountMigration() {
        applicationScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    legacyAccountMigration.migrate()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to run legacy account migration")
                }
            }
        }
    }

    open fun initializeComponent(): AppComponent {
        // We pass the applicationContext that will be used as Context in the graph
        return DaggerAppComponent.builder().appModule(AppModule(this)).build()
    }

    companion object {
        private var INSTANCE: ChronicleApplication? = null

        @JvmStatic
        fun get(): ChronicleApplication = INSTANCE!!
    }

    /**
     * Determines if the server list should be refreshed from plex.tv
     * Debug builds: always refresh
     * Release builds: refresh if > 24 hours old
     */
    private fun shouldRefreshServerList(lastRefreshed: Long): Boolean {
        // Debug builds: always refresh
        if (BuildConfig.DEBUG) return true

        // Release builds: refresh if > 24 hours old
        val twentyFourHoursMs = 24 * 60 * 60 * 1000L
        return (System.currentTimeMillis() - lastRefreshed) > twentyFourHoursMs
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun setupNetwork(plexPrefs: PlexPrefsRepo) {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(
                object :
                    ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        connectToServer()
                        super.onAvailable(network)
                    }

                    override fun onLost(network: Network) {
                        // Prevent from running on ConnectivityThread, because onLost is apparently
                        // called on ConnectivityThread with no warning
                        applicationScope.launch {
                            withContext(Dispatchers.Main) {
                                plexConfig.connectionHasBeenLost()
                            }
                        }
                        super.onLost(network)
                    }
                },
            )
        } else {
            // network listener for sdk 24 and below
            registerReceiver(
                networkStateListener,
                IntentFilter().apply {
                    @Suppress("DEPRECATION")
                    addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                },
            )
        }
        val server = plexPrefs.server
        if (server != null) {
            Timber.d(
                "URL_DEBUG: App startup - stored server '${server.name}' has ${server.connections.size} connections: ${server.connections.map { "${it.uri} (local=${it.local})" }}",
            )
            plexConfig.setPotentialConnections(server.connections)

            // Check if we should refresh the server list
            val shouldRefresh = shouldRefreshServerList(plexPrefs.serverListLastRefreshed)
            Timber.d("Server list refresh check: shouldRefresh=$shouldRefresh, lastRefreshed=${plexPrefs.serverListLastRefreshed}")

            if (shouldRefresh) {
                applicationScope.launch(unhandledExceptionHandler) {
                    val retrievedConnections: List<Connection> =
                        withTimeoutOrNull(4000L) {
                            try {
                                plexLoginService.resources()
                                    .filter { it.provides.contains("server") }
                                    .map { it.asServer() }
                                    .filter { it.serverId == server.serverId }
                                    .flatMap { it.connections }
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve new connections: $e")
                                emptyList()
                            }
                        } ?: emptyList()

                    if (retrievedConnections.isNotEmpty()) {
                        // Fresh data retrieved - REPLACE connections (don't merge)
                        Timber.d(
                            "URL_DEBUG: App startup - retrieved ${retrievedConnections.size} fresh connections from plex.tv: ${retrievedConnections.map { "${it.uri} (local=${it.local})" }}",
                        )
                        Timber.i("Retrieved fresh connections (replacing cached): $retrievedConnections")

                        plexPrefs.server =
                            server.copy(
                                connections = retrievedConnections,
                            )

                        // Update timestamp after successful refresh
                        plexPrefs.serverListLastRefreshed = System.currentTimeMillis()
                        Timber.d("Server list refreshed successfully, timestamp updated")
                    } else {
                        // Network fetch failed or timed out - continue with cached connections
                        Timber.w("Failed to retrieve fresh connections, continuing with ${server.connections.size} cached connections")
                    }

                    try {
                        Timber.i("Connection to server!")
                        plexConfig.connectToServer(plexMediaService)
                    } catch (t: Throwable) {
                        Timber.e("Exception in chooseViableConnections in ChronicleApplication: $t")
                    }
                }
            } else {
                // No refresh needed - connect with cached connections
                Timber.d("Using cached server connections (${server.connections.size} connections)")
                applicationScope.launch(unhandledExceptionHandler) {
                    try {
                        Timber.i("Connection to server!")
                        plexConfig.connectToServer(plexMediaService)
                    } catch (t: Throwable) {
                        Timber.e("Exception in chooseViableConnections in ChronicleApplication: $t")
                    }
                }
            }
        }
    }

    @InternalCoroutinesApi
    private val networkStateListener =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                applicationScope.launch {
                    if (context != null && intent != null) {
                        plexConfig.connectionHasBeenLost()
                        connectToServer()
                    }
                }
            }
        }

    // Connect to the first connection which can establish a connection
    @InternalCoroutinesApi
    private fun connectToServer() {
        plexConfig.connectToServer(plexMediaService)
    }

    override fun onTrimMemory(level: Int) {
        Fresco.getImagePipeline().clearMemoryCaches()
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        Fresco.getImagePipeline().clearMemoryCaches()
        super.onLowMemory()
    }
}
