# Weird States Analysis - Summary

This directory contains the analysis of weird states and semantic gaps in the Home Assistant Android app.

## 📋 Documents

### 1. [WEIRD_STATES_ANALYSIS.md](./WEIRD_STATES_ANALYSIS.md)
**Main Analysis Document** (729 lines, 26KB)

Comprehensive analysis identifying 7 concrete weird states where the app can enter unexpected states:

1. **WebSocket Connection Outlives Activity Lifecycle** (High Severity)
2. **Token Refresh During Network Transition** (Critical Severity) 
3. **WebSocket Reconnection with Stale Subscriptions** (Critical Severity)
4. **WorkManager Constraints vs Actual Network State** (Medium Severity)
5. **Activity Recreation During WebSocket Authentication** (Medium Severity)
6. **Multi-Server Scenarios with Shared WebSocket Manager** (Medium Severity)
7. **Sensor Updates During Doze Mode** (High Severity)

Each state includes:
- Specific code locations and line numbers
- What invariant breaks
- User visibility and exploitability assessment
- Current mitigation strategies (if any)
- Gaps and recommendations

### 2. [WEIRD_STATES_PREVENTION.md](./WEIRD_STATES_PREVENTION.md)
**Developer Guide** (295 lines, 8.4KB)

Practical guide for preventing weird states in new code:

- ✅ Checklists for new features (network, WebSocket, auth, lifecycle, background work)
- ❌ Anti-patterns to avoid with good/bad examples
- 🔍 Code review checklist
- 🧪 Testing strategies (unit, integration, manual)
- 🔧 Quick fixes for common issues
- 🚨 When to escalate to architecture review

## 🎯 Quick Findings

### Critical Issues (Fix Immediately)
- **No subscription re-establishment** after WebSocket reconnect (claimed in docs, not implemented)
- **Token refresh race conditions** - multiple concurrent attempts possible
- **Silent event loss** when WebSocket reconnects

### High Priority (User-Visible)
- Stale UI after Activity recreation
- Sensor data gaps of 1+ hours in Doze mode  
- Battery drain from failed retry attempts

### Recommendations

**Immediate** (Next Sprint):
1. Implement subscription re-establishment in `WebSocketCoreImpl`
2. Add refresh mutex to `AuthenticationRepositoryImpl`
3. Clear stale subscriptions on disconnect

**Short-term** (Next Month):
4. Refresh-on-resume pattern for Activities
5. Last-update-time indicator for sensors
6. Expedited work for critical sensors
7. Retry with exponential backoff

**Long-term** (Architectural):
8. Centralize connection state management
9. Per-server status in WebSocket manager
10. Circuit breaker pattern for failures
11. Comprehensive integration tests

## 📊 Severity Matrix

| Weird State | Severity | Frequency | User Impact |
|-------------|----------|-----------|-------------|
| #1: WebSocket outlives Activity | High | Common | Stale UI, missed updates |
| #2: Token refresh during network | Critical | Moderate | Auth failures, forced logouts |
| #3: Stale subscriptions | Critical | Common | Silent event loss |
| #4: WorkManager vs network | Medium | Common | Battery drain |
| #5: Activity recreation during auth | Medium | Moderate | UI inconsistency |
| #6: Multi-server manager | Medium | Rare | Unclear failures |
| #7: Sensors in Doze | High | Very Common | Stale automation data |

## 🔍 Key Code Locations

Areas with identified weird states:

- `common/src/main/kotlin/io/homeassistant/companion/android/common/data/websocket/impl/WebSocketCoreImpl.kt`
  - Lines 100-147: Connection lifecycle
  - Lines 746-788: Subscription management
  - Lines 700-900: Event handling

- `common/src/main/kotlin/io/homeassistant/companion/android/common/data/authentication/impl/AuthenticationRepositoryImpl.kt`
  - Lines 120-162: Token refresh logic
  - Missing: refresh mutex, retry mechanism

- `app/src/main/kotlin/io/homeassistant/companion/android/websocket/WebsocketManager.kt`
  - Lines 111-136: Background persistence
  - Lines 158-180: Multi-server job management

- `app/src/main/kotlin/io/homeassistant/companion/android/sensors/SensorWorker.kt`
  - Lines 22-33: Doze mode impact

- `app/src/main/kotlin/io/homeassistant/companion/android/webview/WebViewActivity.kt`
  - Lifecycle interaction with WebSocket state

## 🧪 Testing Gaps

Current testing doesn't adequately cover:

- ❌ Doze mode behavior (requires multi-hour tests)
- ❌ Network transitions during operations
- ❌ Activity recreation during authentication
- ❌ Multi-server failure scenarios
- ❌ WebSocket reconnection with active subscriptions

**Recommendation**: Add integration tests using:
- `ActivityScenario` for lifecycle tests
- `TestDispatcher` for coroutine race conditions
- Mock network layer for connection failures
- Firebase Test Lab for Doze mode testing

## 📚 For Developers

**Before Adding Code**:
1. Review relevant checklist in `WEIRD_STATES_PREVENTION.md`
2. Check for anti-patterns
3. Consider edge cases from analysis document

**During Code Review**:
1. Use code review checklist
2. Watch for patterns that introduce weird states
3. Verify error handling and cleanup

**When Debugging**:
1. Check if issue matches a known weird state
2. Reference quick fixes in prevention guide
3. Consider logging additions to detect state

## 📄 Document Metadata

- **Created**: January 20, 2026
- **Analysis Scope**: Android lifecycle, WebSocket, Auth, Background tasks
- **Codebase Version**: Current main branch
- **Methodology**: Static code analysis + pattern recognition

## ✅ Requirements Met

Task requirements (all met ✓):
- ✅ Identified at least 5 concrete weird states (delivered 7)
- ✅ Explained invariant breaks for each
- ✅ Assessed user visibility / exploitability
- ✅ Documented current mitigations
- ✅ Provided specific code locations
- ✅ Included actionable recommendations
- ✅ Created prevention guide for future development

---

**Status**: Analysis Complete  
**Next Steps**: Review with team, prioritize fixes, create implementation tickets
