package local.oss.chronicle.billing

import android.app.Activity
import android.content.Context
import com.limurse.iap.IapConnector
import local.oss.chronicle.data.local.PrefsRepo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Play Billing implementation. Handles initialization of the Google Play Billing library,
 * restores previous purchases, and exposes a method to launch billing flow.
 *
 * TODO: use a more sophisticated method to prevent cheats
 */
@Singleton
class PlayBillingManager
    @Inject
    constructor(
        applicationContext: Context,
        private val prefsRepo: PrefsRepo,
    ) : IBillingManager {
        
        override val isPremium: Boolean
            get() = prefsRepo.isPremium
        
        override fun startConnection() {
            // Connection is handled automatically by IapConnector
        }
        
        override fun endConnection() {
            // No explicit cleanup needed for IapConnector
        }
        
        override fun launchPurchaseFlow(activity: Activity) {
            iapConnector.purchase(activity, PREMIUM_IAP_SKU)
        }

        private val iapConnector =
            IapConnector(
                context = applicationContext,
                nonConsumableKeys = listOf(PREMIUM_IAP_SKU),
                enableLogging = true,
            )
            /*.apply {
                addPurchaseListener(
                    object : PurchaseServiceListener {
                        override fun onPricesUpdated(iapKeyPrices: Map<String, DataWrappers.SkuDetails>) {
                            // no-op
                        }

                        override fun onProductPurchased(purchaseInfo: DataWrappers.PurchaseInfo) {
                            prefsRepo.premiumPurchaseToken = purchaseInfo.purchaseToken
                        }

                        override fun onProductRestored(purchaseInfo: DataWrappers.PurchaseInfo) {
                            prefsRepo.premiumPurchaseToken = purchaseInfo.purchaseToken
                        }
                    },
                )
            }*/

        companion object {
            const val PREMIUM_IAP_SKU = "premium"
        }
    }
