package local.oss.chronicle.licenses

import android.app.Activity
import android.content.Intent
import android.net.Uri
import javax.inject.Inject

/**
 * F-Droid implementation for displaying open source licenses.
 * Opens the project's GitHub repository license page in a browser.
 */
class FdroidLicensesHandler
    @Inject
    constructor() : ILicensesHandler {
        
        override fun showLicenses(activity: Activity) {
            // Open GitHub licenses page
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/jgermann/chronicle/blob/main/LICENSE")
            )
            activity.startActivity(intent)
        }
    }
