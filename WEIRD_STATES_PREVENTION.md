# Quick Reference: Weird States Prevention

This document provides quick references for developers to avoid introducing new weird states and patterns to watch for during code review.

## Checklist for New Features

### Before Adding Network-Related Code

- [ ] Does this code handle mid-request network changes?
- [ ] Is there a retry mechanism with exponential backoff?
- [ ] Are network errors surfaced to the user (not just logged)?
- [ ] Does it work correctly with VPN/captive portals?
- [ ] Is there a timeout to prevent indefinite hangs?

### Before Adding WebSocket Subscriptions

- [ ] What happens if the connection drops mid-subscription?
- [ ] Are subscriptions re-established after reconnect?
- [ ] Is there a cleanup mechanism when subscribers disappear?
- [ ] Can subscription IDs from old connections conflict with new ones?
- [ ] Is there a subscription health check or heartbeat?

### Before Adding Authentication/Token Code

- [ ] Can multiple token refreshes happen simultaneously?
- [ ] What happens if network changes during refresh?
- [ ] Is there a mutex to prevent concurrent refreshes?
- [ ] Are refresh failures handled gracefully (not just thrown)?
- [ ] Does it use `Clock` instead of `System.currentTimeMillis()`?

### Before Adding Activity Lifecycle Code

- [ ] What happens if the Activity is destroyed mid-operation?
- [ ] Are coroutines scoped to appropriate lifecycle (viewModelScope, lifecycleScope)?
- [ ] Is state properly saved/restored across recreations?
- [ ] Are background operations cancelled when appropriate?
- [ ] Does it use `lifecycleScope.repeatOnLifecycle()` for Flow collection?

### Before Adding Background Work (WorkManager)

- [ ] What happens during Doze mode?
- [ ] Are constraints appropriate (battery, network, etc.)?
- [ ] Is there a maximum retry count to prevent infinite loops?
- [ ] Are errors reported to the user (not just logged)?
- [ ] Does it respect user settings (NEVER, SCREEN_ON, etc.)?

## Code Patterns to Avoid

### ❌ Anti-Pattern: Direct System Time Usage
```kotlin
// BAD
val expiration = System.currentTimeMillis() / 1000 + expiresIn

// GOOD
@Inject lateinit var clock: Clock
val expiration = clock.now().epochSeconds + expiresIn
```

### ❌ Anti-Pattern: Unchecked Network Assumptions
```kotlin
// BAD
if (hasActiveConnection()) {
    // Assume WebSocket will connect
    connectWebSocket()
}

// GOOD
if (hasActiveConnection()) {
    try {
        connectWebSocket()
    } catch (e: Exception) {
        showErrorToUser(e)
        scheduleRetryWithBackoff()
    }
}
```

### ❌ Anti-Pattern: Silent Subscription Loss
```kotlin
// BAD
fun subscribeToEvents() {
    websocket.subscribe().collect { event ->
        // What if connection drops?
        handleEvent(event)
    }
}

// GOOD
fun subscribeToEvents() {
    websocket.subscribe()
        .retry { cause ->
            Timber.w(cause, "Subscription lost, retrying")
            delay(backoff())
            true
        }
        .collect { event ->
            handleEvent(event)
        }
}
```

### ❌ Anti-Pattern: Global State Without Synchronization
```kotlin
// BAD
private var isRefreshing = false

suspend fun refresh() {
    if (isRefreshing) return
    isRefreshing = true
    // Race condition if called concurrently
    doRefresh()
    isRefreshing = false
}

// GOOD
private val refreshMutex = Mutex()

suspend fun refresh() {
    refreshMutex.withLock {
        doRefresh()
    }
}
```

### ❌ Anti-Pattern: Uncancellable Background Work
```kotlin
// BAD
lifecycleScope.launch(Dispatchers.IO) {
    // Continues even after Activity destroyed
    longRunningTask()
}

// GOOD
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        withContext(Dispatchers.IO) {
            longRunningTask()
        }
    }
}
```

## Code Review Checklist

When reviewing PRs, watch for these patterns that often introduce weird states:

### Network & WebSocket Changes

- [ ] Proper error handling (try/catch, not just .onFailure)
- [ ] Timeout mechanisms (withTimeoutOrNull)
- [ ] Retry logic with backoff (not infinite retries)
- [ ] State cleanup on failure
- [ ] User notification of errors

### Authentication Changes

- [ ] Mutex around token refresh
- [ ] Check for in-flight requests before starting new ones
- [ ] Proper exception types (AuthorizationException, not generic Exception)
- [ ] Session state validation before operations
- [ ] Clock injection instead of System.currentTimeMillis()

### Lifecycle Changes

- [ ] Coroutines scoped appropriately (viewModelScope, lifecycleScope)
- [ ] Use of repeatOnLifecycle for Flows
- [ ] Proper cleanup in onDestroy/onCleared
- [ ] State saved in ViewModel or SavedStateHandle
- [ ] No memory leaks (weak references, cancellation)

### Background Work Changes

- [ ] Appropriate WorkManager constraints
- [ ] Maximum retry count or timeout
- [ ] Doze mode considerations
- [ ] User-configurable settings respected
- [ ] Foreground service for critical operations

## Testing Strategies

### Unit Tests Should Cover

1. **Race conditions**: Use `TestDispatcher` with `advanceUntilIdle()`
2. **Network failures**: Mock network layer, simulate failures
3. **Token expiration**: Mock clock, advance time
4. **Lifecycle state**: Use `TestLifecycleOwner`
5. **Concurrent operations**: Launch multiple coroutines

### Integration Tests Should Cover

1. **Activity recreation**: Use Espresso with `recreate()`
2. **Background/foreground**: Use ActivityScenario lifecycle methods
3. **Network changes**: Use `ConnectivityManager` test API
4. **Doze mode**: Requires manual testing or Firebase Test Lab

### Manual Testing Checklist

- [ ] Airplane mode during operation
- [ ] WiFi to cellular transition mid-operation
- [ ] Captive portal network
- [ ] Device rotation during loading
- [ ] Home button during operation
- [ ] Device in Doze mode for 2+ hours
- [ ] Multiple servers configured
- [ ] Token expiration while offline

## Quick Fixes for Common Issues

### Issue: Stale UI after Activity recreation

**Fix**: Use repeatOnLifecycle
```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.state.collect { state ->
            updateUI(state)
        }
    }
}
```

### Issue: WebSocket events lost after reconnect

**Fix**: Implement re-subscription (needs code change in WebSocketCoreImpl)
```kotlin
private suspend fun resubscribeAll() {
    activeMessages.values
        .filterIsInstance<ActiveMessage.Subscription>()
        .forEach { subscription ->
            sendMessage(subscription.request)
        }
}
```

### Issue: Multiple concurrent token refreshes

**Fix**: Add mutex
```kotlin
private val refreshMutex = Mutex()

private suspend fun refreshSessionWithToken(baseUrl: HttpUrl, refreshToken: String) {
    refreshMutex.withLock {
        // existing refresh logic
    }
}
```

### Issue: Sensor data stale in Doze mode

**Fix**: Add expedited work for critical sensors
```kotlin
val sensorWorker = PeriodicWorkRequestBuilder<SensorWorker>(15, TimeUnit.MINUTES)
    .setConstraints(constraints)
    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) // Android 12+
    .build()
```

### Issue: Network constraint doesn't guarantee reachability

**Fix**: Add application-level checks
```kotlin
private suspend fun canReachServer(): Boolean {
    return try {
        val url = serverManager.getUrl() ?: return false
        val response = okHttpClient.newCall(
            Request.Builder().url(url).head().build()
        ).execute()
        response.isSuccessful
    } catch (e: Exception) {
        false
    }
}
```

## When to Escalate

Escalate to architecture review if:

1. Adding new singleton state that needs cross-Activity coordination
2. Introducing new background worker types
3. Changing authentication or WebSocket core logic
4. Adding new multi-server capabilities
5. Modifying lifecycle handling in base classes

## Resources

- [Android Lifecycle Guide](https://developer.android.com/guide/components/activities/activity-lifecycle)
- [WorkManager Best Practices](https://developer.android.com/guide/background/persistent/how-to/long-running)
- [WebSocket Reconnection Strategies](https://stomp-js.github.io/guide/stompjs/rx-stomp/ng2-stompjs/2018/09/10/preparing-for-production.html)
- [Kotlin Coroutines & Flow Best Practices](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)

---

**Last Updated**: January 20, 2026  
**Based on**: Weird States Analysis (WEIRD_STATES_ANALYSIS.md)
