package local.oss.chronicle.application

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
import android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
import androidx.lifecycle.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import local.oss.chronicle.application.MainActivityViewModel.BottomSheetState.*
import local.oss.chronicle.data.local.CollectionsRepository
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.model.*
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_FULLY
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlayingSingleton
import local.oss.chronicle.features.player.MediaServiceConnection
import local.oss.chronicle.features.player.id
import local.oss.chronicle.features.player.isPlaying
import local.oss.chronicle.util.DoubleLiveData
import local.oss.chronicle.util.Event
import local.oss.chronicle.util.mapAsync
import local.oss.chronicle.util.postEvent
import timber.log.Timber
import javax.inject.Inject

@ExperimentalCoroutinesApi
class MainActivityViewModel(
    loginRepo: IPlexLoginRepo,
    private val trackRepository: ITrackRepository,
    private val bookRepository: IBookRepository,
    private val mediaServiceConnection: MediaServiceConnection,
    collectionsRepository: CollectionsRepository,
    private val currentlyPlaying: CurrentlyPlaying,
) : ViewModel(), MainActivity.CurrentlyPlayingInterface {
    @Suppress("UNCHECKED_CAST")
    class Factory
        @Inject
        constructor(
            private val loginRepo: IPlexLoginRepo,
            private val trackRepository: ITrackRepository,
            private val bookRepository: IBookRepository,
            private val mediaServiceConnection: MediaServiceConnection,
            private val collectionsRepository: CollectionsRepository,
            private val currentlyPlaying: CurrentlyPlaying,
        ) : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainActivityViewModel::class.java)) {
                    return MainActivityViewModel(
                        loginRepo,
                        trackRepository,
                        bookRepository,
                        mediaServiceConnection,
                        collectionsRepository,
                        currentlyPlaying,
                    ) as T
                } else {
                    throw IllegalArgumentException(
                        "Cannot instantiate $modelClass from MainActivityViewModel.Factory",
                    )
                }
            }
        }

    /** The status of the bottom sheet which contains "currently playing" info */
    enum class BottomSheetState {
        COLLAPSED,
        HIDDEN,
        EXPANDED,
    }

    val isLoggedIn =
        loginRepo.loginEvent.map {
            it.peekContent() == LOGGED_IN_FULLY
        }

    private var _currentlyPlayingLayoutState = MutableLiveData(HIDDEN)
    val currentlyPlayingLayoutState: LiveData<BottomSheetState>
        get() = _currentlyPlayingLayoutState

    private var audiobookId = MutableLiveData(NO_AUDIOBOOK_FOUND_ID)

    val audiobook =
        mapAsync(audiobookId, viewModelScope) { id ->
            bookRepository.getAudiobookAsync(id) ?: EMPTY_AUDIOBOOK
        }

    /**
     * Observe CurrentlyPlayingSingleton.bookLiveData to immediately respond to
     * playback state changes (e.g., from Android Auto voice commands).
     * This ensures the mini player updates synchronously when PlaybackStateController
     * loads an audiobook, without waiting for MediaSession metadata.
     *
     * Uses LiveData directly instead of StateFlow.asLiveData() for robust observation
     * (avoids timing issues with asLiveData() conversion).
     */
    private val currentlyPlayingBookObserver: LiveData<Audiobook> =
        (currentlyPlaying as CurrentlyPlayingSingleton).bookLiveData

    private var tracks =
        audiobookId.switchMap { id ->
            if (id != NO_AUDIOBOOK_FOUND_ID) {
                trackRepository.getTracksForAudiobook(id)
            } else {
                MutableLiveData(emptyList())
            }
        }

    private var _errorMessage = MutableLiveData<Event<String>>()
    val errorMessage: LiveData<Event<String>>
        get() = _errorMessage

    /** Terminal playback errors (recovery gave up) — rendered with a "Run connection test" action. */
    private var _playbackErrorWithAction = MutableLiveData<Event<String>>()
    val playbackErrorWithAction: LiveData<Event<String>>
        get() = _playbackErrorWithAction

    val hasCollections = collectionsRepository.hasCollections()

    // Used to cache tracks.asChapterList when tracks changes
    private val tracksAsChaptersCache: LiveData<List<Chapter>> =
        DoubleLiveData(
            audiobookId,
            tracks,
        ) { bookId: String?, trackList: List<MediaItemTrack>? ->
            if (bookId != null && trackList != null) {
                trackList.asChapterList(bookId)
            } else {
                emptyList()
            }
        }

    val chapters: DoubleLiveData<Audiobook, List<Chapter>, List<Chapter>> =
        DoubleLiveData(
            audiobook,
            tracksAsChaptersCache,
        ) { _audiobook: Audiobook?, _tracksAsChapters: List<Chapter>? ->
            if (_audiobook?.chapters?.isNotEmpty() == true) {
                // We would really prefer this because it doesn't have to be computed
                _audiobook.chapters
            } else {
                _tracksAsChapters ?: emptyList()
            }
        }

    val currentChapterTitle: LiveData<String> =
        (currentlyPlaying as CurrentlyPlayingSingleton).chapterLiveData.map { chapter ->
            if (chapter == EMPTY_CHAPTER) {
                "No track playing"
            } else {
                chapter.title
            }
        }

    val isPlaying: LiveData<Boolean> =
        (currentlyPlaying as CurrentlyPlayingSingleton).isPlayingLiveData

    private val metadataObserver =
        Observer<MediaMetadataCompat> { metadata ->
            metadata.id?.let { trackId ->
                if (trackId.isNotEmpty()) {
                    viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
                        setAudiobook(trackId)
                    }
                }
            } ?: _currentlyPlayingLayoutState.postValue(HIDDEN)
        }

    private val playbackObserver =
        Observer<PlaybackStateCompat> { state ->
            Timber.i("Observing playback: $state")
            when (state.state) {
                STATE_STOPPED, STATE_NONE -> setBottomSheetState(HIDDEN)
                else -> {
                    if (currentlyPlayingLayoutState.value == HIDDEN) {
                        setBottomSheetState(COLLAPSED)
                    }
                }
            }
        }

    init {
        // Observe CurrentlyPlayingSingleton.book for immediate updates
        currentlyPlayingBookObserver.observeForever { book ->
            if (book != EMPTY_AUDIOBOOK && book.id != NO_AUDIOBOOK_FOUND_ID) {
                val currentId = audiobookId.value ?: NO_AUDIOBOOK_FOUND_ID
                if (currentId != book.id) {
                    Timber.i("Mini player: Book changed in CurrentlyPlayingSingleton: ${book.title} (ID: ${book.id})")
                    audiobookId.postValue(book.id)
                    if (_currentlyPlayingLayoutState.value == HIDDEN) {
                        _currentlyPlayingLayoutState.postValue(COLLAPSED)
                    }
                }
            } else if (book == EMPTY_AUDIOBOOK) {
                Timber.i("Mini player: Book cleared in CurrentlyPlayingSingleton")
                audiobookId.postValue(NO_AUDIOBOOK_FOUND_ID)
            }
        }

        // Keep MediaSession metadata observer as fallback for legacy code paths
        mediaServiceConnection.nowPlaying.observeForever(metadataObserver)
        mediaServiceConnection.playbackState.observeForever(playbackObserver)
    }

    private fun setAudiobook(trackId: String) {
        val previousAudiobookId = audiobook.value?.id ?: NO_AUDIOBOOK_FOUND_ID
        viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
            val bookId = trackRepository.getBookIdForTrack(trackId)
            // Only change the active audiobook if it differs from the one currently in metadata
            if (previousAudiobookId != bookId && bookId != NO_AUDIOBOOK_FOUND_ID) {
                audiobookId.postValue(bookId)
                if (_currentlyPlayingLayoutState.value == HIDDEN) {
                    _currentlyPlayingLayoutState.postValue(COLLAPSED)
                }
            }
        }
    }

    /**
     * React to clicks on the "currently playing" modal, which is shown at the bottom of the
     * R.layout.activity_main view when media is active (can be playing or paused)
     */
    fun onCurrentlyPlayingClicked() {
        when (currentlyPlayingLayoutState.value) {
            COLLAPSED -> _currentlyPlayingLayoutState.postValue(EXPANDED)
            EXPANDED -> _currentlyPlayingLayoutState.postValue(COLLAPSED)
            HIDDEN -> throw IllegalStateException("Cannot click on hidden sheet!")
            else -> {}
        }
    }

    fun pausePlayButtonClicked() {
        if (mediaServiceConnection.isConnected.value != true) {
            mediaServiceConnection.connect(this::pausePlay)
        } else {
            pausePlay()
        }
    }

    private fun pausePlay() {
        // Require [mediaServiceConnection] is connected
        check(mediaServiceConnection.isConnected.value == true)
        val transportControls = mediaServiceConnection.transportControls
        mediaServiceConnection.playbackState.value?.let { playbackState ->
            if (playbackState.isPlaying) {
                Timber.i("Pausing!")
                transportControls?.pause()
            } else {
                Timber.i("Playing!")
                transportControls?.play()
            }
        }
    }

    override fun onCleared() {
        currentlyPlayingBookObserver.removeObserver { }
        mediaServiceConnection.nowPlaying.removeObserver(metadataObserver)
        mediaServiceConnection.playbackState.removeObserver(playbackObserver)
        super.onCleared()
    }

    override fun setBottomSheetState(state: BottomSheetState) {
        _currentlyPlayingLayoutState.postValue(state)
    }

    fun showUserMessage(errorMessage: String) {
        Timber.i("Showing error message: $errorMessage")
        _errorMessage.postEvent(errorMessage)
    }

    /**
     * Posts a terminal playback error. Rendered by MainActivity as a Snackbar with an inline
     * "Run connection test" action that opens the connection-test screen.
     */
    fun showPlaybackErrorWithAction(errorMessage: String) {
        Timber.i("Showing terminal playback error: $errorMessage")
        _playbackErrorWithAction.postEvent(errorMessage)
    }

    /** Minimize the currently playing modal/overlay if it is expanded */
    fun minimizeCurrentlyPlaying() {
        if (currentlyPlayingLayoutState.value == EXPANDED) {
            _currentlyPlayingLayoutState.postValue(COLLAPSED)
        }
    }

    /** Maximize the currently playing modal/overlay if it is visible, but not expanded yet */
    fun maximizeCurrentlyPlaying() {
        if (currentlyPlayingLayoutState.value != EXPANDED) {
            _currentlyPlayingLayoutState.postValue(EXPANDED)
        }
    }

    fun onCurrentlyPlayingHandleDragged() {
        if (currentlyPlayingLayoutState.value == COLLAPSED) {
            _currentlyPlayingLayoutState.postValue(EXPANDED)
        }
    }
}
