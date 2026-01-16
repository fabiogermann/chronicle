package local.oss.chronicle.billing

import android.app.Activity

/**
 * Interface for billing operations. Implementations differ based on the build flavor
 * (Google Play vs F-Droid).
 */
interface IBillingManager {
    /**
     * Whether the user has premium features unlocked
     */
    val isPremium: Boolean

    /**
     * Initialize the billing connection. Should be called when the app starts.
     */
    fun startConnection()

    /**
     * Close the billing connection. Should be called when the app is destroyed.
     */
    fun endConnection()

    /**
     * Launch the purchase flow to upgrade to premium.
     * @param activity The activity to launch the billing flow from
     */
    fun launchPurchaseFlow(activity: Activity)
}
