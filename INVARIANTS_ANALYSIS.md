# Violated Invariants Analysis
## Home Assistant Companion for Android

**Analysis Date**: January 20, 2026  
**Purpose**: Extract minimal set of violated invariants from the 7 documented weird states

---

## Methodology

This document analyzes the 7 weird states documented in `WEIRD_STATES_ANALYSIS.md` to identify the core set of violated invariants. Each invariant is:

1. **Stated formally** - As a clear, testable assertion
2. **Mapped to violating states** - Which weird states break this invariant
3. **Enforcement points identified** - Where in code this should be enforced but isn't
4. **Severity assessed** - Based on user impact and frequency

---

## Minimal Invariant Set

After analyzing all 7 weird states, we identified **6 core invariants** that, when violated, create the observed weird states. Some weird states violate multiple invariants.

### Invariant 1: Concurrency Control

**Formal Statement**: *"At most one token refresh operation in flight per server at any given time"*

**Violating States**:
- **Weird State #2**: Token Refresh During Network Transition

**Missing Enforcement Point**:
- `common/src/main/kotlin/io/homeassistant/companion/android/common/data/authentication/impl/AuthenticationRepositoryImpl.kt:133-162`
- Method: `refreshSessionWithToken(baseUrl: HttpUrl, refreshToken: String)`
- **Missing**: No `Mutex` or atomic flag to prevent concurrent invocations
- **Issue**: Multiple components (WebSocket auth, API calls, scheduled refreshes) can trigger refresh simultaneously

**Current State**:
```kotlin
private suspend fun refreshSessionWithToken(baseUrl: HttpUrl, refreshToken: String) {
    val server = server()
    return authenticationService.refreshToken(
        // No mutex protection here
        baseUrl.newBuilder().addPathSegments(SEGMENT_AUTH_TOKEN).build(),
        AuthenticationService.GRANT_TYPE_REFRESH,
        refreshToken,
        AuthenticationService.CLIENT_ID,
    ).let {
        // Token update logic
    }
}
```

**Severity**: **CRITICAL**
- Frequency: Moderate (happens during network transitions, multi-tab scenarios)
- User Impact: Auth failures, forced logouts, inconsistent session state
- Exploitability: Medium (can be triggered by timing network changes)

---

### Invariant 2: State Consistency Across Reconnections

**Formal Statement**: *"All active subscriptions remain valid and receive events continuously across WebSocket reconnections"*

**Violating States**:
- **Weird State #3**: WebSocket Reconnection with Stale Subscriptions

**Missing Enforcement Point**:
- `common/src/main/kotlin/io/homeassistant/companion/android/common/data/websocket/impl/WebSocketCoreImpl.kt:746-788`
- Method: `createSubscriptionFlow<T>(subscribeMessage: Map<String, Any?>, timeout: Duration): Flow<T>?`
- **Missing**: No re-subscription mechanism after reconnect
- **Issue**: Code comments claim automatic re-subscription exists, but implementation is absent

**Current State**:
```kotlin
// From WebSocketCoreImpl.kt comments (lines 117-119):
// "Upon reconnection, the implementation resubscribes to all active subscriptions to
//  ensure continuity."

// REALITY: No implementation of this exists
// activeMessages ConcurrentHashMap retains old subscription IDs after reconnect
// Server doesn't know about these IDs from the new connection
```

**Where Re-subscription Should Happen**:
- `WebSocketCoreImpl.kt:296-364` - In the `connect()` method after authentication succeeds
- OR in `handleAuthComplete()` after line 801 where `connectionState = WebSocketState.Active` is set
- Should iterate through `activeMessages`, filter for `ActiveMessage.Subscription` entries, and re-send subscription requests

**Severity**: **CRITICAL**
- Frequency: Common (happens on every WebSocket disconnect/reconnect)
- User Impact: Silent event loss, real-time updates stop working, must restart app
- Exploitability: Low (mostly brittleness, could be DoS'd by forcing reconnections)

---

### Invariant 3: Lifecycle Synchronization

**Formal Statement**: *"UI state and WebSocket connection state remain synchronized across Activity lifecycle transitions"*

**Violating States**:
- **Weird State #1**: WebSocket Connection Outlives Activity Lifecycle
- **Weird State #5**: Activity Recreation During WebSocket Authentication

**Missing Enforcement Point #1** (State #1):
- `app/src/main/kotlin/io/homeassistant/companion/android/webview/WebViewActivity.kt:onResume()`
- **Missing**: No "refresh-on-resume" pattern to sync UI with background WebSocket state
- **Issue**: When Activity resumes, it doesn't fetch latest state from WebSocket that stayed alive

**Missing Enforcement Point #2** (State #5):
- `common/src/main/kotlin/io/homeassistant/companion/android/common/data/websocket/impl/WebSocketCoreImpl.kt:313-326`
- **Partial**: Has mutex for connection deduplication, but no Activity instance tracking
- **Issue**: Global connection state doesn't know which Activity instance is current

**Current State**:
```kotlin
// WebSocketCoreImpl.kt has deduplication:
connectedMutex.withLock {
    // Already connected?
    if (connectionHolder.get() != null && authCompleted.isCompleted) {
        return !authCompleted.isCancelled
    }
    // Connection already in progress? Reuse its deferred
    pendingConnectDeferred?.takeIf { !it.isCompleted }?.let { existing ->
        connectDeferred = existing
        return@withLock
    }
}

// BUT: WebViewActivity doesn't refresh UI on resume
// No mechanism to invalidate and reload stale UI state
```

**Severity**: **HIGH**
- Frequency: Common (happens on screen rotation, background/foreground transitions)
- User Impact: Stale UI, duplicate connections, missed state updates
- Exploitability: Low (mostly UX issue, some race conditions)

---

### Invariant 4: Resource Reachability Guarantees

**Formal Statement**: *"Network connectivity constraints guarantee that the target server is actually reachable"*

**Violating States**:
- **Weird State #4**: WorkManager Constraints vs Actual Network State

**Missing Enforcement Point**:
- `app/src/main/kotlin/io/homeassistant/companion/android/websocket/WebsocketManager.kt:64-91`
- **Issue**: WorkManager constraint `NetworkType.CONNECTED` only checks for ANY network, not actual server reachability

**Current State**:
```kotlin
// WebsocketManager.kt:64-67
val websocketNotifications =
    PeriodicWorkRequestBuilder<WebsocketManager>(15, TimeUnit.MINUTES)
        .build()

// SensorWorker.kt:23-24
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED).build()

// NetworkType.CONNECTED means:
// - WiFi OR cellular connected
// - Does NOT mean internet access
// - Does NOT mean server is reachable
// - Does NOT detect captive portals
```

**Where Additional Checks Needed**:
- `WebsocketManager.kt:138-156` - The `shouldWeRun()` and `shouldRunForServer()` methods
- **Partial mitigation exists** with `hasActiveConnection()`, but needs enhancement:
  - Add captive portal detection
  - Add circuit breaker pattern to prevent rapid retries
  - Add exponential backoff
  - Add actual server ping/health check before starting work

**Severity**: **MEDIUM**
- Frequency: Common (happens with captive portals, VPN issues, DNS failures)
- User Impact: Battery drain, notification shows "listening" but not actually connected
- Exploitability: Low (could cause battery drain)

---

### Invariant 5: Independent Server State

**Formal Statement**: *"Each configured server's connection state is isolated and independently manageable"*

**Violating States**:
- **Weird State #6**: Multi-Server Scenarios with Shared WebSocket Manager

**Missing Enforcement Point**:
- `app/src/main/kotlin/io/homeassistant/companion/android/websocket/WebsocketManager.kt:158-180`
- **Issue**: Single WebsocketManager worker manages all servers with single notification

**Current State**:
```kotlin
// WebsocketManager.kt:158-180
private suspend fun manageServerJobs(jobs: MutableMap<Int, Job>, coroutineScope: CoroutineScope): Boolean {
    val servers = serverManager.servers()
    
    // Clean up...
    jobs.filter { (serverId, _) ->
        servers.none { it.id == serverId } || !shouldRunForServer(serverId)
    }.forEach { (serverId, job) ->
        job.cancel()
        jobs.remove(serverId)
    }
    // All servers managed by single worker
    // Single notification for all servers
    // No per-server status indication
}
```

**Where Per-Server State Should Be Exposed**:
- `WebsocketManager.kt:229-306` - The `createNotification()` method
- **Missing**: Per-server connection status in notification
- **Missing**: Separate notifications or expandable notification with per-server status
- **Missing**: UI indication of which server is failing

**Severity**: **MEDIUM**
- Frequency: Rare (only affects multi-server setups)
- User Impact: Unclear which server is failing, difficult to debug
- Exploitability: Low (information disclosure in logs)

---

### Invariant 6: Temporal Execution Guarantees

**Formal Statement**: *"Background work scheduled at interval T executes with temporal accuracy within ±δ where δ << T"*

**Violating States**:
- **Weird State #7**: Sensor Updates During Doze Mode

**Missing Enforcement Point**:
- `app/src/main/kotlin/io/homeassistant/companion/android/sensors/SensorWorker.kt:22-33`
- **Issue**: PeriodicWorkRequest with 15-minute interval doesn't account for Doze mode deferrals

**Current State**:
```kotlin
// SensorWorker.kt:22-29
val sensorWorker =
    PeriodicWorkRequestBuilder<SensorWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()

// During Doze mode:
// - WorkManager defers execution to maintenance windows
// - 15 minute interval becomes 1-6+ hour gaps
// - Multiple updates execute in burst after Doze exits
```

**Where Temporal Guarantees Should Be Enforced**:
- `SensorWorker.kt` - Use `setExpedited()` for critical sensors (Android 12+)
- OR implement foreground service for critical sensors (pattern exists in `HighAccuracyLocationService`)
- Add UI indicator showing last successful sensor update time
- Implement sensor-specific strategies (critical sensors use expedited work)

**Severity**: **HIGH**
- Frequency: Very Common (happens to all users during idle periods)
- User Impact: Stale automation data, automations fail, sensor data gaps in HA
- Exploitability: Low (could hide location by forcing Doze)

---

## Summary Table

| Invariant | Violating States | Missing Enforcement Point | Severity |
|-----------|------------------|---------------------------|----------|
| **1. Concurrency Control**<br/>*"At most one token refresh in flight per server"* | #2: Token Refresh During Network Transition | `AuthenticationRepositoryImpl.kt:133-162`<br/>Missing: Mutex in `refreshSessionWithToken()` | **CRITICAL** |
| **2. State Consistency Across Reconnections**<br/>*"Active subscriptions remain valid across reconnections"* | #3: Stale Subscriptions After Reconnect | `WebSocketCoreImpl.kt:746-788`<br/>Missing: Re-subscription logic in `connect()` | **CRITICAL** |
| **3. Lifecycle Synchronization**<br/>*"UI and WebSocket state synchronized across Activity transitions"* | #1: WebSocket Outlives Activity<br/>#5: Activity Recreation During Auth | `WebViewActivity.kt:onResume()`<br/>Missing: Refresh-on-resume pattern | **HIGH** |
| **4. Resource Reachability Guarantees**<br/>*"Network constraints guarantee server reachability"* | #4: WorkManager vs Actual Network | `WebsocketManager.kt:64-91`<br/>Missing: Captive portal detection, circuit breaker | **MEDIUM** |
| **5. Independent Server State**<br/>*"Each server's state is isolated and manageable"* | #6: Multi-Server Shared Manager | `WebsocketManager.kt:229-306`<br/>Missing: Per-server status in notification | **MEDIUM** |
| **6. Temporal Execution Guarantees**<br/>*"Background work executes at scheduled intervals"* | #7: Sensor Updates in Doze Mode | `SensorWorker.kt:22-33`<br/>Missing: Doze-aware scheduling with expedited work | **HIGH** |

---

## Invariant Dependencies

Some invariants are interdependent:

- **Invariant 1** (Concurrency Control) is a prerequisite for **Invariant 3** (Lifecycle Sync)
  - Must prevent concurrent refreshes before attempting to sync lifecycle state
  
- **Invariant 2** (State Consistency) depends on **Invariant 3** (Lifecycle Sync)
  - Activity must know connection state to properly handle subscription state
  
- **Invariant 4** (Reachability) is independent but affects all others
  - Network issues cascade to cause token refresh failures, subscription losses, etc.

---

## Enforcement Priority

Based on severity and dependencies:

### Priority 1 (Fix Immediately):
1. **Invariant 1**: Add token refresh mutex (`AuthenticationRepositoryImpl.kt:133`)
   ```kotlin
   private val refreshMutex = Mutex()
   
   private suspend fun refreshSessionWithToken(baseUrl: HttpUrl, refreshToken: String) {
       refreshMutex.withLock {
           // existing refresh logic
       }
   }
   ```

2. **Invariant 2**: Implement subscription re-establishment (`WebSocketCoreImpl.kt:801+`)
   ```kotlin
   private suspend fun handleAuthComplete(successful: Boolean, haVersion: String?) {
       val parsedVersion = haVersion?.let { HomeAssistantVersion.fromString(it) }
       if (successful) {
           connectionState = WebSocketState.Active
           authCompleted.complete(parsedVersion)
           resubscribeAll() // ADD THIS
       } else {
           // ...
       }
   }
   
   private suspend fun resubscribeAll() {
       activeMessages.entries
           .filter { it.value is ActiveMessage.Subscription }
           .forEach { (_, activeMessage) ->
               val subscription = activeMessage as ActiveMessage.Subscription
               sendMessage(subscription.request)
           }
   }
   ```

### Priority 2 (Fix Within 1 Month):
3. **Invariant 3**: Add refresh-on-resume (`WebViewActivity.kt:onResume()`)
4. **Invariant 6**: Use expedited work for sensors (`SensorWorker.kt:22-29`)

### Priority 3 (Architectural Improvements):
5. **Invariant 4**: Add circuit breaker and reachability checks
6. **Invariant 5**: Add per-server status UI

---

## Testing Recommendations

For each invariant, specific tests are needed:

### Invariant 1 (Concurrency):
```kotlin
@Test
fun `concurrent token refresh attempts are serialized`() = runTest {
    // Launch multiple refresh attempts simultaneously
    val jobs = (1..10).map {
        launch { repository.ensureValidSession(forceRefresh = true) }
    }
    jobs.joinAll()
    
    // Verify: Only one actual HTTP request made
    verify(exactly = 1) { authService.refreshToken(any(), any(), any(), any()) }
}
```

### Invariant 2 (Subscription Consistency):
```kotlin
@Test
fun `subscriptions are re-established after reconnect`() = runTest {
    // Subscribe to events
    val flow = webSocket.getStateChanges()
    
    // Simulate disconnect/reconnect
    webSocket.disconnect()
    webSocket.connect()
    
    // Verify: Subscription request sent again
    // Events continue flowing after reconnect
}
```

### Invariant 3 (Lifecycle Sync):
```kotlin
@Test
fun `activity recreation during auth preserves connection`() {
    // Start auth
    scenario.onActivity { activity ->
        activity.startWebSocketConnection()
    }
    
    // Recreate activity (simulate rotation)
    scenario.recreate()
    
    // Verify: Only one connection exists, no duplicates
    verify(exactly = 1) { webSocket.connect() }
}
```

---

## Conclusion

The 7 weird states ultimately stem from **6 core invariant violations**. Fixing these invariants will resolve multiple weird states simultaneously:

- Fixing **Invariant 1** resolves State #2
- Fixing **Invariant 2** resolves State #3  
- Fixing **Invariant 3** resolves States #1 and #5
- Fixing **Invariant 4** resolves State #4
- Fixing **Invariant 5** resolves State #6
- Fixing **Invariant 6** resolves State #7

**Most Critical**: Invariants 1 and 2 should be fixed immediately as they cause silent data loss and auth failures.

**Most Common**: Invariant 6 (Doze mode) affects 100% of users during idle periods.

**Most Complex**: Invariant 3 (Lifecycle sync) requires architectural changes across Activity, ViewModel, and Repository layers.
