# Automated Screenshot Generation

Chronicle Epilogue has two options for generating Play Store screenshots:

1. **Manual with ImageMagick** (current/simple)
2. **Automated with Screengrab** (advanced/requires Plex account)

---

## Option 1: Manual Screenshots (Recommended for most users)

### Quick Start

```bash
# 1. Capture raw screenshots manually on device/emulator
# 2. Save to images/screenshots/
# 3. Run the generation script
./scripts/generate-playstore-graphics.sh
```

Screenshots are automatically framed and styled for Play Store.

**See** [`scripts/README.md`](README.md) for detailed instructions.

---

## Option 2: Automated Screenshots with Screengrab

### Overview

Uses Fastlane Screengrab + Espresso tests to automatically:
1. Launch the app
2. Login to Plex
3. Navigate through screens
4. Capture screenshots
5. Save to `fastlane/screenshots/`

### Prerequisites

1. **Fastlane installed:**
   ```bash
   # macOS
   brew install fastlane
   
   # Or via Ruby
   gem install fastlane
   ```

2. **Android device/emulator running** with:
   - Screen unlocked
   - Demo mode enabled (optional, removes status bar clutter):
     ```bash
     adb shell settings put global sysui_demo_allowed 1
     adb shell am broadcast -a android.intent.action.DEMO_MODE_ON
     ```

3. **Plex demo account** with sample audiobooks

### Setup

1. **Set Plex credentials** (choose one):
   
   **Option A: Environment variables (recommended)**
   ```bash
   export PLEX_USERNAME="your@email.com"
   export PLEX_PASSWORD="yourpassword"
   ```
   
   **Option B: Edit [`Screengrabfile`](../Screengrabfile)**
   ```ruby
   test_instrumentation_runner_arguments({
     plexUsername: 'your@email.com',
     plexPassword: 'yourpassword'
   })
   ```

2. **Build app and test APKs:**
   ```bash
   ./gradlew assembleDebug assembleDebugAndroidTest
   ```

### Running Screenshot Tests

```bash
# Method 1: Using Fastlane (recommended)
fastlane screengrab

# Method 2: Using Gradle directly
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=local.oss.chronicle.screenshot.ScreenshotTest \
  -Pandroid.testInstrumentationRunnerArguments.plexUsername="your@email.com" \
  -Pandroid.testInstrumentationRunnerArguments.plexPassword="yourpassword"
```

### Output

Screenshots are saved to:
- `fastlane/screenshots/en-US/` (raw screenshots from Screengrab)

Then process with:
```bash
# Copy to images/screenshots/
cp fastlane/screenshots/en-US/*.png images/screenshots/

# Generate framed versions
./scripts/generate-playstore-graphics.sh
```

### Customizing Screenshot Tests

Edit [`app/src/androidTest/java/local/oss/chronicle/screenshot/ScreenshotTest.kt`](../app/src/androidTest/java/local/oss/chronicle/screenshot/ScreenshotTest.kt):

```kotlin
@Test
fun captureScreenshots() {
    // 1. Login (implement with your Plex credentials)
    login(plexUsername, plexPassword)
    
    // 2. Home screen
    waitForHomeScreen()
    Screengrab.screenshot("1-home")
    
    // 3. Library
    navigateToLibrary()
    Screengrab.screenshot("2-library")
    
    // 4. Audiobook details
    openFirstAudiobook()
    Screengrab.screenshot("3-audiobook")
    
    // 5. Playing screen
    startPlayback()
    Screengrab.screenshot("4-playing")
    
    // 6. Search
    openSearch()
    Screengrab.screenshot("5-search")
    
    // 7. Settings
    openSettings()
    Screengrab.screenshot("6-settings")
}
```

### Multi-Locale Screenshots

Edit [`Screengrabfile`](../Screengrabfile):

```ruby
locales(['en-US', 'de-DE', 'fr-FR', 'es-ES'])
```

Then run:
```bash
fastlane screengrab
```

Screenshots will be generated for each locale:
- `fastlane/screenshots/en-US/`
- `fastlane/screenshots/de-DE/`
- etc.

---

## Comparison

| Feature | Manual | Automated |
|---------|--------|-----------|
| **Setup** | ✅ Simple | ⚠️ Complex |
| **Maintenance** | ✅ Easy | ⚠️ Requires test updates |
| **Speed** | ⚠️ ~10 min manual | ✅ ~3 min automated |
| **Consistency** | ⚠️ Varies | ✅ Identical every time |
| **Multi-locale** | ❌ Manual per locale | ✅ Automatic |
| **Plex account** | ❌ Not needed | ✅ Required |

### Recommendation

- **For occasional updates:** Use manual screenshots
- **For frequent updates / CI/CD:** Use automated Screengrab
- **For translations:** Use automated Screengrab with multiple locales

---

## Troubleshooting

### Screengrab Issues

**Screenshots are blank/black:**
- Ensure device screen is unlocked
- Disable screen lock: `adb shell settings put secure lock_screen_enabled 0`
- Enable demo mode to hide status bar

**Tests fail with "Login failed":**
- Verify Plex credentials are correct
- Check network connectivity
- Ensure Plex account has audiobooks in library

**Build errors:**
- Clean and rebuild: `./gradlew clean assembleDebug assembleDebugAndroidTest`
- Sync Gradle files in Android Studio
- Check Screengrab version compatibility

**Device not found:**
- Start emulator: `emulator -avd Pixel_4_API_33`
- Or connect physical device with USB debugging enabled
- Verify: `adb devices`

### Manual Screenshots Issues

See [`scripts/README.md`](README.md#troubleshooting)

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Generate Screenshots

on:
  workflow_dispatch:  # Manual trigger

jobs:
  screenshots:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Android SDK
        uses: android-actions/setup-android@v2
      
      - name: Setup Fastlane
        run: brew install fastlane
      
      - name: Start emulator
        run: |
          echo "y" | sdkmanager "system-images;android-33;google_apis;x86_64"
          avdmanager create avd -n test -k "system-images;android-33;google_apis;x86_64"
          emulator -avd test -no-window -no-audio &
          adb wait-for-device
      
      - name: Build APKs
        run: ./gradlew assembleDebug assembleDebugAndroidTest
      
      - name: Run Screengrab
        env:
          PLEX_USERNAME: ${{ secrets.PLEX_DEMO_USERNAME }}
          PLEX_PASSWORD: ${{ secrets.PLEX_DEMO_PASSWORD }}
        run: fastlane screengrab
      
      - name: Process screenshots
        run: ./scripts/generate-playstore-graphics.sh
      
      - name: Upload screenshots
        uses: actions/upload-artifact@v3
        with:
          name: playstore-screenshots
          path: playstore/graphics/phoneScreenshots/
```

---

## Additional Resources

- [Fastlane Screengrab Documentation](https://docs.fastlane.tools/actions/screengrab/)
- [Espresso Testing Guide](https://developer.android.com/training/testing/espresso)
- [Android UI Automator](https://developer.android.com/training/testing/other-components/ui-automator)
- [Play Store Screenshot Guidelines](https://support.google.com/googleplay/android-developer/answer/9866151)
