# Release Signing Setup Guide

This guide explains how to set up release signing for Chronicle Android app, both for local builds and GitHub Actions automated releases.

## Table of Contents
- [Overview](#overview)
- [Creating a Release Keystore](#creating-a-release-keystore)
- [Local Development Setup](#local-development-setup)
- [GitHub Actions Setup](#github-actions-setup)
- [Testing Your Setup](#testing-your-setup)

---

## Overview

The Chronicle app uses a dual signing configuration:
- **Local builds**: Uses `keystore.properties` file (not committed to Git)
- **GitHub Actions**: Uses GitHub Secrets for secure signing

Both methods use the same keystore file but configure it differently for security.

---

## Creating a Release Keystore

### Step 1: Generate the Keystore File

Use the Java `keytool` utility to create a new keystore:

```bash
keytool -genkey -v \
  -keystore chronicle-release.jks \
  -alias chronicle-key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storetype JKS
```

**Interactive Prompts:**
You'll be asked for the following information:

1. **Keystore password**: Create a strong password (e.g., `MyStrongKeystorePass123!`)
   - You'll need to enter this twice
   - **Save this password** - you'll need it later

2. **Key password**: Create another strong password (e.g., `MyKeyPassword456!`)
   - This can be the same as the keystore password or different
   - **Save this password** - you'll need it later

3. **Your name**: Your name or organization name
4. **Organizational unit**: Your team/department (e.g., "Development")
5. **Organization**: Your company/project name (e.g., "Chronicle")
6. **City/Locality**: Your city
7. **State/Province**: Your state
8. **Country code**: Two-letter country code (e.g., "US", "CA", "GB")

**Example session:**
```
Enter keystore password: MyStrongKeystorePass123!
Re-enter new password: MyStrongKeystorePass123!
What is your first and last name?
  [Unknown]:  John Doe
What is the name of your organizational unit?
  [Unknown]:  Development
What is the name of your organization?
  [Unknown]:  Chronicle
What is the name of your City or Locality?
  [Unknown]:  Toronto
What is the name of your State or Province?
  [Unknown]:  Ontario
What is the two-letter country code for this unit?
  [Unknown]:  CA
Is CN=John Doe, OU=Development, O=Chronicle, L=Toronto, ST=Ontario, C=CA correct?
  [no]:  yes

Enter key password for <chronicle-key>
	(RETURN if same as keystore password): MyKeyPassword456!
Re-enter new password: MyKeyPassword456!
```

### Step 2: Secure Your Keystore

**⚠️ IMPORTANT: Keep your keystore file and passwords secure!**

- **DO NOT** commit `chronicle-release.jks` to Git
- Store the keystore file in a secure location (password manager, encrypted drive)
- Keep a backup of both the keystore file and passwords
- If you lose the keystore, you cannot update your app on Google Play Store

**Verification:**
Check that `.gitignore` includes these entries (should already be there):
```
*.jks
*.keystore
keystore.properties
```

---

## Local Development Setup

### Step 1: Create keystore.properties

In your project root directory (same level as `build.gradle.kts`), create a file named `keystore.properties`:

```bash
touch keystore.properties
```

### Step 2: Configure keystore.properties

Add the following content to `keystore.properties`:

```properties
storeFile=chronicle-release.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=chronicle-key
keyPassword=YOUR_KEY_PASSWORD
```

**Replace with your actual values:**
- `storeFile`: Path to your keystore file (relative to project root)
- `storePassword`: The keystore password you created
- `keyAlias`: The alias you used (default: `chronicle-key`)
- `keyPassword`: The key password you created

**Example:**
```properties
storeFile=/Users/john/chronicle-release.jks
storePassword=MyStrongKeystorePass123!
keyAlias=chronicle-key
keyPassword=MyKeyPassword456!
```

### Step 3: Move Keystore to Secure Location (Optional)

For better security, you can store the keystore outside the project directory:

```bash
# Move to home directory (example)
mv chronicle-release.jks ~/chronicle-release.jks

# Update keystore.properties
# Change storeFile to absolute path:
storeFile=/Users/yourusername/chronicle-release.jks
```

### Step 4: Verify Local Signing

Build a release APK locally:

```bash
./gradlew assembleRelease
```

If successful, you'll find the signed APK at:
```
app/build/outputs/apk/release/app-release.apk
```

**Verify the APK is signed:**
```bash
# Check APK signature
jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release.apk
```

You should see:
```
jar verified.
```

---

## GitHub Actions Setup

### Step 1: Encode Keystore to Base64

GitHub Secrets only accept text, so we need to encode the binary keystore file:

**On Linux/macOS:**
```bash
base64 -i chronicle-release.jks | tr -d '\n' > keystore-base64.txt
```

**On Windows (PowerShell):**
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("chronicle-release.jks")) | Out-File -Encoding ASCII keystore-base64.txt
```

This creates `keystore-base64.txt` containing the base64-encoded keystore.

### Step 2: Add GitHub Secrets

Navigate to your GitHub repository:

1. Go to **Settings** → **Secrets and variables** → **Actions**
2. Click **New repository secret**
3. Add the following **4 secrets**:

| Secret Name | Value | Description |
|-------------|-------|-------------|
| `KEYSTORE_BASE64` | Contents of `keystore-base64.txt` | Base64-encoded keystore file |
| `KEYSTORE_PASSWORD` | Your keystore password | Password for the keystore |
| `KEY_ALIAS` | `chronicle-key` (or your alias) | Alias of the signing key |
| `KEY_PASSWORD` | Your key password | Password for the signing key |

**Adding each secret:**
1. Secret name: Enter the exact name from the table above
2. Secret value: Paste the corresponding value
3. Click **Add secret**

**Example:**
- **Secret 1:**
  - Name: `KEYSTORE_BASE64`
  - Value: `MIIKZAIBAzCCCh4GCSqGSIb3DQEHAa...` (very long base64 string)

- **Secret 2:**
  - Name: `KEYSTORE_PASSWORD`
  - Value: `MyStrongKeystorePass123!`

- **Secret 3:**
  - Name: `KEY_ALIAS`
  - Value: `chronicle-key`

- **Secret 4:**
  - Name: `KEY_PASSWORD`
  - Value: `MyKeyPassword456!`

### Step 3: Secure the Base64 File

After uploading to GitHub Secrets, **delete** the `keystore-base64.txt` file:

```bash
rm keystore-base64.txt
```

**⚠️ This file contains your keystore and should not be kept around!**

---

## Testing Your Setup

### Test Local Signing

```bash
# Clean build
./gradlew clean

# Build release APK
./gradlew assembleRelease

# Verify signing
jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release.apk
```

**Expected output:**
```
jar verified.
```

### Test GitHub Actions Signing

1. **Create a test tag:**
   ```bash
   git tag v0.56.0-test
   git push origin v0.56.0-test
   ```

2. **Monitor the workflow:**
   - Go to **Actions** tab in GitHub
   - Watch the "Release" workflow run
   - Check for any errors

3. **Verify the release:**
   - Go to **Releases** section
   - Download the APK from the release
   - Verify it's signed:
     ```bash
     jarsigner -verify -verbose -certs chronicle-v0.56.0-test.apk
     ```

4. **Clean up test release** (optional):
   - Delete the test release and tag from GitHub

---

## Troubleshooting

### Error: "keystore not found"

**Local builds:**
- Check that `keystore.properties` exists in project root
- Verify `storeFile` path is correct
- Use absolute paths if relative paths fail

**GitHub Actions:**
- Verify `KEYSTORE_BASE64` secret is set
- Re-encode and re-upload the keystore if needed

### Error: "incorrect password"

- Double-check passwords in `keystore.properties` or GitHub Secrets
- Ensure no extra spaces or special characters were added
- Verify you're using the correct keystore/key password pair

### Error: "alias not found"

- Check that `keyAlias` matches the alias used when creating keystore
- List aliases in keystore:
  ```bash
  keytool -list -v -keystore chronicle-release.jks
  ```

### APK signed with debug key

**If GitHub Actions builds with debug key:**
- Verify all 4 GitHub Secrets are set correctly
- Check workflow logs for keystore setup warnings
- Re-encode and re-upload `KEYSTORE_BASE64`

---

## Security Best Practices

1. **Never commit sensitive files:**
   - ✅ `.jks` files are in `.gitignore`
   - ✅ `keystore.properties` is in `.gitignore`
   - ✅ `keystore-base64.txt` should be deleted after use

2. **Backup your keystore:**
   - Store in encrypted cloud storage (1Password, BitWarden, etc.)
   - Keep offline backup on encrypted USB drive
   - Document passwords in password manager

3. **Rotate periodically:**
   - Consider rotating keystore every few years
   - Note: Google Play requires the same key for app updates

4. **Limit access:**
   - Only trusted team members should have keystore access
   - Use GitHub repository secrets (not environment secrets)
   - Enable 2FA on GitHub accounts with repository access

---

## Summary

**Keystore Information to Save:**
- ✅ Keystore file: `chronicle-release.jks`
- ✅ Keystore password
- ✅ Key alias: `chronicle-key`
- ✅ Key password
- ✅ Certificate details (CN, O, etc.)

**Files Created:**
- ✅ `chronicle-release.jks` (secure location, not in Git)
- ✅ `keystore.properties` (project root, not in Git)

**GitHub Secrets Required:**
- ✅ `KEYSTORE_BASE64`
- ✅ `KEYSTORE_PASSWORD`
- ✅ `KEY_ALIAS`
- ✅ `KEY_PASSWORD`

**Workflow:**
- Local builds: Use `keystore.properties`
- Tagged releases: GitHub Actions signs and publishes automatically

---

## Additional Resources

- [Android Developer - Sign your app](https://developer.android.com/studio/publish/app-signing)
- [GitHub Actions - Encrypted secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets)
- [KeyStore documentation](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/keytool.html)
