# Root Cause Analysis: Design Smells
## Home Assistant Companion for Android

**Analysis Date**: January 20, 2026  
**Purpose**: Identify shared root causes behind the 7 documented weird states

---

## Methodology

This document analyzes the 7 weird states to identify the fundamental design and architectural issues that enable these problems. Rather than treating each weird state as an isolated bug, we identify the **shared root causes** that explain multiple weird states simultaneously.

---

## Four Root Cause Categories

After deep analysis, the 7 weird states can be explained by **4 fundamental root causes**:

1. **Lifecycle Boundary Mismatches**
2. **Missing Serialization/Ownership Models**
3. **Incorrect Abstraction of "Connection" vs "Session" vs "Activity"**
4. **Android Background Execution Semantics**

---

## Root Cause #1: Lifecycle Boundary Mismatches

### Definition

**Lifecycle Boundary Mismatch** occurs when two components with different lifecycles share state or dependencies, but the code doesn't explicitly handle the boundary conditions where one lifecycle event occurs while the other is in a different state.

### Core Problem

The app has multiple overlapping lifecycle scopes:
- **Activity lifecycle** (CREATED → STARTED → RESUMED → PAUSED → STOPPED → DESTROYED)
- **ViewModel lifecycle** (survives Activity recreation, tied to ViewModelStore)
- **Repository lifecycle** (Singleton, lives for app lifetime)
- **WorkManager lifecycle** (Periodic, independent of UI)
- **WebSocket connection lifecycle** (connect → auth → active → closing → closed)

These lifecycles **overlap but don't align**, creating gaps where:
- Component A believes it owns a resource, but Component B has already destroyed it
- Component A's event arrives while Component B is transitioning between states
- Component A's state is stale because Component B already transitioned

### Weird States Explained by This Root Cause

| Weird State | Lifecycle Mismatch | Explanation |
|-------------|-------------------|-------------|
| **#1: WebSocket Outlives Activity** | WorkManager lifecycle ≠ Activity lifecycle | WebSocket (in WorkManager, 15-min periodic) persists while Activity is destroyed. Events arrive but no UI to display them. |
| **#5: Activity Recreation During Auth** | Activity lifecycle ≠ WebSocket auth lifecycle | Activity destroyed/recreated mid-authentication. New Activity instance doesn't know auth is in progress. |

### Code Evidence

**State #1 - WebSocket/Activity Mismatch**:
```kotlin
// WebsocketManager.kt:64-67 - WorkManager lifecycle (15 min periodic)
val websocketNotifications =
    PeriodicWorkRequestBuilder<WebsocketManager>(15, TimeUnit.MINUTES)
        .build()

// WebViewActivity.kt - Activity lifecycle (user-driven)
override fun onStop() {
    super.onStop()
    // Activity stops but WebSocket continues in background
    // No synchronization mechanism
}
```

**State #5 - Activity Recreation During Auth**:
```kotlin
// WebSocketCoreImpl.kt:484-518 - Auth takes up to 30 seconds
val result = withTimeoutOrNull(30.seconds) {
    val haVersion = authCompleted.await()
    // Activity could be recreated during this 30-second window
}

// WebViewActivity - Can be destroyed/recreated anytime
// No coordination between Activity recreation and ongoing auth
```

### Missing Design Pattern

**Needed**: **Lifecycle-Aware State Manager**

A component that:
1. Tracks which lifecycle owns which state
2. Explicitly handles boundary transitions
3. Invalidates stale state when lifecycle changes
4. Notifies observers of lifecycle-driven state changes

**Current Gap**: State is shared across lifecycles without ownership tracking or boundary handling.

---

## Root Cause #2: Missing Serialization/Ownership Models

### Definition

**Missing Serialization/Ownership** occurs when multiple components can concurrently access shared mutable state without:
1. A clear owner of the state
2. Serialization of mutations (e.g., mutex, locks, atomic operations)
3. A defined order of operations

### Core Problem

The app has several shared mutable states accessed concurrently:
- **Token refresh** - Multiple components can trigger refresh simultaneously
- **WebSocket subscriptions** - activeMessages ConcurrentHashMap modified from multiple threads
- **Connection state** - Modified from OkHttp callbacks and coroutine threads
- **Server configuration** - Read/written from multiple contexts

Without explicit ownership and serialization:
- Race conditions occur
- State becomes inconsistent
- Operations interleave unpredictably

### Weird States Explained by This Root Cause

| Weird State | Missing Serialization | Explanation |
|-------------|----------------------|-------------|
| **#2: Token Refresh During Network Transition** | No mutex on token refresh | Multiple concurrent refresh attempts possible. Network change mid-refresh causes race. No serialization of refresh operations. |
| **#3: Stale Subscriptions After Reconnect** | No ownership of subscription lifecycle | Old subscription IDs retained in activeMessages. New connection doesn't know about them. No atomic reconnect+resubscribe operation. |

### Code Evidence

**State #2 - Token Refresh Race**:
```kotlin
// AuthenticationRepositoryImpl.kt:133-162
private suspend fun refreshSessionWithToken(baseUrl: HttpUrl, refreshToken: String) {
    val server = server()
    return authenticationService.refreshToken(
        // NO MUTEX HERE - multiple threads can enter simultaneously
        // Potential race conditions:
        // - Thread 1: WebSocket auth triggers refresh
        // - Thread 2: API call triggers refresh (token expired)
        // - Thread 3: Network change triggers refresh
        // All can execute concurrently, causing:
        //   - Multiple HTTP requests
        //   - Last-writer-wins on session update
        //   - Inconsistent session state
    )
}
```

**State #3 - Subscription Ownership**:
```kotlin
// WebSocketCoreImpl.kt:154
val activeMessages = ConcurrentHashMap<Long, ActiveMessage>()

// Problem: Who owns subscription lifecycle?
// - Flow collector owns the subscription conceptually
// - WebSocketCore manages the subscription ID
// - Server manages actual subscription state
// - But no single owner with atomic operations
// Result: Subscription IDs become stale after reconnect
```

### Missing Design Pattern

**Needed**: **Single Owner with Serialized Mutations**

For each shared state:
1. **Single Owner** - One component responsible for mutations
2. **Mutex/Lock** - Serialize concurrent access
3. **Atomic Operations** - State transitions are all-or-nothing
4. **Clear Handoff Protocol** - If ownership transfers, explicit handoff

**Current Gap**: 
- Token refresh has no owner (any component can trigger)
- Subscription lifecycle split between Flow collector and WebSocketCore
- No serialization of concurrent mutations

---

## Root Cause #3: Incorrect Abstraction of "Connection" vs "Session" vs "Activity"

### Definition

**Incorrect Abstraction** occurs when the code model doesn't match the domain model. Specifically:
- **Connection** = Network-level socket (TCP/WebSocket)
- **Session** = Authentication-level state (tokens, user identity)
- **Activity** = UI-level component (what user sees)

These are **distinct concepts** but the code often conflates them.

### Core Problem

The app treats these as a single monolithic entity when they actually have:
- **Different lifecycles** - Connection can outlive Activity, Session outlives Connection
- **Different failure modes** - Connection fails (network), Session fails (auth), Activity fails (system)
- **Different recovery strategies** - Connection: reconnect, Session: re-auth, Activity: recreate

Conflating these concepts creates confusion about:
- What should happen when Connection drops but Session is valid?
- What should happen when Activity recreates but Connection is alive?
- What should happen when Session expires during active Connection?

### Weird States Explained by This Root Cause

| Weird State | Abstraction Confusion | Explanation |
|-------------|----------------------|-------------|
| **#1: WebSocket Outlives Activity** | Connection ≠ Activity | Code assumes Connection lifecycle tied to Activity. But Connection (in WorkManager) outlives Activity. |
| **#2: Token Refresh During Network** | Session ≠ Connection | Network change affects Connection, but code refreshes Session. These are orthogonal concerns conflated. |
| **#5: Activity Recreation During Auth** | Activity ≠ Session | Activity recreation doesn't affect Session, but code doesn't separate these concerns. |

### Code Evidence

**Conflation in WebSocketCore**:
```kotlin
// WebSocketCoreImpl.kt - Conflates Connection + Session + Activity
class WebSocketCoreImpl {
    // Connection state
    private var connectionState: WebSocketState = WebSocketState.Initial
    
    // Session state (auth)
    private var authCompleted = CompletableDeferred<HomeAssistantVersion?>()
    
    // But no clear separation of:
    // - When Connection is alive but Session expired
    // - When Activity is destroyed but Connection alive
    // - When to reconnect Connection vs re-auth Session
}
```

**Conflation in WebViewActivity**:
```kotlin
// WebViewActivity.kt
// Activity owns UI but also manages WebSocket connection
// These are separate concerns conflated:
// - UI rendering (Activity's job)
// - Connection management (Repository's job)
// - Session management (Auth repository's job)
```

### Missing Design Pattern

**Needed**: **Separate Abstractions with Clear Boundaries**

Should have:
1. **ConnectionManager** - Manages TCP/WebSocket connection
   - Responsibilities: connect, disconnect, reconnect, heartbeat
   - Lifecycle: Independent of Activity
   
2. **SessionManager** - Manages authentication session
   - Responsibilities: login, logout, token refresh, session validation
   - Lifecycle: Survives connection drops
   
3. **ActivityPresenter** - Manages UI state
   - Responsibilities: display data, handle user input, lifecycle callbacks
   - Lifecycle: Tied to Activity

**Current Gap**: These are conflated, causing confusion about responsibilities and lifecycle.

---

## Root Cause #4: Android Background Execution Semantics

### Definition

**Android Background Execution Semantics** refers to the platform-specific constraints on background work:
- **Doze mode** - System defers background work during idle
- **WorkManager constraints** - Work only runs when constraints met
- **Battery optimization** - System can kill background processes
- **Network availability** - WorkManager's "connected" ≠ actual reachability

The code assumes **"scheduled work executes reliably"** but Android makes no such guarantee.

### Core Problem

Android's background execution is **opportunistic, not guaranteed**:
- Work scheduled every 15 minutes might run every 2 hours in Doze
- "Network connected" constraint doesn't mean server is reachable
- Background processes can be killed mid-execution
- Foreground/background transitions affect execution priority

The code's mental model:
```
Schedule work every 15 min → Work executes every 15 min → Reliable periodic updates
```

Android's reality:
```
Schedule work every 15 min → Doze defers to maintenance window → Work executes 1-6 hours later → Stale data
```

### Weird States Explained by This Root Cause

| Weird State | Android Semantics Gap | Explanation |
|-------------|----------------------|-------------|
| **#4: WorkManager vs Actual Network** | "Connected" ≠ Reachable | WorkManager constraint NetworkType.CONNECTED = any network. Doesn't mean server reachable (captive portal, VPN, DNS). |
| **#7: Sensor Updates in Doze Mode** | Periodic ≠ Reliable | 15-min periodic becomes 1+ hour gaps in Doze. WorkManager defers to maintenance windows. |

### Code Evidence

**State #4 - Network Constraint Mismatch**:
```kotlin
// SensorWorker.kt:23-24
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED).build()

// Developer expectation: "Network connected means I can reach my server"
// Android reality: "Network connected means WiFi or cellular is ON"
//   - Could be captive portal (coffee shop WiFi)
//   - Could be no internet access (airplane mode WiFi)
//   - Could be VPN required but not connected
//   - Could be DNS failure
```

**State #7 - Doze Mode Deferrals**:
```kotlin
// SensorWorker.kt:27-29
val sensorWorker =
    PeriodicWorkRequestBuilder<SensorWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()

// Developer expectation: "Runs every 15 minutes"
// Android reality: "Runs every 15 minutes WHEN NOT IN DOZE"
//   - Doze mode (screen off, idle 1+ hour): defers to maintenance windows
//   - Maintenance windows: ~30 min windows every 1-6 hours
//   - Result: 15-min interval becomes 1-6 hour gaps
```

### Missing Design Pattern

**Needed**: **Android-Aware Scheduling**

Must account for Android constraints:
1. **Expedited Work** - For critical operations (Android 12+)
2. **Foreground Service** - For real-time requirements
3. **Application-Level Checks** - Don't trust WorkManager constraints alone
4. **Circuit Breaker** - Detect captive portals, unreachable servers
5. **User Feedback** - Show when background constraints prevent execution

**Current Gap**: 
- Code assumes WorkManager constraints are sufficient
- No detection of captive portals or unreachable servers
- No user indication when Doze defers work
- No expedited work for critical sensors

---

## Root Cause Mapping to Weird States

### Complete Mapping Table

| Weird State | RC#1 Lifecycle | RC#2 Ownership | RC#3 Abstraction | RC#4 Android | Primary Root Cause |
|-------------|----------------|----------------|------------------|--------------|-------------------|
| #1: WebSocket Outlives Activity | ✓ | | ✓ | | **RC#1: Lifecycle Mismatch** |
| #2: Token Refresh During Network | | ✓ | ✓ | | **RC#2: Missing Serialization** |
| #3: Stale Subscriptions | | ✓ | | | **RC#2: Missing Ownership** |
| #4: WorkManager vs Network | | | | ✓ | **RC#4: Android Semantics** |
| #5: Activity Recreation During Auth | ✓ | | ✓ | | **RC#1: Lifecycle Mismatch** |
| #6: Multi-Server Shared Manager | | ✓ | | | **RC#2: Missing Ownership** |
| #7: Sensor Updates in Doze | | | | ✓ | **RC#4: Android Semantics** |

### Root Cause Impact Analysis

| Root Cause | States Affected | Severity | Pervasiveness |
|------------|----------------|----------|---------------|
| **RC#1: Lifecycle Boundary Mismatches** | #1, #5 | HIGH | Medium - affects Activity/Background boundaries |
| **RC#2: Missing Serialization/Ownership** | #2, #3, #6 | CRITICAL | High - affects all concurrent operations |
| **RC#3: Incorrect Abstraction** | #1, #2, #5 | HIGH | High - fundamental design issue |
| **RC#4: Android Background Semantics** | #4, #7 | HIGH | Very High - affects all background work |

**Most Pervasive**: RC#4 (Android Semantics) - affects all background operations
**Most Critical**: RC#2 (Missing Ownership) - causes silent data loss and auth failures
**Most Fundamental**: RC#3 (Incorrect Abstraction) - underlying design flaw

---

## Design Smell Summary

### 🔴 Critical Design Smells

1. **"God Object" Connection Manager**
   - **Smell**: WebSocketCore conflates Connection, Session, and Activity concerns
   - **Impact**: Unclear responsibilities, difficult lifecycle management
   - **Fix**: Separate ConnectionManager, SessionManager, ActivityPresenter

2. **Unguarded Shared Mutable State**
   - **Smell**: Token refresh, subscriptions, connection state modified without serialization
   - **Impact**: Race conditions, inconsistent state, concurrent mutation bugs
   - **Fix**: Add mutexes, define single owners, use atomic operations

3. **Implicit Lifecycle Coupling**
   - **Smell**: Components with different lifecycles share state without explicit boundary handling
   - **Impact**: Stale state, missed events, resource leaks
   - **Fix**: Lifecycle-aware state manager, explicit invalidation on transitions

### 🟡 Significant Design Smells

4. **Platform Assumption Mismatch**
   - **Smell**: Code assumes Android background execution is reliable and deterministic
   - **Impact**: Stale data, missed updates, battery drain
   - **Fix**: Android-aware scheduling, user feedback, expedited work for critical paths

5. **Insufficient Abstraction Boundaries**
   - **Smell**: Connection/Session/Activity concepts not cleanly separated
   - **Impact**: Confusion about failure modes and recovery strategies
   - **Fix**: Clear abstraction layers with well-defined interfaces

6. **Missing Observability**
   - **Smell**: State transitions and failures logged but not surfaced to user
   - **Impact**: User doesn't know why things aren't working
   - **Fix**: Per-component status indicators, error notifications, last-update timestamps

### 🟢 Minor Design Smells

7. **Global State Without Ownership**
   - **Smell**: ServerManager manages all servers in single worker with single notification
   - **Impact**: Unclear which server is failing
   - **Fix**: Per-server state management and notifications

8. **Optimistic Concurrency**
   - **Smell**: Code assumes operations won't interleave without guards
   - **Impact**: Race conditions in edge cases
   - **Fix**: Defensive programming, mutexes, atomic operations

---

## Architectural Recommendations

### Immediate Refactoring (High ROI)

1. **Add Serialization to Token Refresh** (RC#2)
   ```kotlin
   private val refreshMutex = Mutex()
   
   private suspend fun refreshSessionWithToken(...) {
       refreshMutex.withLock {
           // Existing refresh logic
       }
   }
   ```
   - **Effort**: Low (1 hour)
   - **Impact**: Eliminates auth race conditions
   - **Risk**: Very low

2. **Implement Subscription Re-establishment** (RC#2)
   ```kotlin
   private suspend fun handleAuthComplete(...) {
       if (successful) {
           connectionState = WebSocketState.Active
           authCompleted.complete(parsedVersion)
           resubscribeAll() // NEW
       }
   }
   ```
   - **Effort**: Medium (4-8 hours)
   - **Impact**: Eliminates silent event loss
   - **Risk**: Medium (affects critical path)

3. **Add Refresh-on-Resume** (RC#1)
   ```kotlin
   override fun onResume() {
       super.onResume()
       lifecycleScope.launch {
           viewModel.refreshState() // NEW
       }
   }
   ```
   - **Effort**: Low (2-4 hours)
   - **Impact**: Eliminates stale UI
   - **Risk**: Low

### Long-term Refactoring (Architectural)

4. **Separate Connection/Session/Activity Abstractions** (RC#3)
   - **Effort**: High (2-4 weeks)
   - **Impact**: Cleaner architecture, easier maintenance
   - **Risk**: High (major refactor)
   
5. **Implement Lifecycle-Aware State Manager** (RC#1)
   - **Effort**: High (1-2 weeks)
   - **Impact**: Proper lifecycle boundary handling
   - **Risk**: Medium (new component)

6. **Android-Aware Background Scheduling** (RC#4)
   - **Effort**: Medium (1 week)
   - **Impact**: Reliable background operations
   - **Risk**: Medium (WorkManager API changes)

---

## Conclusion

The 7 weird states are **symptoms of 4 root causes**:

1. **Lifecycle Boundary Mismatches** - Components with different lifecycles share state without boundary handling
2. **Missing Serialization/Ownership** - Shared mutable state accessed concurrently without guards
3. **Incorrect Abstraction** - Connection/Session/Activity concepts conflated
4. **Android Background Semantics** - Platform constraints not properly handled

**Most Critical**: RC#2 (Missing Serialization) causes immediate user-facing failures
**Most Pervasive**: RC#4 (Android Semantics) affects all users during background operation
**Most Fundamental**: RC#3 (Incorrect Abstraction) is the underlying design flaw enabling others

**Recommendation**: 
1. Fix RC#2 immediately (add mutexes, implement re-subscription) - **Days**
2. Address RC#1 short-term (lifecycle-aware state) - **Weeks**
3. Refactor RC#3 long-term (separate abstractions) - **Months**
4. Enhance RC#4 continuously (Android-aware patterns) - **Ongoing**

The codebase shows **excellent use of modern Android patterns** but exhibits **semantic gaps at abstraction boundaries**. The recommended fixes are **targeted and incremental**, focusing on high-impact, low-risk changes first, with architectural improvements deferred to reduce risk.

---

**Status**: Root Cause Analysis Complete  
**Next Steps**: 
1. Review findings with team
2. Prioritize fixes (RC#2 → RC#1 → RC#4 → RC#3)
3. Create implementation tickets
4. Plan incremental rollout with telemetry
