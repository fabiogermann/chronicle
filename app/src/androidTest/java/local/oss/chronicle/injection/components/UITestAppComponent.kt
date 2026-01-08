package local.oss.chronicle.injection.components

import dagger.Component
import local.oss.chronicle.features.login.OnboardingActivityTest
import local.oss.chronicle.injection.modules.UITestAppModule
import javax.inject.Singleton

@Component(modules = [UITestAppModule::class])
@Singleton
interface UITestAppComponent : AppComponent {
    // Inject
    fun inject(loginActivityTest: OnboardingActivityTest)
}
