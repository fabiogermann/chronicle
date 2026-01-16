package local.oss.chronicle.licenses

import android.app.Activity
import android.content.Intent
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import javax.inject.Inject

/**
 * Google Play implementation for displaying open source licenses.
 * Uses the Google Play Services OSS licenses plugin.
 */
class PlayLicensesHandler
    @Inject
    constructor() : ILicensesHandler {
        
        override fun showLicenses(activity: Activity) {
            activity.startActivity(Intent(activity, OssLicensesMenuActivity::class.java))
        }
    }
