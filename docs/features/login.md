# Login & Authentication

This document covers Chronicle's authentication flow, including OAuth, user selection, server selection, and library selection.

## Plex Authentication

### OAuth Flow (Chrome Custom Tabs with Android App Links)

Chronicle uses Plex's OAuth 2.0 PIN-based authentication flow with **Chrome Custom Tabs** and **Android App Links** for secure, seamless browser authentication. The browser automatically closes when redirecting to Chronicle's verified App Link, providing a smooth OAuth experience.

**Key Technologies**:
- **Chrome Custom Tabs**: Native browser authentication (not WebView) for social login support
- **Android App Links**: HTTPS-based deep linking with domain verification for automatic browser dismissal
- **Digital Asset Links**: Domain ownership verification via `assetlinks.json`

```mermaid
sequenceDiagram
    participant User
    participant Chronicle
    participant Plex.tv
    participant ChromeCustomTabs as Chrome Custom Tabs
    participant DeepLink as Deep Link Handler
    
    User->>Chronicle: Tap Sign In
    Chronicle->>Plex.tv: POST /api/v2/pins.json
    Plex.tv-->>Chronicle: PIN + code + clientID
    Chronicle->>Chronicle: Build OAuth URL with forwardUrl
    Chronicle->>ChromeCustomTabs: Launch with OAuth URL
    ChromeCustomTabs->>Plex.tv: Load login page (platform=Web)
    User->>ChromeCustomTabs: Enter credentials / use social login
    ChromeCustomTabs->>Plex.tv: Complete authentication
    Plex.tv-->>ChromeCustomTabs: Redirect to https://auth.chronicleapp.net/callback
    
    Note over ChromeCustomTabs: Detects verified App Link,<br/>automatically closes
    
    ChromeCustomTabs->>DeepLink: Launch AuthReturnActivity (App Link)
    ChromeCustomTabs->>User: Browser closes automatically
    DeepLink->>Chronicle: Resume app, expedite polling
    Chronicle->>Plex.tv: GET /api/v2/pins/{id} (polling)
    Plex.tv-->>Chronicle: authToken
    Chronicle->>Chronicle: Store token, transition to success
```

**Key Implementation Details:**
- **Platform = "Web"**: Critical for enabling social login (Google, Facebook) which is blocked in WebViews
- **Chrome Custom Tabs**: Provides native browser security, password managers, and autofill
- **Android App Links**: `https://auth.chronicleapp.net/callback` verified via Digital Asset Links—causes automatic browser dismissal
- **Legacy Fallback**: `chronicle://auth/callback` custom URI scheme remains for edge cases where App Links fail
- **Manual linking fallback**: On devices where App Link verification never happened (e.g. some Vivo/Funtouch builds, devices without full Play Services), the deep link never fires. From the very start of polling the login screen shows a tappable "Trouble signing in?" link; tapping it creates a **non-strong PIN** (plex.tv/link only accepts its 4-character code — the strong OAuth PIN's 25-char code is rejected there), displays that code, and opens plex.tv/link in a separate browser task. Polling then watches the manual PIN and completes login with no deep link required. The manual path times out after 10 minutes (the non-strong PIN expires server-side after 15).
- **Process-death recovery**: If the OS kills the app while the user is in the browser, the coordinator dies but the PIN id survives in `PlexPrefsRepo.oAuthTempId`. When the login screen resumes with a persisted PIN id, polling of that PIN resumes automatically (1-minute budget) so an already-completed browser auth still lands instead of looping the user through a fresh PIN.
- **Polling with Timeout**: Checks for token every 1.5s, expedited to 200ms after callback; 2-minute timeout for the browser flow, 10 minutes once the user switches to manual linking

### Key Files
- [`PlexAuthCoordinator`](../../app/src/main/java/local/oss/chronicle/features/auth/PlexAuthCoordinator.kt) - OAuth state machine coordinator
- [`PlexAuthUrlBuilder`](../../app/src/main/java/local/oss/chronicle/features/auth/PlexAuthUrlBuilder.kt) - Builds OAuth URL with proper encoding
- [`PlexAuthState`](../../app/src/main/java/local/oss/chronicle/features/auth/PlexAuthState.kt) - Immutable state representation
- [`AuthReturnActivity`](../../app/src/main/java/local/oss/chronicle/features/auth/AuthReturnActivity.kt) - App Link and deep link handler for auth callbacks
- [`AuthCoordinatorSingleton`](../../app/src/main/java/local/oss/chronicle/features/auth/AuthCoordinatorSingleton.kt) - Cross-component communication bridge
- [`LoginFragment`](../../app/src/main/java/local/oss/chronicle/features/login/LoginFragment.kt) - Login UI, launches Chrome Custom Tabs
- [`LoginViewModel`](../../app/src/main/java/local/oss/chronicle/features/login/LoginViewModel.kt) - OAuth state management
- [`PlexLoginRepo`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexLoginRepo.kt) - PIN creation and token polling
- [`PlexLoginService`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexService.kt) - Plex API endpoints
- [`pages/.well-known/assetlinks.json`](../../pages/.well-known/assetlinks.json) - Digital Asset Links file for domain verification
- [`pages/auth/callback/index.html`](../../pages/auth/callback/index.html) - Fallback page when App Links don't work (rare)

---

## Server/User Selection

### Multi-User Support

Plex accounts can have multiple users (managed users). Chronicle supports user switching:

```mermaid
flowchart TD
    A[Get Users] --> B{Multiple users?}
    B -->|Yes| C[Show User Selection]
    B -->|No| D[Auto-select single user]
    C --> E[User picks profile]
    E --> F{Has PIN?}
    F -->|Yes| G[Enter PIN]
    F -->|No| H[Continue]
    G --> H
    H --> I[Switch user and get token]
```

**Implementation**: 
- [`ChooseUserFragment`](../../app/src/main/java/local/oss/chronicle/features/login/ChooseUserFragment.kt)
- [`ChooseUserViewModel`](../../app/src/main/java/local/oss/chronicle/features/login/ChooseUserViewModel.kt)

### Server Selection

Users can have multiple Plex servers. Chronicle tests connectivity and selects the best connection:

```mermaid
flowchart TD
    A[Fetch servers from Plex.tv] --> B[Display server list]
    B --> C[User selects server]
    C --> D[Get server connections]
    D --> E[Test connections in parallel]
    E --> F{Local available?}
    F -->|Yes| G[Use local connection]
    F -->|No| H{Remote available?}
    H -->|Yes| I[Use remote connection]
    H -->|No| J{Relay available?}
    J -->|Yes| K[Use relay connection]
    J -->|No| L[Connection failed]
```

**Implementation**: 
- [`ChooseServerFragment`](../../app/src/main/java/local/oss/chronicle/features/login/ChooseServerFragment.kt)
- [`PlexConfig.setPotentialConnections()`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexConfig.kt)

### Library Selection

Lists Music libraries from the selected server (audiobooks are stored as music in Plex):

**Implementation**: 
- [`ChooseLibraryFragment`](../../app/src/main/java/local/oss/chronicle/features/login/ChooseLibraryFragment.kt)
- [`ChooseLibraryViewModel`](../../app/src/main/java/local/oss/chronicle/features/login/ChooseLibraryViewModel.kt)

---

## Android App Links Implementation

Chronicle uses **Android App Links** for OAuth callback to enable automatic Chrome Custom Tabs dismissal.

### Domain Verification

**Digital Asset Links File** at `https://auth.chronicleapp.net/.well-known/assetlinks.json`:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "local.oss.chronicle",
      "sha256_cert_fingerprints": ["SHA256:..."]
    }
  }
]
```

This file proves Chronicle owns the `auth.chronicleapp.net` domain. Android verifies this file at install time when `android:autoVerify="true"` is present in the intent filter.

### AndroidManifest Configuration

**Primary (App Link with auto-verification)**:
```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data
        android:scheme="https"
        android:host="auth.chronicleapp.net"
        android:pathPrefix="/callback" />
</intent-filter>
```

**Fallback (Custom URI scheme)**:
```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data
        android:scheme="chronicle"
        android:host="auth"
        android:pathPrefix="/callback" />
</intent-filter>
```

### Why App Links Auto-Close Chrome Custom Tabs

When Chrome Custom Tabs navigates to `https://auth.chronicleapp.net/callback`:

1. **Android intercepts**: System recognizes verified App Link before browser renders
2. **Domain verification**: Checks Digital Asset Links file matches app signature
3. **App launch**: Launches [`AuthReturnActivity`](../../app/src/main/java/local/oss/chronicle/features/auth/AuthReturnActivity.kt)
4. **Auto-close**: Chrome Custom Tabs automatically closes since navigation was handled by app
5. **Seamless return**: User sees Chronicle immediately without manual browser dismissal

**This does NOT work with custom URI schemes** (`chronicle://`) which leave Chrome Custom Tabs in the foreground, requiring manual close.

---

## Related Documentation

- [Plex OAuth Auto-Close Implementation](../PLEX_LOGIN_AUTO_CLOSE.md) - Detailed Chrome Custom Tabs and App Links architecture
- [Features Index](../FEATURES.md) - Overview of all features
- [API Flows](../API_FLOWS.md) - Detailed API documentation
- [OAuth Flow Examples](../example-query-responses/oauth-flow.md) - Real OAuth API responses
- [Managed Users](../example-query-responses/managed_users.md) - Managed user account examples
