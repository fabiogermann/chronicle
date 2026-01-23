# Login & Authentication

This document covers Chronicle's authentication flow, including OAuth, user selection, server selection, and library selection.

## Plex Authentication

### OAuth Flow

Chronicle uses Plex's OAuth 2.0 PIN-based authentication flow.

```mermaid
sequenceDiagram
    participant User
    participant Chronicle
    participant Plex.tv
    participant Browser
    
    User->>Chronicle: Tap Sign In
    Chronicle->>Plex.tv: POST /api/v2/pins.json
    Plex.tv-->>Chronicle: PIN + code
    Chronicle->>Browser: Open auth URL with code
    User->>Browser: Enter credentials
    Browser->>Plex.tv: Authenticate
    Plex.tv-->>Browser: Redirect/complete
    Chronicle->>Plex.tv: GET /api/v2/pins/id
    Plex.tv-->>Chronicle: authToken
    Chronicle->>Chronicle: Store token
```

**Implementation**: [`PlexLoginRepo`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexLoginRepo.kt)

### Key Files
- [`LoginFragment`](../../app/src/main/java/local/oss/chronicle/features/login/LoginFragment.kt) - Login UI
- [`LoginViewModel`](../../app/src/main/java/local/oss/chronicle/features/login/LoginViewModel.kt) - OAuth state management
- [`PlexLoginService`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexService.kt) - API endpoints

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

## Related Documentation

- [Features Index](../FEATURES.md) - Overview of all features
- [API Flows](../API_FLOWS.md) - Detailed API documentation
- [OAuth Flow Examples](../example-query-responses/oauth-flow.md) - Real OAuth API responses
- [Managed Users](../example-query-responses/managed_users.md) - Managed user account examples
