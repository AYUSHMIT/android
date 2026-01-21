# PR: Fix concurrent token refresh race conditions

## Title
Fix: Add mutex protection to prevent concurrent token refresh race conditions

## Description

### Problem

Multiple components can trigger token refresh simultaneously (WebSocket authentication, API calls, scheduled refreshes), especially during network transitions (WiFi ↔ cellular, VPN connect/disconnect). Without serialization, this causes:

- **Race conditions**: Multiple concurrent HTTP refresh requests to auth server
- **Inconsistent session state**: Last-writer-wins on session update
- **Auth failures**: Conflicting token updates cause authorization errors

This affects users during network transitions and multi-tab scenarios (Android Auto + main app).

### Solution

Add `Mutex` to serialize token refresh operations per server instance:

```kotlin
private val refreshMutex = Mutex()

private suspend fun refreshSessionWithToken(...) {
    refreshMutex.withLock {
        // Existing refresh logic
    }
}
```

### Changes

**Modified files:**
- `common/src/main/kotlin/io/homeassistant/companion/android/common/data/authentication/impl/AuthenticationRepositoryImpl.kt`
  - Add 2 imports: `kotlinx.coroutines.sync.Mutex`, `kotlinx.coroutines.sync.withLock`
  - Add `refreshMutex` property with documentation (4 lines)
  - Wrap `refreshSessionWithToken` body with `mutex.withLock` (2 lines)
  - **Total: 9 lines changed**

**New files:**
- `common/src/test/kotlin/io/homeassistant/companion/android/common/data/authentication/impl/AuthenticationRepositoryImplTest.kt`
  - 3 deterministic tests using `kotlinx-coroutines-test`
  - Uses barriers (`CompletableDeferred`) instead of sleeps
  - Tests: concurrent serialization, exception handling, cancellation safety

### Why This Fix is Minimal

- **No behavior changes**: Same refresh logic, just serialized
- **No refactoring**: Only adds mutex wrapper around existing code
- **Per-server scope**: Each `AuthenticationRepositoryImpl` instance (one per server via `@Assisted` injection) has its own mutex
- **Coroutine-native**: Uses Kotlin's `Mutex.withLock` which is cancellation-safe

### Testing

Added 3 comprehensive unit tests:

1. **Concurrent refresh serialization**: Verifies multiple concurrent calls result in single HTTP request
2. **Exception handling**: Confirms mutex releases on failure, allowing subsequent attempts
3. **Cancellation safety**: Ensures cancelled refresh doesn't deadlock mutex

All tests use `kotlinx-coroutines-test` with barriers for deterministic execution (no `delay()` or `sleep()`).

### Impact

- **Risk**: Very Low - Purely additive, no logic changes
- **Performance**: Negligible - Only affects contention case (already problematic)
- **User Experience**: Positive - Eliminates auth failures during network transitions

### Review Notes

- ✅ No database schema changes
- ✅ No API changes
- ✅ No UI changes
- ✅ Backward compatible
- ✅ No new dependencies (uses existing Kotlin coroutines)
- ✅ Thread-safe (per-instance mutex)
- ✅ Follows project code style

### Related

- Addresses Weird State #2: "Token Refresh During Network Transition"
- Enforces Invariant: "At most one token refresh in flight per server"
- Part of comprehensive weird states analysis (see analysis documents)

## Checklist

- [x] New or updated tests have been added to cover the changes following the testing [guidelines](https://developers.home-assistant.io/docs/android/testing/introduction)
- [x] The code follows the project's [code style](https://developers.home-assistant.io/docs/android/codestyle) and [best practices](https://developers.home-assistant.io/docs/android/best_practices)
- [x] The changes have been thoroughly tested, and edge cases have been considered
- [x] Changes are backward compatible whenever feasible. Any breaking changes are documented in the changelog for users and/or in the code for developers depending on the relevance

## Testing Instructions

```bash
# Run new tests
./gradlew :common:test --tests AuthenticationRepositoryImplTest

# Verify no regressions
./gradlew :common:test
```

## Screenshots

N/A - No UI changes (internal concurrency control)
