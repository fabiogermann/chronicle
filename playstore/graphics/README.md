# Play Store Graphics

This directory should contain graphics and screenshots for the Google Play Store listing.

## Required Graphics

### App Icon
- **File:** `icon-512.png`
- **Size:** 512x512 px
- **Format:** 32-bit PNG with alpha
- **Notes:** High-res app icon (already included in app resources)

### Feature Graphic
- **File:** `featureGraphic.png`
- **Size:** 1024x500 px
- **Format:** JPEG or 24-bit PNG (no alpha)
- **Notes:** Displayed at the top of the Play Store listing

### Screenshots
- **Directory:** `phoneScreenshots/`
- **Min:** 2 screenshots
- **Max:** 8 screenshots
- **Size:** 
  - Minimum dimension: 320 px
  - Maximum dimension: 3840 px
  - Aspect ratio: 16:9 or 9:16 (phone)
- **Format:** JPEG or 24-bit PNG
- **Notes:** Showcase key features and UI

### Optional Graphics

#### Tablet Screenshots
- **Directory:** `sevenInchScreenshots/` (7-inch tablets)
- **Directory:** `tenInchScreenshots/` (10-inch tablets)

#### TV Screenshots
- **Directory:** `tvScreenshots/`
- **Size:** 1920x1080 px or 3840x2160 px

#### Wear Screenshots
- **Directory:** `wearScreenshots/`

#### Promo Video
- **Field:** YouTube video URL
- **Notes:** Added through Play Console, not uploaded via Gradle

## Existing Screenshots

Screenshots are available in the [`images/store/`](../../images/store/) directory:

- `home.png` - Home screen view
- `library.png` - Library with audiobook collection
- `audiobook.png` - Audiobook details view
- `playing.png` - Currently playing screen
- `search.png` - Search functionality
- `settings.png` - Settings screen

These can be copied to `playstore/graphics/phoneScreenshots/` for Play Store publishing.

### Feature Graphic Available
- `feature-graphic.png` (1024x500) - Ready for use
- `feature-graphic.svg` - Source file

## Directory Structure

```
graphics/
├── README.md (this file)
├── icon-512.png (optional, can use app icon)
├── featureGraphic.png (1024x500)
└── phoneScreenshots/
    ├── 1-home.png
    ├── 2-library.png
    ├── 3-audiobook.png
    ├── 4-playing.png
    ├── 5-search.png
    └── 6-settings.png
```

## Naming Convention

Screenshots are processed in alphabetical order. Prefix with numbers to control the order:
- `1-home.png`
- `2-library.png`
- etc.

## Uploading

The Gradle Play Publisher plugin will automatically upload graphics from this directory when you run:

```bash
./gradlew publishListing
```

Or include them in a full publish:

```bash
./gradlew publishBundle
```

## References

- [Screenshot Guidelines](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Graphic Asset Specifications](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Gradle Play Publisher Graphics Docs](https://github.com/Triple-T/gradle-play-publisher#uploading-images)
