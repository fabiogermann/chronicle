# Google Play Store Setup Guide

This guide walks you through setting up automated Google Play Store publishing using the Gradle Play Publisher plugin.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Google Cloud Setup](#google-cloud-setup)
3. [Play Console Setup](#play-console-setup)
4. [GitHub Secrets Setup](#github-secrets-setup)
5. [Local Development Setup](#local-development-setup)
6. [Publishing to Play Store](#publishing-to-play-store)
7. [Troubleshooting](#troubleshooting)

## Prerequisites

- A Google Play Console account
- A published app on Play Console (or ready for initial upload)
- Admin access to the Google Play Console
- Admin access to the GitHub repository

## Google Cloud Setup

### 1. Create a Google Cloud Project

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Note the project ID for later use

### 2. Enable Google Play Developer API

1. In Google Cloud Console, go to **APIs & Services** → **Library**
2. Search for "Google Play Android Developer API"
3. Click **Enable**

### 3. Create a Service Account

1. Go to **APIs & Services** → **Credentials**
2. Click **Create Credentials** → **Service Account**
3. Fill in the details:
   - **Service account name:** `github-actions-playstore` (or your choice)
   - **Service account ID:** Will be auto-generated
   - **Description:** "Service account for GitHub Actions to publish to Play Store"
4. Click **Create and Continue**
5. Skip the optional steps and click **Done**

### 4. Generate Service Account Key

1. In the **Service Accounts** list, click on the account you just created
2. Go to the **Keys** tab
3. Click **Add Key** → **Create new key**
4. Select **JSON** format
5. Click **Create**
6. The JSON key file will be downloaded to your computer
7. **Keep this file secure** - it provides access to publish your app

## Play Console Setup

### 1. Link Service Account to Play Console

1. Go to [Google Play Console](https://play.google.com/console/)
2. Select your app (or create it if this is the first time)
3. Go to **Setup** → **API access**
4. Click **Create new service account** (or link existing)
5. Click **View service accounts** to go to Google Cloud Console
6. Copy the email address of your service account (format: `account-name@project-id.iam.gserviceaccount.com`)
7. Return to Play Console
8. Click **Grant access** under Service accounts
9. Find your service account in the list and click **Invite user**

### 2. Set Service Account Permissions

On the permissions screen:

1. **Account permissions:**
   - **Admin (all permissions)** OR select specific permissions:
   - ✅ View app information and download bulk reports
   - ✅ Manage store presence (required for publishing)
   - ✅ Manage production releases (if publishing to production)
   - ✅ Manage testing track releases (if publishing to alpha/beta/internal)

2. Click **Invite user**
3. Click **Send invite**

### 3. Initial App Setup

If this is your first release, you need to create an initial release manually:

1. Go to **Production** (or **Testing** → **Internal testing**)
2. Click **Create new release**
3. Upload your first APK or AAB manually
4. Fill in release details
5. **Do not publish yet** - just create the release
6. This allows the API to work for future automated releases

## GitHub Secrets Setup

### 1. Encode the Service Account JSON

On your local machine:

```bash
# macOS/Linux
cat path/to/service-account.json | base64 | pbcopy

# Or without copying to clipboard
base64 path/to/service-account.json > encoded.txt
```

On Windows (PowerShell):
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("path\to\service-account.json")) | Set-Clipboard
```

### 2. Add GitHub Secret

1. Go to your GitHub repository
2. Navigate to **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add the following secret:

   - **Name:** `PLAY_STORE_SERVICE_ACCOUNT_JSON`
   - **Value:** Paste the service account JSON content (not base64 encoded, just the raw JSON)

### 3. Verify Existing Secrets

Ensure these secrets are already set (from previous release setup):

- `KEYSTORE_BASE64` - Base64-encoded release keystore
- `KEYSTORE_PASSWORD` - Keystore password
- `KEY_ALIAS` - Key alias
- `KEY_PASSWORD` - Key password

## Local Development Setup

### 1. Download Service Account Key

Save your service account JSON key to:
```
/path/to/chronicle/play-store-credentials.json
```

This file is in `.gitignore` and will not be committed.

### 2. Test Local Publishing

```bash
# Validate Play Store resources
./gradlew validatePlayResources

# Publish to internal track (test)
./gradlew publishBundle --track=internal

# Publish listing metadata only (no binary)
./gradlew publishListing
```

### 3. Available Gradle Tasks

```bash
# View all Play Publisher tasks
./gradlew tasks --group publishing

# Common tasks:
./gradlew publishBundle              # Publish AAB to default track (internal)
./gradlew publishBundle --track=alpha   # Publish to alpha track
./gradlew publishBundle --track=beta    # Publish to beta track
./gradlew publishBundle --track=production  # Publish to production
./gradlew publishListing             # Update Play Store listing only
./gradlew bootstrap                  # Download existing metadata from Play Store
```

## Publishing to Play Store

### GitHub Actions (Recommended)

1. Go to **Actions** tab in GitHub
2. Select **Deploy to Google Play Store** workflow
3. Click **Run workflow**
4. Select the desired track:
   - `internal` - Internal testing (limited testers)
   - `alpha` - Alpha testing
   - `beta` - Beta/open testing
   - `production` - Production release
5. Click **Run workflow**

The workflow will:
- Build the release AAB
- Sign it with your keystore
- Upload to Play Store
- Set the release to the chosen track

### Manual Local Publishing

```bash
# Build and publish to internal track
./gradlew publishBundle --track=internal

# Build and publish to production
./gradlew publishBundle --track=production
```

## Release Notes Management

### Version-Specific Release Notes

Create version-specific changelogs:

```
playstore/en-US/changelogs/29.txt  # For version code 29
playstore/en-US/changelogs/30.txt  # For version code 30
```

The plugin will automatically use the correct changelog for each version.

### Default Release Notes

If no version-specific file exists, the plugin uses:
```
playstore/en-US/changelogs/default.txt
```

### Character Limit

Release notes are limited to **500 characters** including spaces.

## Metadata Updates

### Update Store Listing

Edit files in `playstore/en-US/` (or other locales):

- `title.txt` - App title (50 chars max)
- `short-description.txt` - Short description (80 chars max)
- `full-description.txt` - Full description (4000 chars max)

### Update Graphics

Add graphics to `playstore/graphics/`:

```
playstore/graphics/
├── featureGraphic.png (1024x500)
└── phoneScreenshots/
    ├── 1-home.png
    ├── 2-library.png
    └── ...
```

### Publish Metadata Changes

```bash
# Publish all metadata and graphics (no binary upload)
./gradlew publishListing

# Or include with AAB upload
./gradlew publishBundle
```

## Troubleshooting

### Error: "The caller does not have permission"

**Solution:**
1. Verify service account has correct permissions in Play Console
2. Wait 24 hours after granting permissions (can take time to propagate)
3. Ensure API is enabled in Google Cloud Console

### Error: "Application not found"

**Solution:**
1. Ensure you've created at least one release manually in Play Console
2. Verify the application ID matches in `app/build.gradle.kts`
3. Check that service account has access to the specific app

### Error: "Invalid credentials"

**Solution:**
1. Regenerate the service account JSON key
2. Update the GitHub secret with the new key
3. Ensure the JSON is not corrupted or base64-encoded when added as secret

### Error: "Version code X has already been used"

**Solution:**
1. Increment version code in `app/build.gradle.kts`
2. Version codes must be unique and increasing

### Error: "Track not found"

**Solution:**
1. Create the track manually in Play Console first
2. For internal track: Go to **Testing** → **Internal testing** → **Create new release**
3. Upload one release manually to initialize the track

### Local Publishing Issues

If local publishing fails:

1. Verify credentials file exists:
   ```bash
   ls -la play-store-credentials.json
   ```

2. Validate JSON format:
   ```bash
   cat play-store-credentials.json | jq .
   ```

3. Check file permissions:
   ```bash
   chmod 600 play-store-credentials.json
   ```

### Metadata Validation Failures

If metadata validation fails:

1. Check character limits:
   - Title: 50 chars
   - Short description: 80 chars
   - Full description: 4000 chars
   - Release notes: 500 chars

2. Validate all required files exist:
   ```bash
   ./gradlew validatePlayResources
   ```

## Best Practices

### Security

1. **Never commit** service account JSON to version control
2. **Rotate keys** every 90 days
3. **Use minimal permissions** - only what's needed
4. **Monitor API usage** in Google Cloud Console

### Release Strategy

1. **Internal → Alpha → Beta → Production**
   - Test on internal track first
   - Promote to alpha for wider testing
   - Beta for public testing
   - Production for all users

2. **Staged Rollouts**
   - Use Play Console to set rollout percentage
   - Start with 10-20% of users
   - Monitor crash reports
   - Increase rollout gradually

3. **Version Management**
   - Keep version codes sequential
   - Use semantic versioning for version names
   - Update changelogs for each release

### Automation

1. **CI/CD Pipeline**
   - Trigger Play Store deploy after successful release
   - Run tests before publishing
   - Validate metadata in PR checks

2. **Monitoring**
   - Set up alerts for failed deployments
   - Monitor Play Console for new reviews
   - Check crash reports regularly

## Additional Resources

- [Gradle Play Publisher Documentation](https://github.com/Triple-T/gradle-play-publisher)
- [Google Play Developer API](https://developers.google.com/android-publisher)
- [Play Console Help](https://support.google.com/googleplay/android-developer)
- [Publishing Overview](https://developer.android.com/studio/publish)

## Support

For issues specific to this app:
- Open an issue on GitHub
- Check existing issues for solutions
- Consult the troubleshooting section above

For Play Console issues:
- [Google Play Console Support](https://support.google.com/googleplay/android-developer)
