# DI Scope Analysis: AuthenticationRepositoryImpl

## Evidence Summary

### 1. Constructor Analysis
```kotlin
class AuthenticationRepositoryImpl @AssistedInject constructor(
    private val authenticationService: AuthenticationService,
    private val serverManager: ServerManager,
    @Assisted private val serverId: Int,  // <-- KEY: serverId is @Assisted parameter
    @NamedSessionStorage private val localStorage: LocalStorage,
    @NamedInstallId private val installId: SuspendProvider<String>,
) : AuthenticationRepository
```

**Key Evidence**: The `serverId` parameter is marked with `@Assisted`, indicating this class uses Assisted Injection pattern.

### 2. Factory Definition
```kotlin
@AssistedFactory
internal interface AuthenticationRepositoryFactory {
    fun create(serverId: Int): AuthenticationRepositoryImpl  // <-- Creates instance per serverId
}
```

**Key Evidence**: Factory creates instances on-demand per serverId.

### 3. ServerManager Implementation
```kotlin
internal class ServerManagerImpl @Inject constructor(
    private val authenticationRepositoryFactory: AuthenticationRepositoryFactory,
    // ...
) : ServerManager {
    
    // Stores one AuthenticationRepository instance per serverId
    private val authenticationRepos = ServerMap<AuthenticationRepository>(authenticationRepositoryFactory::create)
    
    override suspend fun authenticationRepository(serverId: Int): AuthenticationRepository {
        val id = validateServerId(serverId)
        return authenticationRepos.getOrCreate(id)  // <-- Caches per serverId
    }
}
```

### 4. ServerMap Implementation
```kotlin
private class ServerMap<T>(private val creator: suspend (Int) -> T) {
    private val internalMap = mutableMapOf<Int, T>()  // <-- Caches instances by serverId
    private val mutex = Mutex()

    suspend fun getOrCreate(serverId: Int): T {
        return mutex.withLock {
            internalMap.getOrPut(serverId) {
                creator(serverId)  // Creates new instance only if not cached
            }
        }
    }
}
```

## Conclusion

**`AuthenticationRepositoryImpl` is scoped PER SERVER**, not shared across servers.

### How it works:
1. `ServerManagerImpl` maintains a `ServerMap<AuthenticationRepository>`
2. `ServerMap` caches one `AuthenticationRepositoryImpl` instance per `serverId`
3. When `serverManager.authenticationRepository(serverId)` is called:
   - First call for serverId=1: Creates new instance via factory
   - Subsequent calls for serverId=1: Returns cached instance
   - Call for serverId=2: Creates new instance (separate from serverId=1)

### Implications for Mutex Implementation

✅ **Current PR skeleton is CORRECT** - Using instance-level mutex (`private val refreshMutex = Mutex()`) is appropriate because:

1. Each server gets its own `AuthenticationRepositoryImpl` instance
2. Each instance has its own `refreshMutex`
3. Token refreshes for server A are serialized independently from server B
4. No cross-server contention (which is correct - servers have independent auth state)

### Why the original assumption was correct:

In the PR skeleton documentation, I stated:
> "The mutex is **per-server instance** because:
> - `AuthenticationRepositoryImpl` is created per-server via `@Assisted` injection
> - Each server has independent auth state
> - No cross-server contention needed"

This is **accurate** based on the DI scoping evidence.

## No Changes Required

The current implementation in `PR_SKELETON_CONCURRENCY_CONTROL.md` is correct and does not need revision.

### Verification:

```kotlin
// Example usage:
val server1Auth = serverManager.authenticationRepository(serverId = 1)
val server2Auth = serverManager.authenticationRepository(serverId = 2)

// server1Auth and server2Auth are DIFFERENT instances
// Each has its own refreshMutex
// Concurrent refreshes:
//   - Within server1: Serialized by server1's mutex
//   - Within server2: Serialized by server2's mutex
//   - Across servers: No interaction (as intended)
```

This design is optimal because:
1. **Independence**: Servers have independent auth state and credentials
2. **Isolation**: Auth failures in one server don't block others
3. **Simplicity**: No need for complex keyed mutex maps
4. **Performance**: No global serialization bottleneck
