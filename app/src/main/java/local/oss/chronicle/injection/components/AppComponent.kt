package local.oss.chronicle.injection.components

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.facebook.imagepipeline.core.ImagePipelineConfig
import com.squareup.moshi.Moshi
import com.tonyodev.fetch2.Fetch
import dagger.Component
import local.oss.chronicle.application.ChronicleApplication
import local.oss.chronicle.application.ChronicleBillingManager
import local.oss.chronicle.data.local.*
import local.oss.chronicle.data.sources.plex.*
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.features.login.ChooseLibraryFragment
import local.oss.chronicle.features.login.ChooseServerFragment
import local.oss.chronicle.features.login.ChooseUserFragment
import local.oss.chronicle.features.login.LoginFragment
import local.oss.chronicle.injection.modules.AppModule
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class])
interface AppComponent {
    fun applicationContext(): Context

    fun internalFilesDir(): File

    fun externalDeviceDirs(): List<File>

    fun sharedPrefs(): SharedPreferences

    fun trackDao(): TrackDao

    fun bookDao(): BookDao

    fun collectionsDao(): CollectionsDao

    fun moshi(): Moshi

    fun plexLoginRepo(): IPlexLoginRepo

    fun plexPrefs(): PlexPrefsRepo

    fun prefsRepo(): PrefsRepo

    fun trackRepo(): ITrackRepository

    fun librarySyncRepo(): LibrarySyncRepository

    fun collectionsRepo(): CollectionsRepository

    fun bookRepo(): IBookRepository

    fun bookRepos(): BookRepository

    fun workManager(): WorkManager

    fun unhandledExceptionHandler(): CoroutineExceptionHandler

    fun plexConfig(): PlexConfig

    fun plexLoginService(): PlexLoginService

    fun plexMediaService(): PlexMediaService

    fun cachedFileManager(): ICachedFileManager

    fun currentlyPlaying(): CurrentlyPlaying

    fun fetch(): Fetch

    fun frescoConfig(): ImagePipelineConfig

    //    fun plexMediaSource(): PlexMediaSource
    fun billingManager(): ChronicleBillingManager

    // Inject
    fun inject(chronicleApplication: ChronicleApplication)

    fun inject(loginFragment: LoginFragment)

    fun inject(chooseLibraryFragment: ChooseLibraryFragment)

    fun inject(chooseUserFragment: ChooseUserFragment)

    fun inject(chooseServerActivity: ChooseServerFragment)
}
