package local.oss.chronicle.application

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import local.oss.chronicle.R
import local.oss.chronicle.application.MainActivityViewModel.BottomSheetState.COLLAPSED
import local.oss.chronicle.application.MainActivityViewModel.BottomSheetState.EXPANDED
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.model.EMPTY_AUDIOBOOK
import local.oss.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_FULLY
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.databinding.ActivityMainBinding
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlayingFragment
import local.oss.chronicle.features.player.MediaPlayerService.Companion.ACTION_PLAYBACK_ERROR
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLAYBACK_ERROR_MESSAGE
import local.oss.chronicle.features.player.MediaServiceConnection
import local.oss.chronicle.injection.components.ActivityComponent
import local.oss.chronicle.injection.components.DaggerActivityComponent
import local.oss.chronicle.injection.modules.ActivityModule
import local.oss.chronicle.injection.scopes.ActivityScope
import local.oss.chronicle.navigation.Navigator
import local.oss.chronicle.util.observeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var localBroadcastManager: LocalBroadcastManager

    @Inject
    lateinit var mainActivityViewModelFactory: MainActivityViewModel.Factory

    private val viewModel: MainActivityViewModel by lazy {
        ViewModelProvider(this, mainActivityViewModelFactory).get(MainActivityViewModel::class.java)
    }

    @Inject
    lateinit var plexLoginRepo: IPlexLoginRepo

    @Inject
    lateinit var navigator: Navigator

    @Inject
    lateinit var plexPrefsRepo: PlexPrefsRepo

    @Inject
    lateinit var bookRepository: IBookRepository

    @Inject
    lateinit var trackRepository: ITrackRepository

    @Inject
    lateinit var plexConfig: PlexConfig

    @Inject
    lateinit var mediaServiceConnection: MediaServiceConnection

    var activityComponent: ActivityComponent? = null

    override fun onDestroy() {
        activityComponent = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.i("MainActivity onCreate()")
        activityComponent =
            DaggerActivityComponent.builder()
                .appComponent((application as ChronicleApplication).appComponent)
                .activityModule(ActivityModule(this))
                .build()
        activityComponent!!.inject(this)

        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display for MD3
        WindowCompat.setDecorFitsSystemWindows(window, false)

        localBroadcastManager = LocalBroadcastManager.getInstance(this)

        val binding =
            DataBindingUtil.setContentView<ActivityMainBinding>(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.viewModel = viewModel
        binding.plexConfig = plexConfig

        // Apply window insets to bottom navigation for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.currentlyPlayingHandle.setOnClickListener {
            viewModel.onCurrentlyPlayingClicked()
        }

        viewModel.errorMessage.observeEvent(this) { errorMessage ->
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }

        // TODO: show/hide this item on launch more performantly
        viewModel.hasCollections.observe(this) {
            binding.bottomNav.menu.findItem(R.id.nav_collections).isVisible = it
        }

        binding.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_settings -> navigator.showSettings()
                R.id.nav_library -> navigator.showLibrary()
                R.id.nav_collections -> navigator.showCollections()
                R.id.nav_home -> navigator.showHome()
                else -> throw NoWhenBranchMatchedException("Unknown bottom tab id: ${it.itemId}")
            }
            viewModel.minimizeCurrentlyPlaying()
            return@setOnItemSelectedListener true
        }

        if (savedInstanceState == null) {
            setupCurrentlyPlaying()
            // Re-post a fresh login state event for this new activity instance
            plexLoginRepo.determineLoginState()
            plexLoginRepo.loginEvent.value?.let {
                if (it.peekContent() == LOGGED_IN_FULLY) {
                    navigator.showHome()
                }
            }
        }

        // Handle back button press with modern OnBackPressedCallback
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If currently playing view is over fragments, close it via back button
                if (viewModel.currentlyPlayingLayoutState.value == EXPANDED) {
                    viewModel.setBottomSheetState(COLLAPSED)
                    return
                }
                // default to activity back stack if navigator did not handle anything
                if (!navigator.onBackPressed()) {
                    Timber.i("MainActivity handleOnBackPressed()")
                    if (supportFragmentManager.backStackEntryCount == 0) {
                        // The prevent Q+ from leaking the activity internally, don't call
                        // super.onBackPressed() if at base fragment, manually end...
                        finishAfterTransition()
                    } else {
                        // Let the system handle the back press
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        // If the app is being launched by voice assistant with a query
        val query = intent.getStringExtra(SearchManager.QUERY)
        if (!query.isNullOrEmpty()) {
            mediaServiceConnection.connect {
                mediaServiceConnection.transportControls?.playFromSearch(query, Bundle())
            }
        }

        handleNotificationIntent(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCurrentlyPlaying() {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(
            R.id.currently_playing_fragment_container,
            CurrentlyPlayingFragment.newInstance(),
        )
        transaction.commit()
        val handle = findViewById<View>(R.id.currently_playing_handle)
        val gd =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onScroll(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        distanceX: Float,
                        distanceY: Float,
                    ): Boolean {
                        if (distanceY > distanceX) {
                            viewModel.onCurrentlyPlayingHandleDragged()
                        }
                        return super.onScroll(e1, e2, distanceX, distanceY)
                    }
                },
            )
        handle.setOnTouchListener { v, event ->
            gd.onTouchEvent(event)
            v.onTouchEvent(event)
        }
    }

    interface CurrentlyPlayingInterface {
        fun setBottomSheetState(state: MainActivityViewModel.BottomSheetState)
    }

    fun getCurrentlyPlayingInterface(): CurrentlyPlayingInterface {
        return viewModel
    }

    override fun onStart() {
        super.onStart()
        Timber.i("MainActivity onStart()")
        localBroadcastManager.registerReceiver(onPlaybackError, IntentFilter(ACTION_PLAYBACK_ERROR))
    }

    override fun onStop() {
        Timber.i("MainActivity onStop()")
        localBroadcastManager.unregisterReceiver(onPlaybackError)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val openCurrentlyPlaying =
            intent?.extras?.getBoolean(
                FLAG_OPEN_ACTIVITY_TO_CURRENTLY_PLAYING, false,
            ) == true
        if (openCurrentlyPlaying) {
            viewModel.maximizeCurrentlyPlaying()
        }

        val openAudiobookWithId =
            intent?.extras?.getInt(
                FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID, NO_AUDIOBOOK_FOUND_ID,
            ) ?: NO_AUDIOBOOK_FOUND_ID
        if (openAudiobookWithId != NO_AUDIOBOOK_FOUND_ID) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val audiobook = bookRepository.getAudiobookAsync(openAudiobookWithId)
                    if (audiobook != null && audiobook != EMPTY_AUDIOBOOK) {
                        navigator.showDetails(audiobook.id, audiobook.title, audiobook.isCached)
                    }
                }
            }
        }
    }

    private val onPlaybackError =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    ACTION_PLAYBACK_ERROR -> {
                        val errorMessage =
                            intent.getStringExtra(PLAYBACK_ERROR_MESSAGE)
                                ?: getString(R.string.playback_error_unknown)
                        val userMessage =
                            when {
                                errorMessage.contains(
                                    "404",
                                ) -> getString(R.string.playback_error_404)
                                errorMessage.contains(
                                    "503",
                                ) -> getString(R.string.playback_error_503)
                                errorMessage.contains(
                                    "401",
                                ) -> getString(R.string.playback_error_401)
                                else -> errorMessage
                            }
                        viewModel.showUserMessage(userMessage)
                    }
                    else -> throw NoWhenBranchMatchedException(
                        getString(R.string.playback_error_unknown),
                    )
                }
            }
        }

    companion object {
        const val FLAG_OPEN_ACTIVITY_TO_CURRENTLY_PLAYING = "OPEN_ACTIVITY_TO_AUDIOBOOK"
        const val REQUEST_CODE_OPEN_APP_TO_CURRENTLY_PLAYING = -12
        const val FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID = "OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID"

        // add audiobook id to this number to avoid repeats
        const val REQUEST_CODE_PREFIX_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID = -1001110
    }
}
