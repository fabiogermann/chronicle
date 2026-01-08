package local.oss.chronicle.application

import local.oss.chronicle.injection.components.AppComponent
import local.oss.chronicle.injection.components.DaggerUITestAppComponent
import local.oss.chronicle.injection.modules.UITestAppModule
import timber.log.Timber
import javax.inject.Singleton

@Singleton
class TestChronicleApplication : ChronicleApplication() {
    override fun initializeComponent(): AppComponent {
        Timber.i("Test chronicle application component")
        return DaggerUITestAppComponent.builder()
            .uITestAppModule(UITestAppModule(applicationContext)).build()
    }
}
