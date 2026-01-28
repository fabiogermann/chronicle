package local.oss.chronicle.injection.components

import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.Component
import local.oss.chronicle.application.MainActivity
import local.oss.chronicle.application.MainActivityViewModel
import local.oss.chronicle.features.account.AccountListFragment
import local.oss.chronicle.features.account.AccountListViewModel
import local.oss.chronicle.features.account.LibrarySelectorBottomSheet
import local.oss.chronicle.features.bookdetails.AudiobookDetailsFragment
import local.oss.chronicle.features.bookdetails.AudiobookDetailsViewModel
import local.oss.chronicle.features.collections.CollectionDetailsFragment
import local.oss.chronicle.features.collections.CollectionsFragment
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlayingFragment
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlayingViewModel
import local.oss.chronicle.features.home.HomeFragment
import local.oss.chronicle.features.library.LibraryFragment
import local.oss.chronicle.features.player.MediaServiceConnection
import local.oss.chronicle.features.player.ProgressUpdater
import local.oss.chronicle.features.settings.DebugInfoDialogFragment
import local.oss.chronicle.features.settings.DebugInfoViewModel
import local.oss.chronicle.features.settings.SettingsFragment
import local.oss.chronicle.features.settings.SettingsViewModel
import local.oss.chronicle.injection.modules.ActivityModule
import local.oss.chronicle.injection.scopes.ActivityScope
import local.oss.chronicle.navigation.Navigator
import local.oss.chronicle.views.ModalBottomSheetSpeedChooser

@ActivityScope
@Component(dependencies = [AppComponent::class], modules = [ActivityModule::class])
interface ActivityComponent {
    fun navigator(): Navigator

    fun progressUpdater(): ProgressUpdater

    fun localBroadcastManager(): LocalBroadcastManager

    fun mediaServiceConnection(): MediaServiceConnection

    fun mainActivityViewModelFactory(): MainActivityViewModel.Factory

    fun currentPlayingViewModelFactory(): CurrentlyPlayingViewModel.Factory

    fun audiobookDetailsViewModelFactory(): AudiobookDetailsViewModel.Factory

    fun settingsViewModelFactory(): SettingsViewModel.Factory

    fun debugInfoViewModelFactory(): DebugInfoViewModel.Factory

    fun accountListViewModelFactory(): AccountListViewModel.Factory

    fun inject(activity: MainActivity)

    fun inject(accountListFragment: AccountListFragment)

    fun inject(librarySelectorBottomSheet: LibrarySelectorBottomSheet)

    fun inject(libraryFragment: LibraryFragment)

    fun inject(detailsFragment: AudiobookDetailsFragment)

    fun inject(homeFragment: HomeFragment)

    fun inject(settingsFragment: SettingsFragment)

    fun inject(collectionsFragment: CollectionsFragment)

    fun inject(collectionDetailsFragment: CollectionDetailsFragment)

    fun inject(currentlyPlayingFragment: CurrentlyPlayingFragment)

    fun inject(modalBottomSheetSpeedChooser: ModalBottomSheetSpeedChooser)

    fun inject(debugInfoDialogFragment: DebugInfoDialogFragment)
}
