# Play Store Assets

This directory contains all assets and metadata required for publishing to the Google Play Store using the Gradle Play Publisher plugin.

## Directory Structure

```
playstore/
├── README.md                     # This file
├── default/                      # Default locale (fallback)
│   ├── title.txt                 # App title (50 chars max)
│   ├── short-description.txt     # Short description (80 chars max)
│   ├── full-description.txt      # Full description (4000 chars max)
│   └── changelogs/
│       └── default.txt           # Default release notes
├── en-US/                        # English (US) locale
│   ├── title.txt
│   ├── short-description.txt
│   ├── full-description.txt
│   └── changelogs/
│       └── <versionCode>.txt     # Version-specific release notes
└── graphics/
    └── README.md                 # Instructions for screenshots/graphics
```

## Metadata Files

### title.txt
- **Max length:** 50 characters
- **Description:** The app name as it appears in the Play Store
- **Example:** "Chronicle"

### short-description.txt
- **Max length:** 80 characters
- **Description:** Brief tagline or subtitle for your app
- **Example:** "The best Android Audiobook Player for Plex"

### full-description.txt
- **Max length:** 4000 characters
- **Description:** Complete app description shown in the Play Store listing
- Supports basic HTML formatting (`<b>`, `<i>`, `<u>`)
- Should include:
  - What the app does
  - Key features
  - Target audience
  - Any requirements (e.g., Plex server)

### changelogs/
- Release notes for each version
- **Filename:** `<versionCode>.txt` or `default.txt` (fallback)
- **Max length:** 500 characters
- **Example:** For version code 29: `29.txt`

## Localization

To add a new locale:
1. Create a directory with the locale code (e.g., `fr-FR`, `de-DE`)
2. Copy all files from `default/` or `en-US/`
3. Translate the content
4. Ensure you stay within character limits

Available locale codes: https://support.google.com/googleplay/android-developer/table/4419860

## Graphics

Graphics are placed in the `graphics/` directory. See [`graphics/README.md`](graphics/README.md) for details.

## Using the Plugin

The Gradle Play Publisher plugin reads these files automatically when publishing.

### Local Testing
```bash
# Validate metadata
./gradlew validatePlayResources

# Publish to internal track
./gradlew publishBundle --track=internal
```

### CI/CD
See [`.github/workflows/deploy-playstore.yml`](../.github/workflows/deploy-playstore.yml) for automated deployment.

## References

- [Gradle Play Publisher Documentation](https://github.com/Triple-T/gradle-play-publisher)
- [Play Store Listing Guidelines](https://support.google.com/googleplay/android-developer/answer/113469)
- [Google Play Store Setup Guide](../.github/PLAYSTORE_SETUP.md)
