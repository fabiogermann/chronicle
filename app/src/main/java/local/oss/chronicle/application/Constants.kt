package local.oss.chronicle.application

import local.oss.chronicle.BuildConfig

const val FEATURE_FLAG_IS_AUTO_ENABLED = true
const val FEATURE_FLAG_IS_CAST_ENABLED = false
const val MILLIS_PER_SECOND = 1000L
const val SECONDS_PER_MINUTE = 60L
const val USE_STRICT_MODE = false

val LOG_NETWORK_REQUESTS = BuildConfig.DEBUG
const val PREMIUM_IAP_SKU = "premium"
val IAP_SKU_LIST = listOf(PREMIUM_IAP_SKU)
