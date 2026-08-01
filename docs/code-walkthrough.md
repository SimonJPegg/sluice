# Code Walkthrough

These might not jump out from the HLD, so I thought they might be worth explaining.

## Sealed types as exhaustive control flow

Every rate limit evaluation returns one of five outcomes:

```kotlin
sealed interface RateLimitResponse

data class Allowed(val remaining: UInt, val resetIn: Duration) : RateLimitResponse
data class Denied(val retryAfter: Duration) : RateLimitResponse
data class Failed(val reason: String, val retryAfter: Duration?, ...) : RateLimitResponse
data class FailedOpen(val remaining: UInt, val resetIn: Duration) : RateLimitResponse
data class FailedClosed(val retryAfter: Duration) : RateLimitResponse
```

`FailedOpen` and `FailedClosed` allow us to return a response to the client that follows what the policy states (and they look identical to the client), but the type itself is metadata that lets us create metrics on it. The type system forces each to be handled everywhere they are consumed:

```kotlin
fun RateLimitResponse.toProcessed(policy: Policy): ProcessedRequest =
    when (this) {
      is Allowed -> AllowedRequest(remaining.toInt(), policy.limit.toInt(), resetIn)
      is Denied -> DeniedRequest(retryAfter)
      is Failed -> FailedRequest(reason, failureCategory, retryAfter)
      is FailedOpen -> AllowedRequest(remaining.toInt(), policy.limit.toInt(), resetIn)
      is FailedClosed -> DeniedRequest(retryAfter)
    }
```

No `else` branch. Add a sixth outcome and the compiler breaks every `when` that doesn't handle it.

## Store composition

The store chain is built by wrapping decorators. Each one does exactly one thing:

```kotlin
val withCircuitBreaker =
    config.circuitBreaker?.let {
      CircuitBreakerCounterStore(
          InstrumentedCounterStore(baseStore, metrics, "store"),
          it.failureThreshold,
          it.resetTimeout,
      )
    } ?: InstrumentedCounterStore(baseStore, metrics, "store")

val withFailureMode = FailureModeCounterStore(withCircuitBreaker)

val withThrottle =
    config.maxConcurrentRequests?.let { ThrottledCounterStore(it, withFailureMode) }
        ?: withFailureMode

val finalStore = InstrumentedCounterStore(withThrottle, metrics, "chain")
```

Every layer implements `CounterStore`. One interface: `evaluate(key, policy) → RateLimitResponse`. Circuit breaker and throttling are config-driven — omit them and the chain skips those layers at startup. `FailureModeCounterStore` and `InstrumentedCounterStore` are always present. Each layer is independently testable because it takes a `CounterStore` and returns a `CounterStore`.

Two `InstrumentedCounterStore` instances: inner one tracks raw Redis latency, outer one tracks what the caller actually experienced (including circuit breaker short-circuits and throttle rejections). Different metrics from the same decorator.

## Lua return contract

The sliding window log doesn't add an entry when it denies a request. There's no value in polluting the sorted set with rejected timestamps.

```lua
if usage + 1 <= limit then
  local member = now .. ':' .. usage
  redis.call('ZADD', key, now, member)
  redis.call('EXPIRE', key, window)
  return {1, usage + 1, ttl}
end

return {0, usage, ttl}
```

This broke the original design where Kotlin derived allow/deny from `count <= limit`. On denial, count stays at exactly the limit. `count <= limit` is true. Kotlin allows a request that should be denied.

Fix: all scripts return `{allowed, count, ttl}`. The script owns the decision. Kotlin reads a flag instead of re-deriving it. Added the explicit contract to all four algorithms at once so the interface stays uniform.

Full context in [ADR 003](decisions/003-lua-return-contract.md).
