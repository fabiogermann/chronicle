# Chronicle - Plex Integration Architecture

## Overview

Chronicle integrates with Plex Media Server to stream and download audiobook content. This document describes the architecture of the Plex integration, the client-server communication flow, and important implementation details.

## Architecture Components

### 1. Core Plex Classes

#### PlexInterceptor (`app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt`)
**Purpose**: OkHttp interceptor that injects required Plex headers into all HTTP requests.

**Key Responsibilities**:
- Adds Plex identification headers (`X-Plex-Product`, `X-Plex-Platform`, `X-Plex-Client-Identifier`, etc.)
- Injects authentication tokens (user token, server token, or account token)
- **Critical**: Adds `X-Plex-Client-Profile-Extra` header to declare client capabilities
- Replaces placeholder URLs with actual server URLs

**Authentication Flow**:
```kotlin
serviceToken = if (isLoginService) userToken else serverToken
authToken = if (serviceToken.isNullOrEmpty()) accountToken else serviceToken
```

#### PlexConfig (`app/src/main/java/local/oss/chronicle/data/sources/plex/PlexConfig.kt`)
**Purpose**: Central configuration manager for Plex connectivity and state.

**Key Responsibilities**:
- Manages server connection state (`CONNECTING`, `CONNECTED`, `NOT_CONNECTED`, `CONNECTION_FAILED`)
- Handles connection selection from multiple available connections (local vs. remote/relayed)
- Provides URL construction utilities (`toServerString()`)
- Creates download requests with proper authentication
- Manages session identifiers

**Connection Selection Algorithm**:
1. Sorts connections with local connections prioritized
2. Tests all connections in parallel (with 15-second timeout)
3. Returns first successful connection
4. Falls back to remote/relayed connections if local fails

#### PlexMediaSource (`app/src/main/java/local/oss/chronicle/data/sources/plex/PlexMediaSource.kt`)
**Purpose**: Implementation of `MediaSource` interface for Plex.

**Status**: Currently contains stub implementations - to be completed.

### 2. Plex Client Profile System

#### What is a Client Profile?

Plex Media Server uses **client profiles** to determine:
1. What audio/video formats a client can natively play (direct play)
2. What formats require transcoding
3. What protocols the client supports (HTTP, DASH, HLS, etc.)

Without a proper client profile, Plex cannot make intelligent playback decisions.

#### The Problem (Before Fix)

**Server Error**:
```
ERROR - Streaming Resource: Cannot make a decision because either the file is unplayable 
or the client provided bad data

Reached Decision codes=(
  Direct Play=3000, App cannot direct play this item. No direct play music profile exists 
  for protocol http, with container mp4 using codec aac.
  
  Transcode=4005, Cannot convert this item. No conversion profile found for protocol http.
)
```

**Root Cause**: Chronicle was not sending `X-Plex-Client-Profile-Extra` header, so Plex had no information about what audio formats the Android app could handle.

#### The Solution

Added `X-Plex-Client-Profile-Extra` header with **direct play profile** per Plex API specification.

```kotlin
private const val CLIENT_PROFILE_EXTRA =
    "add-direct-play-profile(type=musicProfile&container=mp4,m4a,m4b,mp3,flac,ogg,opus&audioCodec=aac,mp3,flac,vorbis,opus&videoCodec=*&subtitleCodec=*)"
```

**Profile Breakdown** (per [Plex API Profile Augmentation spec](https://developer.plex.tv/pms/)):

**Direct Play Profile**:
- `type=musicProfile`: Required type for audio/music content (not `music`)
- `container=mp4,m4a,m4b,mp3,flac,ogg,opus`: Supported container formats
- `audioCodec=aac,mp3,flac,vorbis,opus`: Natively supported audio codecs
- `videoCodec=*`: Wildcard (not applicable to audio, required by API)
- `subtitleCodec=*`: Wildcard (not applicable to audio, required by API)

**Why Only Direct Play Profile?**

The Plex "Generic" profile already includes transcode targets for HTTP streaming. Adding custom transcode targets causes conflicts:
```
ERROR - ClientProfileExtra: music transcode target already exists for streaming http
```

The direct play profile alone is sufficient because:
1. Enables native playback when possible (no transcoding)
2. When bandwidth is limited, Plex falls back to its built-in Generic profile transcode targets
3. Avoids duplicate target conflicts

#### Supported Audio Formats

Based on Android's MediaCodec and ExoPlayer capabilities:

| Format | Container | Direct Play | Notes |
|--------|-----------|-------------|-------|
| AAC    | mp4/m4a/m4b | ✅ Yes | Primary audiobook format |
| MP3    | mp3       | ✅ Yes | Universal support |
| FLAC   | flac      | ✅ Yes | Lossless audio |
| Vorbis | ogg       | ✅ Yes | Open format |
| Opus   | opus/ogg  | ✅ Yes | Modern codec |

### 3. Authentication Flow

```
┌─────────────────┐
│   User Login    │
│   (Plex.tv)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ OAuth Flow      │
│ Get Auth Token  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Select Server   │
│ (Local/Remote)  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Select Library  │
│ (Audiobooks)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Connection Test │
│ (PlexConfig)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Ready to Stream │
└─────────────────┘
```

### 4. Dual HTTP Client Architecture (CRITICAL)

Chronicle uses **TWO separate HTTP clients** for Plex communication, and **BOTH must send the client profile header**:

#### 1. OkHttp with PlexInterceptor
**Used for**: API calls (metadata, playlists, library browsing)
**Configuration**: [`PlexInterceptor.kt`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt)
**Headers set**: All Plex identification headers including `X-Plex-Client-Profile-Extra`

#### 2. ExoPlayer's DefaultHttpDataSource
**Used for**: **Actual audio streaming** (the critical path for playback)
**Configuration**: [`ServiceModule.plexDataSourceFactory()`](app/src/main/java/local/oss/chronicle/injection/modules/ServiceModule.kt)
**Headers set**: Must include `X-Plex-Client-Profile-Extra` for direct play to work

⚠️ **CRITICAL**: The `X-Plex-Client-Profile-Extra` header MUST be set in **BOTH** HTTP clients:
- PlexInterceptor handles API/metadata requests (via OkHttp)
- ExoPlayer's data source handles actual audio streaming URLs like `/library/parts/26/file.m4b`

#### Why Both Are Needed

When you request an audiobook to play:
1. **App → Plex API** (via OkHttp/PlexInterceptor): Gets metadata and track URLs
2. **ExoPlayer → Audio Stream** (via DefaultHttpDataSource): Streams the actual audio file

If the client profile is only in PlexInterceptor but not in ExoPlayer's headers, **Plex server will still fail** because it doesn't see the profile when ExoPlayer requests the audio stream.

#### The Dual Fix Applied

**PlexInterceptor.kt** (for API calls):
```kotlin
private const val CLIENT_PROFILE_EXTRA =
    "add-direct-play-profile(type=musicProfile&container=mp4,m4a,m4b,mp3,flac,ogg,opus&audioCodec=aac,mp3,flac,vorbis,opus&videoCodec=*&subtitleCodec=*)"

requestBuilder.header("X-Plex-Client-Profile-Extra", CLIENT_PROFILE_EXTRA)
```

**ServiceModule.kt** (for audio streaming - THIS IS WHERE IT MATTERS MOST):
```kotlin
// In plexDataSourceFactory()
dataSourceFactory.setDefaultRequestProperties(
    mapOf(
        // ... other headers ...
        "X-Plex-Client-Profile-Extra" to
            "add-direct-play-profile(type=musicProfile&container=mp4,m4a,m4b,mp3,flac,ogg,opus&audioCodec=aac,mp3,flac,vorbis,opus&videoCodec=*&subtitleCodec=*)"
    )
)
```

**Key Points:**
- Must use `type=musicProfile` (not `music`) - server rejects incorrect types
- Must include `videoCodec=*` and `subtitleCodec=*` even for audio-only (API requirement)
- Do NOT add duplicate transcode targets - Generic profile already has them
- Profile must exist in both HTTP clients for complete functionality

**Server Log Validation:**
```
✅ Direct Play=1000, Direct play OK
✅ MDE: DirectPlay decision made
✅ Transcode=1001, Conversion OK (from Generic profile)
```

### 5. Request/Response Flow

#### Typical Audio Playback Request

1. **Client Request** (via PlexInterceptor):
```http
GET /library/parts/30/1767632118/file.m4b HTTP/1.1
Host: [server-url]
X-Plex-Platform: Android
X-Plex-Product: Chronicle
X-Plex-Client-Identifier: [uuid]
X-Plex-Token: [auth-token]
X-Plex-Client-Profile-Extra: add-direct-play-profile(type=music&protocol=http&container=mp4...)
```

2. **Server Decision Process**:
   - Parse client profile
   - Check file format (mp4/m4b with AAC codec)
   - Match against client capabilities
   - Decision: **Direct Play** (no transcoding needed)

3. **Server Response**:
```http
HTTP/1.1 200 OK
Content-Type: audio/mp4
Content-Length: 726341316

[audio stream data]
```

#### Metadata Request

```http
GET /library/metadata/119 HTTP/1.1
Accept: application/json
X-Plex-Token: [auth-token]

Response:
{
  "MediaContainer": {
    "Metadata": [{
      "title": "Empire of Storms",
      "Media": [{
        "audioCodec": "aac",
        "container": "mp4",
        "Part": [{
          "file": "/path/to/audiobook.m4b"
        }]
      }]
    }]
  }
}
```

### 6. Connection Types

Plex supports multiple connection methods:

1. **Local Connection**:
   - Direct LAN access (fastest)
   - No relay through Plex servers
   - URL pattern: `http://192.168.x.x:32400`

2. **Remote Connection**:
   - Direct internet access to server
   - URL pattern: `https://[public-ip]:32400`

3. **Relayed Connection** (fallback):
   - Proxied through Plex.tv infrastructure
   - URL pattern: `https://[random].plex.direct:8443`
   - Higher latency, used when direct connections fail

### 7. Data Models

Key Plex-specific models in `app/src/main/java/local/oss/chronicle/data/sources/plex/model/`:

- **PlexMediaContainer**: Root response wrapper
- **PlexDirectory**: Album/book metadata
- **TrackPlexModel**: Individual audio track
- **PlexChapter**: Chapter markers
- **PlexServer**: Server information
- **PlexUser**: User/account details

## Implementation Details

### Session Management

Each playback session has:
- **Session Identifier**: Random ID generated per app session
- **Playback Session ID**: Unique ID per playback instance
- Used for timeline updates and progress tracking

### Timeline Updates

Chronicle sends timeline updates to Plex for:
- Playback position tracking
- Watch/listen history
- "Continue Watching/Listening" features

```kotlin
POST /:/timeline
?ratingKey=41
&playbackTime=4000
&state=playing
&X-Plex-Session-Identifier=[session-id]
```

### Download Implementation

Downloads use Fetch2 library with Plex authentication:

```kotlin
Request(remoteUri, downloadLoc).apply {
    tag = bookTitle
    groupId = uniqueBookId
    addHeader("X-Plex-Token", token)
}
```

## Testing the Fix

### Before Fix
- Server logs: `Direct Play=3000, App cannot direct play...`
- Audio forced to transcode (unnecessary CPU usage on server)
- Potential playback delays

### After Fix (Expected)
- Server logs: `Direct Play=1000, Direct play OK`
- AAC/MP4/M4B files play without transcoding
- Lower server CPU usage
- Faster playback start

### How to Test

1. Build and install the app with the updated files:
   - `PlexInterceptor.kt` (adds header for API calls)
   - `ServiceModule.kt` (adds header for audio streaming - **critical for playback**)
2. Configure Plex server connection
3. Select an audiobook in AAC/MP4/M4B format
4. Start playback
5. Check Plex server logs at: `/var/lib/plexmediaserver/Library/Application Support/Plex Media Server/Logs/`
6. Look for: `Direct Play=1000` instead of `Direct Play=3000`
7. Verify audio streams directly without transcoding

## Debugging Tips

### Enable Plex Logging
Add to Timber logging in PlexInterceptor:
```kotlin
Timber.d("Plex Request: ${chain.request().url}")
Timber.d("Client-Profile: $CLIENT_PROFILE_EXTRA")
```

### Common Issues

1. **Authentication failures**: Check token in PlexPrefsRepo
2. **Connection timeouts**: Verify server URL in PlexConfig
3. **Transcode instead of direct play**: Verify client profile header is being sent

### Useful Server Log Patterns

```bash
# Check decision logs
grep "Reached Decision" "Plex Media Server.log"

# Check streaming requests
grep "Streaming Resource" "Plex Media Server.log"

# Check client profiles
grep "Client-Profile" "Plex Media Server.log"
```

## Future Improvements

1. **Complete PlexMediaSource implementation**
   - Implement `fetchAudiobooks()`, `fetchTracks()`, etc.
   - Replace current placeholder methods

2. **Enhanced Profile Support**
   - Add video profiles for future video support
   - Dynamic profile generation based on device capabilities

3. **Offline Detection**
   - Better handling of connectivity changes
   - Automatic reconnection on network restore

4. **Better Error Handling**
   - User-friendly error messages
   - Retry logic for transient failures

## References

- [Official Plex Media Server API Documentation](https://developer.plex.tv/pms/) - **Primary reference for Profile Augmentations**
- [Plex Media Server API Community Wiki](https://github.com/Arcanemagus/plex-api/wiki)
- [Plex Client Identification](https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/)
- [ExoPlayer Supported Formats](https://exoplayer.dev/supported-formats.html)
- [Android MediaCodec Capabilities](https://developer.android.com/guide/topics/media/media-formats)
- [Profile Augmentation API Spec](https://developer.plex.tv/pms/#profile-augmentations) - Details on `X-Plex-Client-Profile-Extra` header

## 8. Bandwidth Handling and Permission Issues

### The Problem

When using the current implementation, users may encounter these Plex server errors:

```
WARN - Bandwidth exceeded: 3741 kbps > 2000 kbps
WARN - Denying access due to session lacking permission to direct play
```

### Root Cause Analysis

Chronicle's current approach directly constructs file path URLs and bypasses Plex's playback decision engine:

**Current Implementation** (in [`MediaItemTrack.getTrackSource()`](app/src/main/java/local/oss/chronicle/data/model/MediaItemTrack.kt:121)):
```kotlin
fun getTrackSource(): String {
    return if (cached) {
        File(Injector.get().prefsRepo().cachedMediaDir, getCachedFileName()).absolutePath
    } else {
        Injector.get().plexConfig().toServerString(media)
        // Results in: https://server.com/library/parts/30/1767632118/file.m4b
    }
}
```

This creates URLs like `/library/parts/30/1767632118/file.m4b` which are fed directly to ExoPlayer.

**Problems with this approach:**
1. **No playback session negotiation** - Server doesn't know client capabilities or bandwidth limits
2. **No session permissions** - Direct file requests lack proper playback session context
3. **No bandwidth adaptation** - Server can't transcode to lower bitrate when bandwidth is limited
4. **Rigid error handling** - Server simply rejects the request instead of adapting

### How Plex Web Browser Handles This

From HAR file analysis (`/Users/germann/Downloads/app.plex.tv_Archive [26-01-11 10-18-35].har`):

#### Step-by-Step Flow

1. **Create Play Queue** (establishes context):
```http
POST /playQueues
?type=audio
&uri=server://[serverId]/com.plexapp.plugins.library/library/metadata/119
&repeat=0
&own=1
&includeChapters=1
```

2. **Request Playback Decision** (negotiate with server):
```http
GET /music/:/transcode/universal/decision
?hasMDE=1
&path=/library/metadata/41
&mediaIndex=0
&partIndex=0
&musicBitrate=320
&directStreamAudio=1
&mediaBufferSize=12288
&session=lhtefwijv0pmqnze63pm7p4j
&protocol=dash
&directPlay=1
&directStream=0
&X-Plex-Session-Identifier=9miajqsqe5lr1ikci0wposqo
&X-Plex-Client-Profile-Extra=add-transcode-target(type=musicProfile&context=streaming&protocol=dash&container=mp4&audioCodec=aac)...
```

3. **Server Decision Response** (XML):
```xml
<MediaContainer
    directPlayDecisionCode="3000"
    directPlayDecisionText="App cannot direct play this item..."
    mdeDecisionCode="3001"
    mdeDecisionText="Not enough bandwidth for direct play of this item. Required bandwidth is 5226kbps and only 2000kbps is available."
    transcodeDecisionCode="1001"
    transcodeDecisionText="Direct play not available; Conversion OK.">
  <Track>
    <Media protocol="dash" selected="1">
      <Part decision="transcode">
        <Stream decision="copy" location="segments-audio" />
      </Part>
    </Media>
  </Track>
</MediaContainer>
```

4. **Get Streaming Manifest**:
```http
GET /music/:/transcode/universal/start.mpd
?session=fiz2matvkl9xkijcy94jnr5x
&protocol=dash
&[all previous parameters]
```

5. **Stream Segments** (DASH adaptive streaming):
```http
GET /music/:/transcode/universal/session/fiz2matvkl9xkijcy94jnr5x/0/header
GET /music/:/transcode/universal/session/fiz2matvkl9xkijcy94jnr5x/0/0.m4s
GET /music/:/transcode/universal/session/fiz2matvkl9xkijcy94jnr5x/0/1.m4s
...
```

#### Key Parameters from HAR Analysis

**Critical Query Parameters**:
- `hasMDE=1` - Has Modern Direct Engine support
- `path=/library/metadata/{trackRatingKey}` - Track to play (NOT the file path)
- `mediaIndex=0` - Which media stream to use
- `partIndex=0` - Which part of the media
- `musicBitrate=320` - Desired bitrate in kbps (server may reduce if needed)
- `protocol=dash` - Preferred streaming protocol (DASH, HLS, HTTP)
- `directPlay=1` - Willing to direct play if possible
- `directStream=0` or `1` - Direct stream preference
- `session={uniqueId}` - Session identifier for this transcode
- `mediaBufferSize=12288` - Buffer size in KB

**Critical Headers** (already implemented):
- `X-Plex-Session-Identifier` - Overall session ID (we generate in PlexConfig)
- `X-Plex-Client-Profile-Extra` - Client capabilities (already implemented)
- `X-Plex-Token` - Authentication (already handled by PlexInterceptor)

**Additional Session Headers** (used by browser):
- `X-Plex-Playback-Session-Id` - Unique ID for this playback
- `X-Plex-Playback-Id` - Another playback identifier

### Why This Approach Works

The `/transcode/universal/decision` endpoint allows Plex to:

1. **Evaluate bandwidth constraints**: Server checks if client's bandwidth can handle direct play
2. **Make intelligent decisions**:
   - If bandwidth is sufficient → Direct play (no transcoding)
   - If bandwidth is limited → Transcode to lower bitrate (adaptive)
3. **Establish proper session**: Creates playback session with permissions
4. **Return appropriate stream**:
   - Direct play: Returns direct file URL
   - Transcode: Returns DASH/HLS manifest URL with session info

### Server Decision Codes

From HAR file and Plex documentation:

| Code | Meaning | Action |
|------|---------|--------|
| **Direct Play Decision** |
| 1000 | Direct play OK | Use file directly |
| 3000 | Cannot direct play | Need to transcode or convert |
| **MDE (Modern Direct Engine) Decision** |
| 1000 | MDE OK | Direct streaming OK |
| 3001 | Not enough bandwidth | Transcode required due to bandwidth |
| **Transcode Decision** |
| 1001 | Conversion OK | Transcoding available and will work |
| 4005 | Cannot convert | No suitable transcode profile |

### Comparison: Current vs Proper Approach

| Aspect | Chronicle (Current) | Plex Web (Proper) |
|--------|---------------------|-------------------|
| **URL Construction** | Direct file path: `/library/parts/30/file.m4b` | Decision endpoint: `/music/:/transcode/universal/decision` |
| **Session Establishment** | None - no session negotiation | Creates proper playback session with IDs |
| **Bandwidth Handling** | Server rejects if over limit | Server adapts by transcoding to lower bitrate |
| **Permission Model** | No session → permission denied | Session established → granted permissions |
| **Error Resilience** | Fails immediately | Gracefully degrades to transcode |
| **Protocol** | Direct HTTP file download | DASH/HLS adaptive streaming when needed |
| **Server Load** | All-or-nothing (direct play or fail) | Smart (transcodes only when necessary) |

### Proposed Solution Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Before Playback                            │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
        ┌──────────────────────────────────────────────┐
        │ Call /music/:/transcode/universal/decision   │
        │                                              │
        │ Parameters:                                  │
        │  - path=/library/metadata/{trackRatingKey}   │
        │  - protocol=dash                             │
        │  - musicBitrate=320                          │
        │  - session={generateUniqueId()}              │
        │  - directPlay=1                              │
        │  + All Plex headers (already implemented)    │
        └──────────────────┬───────────────────────────┘
                           │
                           ▼
        ┌──────────────────────────────────────────────┐
        │        Server Makes Decision                 │
        │                                              │
        │ Checks:                                      │
        │  ✓ Client bandwidth vs file bitrate          │
        │  ✓ Client capabilities (from profile)        │
        │  ✓ User permissions                          │
        │  ✓ File format compatibility                 │
        └──────────────────┬───────────────────────────┘
                           │
            ┌──────────────┴──────────────┐
            │                             │
            ▼                             ▼
┌─────────────────────┐       ┌─────────────────────┐
│  Direct Play OK     │       │  Transcode Needed   │
│  (Code: 1000)       │       │  (Code: 3001)       │
└──────────┬──────────┘       └──────────┬──────────┘
           │                             │
           │                             ▼
           │              ┌──────────────────────────────┐
           │              │ Return DASH Manifest URL:    │
           │              │ /music/:/transcode/universal/│
           │              │   start.mpd?session={id}     │
           │              └──────────────┬───────────────┘
           │                             │
           ▼                             ▼
┌──────────────────────────────────────────────────────┐
│              ExoPlayer Media Source                  │
│                                                      │
│  Direct Play: https://server/library/parts/30/...   │
│         OR                                           │
│  DASH Stream: https://server/music/:/transcode/...  │
└──────────────────────────────────────────────────────┘
```

### Implementation Plan

#### Phase 1: Add Decision Endpoint to PlexService

```kotlin
// In PlexService.kt - add new endpoint
@GET("/music/:/transcode/universal/decision")
suspend fun getTranscodeDecision(
    @Query("hasMDE") hasMDE: Int = 1,
    @Query("path") path: String, // e.g., "/library/metadata/41"
    @Query("mediaIndex") mediaIndex: Int = 0,
    @Query("partIndex") partIndex: Int = 0,
    @Query("musicBitrate") musicBitrate: Int = 320,
    @Query("directStreamAudio") directStreamAudio: Int = 1,
    @Query("mediaBufferSize") mediaBufferSize: Int = 12288,
    @Query("session") session: String,
    @Query("protocol") protocol: String = "dash",
    @Query("directPlay") directPlay: Int = 1,
    @Query("directStream") directStream: Int = 0,
): Response<String> // Returns XML
```

#### Phase 2: Create Decision Response Model

```kotlin
// New file: PlexTranscodeDecision.kt
data class PlexTranscodeDecision(
    val directPlayDecisionCode: Int,
    val mdeDecisionCode: Int,
    val transcodeDecisionCode: Int,
    val shouldDirectPlay: Boolean,
    val streamingUrl: String, // Either direct path or manifest URL
    val protocol: String // "http" or "dash"
)
```

#### Phase 3: Update MediaItemTrack.getTrackSource()

```kotlin
suspend fun getTrackSourceWithDecision(plexService: PlexService): String {
    return if (cached) {
        File(Injector.get().prefsRepo().cachedMediaDir, getCachedFileName()).absolutePath
    } else {
        // Call decision endpoint
        val session = UUID.randomUUID().toString()
        val decision = plexService.getTranscodeDecision(
            path = "/library/metadata/$id",
            session = session
        )
        
        // Parse response and return appropriate URL
        if (decision.shouldDirectPlay) {
            Injector.get().plexConfig().toServerString(media)
        } else {
            // Return DASH manifest URL
            "/music/:/transcode/universal/start.mpd?session=$session&..."
        }
    }
}
```

#### Phase 4: Update Playlist Builder

Modify [`AudiobookMediaSessionCallback.buildPlaylist()`](app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) to:
1. Call decision endpoint for each track before adding to playlist
2. Use returned streaming URL as media source
3. Handle both direct play and DASH streaming URLs

#### Phase 5: ExoPlayer DASH Support

ExoPlayer already supports DASH natively. Ensure:
1. DASH data source factory is properly configured
2. Media items with `.mpd` URLs are recognized as DASH streams
3. Proper headers are passed to DASH segments (already done via ServiceModule)

### Benefits of This Approach

1. **Bandwidth Compliance**: Server can transcode to lower bitrate instead of rejecting
2. **Better User Experience**: Playback works even on limited bandwidth connections
3. **Server-Controlled Quality**: Plex server manages quality based on available bandwidth
4. **Proper Session Management**: Establishes sessions with permissions
5. **Adaptive Streaming**: DASH allows quality adaptation during playback
6. **Future-Proof**: Aligns with how official Plex clients work

### Backwards Compatibility Note

The `X-Plex-Client-Profile-Extra` fix (already implemented) should be kept because:
1. It's still needed for the decision endpoint to understand client capabilities
2. It enables direct play when bandwidth is sufficient
3. It's required for both direct play and transcode decisions

The transcode/decision approach **complements** rather than replaces the client profile fix.

### Testing the Solution

1. **Set Plex server bandwidth limit** (Plex Web → Settings → Network → Remote Stream Bitrate: 2 Mbps)
2. **Attempt playback** of high-bitrate audiobook (e.g., 320 kbps AAC)
3. **Expected behavior**:
   - **Before fix**: `Bandwidth exceeded` error, playback fails
   - **After fix**: Server transcodes to 128 kbps, playback succeeds
4. **Verify in server logs**:
   ```
   ✓ MDE Decision: Not enough bandwidth, will transcode
   ✓ Transcode Decision: 1001 - Conversion OK
   ✓ Starting transcode session: fiz2matvkl9xkijcy94jnr5x
   ```

### Implementation Status (January 2026)

The bandwidth-aware playback solution has been **implemented** using the following components:

#### Components Added

1. **[`PlexTranscodeDecision.kt`](app/src/main/java/local/oss/chronicle/data/sources/plex/model/PlexTranscodeDecision.kt)** - NEW
   - Data models for decision endpoint response
   - Helper functions to extract streaming URLs
   - Decision code validation

2. **[`PlaybackUrlResolver.kt`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlaybackUrlResolver.kt)** - NEW
   - Calls `/music/:/transcode/universal/decision` endpoint
   - Resolves and caches streaming URLs before playback
   - Populates `MediaItemTrack.streamingUrlCache`

3. **[`PlexService.kt:151-175`](app/src/main/java/local/oss/chronicle/data/sources/plex/PlexService.kt)** - UPDATED
   - Added `getPlaybackDecision()` endpoint method
   - Includes all required parameters for decision negotiation

4. **[`MediaItemTrack.kt:45-54,121-157`](app/src/main/java/local/oss/chronicle/data/model/MediaItemTrack.kt)** - UPDATED
   - Added static `streamingUrlCache` for pre-resolved URLs
   - Updated `getTrackSource()` to check cache first
   - Falls back to direct file URLs if no pre-resolved URL available

5. **[`AudiobookMediaSessionCallback.kt:59,312-317,493-495`](app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt)** - UPDATED
   - Injected `PlaybackUrlResolver` dependency
   - Pre-resolves streaming URLs before building playlist
   - Clears URL cache when playback stops

6. **[`ServiceModule.kt:26-28,194-198`](app/src/main/java/local/oss/chronicle/injection/modules/ServiceModule.kt)** - UPDATED
   - Added dependency injection provider for `PlaybackUrlResolver`

#### How It Works

**Playback Flow**:
1. User starts playback → [`AudiobookMediaSessionCallback.playBook()`](app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt:269)
2. Pre-resolve URLs → `playbackUrlResolver.preResolveUrls(tracks)` calls decision endpoint for each track
3. Cache populated → URLs stored in `MediaItemTrack.streamingUrlCache`
4. Build playlist → [`buildPlaylist()`](app/src/main/java/local/oss/chronicle/features/player/AudiobookPlaybackPreparer.kt:8) calls `track.getTrackSource()`
5. Get URL → `getTrackSource()` checks cache first, falls back to direct URL
6. ExoPlayer streams → Uses bandwidth-aware URL (direct play or transcode session)

**Benefits**:
- ✅ Handles bandwidth limits gracefully (transcodes instead of failing)
- ✅ Establishes proper Plex playback sessions with permissions
- ✅ Falls back to direct URLs if decision endpoint unavailable (backwards compatible)
- ✅ Pre-resolves all track URLs upfront (avoids playback delays)
- ✅ Caches URLs to avoid redundant API calls

**Error Handling**:
- If decision endpoint fails → Falls back to direct file URL (original behavior)
- If specific track resolution fails → That track uses direct URL, others may succeed
- Cache cleared on playback stop → Fresh resolution for next playback session

## Conclusion

The Plex integration in Chronicle relies on proper client identification through headers, especially the `X-Plex-Client-Profile-Extra` header. This enables the Plex server to make intelligent decisions about whether to direct play or transcode content. The fix implemented ensures AAC/MP4/M4B audiobooks can be direct-played without unnecessary transcoding, improving performance and reducing server load.

**The bandwidth-aware playback implementation** using `/music/:/transcode/universal/decision` endpoint allows Chronicle to handle Plex server bandwidth limits gracefully. Instead of failing with permission errors, the app now negotiates with Plex to either direct play (when bandwidth allows) or transcode to lower bitrates (when bandwidth is limited), providing a robust and user-friendly playback experience similar to the official Plex web client.
