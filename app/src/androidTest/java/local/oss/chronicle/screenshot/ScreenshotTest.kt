package local.oss.chronicle.screenshot

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import local.oss.chronicle.application.MainActivity
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.locale.LocaleTestRule

/**
 * Automated screenshot generation test for Play Store listings.
 *
 * Prerequisites:
 * 1. Device/emulator must be running and unlocked
 * 2. PLEX_USERNAME and PLEX_PASSWORD must be provided via environment or test config
 * 3. The Plex account should have sample audiobooks in a library
 *
 * Usage:
 * - Run with: ./gradlew executeScreenshotTests
 * - Or manually: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=local.oss.chronicle.screenshot.ScreenshotTest
 *
 * Screenshots will be saved to: app/build/outputs/androidTest-results/connected/screenshots/
 *
 * Note: This test requires Plex credentials. Set them as:
 * - InstrumentationRegistry arguments: -e plexUsername "your@email.com" -e plexPassword "yourpassword"
 * - Or configure in fastlane/Screengrabfile
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    companion object {
        @ClassRule
        @JvmField
        val localeTestRule: LocaleTestRule = LocaleTestRule()
    }

    @Rule
    @JvmField
    val activityRule: ActivityScenarioRule<MainActivity> = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun setUp() {
        // Use UiAutomator for reliable screenshots
        Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())

        // Get Plex credentials from instrumentation arguments
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bundle = InstrumentationRegistry.getArguments()

        val plexUsername = bundle.getString("plexUsername")
        val plexPassword = bundle.getString("plexPassword")

        requireNotNull(plexUsername) {
            "Plex username not provided. Pass via: -e plexUsername \"your@email.com\""
        }
        requireNotNull(plexPassword) {
            "Plex password not provided. Pass via: -e plexPassword \"yourpassword\""
        }
    }

    @Test
    fun captureScreenshots() {
        // TODO: Implement screenshot capture flow
        // This test needs to be completed with actual UI navigation once Plex credentials are available

        // Example flow outline:
        // 1. Login to Plex
        // 2. Navigate to home screen -> Screengrab.screenshot("1-home")
        // 3. Navigate to library -> Screengrab.screenshot("2-library")
        // 4. Open an audiobook -> Screengrab.screenshot("3-audiobook")
        // 5. Start playback -> Screengrab.screenshot("4-playing")
        // 6. Open search -> Screengrab.screenshot("5-search")
        // 7. Open settings -> Screengrab.screenshot("6-settings")

        Screengrab.screenshot("0-placeholder")
    }
}
