# Sequence: Redis Failure

Redis becomes unavailable. Shows the path from timeout through circuit breaker trip to policy-driven fail-open/fail-closed.

## First failure (circuit breaker still closed)

```mermaid
sequenceDiagram
    participant C as Client
    participant R as RateLimitRoute
    participant IC1 as InstrumentedStore (chain)
    participant T as ThrottledStore
    participant FM as FailureModeStore
    participant CB as CircuitBreakerStore
    participant IC2 as InstrumentedStore (store)
    participant RS as RedisCounterStore
    participant Redis as Redis

    C->>R: POST /check {key, policyId}
    R->>R: validate → ValidRequest

    R->>IC1: evaluate(key, policy)
    IC1->>T: evaluate(key, policy)
    T->>FM: evaluate(key, policy)
    FM->>CB: evaluate(key, policy)
    CB->>CB: state = CLOSED (failures < threshold)
    CB->>IC2: evaluate(key, policy)
    IC2->>RS: evaluate(key, policy)
    RS->>Redis: EVALSHA ...
    Redis--xRS: RedisCommandTimeoutException

    RS-->>IC2: Failed(reason, STORE_TIMEOUT)
    IC2->>IC2: record duration + error metric
    IC2-->>CB: Failed
    CB->>CB: recordFailure() (failures++)
    CB-->>FM: Failed(STORE_TIMEOUT)

    FM->>FM: policy.failType == OPEN?

    alt fail-open policy
        FM-->>T: FailedOpen(0, window)
        T-->>IC1: FailedOpen
        IC1-->>R: FailedOpen
        R->>R: toProcessed() → AllowedRequest(remaining=0)
        R-->>C: 200 OK (degraded — no rate limiting)
    else fail-closed policy
        FM-->>T: FailedClosed(window)
        T-->>IC1: FailedClosed
        IC1-->>R: FailedClosed
        R->>R: toProcessed() → DeniedRequest(retryAfter)
        R-->>C: 429 Too Many Requests + Retry-After
    end
```

## After threshold (circuit breaker open)

Once `failures >= failureThreshold`, the circuit breaker stops calling Redis entirely.

```mermaid
sequenceDiagram
    participant C as Client
    participant R as RateLimitRoute
    participant IC1 as InstrumentedStore (chain)
    participant T as ThrottledStore
    participant FM as FailureModeStore
    participant CB as CircuitBreakerStore
    participant Redis as Redis

    C->>R: POST /check {key, policyId}
    R->>R: validate → ValidRequest

    R->>IC1: evaluate(key, policy)
    IC1->>T: evaluate(key, policy)
    T->>FM: evaluate(key, policy)
    FM->>CB: evaluate(key, policy)
    CB->>CB: state = OPEN (failures >= threshold, within resetTimeout)

    Note over CB,Redis: Redis is NOT called

    alt fail-open policy
        CB-->>FM: Allowed(0, window)
        FM-->>T: Allowed (not Failed, pass through)
        T-->>IC1: Allowed
        IC1-->>R: Allowed
        R->>R: toProcessed() → AllowedRequest(remaining=0)
        R-->>C: 200 OK (degraded)
    else fail-closed policy
        CB-->>FM: Failed(CIRCUIT_OPEN)
        FM->>FM: CIRCUIT_OPEN + failType=CLOSED
        FM-->>T: FailedClosed(window)
        T-->>IC1: FailedClosed
        IC1-->>R: FailedClosed
        R->>R: toProcessed() → DeniedRequest
        R-->>C: 429 Too Many Requests + Retry-After
    end
```

## Recovery (half-open probe)

After `resetTimeout` elapses, the circuit breaker lets one request through to test Redis.

```mermaid
sequenceDiagram
    participant C as Client
    participant CB as CircuitBreakerStore
    participant IC2 as InstrumentedStore (store)
    participant RS as RedisCounterStore
    participant Redis as Redis

    C->>CB: evaluate (via chain, omitted for clarity)
    CB->>CB: state = HALF_OPEN (resetTimeout elapsed)
    CB->>IC2: probe — evaluate(key, policy)
    IC2->>RS: evaluate(key, policy)
    RS->>Redis: EVALSHA ...

    alt Redis recovered
        Redis-->>RS: {1, count, ttl}
        RS-->>IC2: Allowed
        IC2-->>CB: Allowed
        CB->>CB: reset() — circuit CLOSED
        CB-->>C: Allowed → 200 OK
    else Redis still dead
        Redis--xRS: RedisException
        RS-->>IC2: Failed
        IC2-->>CB: Failed
        CB->>CB: recordFailure() — circuit stays OPEN
        CB-->>C: Failed → FailureModeStore applies policy
    end
```
