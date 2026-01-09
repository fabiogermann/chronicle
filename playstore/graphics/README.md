# Play Store Graphics Assets

This directory contains all graphics assets for the Google Play Store listing, organized for use with the Gradle Play Publisher plugin.

## Directory Structure

```
playstore/graphics/
├── icon/
│   └── icon-512.png
├── featureGraphic/
│   └── feature-graphic.png
└── phoneScreenshots/
    ├── 1-home.png
    ├── 2-library.png
    ├── 3-audiobook.png
    ├── 4-playing.png
    ├── 5-search.png
    └── 6-settings.png
```

## Asset Requirements and Specifications

### App Icon (`icon/`)
- **File**: `icon-512.png`
- **Dimensions**: 512 x 512 pixels
- **Format**: PNG (32-bit)
- **Purpose**: High-resolution app icon for Play Store listing
- **Source**: `app/src/main/play_store_512.png`

### Feature Graphic (`featureGraphic/`)
- **File**: `feature-graphic.png`
- **Dimensions**: 1024 x 500 pixels
- **Format**: PNG or JPG
- **Purpose**: Banner image displayed at the top of the Play Store listing
- **Source**: `images/store/feature-graphic.png`

### Phone Screenshots (`phoneScreenshots/`)
Screenshots are numbered with prefixes to control their display order in the Play Store:

1. **1-home.png** - Home screen showing featured audiobooks
   - Source: `images/store/home.png`

2. **2-library.png** - Library view with user's audiobook collection
   - Source: `images/store/library.png`

3. **3-audiobook.png** - Audiobook details screen
   - Source: `images/store/audiobook.png`

4. **4-playing.png** - Now Playing screen with playback controls
   - Source: `images/store/playing.png`

5. **5-search.png** - Search interface
   - Source: `images/store/search.png`

6. **6-settings.png** - Settings and preferences screen
   - Source: `images/store/settings.png`

#### Screenshot Specifications
- **Format**: PNG or JPG
- **Minimum dimensions**: 320 pixels
- **Maximum dimensions**: 3840 pixels
- **Aspect ratio**: Between 16:9 and 9:16
- **Maximum file size**: 8 MB per screenshot
- **Ordering**: Files are displayed in alphanumeric order (hence the numbered prefixes)

## Usage with Gradle Play Publisher Plugin

The Gradle Play Publisher plugin automatically detects and uses these assets when publishing to the Play Store. Ensure this directory structure is maintained for proper integration.

## Updating Assets

When updating graphics assets:

1. Replace the appropriate file in this directory structure
2. Maintain the same filename to preserve ordering (especially for screenshots)
3. Verify dimensions and format requirements are met
4. Original source files are preserved in `app/src/main/` and `images/store/` directories

## References

- [Google Play Store Asset Guidelines](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Gradle Play Publisher Plugin Documentation](https://github.com/Triple-T/gradle-play-publisher)
