# Play Store Listing Assets

This directory contains all metadata, text content, and graphics assets for the **Chronicle Epilogue - Audiobook Player for Plex** Google Play Store listing. The structure is organized for compatibility with the [Gradle Play Publisher plugin](https://github.com/Triple-T/gradle-play-publisher).

## Overview

The [`playstore/`](.) folder contains:
- **Metadata files**: App title, descriptions, and changelogs in multiple languages
- **Graphics assets**: App icon, feature graphic, and screenshots
- **Localization support**: Default and language-specific content (currently `en-US`)

All content follows Google Play Store requirements and is automatically processed by the Gradle Play Publisher plugin during the app publishing workflow.

## Directory Structure

```
playstore/
├── README.md                           # This file
├── default/                            # Default/fallback metadata
│   ├── title.txt                       # App title (max 50 chars)
│   ├── short-description.txt           # Short description (max 80 chars)
│   ├── full-description.txt            # Full description (max 4000 chars)
│   └── changelogs/
│       └── default.txt                 # Default changelog (max 500 chars)
├── en-US/                              # English (US) localization
│   ├── title.txt                       # Localized app title
│   ├── short-description.txt           # Localized short description
│   ├── full-description.txt            # Localized full description
│   └── changelogs/
│       └── default.txt                 # Default changelog for this locale
└── graphics/                           # Graphics assets (all locales)
    ├── README.md                       # Graphics asset documentation
    ├── icon/
    │   └── icon-512.png                # App icon (512x512 PNG)
    ├── featureGraphic/
    │   └── feature-graphic.png         # Feature graphic (1024x500 PNG/JPG)
    └── phoneScreenshots/
        ├── 1-home.png                  # Home screen
        ├── 2-library.png               # Library view
        ├── 3-audiobook.png             # Audiobook details
        ├── 4-playing.png               # Now playing screen
        ├── 5-search.png                # Search interface
        └── 6-settings.png              # Settings screen
```

## Metadata Files

### [`title.txt`](default/title.txt)
**Purpose**: The app name as it appears in the Play Store  
**Character limit**: 50 characters maximum  
**Example**: `Chronicle Epilogue - Audiobook Player for Plex`

### [`short-description.txt`](default/short-description.txt)
**Purpose**: Brief tagline displayed in Play Store search results  
**Character limit**: 80 characters maximum  
**Example**: `Stream audiobooks from your Plex Media Server with a beautiful player`

### [`full-description.txt`](default/full-description.txt)
**Purpose**: Complete app description displayed on the Play Store listing page  
**Character limit**: 4000 characters maximum  
**Formatting**: Supports basic formatting (line breaks, bullet points)  
**Content**: Should include:
- Key features and functionality
- Use cases and benefits
- Technical requirements (e.g., Plex Media Server needed)
- Target audience

### Changelogs

#### [`changelogs/default.txt`](default/changelogs/default.txt)
**Purpose**: Default release notes shown for all versions without version-specific changelogs  
**Character limit**: 500 characters maximum  
**Content**: Generic update message or placeholder

#### Version-Specific Changelogs
**Naming convention**: `changelogs/<versionCode>.txt`  
**Example**: `changelogs/29.txt` for version code 29  
**Purpose**: Release notes for specific app versions  
**Character limit**: 500 characters maximum  

When publishing a new version, create a changelog file named after the version code:
```bash
# For version code 30
echo "- Added sleep timer\n- Fixed playback issues\n- Performance improvements" > playstore/en-US/changelogs/30.txt
```

## Graphics Assets

All graphics assets are stored in the [`graphics/`](graphics/) subdirectory. For detailed specifications and requirements, see [`graphics/README.md`](graphics/README.md).

### Quick Reference

| Asset Type | File Path | Dimensions | Format | Required |
|------------|-----------|------------|--------|----------|
| App Icon | [`graphics/icon/icon-512.png`](graphics/icon/icon-512.png) | 512 x 512 px | 32-bit PNG | Yes |
| Feature Graphic | [`graphics/featureGraphic/feature-graphic.png`](graphics/featureGraphic/feature-graphic.png) | 1024 x 500 px | PNG/JPG | Yes |
| Phone Screenshots | [`graphics/phoneScreenshots/`](graphics/phoneScreenshots/) | 320-3840 px | PNG/JPG | 2-8 images |

**Note**: Screenshots are displayed in alphanumeric order by filename. Use numbered prefixes (e.g., `1-home.png`, `2-library.png`) to control the display order.

### Asset Requirements Summary

- **App Icon**: 512x512 PNG with transparency support (32-bit)
- **Feature Graphic**: 1024x500 PNG or JPEG (no transparency/alpha channel)
- **Phone Screenshots**: 
  - Minimum 2, maximum 8 screenshots
  - Aspect ratio between 16:9 and 9:16
  - Minimum dimension: 320px
  - Maximum dimension: 3840px
  - Maximum file size: 8MB per screenshot

## Localization

### Adding New Locales

To add support for a new language/region:

1. **Create a new locale directory** using the appropriate [language code](https://support.google.com/googleplay/android-developer/answer/9844778):
   ```bash
   mkdir -p playstore/fr-FR/changelogs
   ```

2. **Create the required metadata files**:
   ```bash
   touch playstore/fr-FR/title.txt
   touch playstore/fr-FR/short-description.txt
   touch playstore/fr-FR/full-description.txt
   touch playstore/fr-FR/changelogs/default.txt
   ```

3. **Translate the content** from `default/` or `en-US/` to the new language

4. **Add version-specific changelogs** as needed (e.g., `fr-FR/changelogs/30.txt`)

### Supported Locale Codes

Common examples:
- `en-US` - English (United States)
- `en-GB` - English (United Kingdom)
- `fr-FR` - French (France)
- `de-DE` - German (Germany)
- `es-ES` - Spanish (Spain)
- `es-419` - Spanish (Latin America)
- `ja-JP` - Japanese (Japan)
- `ko-KR` - Korean (South Korea)
- `zh-CN` - Chinese (Simplified)
- `zh-TW` - Chinese (Traditional)

See the [complete list of supported locales](https://support.google.com/googleplay/android-developer/answer/9844778).

### Fallback Behavior

If a locale-specific file is not found, the Gradle Play Publisher plugin falls back to:
1. The requested locale (e.g., `fr-FR`)
2. The language code only (e.g., `fr`)
3. The `default/` directory content

**Graphics assets** are currently shared across all locales. To create locale-specific graphics, create a `graphics/` subdirectory within the locale folder (e.g., `fr-FR/graphics/`).

## Gradle Play Publisher Integration

This directory structure is designed for the [Gradle Play Publisher](https://github.com/Triple-T/gradle-play-publisher) plugin, which automates Play Store publishing.

### Configuration

The plugin should be configured in [`app/build.gradle.kts`](../app/build.gradle.kts):

```kotlin
play {
    // Play Store publishing configuration
    defaultToAppBundles.set(true)
    track.set("internal") // or "alpha", "beta", "production"
    
    // Metadata comes from playstore/ directory
}
```

### Publishing Commands

```bash
# Publish to internal testing track
./gradlew publishBundle

# Promote from internal to beta
./gradlew promoteArtifact --from-track internal --promote-track beta

# Upload only metadata/graphics (no APK/Bundle)
./gradlew publishListing
```

### What Gets Published

The plugin automatically:
- Uploads the app title, descriptions, and changelogs from locale folders
- Uploads graphics assets (icon, feature graphic, screenshots)
- Uses version-specific changelogs when available
- Falls back to `default.txt` changelog if no version-specific file exists

## Updating Assets

### Creating New Screenshots

1. **Generate screenshots** from the app (use emulator or physical device)
2. **Frame screenshots** using the template in [`images/store_frame_template.svg`](../images/store_frame_template.svg)
3. **Export as PNG** at appropriate resolution
4. **Copy to playstore** maintaining the numbered naming convention:
   ```bash
   cp images/store/1-home.png playstore/graphics/phoneScreenshots/1-home.png
   ```

### Updating the Feature Graphic

1. **Edit the source file** at [`images/store/feature-graphic.png`](../images/store/feature-graphic.png)
   - Or use the SVG template: [`images/store/feature-graphic.svg`](../images/store/feature-graphic.svg)
2. **Export as PNG or JPG** at 1024x500 pixels (no transparency)
3. **Copy to playstore**:
   ```bash
   cp images/store/feature-graphic.png playstore/graphics/featureGraphic/feature-graphic.png
   ```

### Updating the App Icon

1. **Update the source icon** at [`app/src/main/play_store_512.png`](../app/src/main/play_store_512.png)
2. **Copy to playstore**:
   ```bash
   cp app/src/main/play_store_512.png playstore/graphics/icon/icon-512.png
   ```

### Adding Version-Specific Changelogs

When releasing a new version:

1. **Determine the version code** from [`app/build.gradle.kts`](../app/build.gradle.kts) or [`gradle.properties`](../gradle.properties)
2. **Create a changelog file** for each locale:
   ```bash
   # For version code 30
   echo "• Added sleep timer
   • Fixed playback resume issues  
   • Improved library sync performance
   • Bug fixes and stability improvements" > playstore/en-US/changelogs/30.txt
   ```
3. **Keep it concise** (500 character limit)
4. **Focus on user-facing changes** rather than technical details

### Best Practices for Changelogs

- Use bullet points (•, -, *) for readability
- Lead with new features, then improvements, then bug fixes
- Be specific but concise: "Fixed crash on startup" vs "Bug fixes"
- Avoid technical jargon when possible
- Create version-specific changelogs for significant releases
- Update `default.txt` periodically to reflect recent improvements

## File Management

### What's Tracked in Git

All files in this directory should be tracked in version control:
- ✅ Metadata files (`title.txt`, descriptions, changelogs)
- ✅ Graphics assets (already optimized for distribution)
- ✅ This README and the graphics README

### Source Files

Original, editable source files are stored separately:
- **Design mockups**: [`images/`](../images/) directory
- **SVG templates**: [`images/store/`](../images/store/) and [`images/logo/`](../images/logo/)
- **App icon source**: [`app/src/main/play_store_512.png`](../app/src/main/play_store_512.png)

## References

- [Google Play Console Help](https://support.google.com/googleplay/android-developer)
- [Google Play Store Listing Guidelines](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Store Listing Best Practices](https://developer.android.com/distribute/best-practices/launch/store-listing)
- [Gradle Play Publisher Plugin](https://github.com/Triple-T/gradle-play-publisher)
- [Supported Locale Codes](https://support.google.com/googleplay/android-developer/answer/9844778)
- [Graphics Asset Specifications](https://support.google.com/googleplay/android-developer/answer/9866151#zippy=%2Cgraphic-assets)

---

**Maintained for**: Chronicle Epilogue - Audiobook Player for Plex  
**Last updated**: 2026-01-09
