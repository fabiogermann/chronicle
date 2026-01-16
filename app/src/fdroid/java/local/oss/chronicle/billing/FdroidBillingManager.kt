package local.oss.chronicle.billing

import android.app.Activity
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid billing implementation. All premium features are unlocked by default in the
 * open source F-Droid build.
 */
@Singleton
class FdroidBillingManager
    @Inject
    constructor(
        private val context: Context,
    ) : IBillingManager {
        
        // All features unlocked on F-Droid
        override val isPremium: Boolean = true
        
        override fun startConnection() {
            // No-op for F-Droid
        }
        
        override fun endConnection() {
            // No-op for F-Droid
        }
        
        override fun launchPurchaseFlow(activity: Activity) {
            // No-op for F-Droid - could show message that this is the FOSS version
            // For now, this does nothing as premium features are already unlocked
        }
    }
