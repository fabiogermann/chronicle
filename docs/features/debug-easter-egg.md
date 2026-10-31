# Debug Easter Egg Feature

## Overview

A debug screen that displays connection debugging information. Users can view available server URLs, the currently connected URL, and test connections to diagnose connectivity issues. It is reachable through a discoverable settings entry, the original easter egg, and an inline action on terminal playback errors.

## Usage

There are three ways to open the connection-test screen:

1. **Settings entry (discoverable):** Navigate to **Settings → Help & Diagnostics → Connection test**.
2. **Easter egg (kept for backwards compatibility):** In **Settings**, tap the **version information** preference 10 times within 15 seconds.
3. **Inline on playback failure:** when a playback error is shown after automatic recovery has given up (a *terminal* error), the error Snackbar includes a **Run connection test** action that opens the same screen. Errors that are still being retried in the background only show a plain toast, so a self-healing hiccup never nags with the action.

In all cases a full-screen debug dialog appears.

## Features

The debug dialog displays:

- **App Version** - Version name and build number
- **Server Name** - Name of the connected Plex server
- **Connection Status** - Current connection state (Connected, Connecting, Not Connected, etc.)
- **Active URL** - The currently used server URL
- **Available Connections** - List of all potential connection URLs with:
  - **Local/Remote labels** - `(L)` for local connections, `(R)` for remote connections
  - **Status indicators:**
    - `●` (filled circle) - Currently connected
    - `✓` (checkmark) - Test successful
    - `✗` (X) - Test failed
    - `⟳` (loading) - Test in progress
- **Test All Connections** button - Tests each URL against the `/identity` endpoint
- **Copy diagnostics** button - Copies a plain-text diagnostics report to the clipboard
- **Share** button - Sends the same report through the system share sheet

The diagnostics report contains: app version + build number, Android version + security patch
level, device manufacturer/model, Plex server name + version (once a connection test has
observed the `/identity` response), connection state, the chosen connection URI, and the last
connection-test result.

**The report is secret-safe by construction.** It is produced exclusively by
`DiagnosticsReportBuilder` (`DebugInfoViewModel.buildDiagnosticsReport` is the single choke
point used by both Copy and Share), which redacts every declared token (account/user/server)
in both raw and percent-encoded form, and additionally strips any `X-Plex-Token=` URL query
value by pattern — even a token that was never declared as a secret. Users paste these reports
into public Reddit threads and GitHub issues, so the Plex token must never appear; unit tests
(`DiagnosticsReportBuilderTest`, `DebugInfoViewModelTest`) pin that guarantee.

This feature is available in **all builds** (production and debug).

## Implementation

### Architecture

The feature follows the app's MVVM architecture with a `DialogFragment` for the UI and a `ViewModel` for state management.

```mermaid
graph TD
    subgraph Settings Feature
        SF[SettingsFragment]
        SVM[SettingsViewModel]
    end
    
    subgraph Debug Dialog
        DDF[DebugInfoDialogFragment]
        DIVM[DebugInfoViewModel]
    end
    
    subgraph Data Layer
        PC[PlexConfig]
        PPR[PlexPrefsRepo]
        PMS[PlexMediaService]
    end
    
    SF --> SVM
    SVM -->|10 taps| DDF
    DDF --> DIVM
    
    DIVM --> PC
    DIVM --> PPR
    DIVM --> PMS
```

### Implementation Files

**New Files:**

- [`DebugInfoDialogFragment.kt`](../../app/src/main/java/local/oss/chronicle/features/settings/DebugInfoDialogFragment.kt) - Dialog UI controller
- [`DebugInfoViewModel.kt`](../../app/src/main/java/local/oss/chronicle/features/settings/DebugInfoViewModel.kt) - Dialog state management
- [`dialog_debug_info.xml`](../../app/src/main/res/layout/dialog_debug_info.xml) - Full-screen dialog layout
- [`list_item_debug_connection.xml`](../../app/src/main/res/layout/list_item_debug_connection.xml) - Connection list item layout

**Modified Files:**

- [`PlexConfig.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexConfig.kt) - Added `getAvailableConnections()` method to expose connection set
- [`SettingsViewModel.kt`](../../app/src/main/java/local/oss/chronicle/features/settings/SettingsViewModel.kt) - Added tap counter logic and `showDebugDialog` LiveData event; also added the **Settings → Help & Diagnostics → Connection test** preference that posts the same event
- [`SettingsFragment.kt`](../../app/src/main/java/local/oss/chronicle/features/settings/SettingsFragment.kt) - Observes debug dialog event and shows the dialog
- [`ActivityComponent.kt`](../../app/src/main/java/local/oss/chronicle/injection/components/ActivityComponent.kt) - Added `inject(DebugInfoDialogFragment)` for dependency injection
- [`MainActivity.kt`](../../app/src/main/java/local/oss/chronicle/application/MainActivity.kt) - Terminal playback errors (recovery gave up) render as a Snackbar with a **Run connection test** action that opens the dialog; transient errors still being recovered show a plain toast. The decision is delegated to `PlaybackErrorUiDecider`
- [`PlaybackErrorUiDecider.kt`](../../app/src/main/java/local/oss/chronicle/features/player/PlaybackErrorUiDecider.kt) - Pure decision unit: only terminal (non-recovering) playback errors offer the inline connection-test action
- [`PlexMediaContainer.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/model/PlexMediaContainer.kt) - Added optional `version` field so the `/identity` response can supply the Plex server version shown in the diagnostics report

### Tap Detection

The version preference click handler in [`SettingsViewModel`](../../app/src/main/java/local/oss/chronicle/features/settings/SettingsViewModel.kt) tracks taps within a 15-second window:

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Counting: First tap
    Counting --> Counting: Tap within 15s<br/>tapCount < 10
    Counting --> ShowDialog: Tap within 15s<br/>tapCount == 10
    Counting --> Idle: 15s elapsed
    ShowDialog --> Idle: Dialog dismissed
```

**Implementation:**
- Maintains a list of tap timestamps
- On each tap, removes taps older than 15 seconds
- When 10 taps are detected, triggers the debug dialog via LiveData event

### Connection Testing

When the user clicks "Test All Connections":

1. Each connection URL is marked as "Testing" (`⟳`)
2. The app makes a request to `{url}/identity` endpoint
3. Status updates to either:
   - `✓` Success - Server responded successfully
   - `✗` Failed - Server request failed or timed out
4. The currently active connection always shows `●` Connected

All testing happens sequentially to avoid overloading the server or network.

### Dependency Injection

[`DebugInfoDialogFragment`](../../app/src/main/java/local/oss/chronicle/features/settings/DebugInfoDialogFragment.kt) uses Dagger 2 injection in `onAttach()`:

```kotlin
override fun onAttach(context: Context) {
    (requireActivity() as MainActivity).activityComponent!!.inject(this)
    super.onAttach(context)
}
```

**Injected dependencies:**
- `PlexConfig` - For current URL, connection state, and available connections
- `PlexPrefsRepo` - For server name and connection list
- `PlexMediaService` - For testing connections via `/identity` endpoint

## Testing

### Manual Testing

1. Navigate to Settings
2. Tap version info 10 times within 15 seconds
3. Verify debug dialog appears with correct information
4. Test the "Test All Connections" button
5. Verify status indicators update correctly for each connection
6. Test with different connection scenarios (local only, remote only, mixed)

---

**Related Documentation:**
- [`settings.md`](settings.md) - Settings feature overview
- [`plex-integration.md`](../architecture/plex-integration.md) - Plex connection architecture
