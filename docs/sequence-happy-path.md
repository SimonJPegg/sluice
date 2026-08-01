# Sequence: Happy Path

Request allowed under a fixed window policy. All components healthy.

```mermaid
sequenceDiagram
    participant C as Client
    participant K as Ktor
    participant R as RateLimitRoute
    participant V as RequestValidator
    participant PR as PolicyRegistry
    participant IC1 as InstrumentedStore (chain)
    participant T as ThrottledStore
    participant FM as FailureModeStore
    participant CB as CircuitBreakerStore
    participant IC2 as InstrumentedStore (store)
    participant RS as RedisCounterStore
    participant Lua as Redis (Lua script)

    C->>K: POST /check {key, policyId}
    K->>K: Auth check (if enabled)
    K->>R: Route matched

    R->>V: request.validate(registry, maxLength)
    V->>PR: get(policyId)
    PR-->>V: Policy
    V-->>R: ValidRequest(key, policy)

    R->>IC1: evaluate(key, policy)
    IC1->>T: evaluate(key, policy)
    T->>T: concurrency check (atomic increment)
    T->>FM: evaluate(key, policy)
    FM->>CB: evaluate(key, policy)
    CB->>CB: state = CLOSED
    CB->>IC2: evaluate(key, policy)
    IC2->>RS: evaluate(key, policy)
    RS->>Lua: EVALSHA fixed_window.lua [key] [limit, window]
    Lua->>Lua: INCR key, set EXPIRE if count==1
    Lua-->>RS: {1, count, ttl}
    RS-->>IC2: Allowed(remaining, resetIn)
    IC2->>IC2: record duration metric
    IC2-->>CB: Allowed
    CB->>CB: reset failure count
    CB-->>FM: Allowed
    FM-->>T: Allowed (not Failed, pass through)
    T->>T: concurrency decrement
    T-->>IC1: Allowed
    IC1->>IC1: record duration metric
    IC1-->>R: Allowed

    R->>R: toProcessed() → AllowedRequest
    R->>R: toResponse() → set headers
    R-->>C: 200 OK + X-RateLimit-Limit, Remaining, Reset
```

## Denied variant

Same flow until the Lua script returns `{0, count, ttl}` — the counter exceeded the limit.
`RedisCounterStore` returns `Denied(retryAfter)` instead of `Allowed`, and the response maps to `429` with `Retry-After`.
