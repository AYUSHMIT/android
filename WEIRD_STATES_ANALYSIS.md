# Weird States and Semantic Gaps Analysis
## Home Assistant Companion for Android

**Analysis Date**: January 20, 2026
**Codebase**: Home Assistant Android App
**Focus Areas**: Android lifecycle mismatches, Network/WebSocket state, Authentication, Background tasks

---

## Executive Summary

This document identifies 7 concrete "weird states" in the Home Assistant Android app where the application can enter states not anticipated by the intended design model. These states arise from complex interactions between:
- Android lifecycle management (Activities, Services, WorkManager)
- WebSocket connection state management
- Authentication token lifecycle
- Background task suspension and system constraints

Each weird state is analyzed for:
1. **What invariant breaks** - The assumption that is violated
2. **User visibility / exploitability** - Whether the state is user-visible, could be exploited, or is just brittle
3. **Current mitigation** - How (if at all) the codebase currently handles it

---

## Weird State #1: WebSocket Connection Outlives Activity Lifecycle

### Location
- `WebSocketCoreImpl.kt` (lines 136-147)
- `WebsocketManager.kt` (Worker-based connection management)
- `WebViewActivity.kt` (lifecycle methods)

### Description
The WebSocket connection is managed by a `CoroutineWorker` (WebsocketManager) that runs every 15 minutes as a periodic work request, independent of any Activity lifecycle. This creates a state where:

- The WebSocket connection persists in the background via WorkManager
- The WebViewActivity may be destroyed (onStop/onDestroy called)
- Incoming WebSocket events (notifications, state changes) are still being received
- The foreground UI that might display these events no longer exists

### Invariant That Breaks
**"WebSocket event handlers have a valid UI context to update"**

When the WebViewActivity is destroyed but WebsocketManager continues running, incoming events may attempt to update UI state that no longer exists. The app maintains multiple parallel states:
- WorkManager keeps WebSocket alive (up to 15 min intervals)
- Activity lifecycle may complete (user navigates away)
- Event subscriptions in `WebSocketCore` continue emitting to Flows

### User Visibility / Exploitability

**User Visible**: YES
- Notifications may arrive but UI doesn't update
- State changes occur without visual feedback
- When user returns to app, they see stale state until manual refresh

**Exploitable**: LOW
- Not a security issue, but creates UX confusion
- Could cause missed notifications or delayed state updates

**Brittle**: HIGH
- Race conditions between Activity lifecycle and background workers
- Multiple sources of truth for connection state

### Current Mitigation

**Partial mitigation exists**:

1. **WorkManager handles background persistence** (`WebsocketManager.kt:111-136`)
   ```kotlin
   override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
       if (!shouldWeRun()) {
           return@withContext Result.success()
       }
       // Maintains connection independent of Activity
   ```

2. **Notification-based updates** (`WebsocketManager.kt:182-222`)
   - Events are converted to system notifications via `MessagingManager`
   - This provides updates even when Activity is destroyed

3. **Flow-based architecture** with lifecycle-aware collection
   - ViewModels use `lifecycleScope.repeatOnLifecycle()` for safe collection
   - But this doesn't prevent background workers from processing events

**Gaps**:
- No explicit synchronization between WorkManager state and Activity state
- WebView may show stale data when resumed
- No clear "reconnect and refresh" pattern when Activity is recreated

---

## Weird State #2: Token Refresh During Network Transition

### Location
- `AuthenticationRepositoryImpl.kt:120-162`
- `WebSocketCoreImpl.kt:444-452` (auth message sending)
- Network state changes during token refresh

### Description
When a token refresh is in progress and the network changes (WiFi to cellular, or connection loss), the app enters a state where:

- `refreshSessionWithToken()` is executing (suspended coroutine)
- Network changes mid-request
- The HTTP refresh request fails or times out
- WebSocket connection may also be attempting to authenticate with the now-expired token
- The session state becomes inconsistent

### Invariant That Breaks
**"Authentication state is always consistent with network connectivity"**

The code assumes that:
1. Token refresh completes atomically
2. Network state remains stable during refresh
3. Only one token refresh happens at a time

But in reality:
- Network can change during the refresh HTTP call
- Multiple components may trigger refresh simultaneously (WebSocket auth + API call)
- System time changes can affect token expiration checks

### User Visibility / Exploitability

**User Visible**: YES
- User sees auth failures
- May be kicked out to login screen unexpectedly
- UI shows "connection error" but unclear if it's network or auth

**Exploitable**: MEDIUM
- Could be used to force logout by timing network changes
- Potential for race conditions in multi-server scenarios

**Brittle**: VERY HIGH
- Complex race conditions
- System time changes (timezone, clock adjustments) can trigger edge cases

### Current Mitigation

**Partial mitigation**:

1. **AuthorizationException handling** (`AuthenticationRepositoryImpl.kt:160`)
   ```kotlin
   throw AuthorizationException("Failed to refresh token", it.code(), it.errorBody())
   ```
   - Throws exception on failure
   - Forces re-authentication

2. **Invalid grant revocation** (`AuthenticationRepositoryImpl.kt:155-158`)
   ```kotlin
   if (it.code() == 400 && it.errorBody()?.string()?.contains("invalid_grant") == true) {
       revokeSession()
   }
   ```
   - Automatically revokes session on 400 invalid_grant

3. **Session validation before refresh** (`AuthenticationRepositoryImpl.kt:120-131`)
   - Checks `isComplete()` and `installId` before refreshing

**Gaps**:
- No retry mechanism with backoff
- No detection of in-flight refresh requests (could have multiple simultaneous)
- Network state not explicitly checked before/during refresh
- System time issues not detected (line 148 uses `System.currentTimeMillis()` directly instead of `Clock`)

---

## Weird State #3: WebSocket Reconnection with Stale Subscriptions

### Location
- `WebSocketCoreImpl.kt:100-147` (connection lifecycle)
- `WebSocketCoreImpl.kt:746-788` (subscription management)
- Reconnection logic and subscription re-establishment

### Description
When the WebSocket connection drops and reconnects, the app enters a state where:

- Old subscriptions exist in `activeMessages` ConcurrentHashMap
- Connection is re-established with new WebSocket instance
- Old subscription IDs from previous connection are now invalid
- Server doesn't know about these subscriptions
- Events stop flowing but the UI thinks it's still subscribed

The code comment explicitly mentions this gap:
```kotlin
// #### Reconnection and re-subscription:
// - On failure or when the socket is closing, if there are active subscriptions created
//   with [subscribeTo], the implementation will automatically retry to open the connection
//   until it succeeds.
// - Upon reconnection, the implementation resubscribes to all active subscriptions to
//   ensure continuity.
```

However, the re-subscription logic is incomplete.

### Invariant That Breaks
**"All active subscriptions receive events continuously across reconnections"**

When WebSocket reconnects:
- New socket instance created
- Old subscription IDs (from `activeMessages`) reference the old connection
- Server state is reset (doesn't have old subscription IDs)
- Client believes subscriptions are active, server disagrees

### User Visibility / Exploitability

**User Visible**: YES
- Real-time updates stop working
- Entity states don't update
- Assist pipeline events may be lost
- User must manually refresh or restart app

**Exploitable**: LOW
- Could be DoS'd by forcing reconnections
- Event data could be lost during critical operations (e.g., Matter commissioning)

**Brittle**: VERY HIGH
- Complex state machine with many edge cases
- Difficult to reproduce reliably
- Silent failure mode (no error shown)

### Current Mitigation

**Limited mitigation**:

1. **Connection state tracking** (`WebSocketCoreImpl.kt:175`)
   ```kotlin
   private var connectionState: WebSocketState = WebSocketState.Initial
   ```
   - Tracks connection state

2. **Stale connection detection** (`WebSocketCoreImpl.kt:718-721`)
   ```kotlin
   if (isStaleConnection(webSocket)) {
       Timber.w("Ignoring onClosing from stale connection")
       return
   }
   ```
   - Prevents handling events from old connections

3. **Subscription Flow with SharedFlow** (`WebSocketCoreImpl.kt:772`)
   ```kotlin
   .shareIn(backgroundScope, SharingStarted.WhileSubscribed(timeout.inWholeMilliseconds, 0))
   ```
   - Uses SharedFlow for broadcast to multiple collectors

**Critical Gaps**:
- **NO automatic re-subscription after reconnect**
  - The code comments claim this happens, but no implementation is visible
  - Subscriptions are created in `createSubscriptionFlow()` but never recreated on reconnect
  
- **No subscription state reconciliation**
  - When reconnecting, old `activeMessages` entries are not cleared
  - New subscriptions won't be created because code thinks they already exist
  
- **No subscription health check**
  - No ping/pong or heartbeat to verify subscriptions are still alive
  - Server-side subscription timeout could silently stop events

---

## Weird State #4: WorkManager Constraints vs Actual Network State

### Location
- `WebsocketManager.kt:64-91` (periodic work setup)
- `WebsocketManager.kt:138-156` (shouldWeRun checks)
- `SensorWorker.kt:22-33` (network constraints)

### Description
The background workers (WebsocketManager, SensorWorker) use WorkManager with network constraints:

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED).build()
```

However, there's a gap between WorkManager's network state and the actual WebSocket connectivity:

- WorkManager says "network connected" (any connection)
- WebSocket needs specific URL reachability (internal vs external)
- Network could be "connected" but captive portal blocks access
- WiFi connected but no internet access

This creates states where:
- WorkManager starts the worker (network constraint met)
- Worker tries to connect WebSocket
- Connection fails (unreachable URL, captive portal, etc.)
- Worker stays running, continuously retrying
- Battery drain with no user benefit

### Invariant That Breaks
**"Network connectivity constraint guarantees WebSocket reachability"**

WorkManager's `NetworkType.CONNECTED` only checks for ANY network connection, not:
- Internet access
- Specific server reachability
- VPN requirements
- Certificate validity

### User Visibility / Exploitability

**User Visible**: SOMEWHAT
- Battery drain from failed retry attempts
- Notification may persist showing "listening" when not actually connected
- Users on metered connections waste data

**Exploitable**: LOW
- Could trigger battery drain by putting device on captive portal WiFi

**Brittle**: HIGH
- Many network edge cases
- Difficult to test all scenarios

### Current Mitigation

**Partial mitigation**:

1. **Custom shouldWeRun checks** (`WebsocketManager.kt:138-156`)
   ```kotlin
   private suspend fun shouldWeRun(): Boolean = serverManager.servers().any { shouldRunForServer(it.id) }
   
   private suspend fun shouldRunForServer(serverId: Int): Boolean {
       val setting = settingsDao.get(serverId)?.websocketSetting ?: DEFAULT_WEBSOCKET_SETTING
       val isHome = serverManager.connectionStateProvider(serverId).isInternal(requiresUrl = false)
       val powerManager = applicationContext.getSystemService<PowerManager>()!!
       val displayOff = !powerManager.isInteractive
       
       return when {
           (setting == WebsocketSetting.NEVER) -> false
           (!applicationContext.hasActiveConnection()) -> false
           !serverManager.isRegistered() -> false
           (displayOff && setting == WebsocketSetting.SCREEN_ON) -> false
           (!isHome && setting == WebsocketSetting.HOME_WIFI) -> false
           else -> true
       }
   }
   ```
   - Additional checks beyond WorkManager constraints
   - Checks for registration, settings, home WiFi

2. **Connection retry logic** with ping/pong (`WebsocketManager.kt:126-128`)
   ```kotlin
   do {
       delay(30000)
   } while (jobs.values.any { it.isActive } && isActive && shouldWeRun() && manageServerJobs(jobs, this))
   ```
   - Periodically re-evaluates conditions
   - Can stop worker if conditions no longer met

**Gaps**:
- No exponential backoff for failed connections
- No detection of captive portals
- No circuit breaker pattern to prevent rapid retry attempts
- `hasActiveConnection()` is limited (checks ConnectivityManager, not actual reachability)

---

## Weird State #5: Activity Recreation During WebSocket Authentication

### Location
- `WebSocketCoreImpl.kt:296-364` (connect method)
- `WebSocketCoreImpl.kt:479-521` (awaitAuthAndSetupConnectionHolder)
- `WebViewActivity.kt` lifecycle interaction

### Description
When the WebViewActivity triggers a WebSocket connection and authentication begins, the Activity can be destroyed mid-authentication:

1. User opens app → WebViewActivity starts
2. WebSocket `connect()` called → begins authentication handshake
3. User rotates device or system kills Activity for memory
4. Activity destroyed, but WebSocket auth continues in background
5. Auth completes successfully
6. New Activity instance created
7. New Activity doesn't know WebSocket is already connected

This creates duplicate connections or orphaned authentication state.

### Invariant That Breaks
**"Activity lifecycle and WebSocket lifecycle are synchronized"**

The code assumes:
- Single Activity instance manages connection state
- Activity destruction means connection should close
- Activity creation means new connection needed

But actually:
- WebSocket persists across Activity recreations (in WorkManager or ViewModel scope)
- Multiple Activity instances may exist briefly during rotation
- Connection state is global (singleton repositories) but UI state is per-Activity

### User Visibility / Exploitability

**User Visible**: SOMETIMES
- Brief loading states after rotation
- Duplicate data loads
- Inconsistent UI state

**Exploitable**: LOW
- Mostly a UX issue
- Could cause data duplication or race conditions

**Brittle**: HIGH
- Difficult to test reliably
- Depends on timing of rotation vs auth completion

### Current Mitigation

**Some mitigation**:

1. **Connection attempt deduplication** (`WebSocketCoreImpl.kt:313-326`)
   ```kotlin
   connectedMutex.withLock {
       // Already connected?
       if (connectionHolder.get() != null && authCompleted.isCompleted) {
           return !authCompleted.isCancelled
       }
       
       // Connection already in progress? Reuse its deferred
       pendingConnectDeferred?.takeIf { !it.isCompleted }?.let { existing ->
           Timber.d("Connection already in progress, reusing existing deferred")
           connectDeferred = existing
           return@withLock
       }
   ```
   - Mutex prevents duplicate connection attempts
   - Reuses in-flight authentication

2. **Singleton repositories** via Hilt DI
   - WebSocketCore is created per server (singleton scoped)
   - Survives Activity recreation

3. **30-second timeout on auth** (`WebSocketCoreImpl.kt:484-518`)
   ```kotlin
   val result = withTimeoutOrNull(30.seconds) {
       try {
           val haVersion = authCompleted.await()
   ```
   - Prevents indefinite hanging

**Gaps**:
- No explicit Activity instance tracking
- No cleanup of stale Activity references
- UI may not reflect actual connection state during recreations
- `connectionHolder` is global but Activity-specific UI updates may fail

---

## Weird State #6: Multi-Server Scenarios with Shared WebSocket Manager

### Location
- `WebsocketManager.kt:158-180` (manageServerJobs)
- `ServerManager.kt` (multi-server support)
- Per-server authentication and WebSocket state

### Description
The app supports multiple servers (primary, additional), each with its own:
- Authentication state
- WebSocket connection
- Notification subscriptions

However, there's a single WebsocketManager worker that manages all servers:

```kotlin
private suspend fun manageServerJobs(jobs: MutableMap<Int, Job>, coroutineScope: CoroutineScope): Boolean {
    val servers = serverManager.servers()
    
    // Clean up...
    jobs.filter { (serverId, _) ->
        servers.none { it.id == serverId } || !shouldRunForServer(serverId)
    }.forEach { (serverId, job) ->
        job.cancel()
        jobs.remove(serverId)
    }
```

This creates weird states:
- Server A's WebSocket is healthy, Server B is failing
- Manager continues running for A, keeps retrying B
- Notification from B fails, but no user indication which server
- User thinks all connections are working (single notification)

Worse: if ANY server's `shouldRunForServer()` returns true, manager keeps running and retrying ALL servers, even those that should be stopped.

### Invariant That Breaks
**"Each server's connection state is independent and clearly communicated"**

The single worker model assumes:
- All servers have similar connectivity
- Failure of one doesn't impact others
- User doesn't need per-server status

But reality:
- Servers may have different network requirements (VPN, internal only)
- One server failure shouldn't prevent others from working
- User needs to know WHICH server is failing

### User Visibility / Exploitability

**User Visible**: YES
- Single "WebSocket listening" notification doesn't show per-server state
- Failures are logged but not surfaced to user
- User doesn't know which server is causing issues

**Exploitable**: LOW
- Could cause resource waste (retrying dead servers)
- Information disclosure risk (logging exposes server details)

**Brittle**: MEDIUM
- Complex multi-server state management
- Difficult to debug which server is causing issues

### Current Mitigation

**Limited mitigation**:

1. **Per-server job management** (`WebsocketManager.kt:158-180`)
   ```kotlin
   // Start new connections...
   servers.filter { it.id !in jobs.keys && shouldRunForServer(it.id) }
       .forEach {
           jobs[it.id] = coroutineScope.launch { collectNotifications(it.id) }
       }
   ```
   - Separate coroutine job per server
   - Can cancel individual servers

2. **Per-server shouldRunForServer checks**
   - Each server evaluated independently
   - Can stop servers that shouldn't run

**Gaps**:
- Single notification for all servers (no per-server status)
- No per-server failure indication to user
- Logging only (not user-facing)
- No prioritization (all servers treated equally)
- Resource sharing issues (single worker period for all servers)

---

## Weird State #7: Sensor Updates During App Background with Doze Mode

### Location
- `SensorWorker.kt:19-55` (periodic sensor updates)
- `SensorWorkerBase.kt` (base implementation)
- WorkManager constraints vs Doze mode

### Description
The SensorWorker runs every 15 minutes to update sensor data:

```kotlin
val sensorWorker = PeriodicWorkRequestBuilder<SensorWorker>(15, TimeUnit.MINUTES)
    .setConstraints(constraints)
    .build()
```

However, Android Doze mode introduces weird states:

1. App enters Doze mode (screen off, device idle for 1+ hours)
2. WorkManager defers execution during Doze maintenance windows
3. Sensor data becomes stale (15 min → 1+ hour gaps)
4. User expects continuous sensor updates
5. Home Assistant automation fails due to stale data
6. When device exits Doze, multiple sensor updates queued
7. Rapid-fire sensor updates when resuming

This creates:
- **Stale sensor state**: HA thinks device battery is at 80% but actually 20%
- **Burst updates**: When exiting Doze, 4+ hours of queued updates execute rapidly
- **Inconsistent intervals**: Sometimes 15 min, sometimes 2+ hours

### Invariant That Breaks
**"Sensor updates occur at regular 15-minute intervals"**

The code assumes:
- Periodic work executes every 15 minutes reliably
- Sensor data freshness is consistent
- Automations can rely on this cadence

But Doze mode breaks this:
- Work is deferred during idle periods
- Multiple updates execute in burst after Doze exits
- No guaranteed execution time

### User Visibility / Exploitability

**User Visible**: YES
- Automations fail or execute incorrectly
- Sensor data shows long gaps in Home Assistant
- Battery level, location, etc. become stale
- Users report "sensors not working" when actually delayed

**Exploitable**: LOW
- Could be used to hide location by forcing Doze
- Automation bypass if relying on sensor state

**Brittle**: HIGH
- Depends on device manufacturer Doze implementation
- Difficult to test (requires hours of idle time)
- User expectations vs Android reality mismatch

### Current Mitigation

**Minimal mitigation**:

1. **Network constraint** (`SensorWorker.kt:23-24`)
   ```kotlin
   val constraints = Constraints.Builder()
       .setRequiredNetworkType(NetworkType.CONNECTED).build()
   ```
   - Prevents running without network
   - Doesn't address Doze delays

2. **15-minute interval chosen** (compromise)
   - More frequent = more battery drain
   - Less frequent = staler data
   - 15 min is middle ground

**Gaps**:
- No Doze mode exemption request (could use `setRequiresDeviceIdle(false)` but discouraged)
- No foreground service for critical sensors (only WebSocket has this)
- No user notification when sensors are stale
- No retry mechanism for failed sensor updates
- No fallback for critical sensors (battery, location)

Potential improvements:
- Use `setExpedited()` for critical sensor updates (Android 12+)
- Implement foreground service for location tracking (already exists for HighAccuracyLocationService but not general sensors)
- Add UI indicator showing last successful sensor update time
- Implement sensor-specific update strategies (battery every hour, location every 15 min)

---

## Summary and Recommendations

### Severity Classification

| Weird State | Severity | Frequency | User Impact |
|-------------|----------|-----------|-------------|
| #1: WebSocket outlives Activity | High | Common | Stale UI, missed updates |
| #2: Token refresh during network transition | Critical | Moderate | Auth failures, forced logouts |
| #3: Stale subscriptions after reconnect | Critical | Common | Silent event loss |
| #4: WorkManager vs actual network | Medium | Common | Battery drain, failed connections |
| #5: Activity recreation during auth | Medium | Moderate | Duplicate connections, UI inconsistency |
| #6: Multi-server shared manager | Medium | Rare | Unclear failure indication |
| #7: Sensor updates in Doze mode | High | Very Common | Stale automation data |

### Recommendations

#### Immediate (Critical)

1. **Fix subscription re-establishment** (#3)
   - Implement actual re-subscription logic after WebSocket reconnect
   - Clear stale subscriptions on connection loss
   - Add subscription health checks

2. **Add token refresh mutex** (#2)
   - Prevent concurrent refresh attempts
   - Add retry with exponential backoff
   - Detect network changes during refresh

#### Short-term (High Priority)

3. **Add UI synchronization** (#1)
   - Implement refresh-on-resume pattern
   - Add visual indicator of connection state
   - Clear stale UI state when Activity recreates

4. **Improve sensor reliability** (#7)
   - Add expedited work for critical sensors
   - Show last update time in UI
   - Implement sensor-specific strategies

#### Long-term (Medium Priority)

5. **Enhanced network detection** (#4)
   - Add captive portal detection
   - Implement circuit breaker for failed connections
   - Add exponential backoff for retries

6. **Multi-server UX** (#6)
   - Per-server connection status
   - Separate notifications for server issues
   - Priority-based server management

7. **Activity lifecycle improvements** (#5)
   - Centralize connection state management
   - Add Activity instance tracking
   - Implement proper cleanup on rotation

### Testing Recommendations

Many of these weird states are difficult to test with standard unit/integration tests. Recommended approaches:

1. **Chaos Engineering**: Use tools to simulate network failures, Doze mode, Activity recreation
2. **Espresso Tests**: For lifecycle scenarios (rotation, background/foreground)
3. **Robolectric**: For WorkManager constraint testing
4. **Manual Testing**: Long-running tests with real devices in Doze mode
5. **Logging/Telemetry**: Add structured logging to detect these states in production

### Code Patterns to Avoid

Based on this analysis:

1. **Don't use `System.currentTimeMillis()` directly** - Use injected `Clock` for testability
2. **Don't assume network constraints guarantee reachability** - Add application-level checks
3. **Don't share global state across Activity instances** - Use ViewModel or proper lifecycle scoping
4. **Don't silently drop errors** - Surface connection/auth issues to user
5. **Don't assume subscriptions persist across reconnections** - Explicitly re-subscribe

---

## Conclusion

The Home Assistant Android app exhibits sophisticated state management with excellent use of modern Android patterns (WorkManager, Hilt, Coroutines, Flow). However, the complexity of coordinating:
- Multiple server connections
- WebSocket lifecycle vs Activity lifecycle
- Background work vs Doze mode
- Authentication state vs network state

Creates several "semantic gaps" where the intended design model doesn't account for edge cases. Most issues are **brittle** rather than **exploitable**, but they significantly impact **user experience** through:
- Silent failures
- Stale data
- Unclear error states
- Battery drain

The recommended fixes are targeted and surgical, focusing on:
1. Explicit state synchronization
2. Better error surfacing
3. Lifecycle-aware cleanup
4. User-facing status indicators

These changes would move the app from "usually works" to "reliably works in edge cases."
