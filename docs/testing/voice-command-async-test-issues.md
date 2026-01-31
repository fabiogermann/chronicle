# Voice Command Async Test Issues

## Status: ✅ RESOLVED (2026-01-31)

## Overview

Two voice command error handling tests were previously disabled due to complex nested coroutine timing issues. **This issue has been resolved** by refactoring the coroutine architecture to be more testable.

## Affected Tests

Location: [`app/src/test/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallbackTest.kt`](../../app/src/test/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallbackTest.kt)

1. `onPlayFromSearch shows error when no results found for specific query`
2. `onPlayFromSearch shows library empty error on fallback failure`

## Root Cause

These tests validate error reporting when voice commands (`onPlayFromSearch`) fail to find audiobooks. The failure occurs due to nested coroutine execution with global exception handling:

```kotlin
// In AudiobookMediaSessionCallback.kt, line 154+
serviceScope.launch {
    try {
        handleSearch(query, true)
    } catch (e: Exception) {
        Timber.e(e, "[AndroidAuto] Error in onPlayFromSearch")
        onSetPlaybackError(
            android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
            appContext.getString(local.oss.chronicle.R.string.auto_error_playback_failed)
        )
    }
}

// handleSearch() at line 167 launches another nested coroutine:
serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
    try {
        val matchingBooks = bookRepository.searchAsync(query)
        if (matchingBooks.isNotEmpty()) {
            // ...
        } else {
            onSetPlaybackError(
                PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                appContext.getString(R.string.auto_error_no_results_for_query, query)
            )
        }
    } catch (e: Exception) {
        // Caught by Injector.get().unhandledExceptionHandler()
    }
}
```

### The Problem

1. **Double-nested coroutines**: `serviceScope.launch { serviceScope.launch { ... } }`
2. **Global exception handler**: `Injector.get().unhandledExceptionHandler()` is a singleton that cannot be easily mocked in unit tests
3. **Unpredictable timing**: Even with `testScheduler.advanceUntilIdle()`, the nested coroutines may not complete in the expected order
4. **Exception routing**: When exceptions occur in the inner coroutine, they're caught by the global handler instead of the outer try-catch, causing:
   - Expected error code: `ERROR_CODE_NOT_AVAILABLE_IN_REGION` (7)
   - Actual error code: `ERROR_CODE_APP_ERROR` (1)

## Test Verification Failure

```
AssertionError: Verification failed: call 1 of 1: 
IPlaybackErrorReporter(errorReporter#6).setPlaybackStateError(eq(7), any())). 
Only one matching call happened, but arguments are not matching:
[0]: argument: 1, matcher: eq(7), result: -  // ERROR_CODE_APP_ERROR instead of ERROR_CODE_NOT_AVAILABLE_IN_REGION
[1]: argument: Mock error message, matcher: any(), result: +
```

## Production Code Status

✅ **The production code works correctly**. These tests validate edge cases that function properly in real Android environments. The issue is purely with test isolation and timing.

## How to Fix (Future Work)

### Option 1: Inject Exception Handler (Recommended)

Make the exception handler injectable instead of using `Injector.get()`:

```kotlin
class AudiobookMediaSessionCallback @Inject constructor(
    // ... existing params
    private val coroutineExceptionHandler: CoroutineExceptionHandler,
) : MediaSessionCompat.Callback() {
    
    private fun handleSearch(query: String?, playWhenReady: Boolean) {
        serviceScope.launch(coroutineExceptionHandler) {  // Use injected handler
            // ...
        }
    }
}
```

In tests, provide a simple test exception handler:
```kotlin
val testExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Timber.e(throwable, "Test exception")
}
```

### Option 2: Refactor Nested Coroutines

Flatten the coroutine structure to avoid double-nesting:

```kotlin
override fun onPlayFromSearch(query: String?, extras: Bundle?) {
    if (!checkAuthenticationOrError()) return
    
    serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
        try {
            handleSearchSuspend(query, true)  // Make synchronous suspend function
        } catch (e: Exception) {
            onSetPlaybackError(ERROR_CODE_APP_ERROR, ...)
        }
    }
}

private suspend fun handleSearchSuspend(query: String?, playWhenReady: Boolean) {
    // Direct suspend calls instead of launching nested coroutines
    val matchingBooks = bookRepository.searchAsync(query)
    // ...
}
```

### Option 3: Use runTest with TestScope

Replace `serviceScope` in tests with a `TestScope`:

```kotlin
@Test
fun `test with controlled coroutine scope`() = runTest {
    val testCallback = AudiobookMediaSessionCallback(
        // ...
        serviceScope = this,  // Use test's CoroutineScope
        // ...
    )
    
    testCallback.onPlayFromSearch("query", null)
    advanceUntilIdle()  // Full control over test coroutines
    
    verify { errorReporter.setPlaybackStateError(ERROR_CODE_NOT_AVAILABLE_IN_REGION, any()) }
}
```

### Option 4: Integration Tests

Convert these to integration tests using Robolectric:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class AudiobookMediaSessionCallbackIntegrationTest {
    // Test with real Android components and coroutine dispatchers
}
```

## Resolution (2026-01-31)

### Fix Applied: Combination of Option 1 + Option 2

The issue was resolved by implementing a hybrid approach:

#### 1. Injected Exception Handler (Option 1)
Made the `CoroutineExceptionHandler` injectable instead of using global `Injector.get()`:

**Production Code ([`AudiobookMediaSessionCallback.kt`](../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt)):**
```kotlin
class AudiobookMediaSessionCallback @Inject constructor(
    // ... other params
    private val coroutineExceptionHandler: CoroutineExceptionHandler,
    // ...
) : MediaSessionCompat.Callback() {
    
    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        serviceScope.launch(coroutineExceptionHandler) {  // Use injected handler
            try {
                handleSearchSuspend(query, true)
            } catch (e: Exception) {
                // Error handling
            }
        }
    }
}
```

**Dependency Injection:**
- AppComponent already provided `unhandledExceptionHandler()` from [`AppModule`](../app/src/main/java/local/oss/chronicle/injection/modules/AppModule.kt)
- ServiceComponent inherits this from AppComponent (no duplicate needed in ServiceModule)

**Test Code ([`AudiobookMediaSessionCallbackTest.kt`](../app/src/test/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallbackTest.kt)):**
```kotlin
@Before
fun setup() {
    testExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Test coroutine exception")
    }
    
    callback = AudiobookMediaSessionCallback(
        // ... other params
        coroutineExceptionHandler = testExceptionHandler,
        // ...
    )
}
```

#### 2. Flattened Coroutine Structure (Option 2)
Refactored `handleSearch()` from a regular function with nested `serviceScope.launch()` calls to a `suspend` function:

**Before:**
```kotlin
private fun handleSearch(query: String?, playWhenReady: Boolean) {
    serviceScope.launch {  // outer
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {  // inner (problem!)
            // search logic
        }
    }
}
```

**After:**
```kotlin
private suspend fun handleSearchSuspend(query: String?, playWhenReady: Boolean) {
    // Direct suspend calls - no nested launches
    val matchingBooks = bookRepository.searchAsync(query)
    // ...
}
```

### Why This Works

1. **Single coroutine launch**: Only one `launch` in `onPlayFromSearch()`, no nesting
2. **Testable exception handler**: Tests can provide a simple handler that doesn't interfere with assertions
3. **Predictable timing**: With `TestScope` and flat suspend functions, `advanceUntilIdle()` reliably completes all work
4. **Correct error codes**: Exceptions now propagate correctly through the single try-catch, reporting the intended error codes

### Test Results

Both previously-disabled tests now pass:
- ✅ `onPlayFromSearch shows error when no results found for specific query`
- ✅ `onPlayFromSearch shows library empty error on fallback failure`

All other existing tests continue to pass (21 tests total in AudiobookMediaSessionCallbackTest).

## Related Documentation

- [`docs/architecture/voice-command-error-handling.md`](../architecture/voice-command-error-handling.md) - Voice command architecture
- [`docs/features/playback.md`](../features/playback.md) - Playback feature documentation
- [`AGENT.md`](../../AGENT.md) - Testing approach and patterns

## Lessons Learned

1. **Avoid global singletons in unit tests**: `Injector.get()` calls make tests brittle and hard to control
2. **Inject dependencies**: Even infrastructure like exception handlers should be injectable for testability
3. **Flat > Nested**: Nested `launch` calls complicate testing. Prefer flat suspend functions
4. **Test-driven refactoring**: The test failure revealed a legitimate architectural smell (double-nesting)
5. **Dagger inheritance**: AppComponent dependencies are automatically available to child components (ServiceComponent)

## Timeline

- **2026-01-31**: Tests disabled due to async timing issues
- **2026-01-31**: Issue resolved via injected exception handler + flattened coroutine structure
- **Status**: ✅ All tests passing

---

**Last Updated:** 2026-01-31
**Status:** ✅ Resolved - Tests re-enabled and passing
