package local.oss.chronicle.licenses

import android.app.Activity

/**
 * Interface for displaying open source licenses. Implementations differ based on
 * the build flavor (Google Play vs F-Droid).
 */
interface ILicensesHandler {
    /**
     * Show the licenses screen or page.
     * @param activity The activity to launch from or use for context
     */
    fun showLicenses(activity: Activity)
}
