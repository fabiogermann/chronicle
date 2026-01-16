package local.oss.chronicle.injection.modules

import dagger.Binds
import dagger.Module
import local.oss.chronicle.billing.IBillingManager
import local.oss.chronicle.billing.PlayBillingManager
import local.oss.chronicle.licenses.ILicensesHandler
import local.oss.chronicle.licenses.PlayLicensesHandler
import javax.inject.Singleton

/**
 * Dagger module for Play flavor-specific bindings.
 */
@Module
abstract class FlavorModule {
    @Binds
    @Singleton
    abstract fun bindBillingManager(impl: PlayBillingManager): IBillingManager
    
    @Binds
    abstract fun bindLicensesHandler(impl: PlayLicensesHandler): ILicensesHandler
}
