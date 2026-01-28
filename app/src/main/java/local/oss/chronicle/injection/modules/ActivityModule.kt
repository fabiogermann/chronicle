package local.oss.chronicle.injection.modules

import android.content.ComponentName
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import local.oss.chronicle.features.account.AccountListViewModel
import local.oss.chronicle.features.account.AccountManager
import local.oss.chronicle.features.account.ActiveLibraryProvider
import local.oss.chronicle.features.player.MediaPlayerService
import local.oss.chronicle.features.player.MediaServiceConnection
import local.oss.chronicle.features.player.ProgressUpdater
import local.oss.chronicle.features.player.SimpleProgressUpdater
import local.oss.chronicle.injection.scopes.ActivityScope
import local.oss.chronicle.navigation.Navigator
import local.oss.chronicle.util.ServiceUtils
import timber.log.Timber

@Module
class ActivityModule(private val activity: AppCompatActivity) {
    @Provides
    @ActivityScope
    fun activity(): AppCompatActivity = activity

    @Provides
    @ActivityScope
    fun coroutineScope(): CoroutineScope = activity.lifecycleScope

    @Provides
    @ActivityScope
    fun fragmentManager(): FragmentManager = activity.supportFragmentManager

    @Provides
    @ActivityScope
    fun provideProgressUpdater(progressUpdater: SimpleProgressUpdater): ProgressUpdater = progressUpdater

    @Provides
    @ActivityScope
    fun provideBroadcastManager(): LocalBroadcastManager =
        LocalBroadcastManager.getInstance(
            activity,
        )

    @Provides
    @ActivityScope
    fun mediaServiceConnection(): MediaServiceConnection {
        val conn =
            MediaServiceConnection(
                activity.applicationContext,
                ComponentName(activity.applicationContext, MediaPlayerService::class.java),
            )
        val doesServiceExist =
            ServiceUtils.isServiceRunning(
                activity.applicationContext,
                MediaPlayerService::class.java,
            )
        Timber.i("Connecting to existing service? $doesServiceExist")
        if (doesServiceExist) {
            conn.connect()
        }
        return conn
    }

    @Provides
    @ActivityScope
    fun provideAccountListViewModelFactory(
        accountManager: AccountManager,
        activeLibraryProvider: ActiveLibraryProvider,
        navigator: Navigator
    ): AccountListViewModel.Factory {
        return AccountListViewModel.Factory(accountManager, activeLibraryProvider, navigator)
    }
}
