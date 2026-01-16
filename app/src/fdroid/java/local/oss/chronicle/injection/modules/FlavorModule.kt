package local.oss.chronicle.injection.modules

import dagger.Binds
import dagger.Module
import local.oss.chronicle.billing.FdroidBillingManager
import local.oss.chronicle.billing.IBillingManager
import local.oss.chronicle.licenses.FdroidLicensesHandler
import local.oss.chronicle.licenses.ILicensesHandler
import javax.inject.Singleton

/**
 * Dagger module for F-Droid flavor-specific bindings.
 */
@Module
abstract class FlavorModule {
    @Binds
    @Singleton
    abstract fun bindBillingManager(impl: FdroidBillingManager): IBillingManager
    
    @Binds
    abstract fun bindLicensesHandler(impl: FdroidLicensesHandler): ILicensesHandler
}
