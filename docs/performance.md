# Performance Baselines

## Environment

| Component | Detail |
|-|-|
| Sluice | 1 replica, 512Mi request / 4Gi limit, 1000m / 4000m CPU |
| Redis | Single instance, 256Mi request / 512Mi limit, redis:8-alpine, no persistence |
| Network | Via Traefik ingress, TLS terminated at ingress |
| JVM | 21, default GC |
| k6 | v2.1.0, containerised, separate machine (32GB RAM, NVMe) |

All results are single runs, not averaged across multiple executions. k6 runs on a separate host from the cluster — load generator is not competing with the service for resources.

## Scenarios

### Sustained throughput (constant-arrival-rate executor)

#### fixed-window-example

##### Denial Path

Policy: `fixed-window-example` (100 req/min). Majority of requests denied after first second of each window.

| Rate | Med | P95 | Max | Denied % |
|------|-----|-----|-----|----------|
| 500 req/s | 3.98ms | 4.89ms | 71ms | 99.66% |

##### Allow Path

Policy limit set high so all requests execute the full Redis Lua script.

| Rate | Med | P95 | Max | Denied % |
|------|-----|-----|-----|----------|
| 500 req/s | 4.01ms | 4.94ms | 99ms | 0% |

##### Observations

- Denial responses are faster than allows
- At 500 req/s all-allow traffic, Redis Lua script execution starts hitting the 50ms coroutine timeout
- Fail-open masks the problem: service remains available but rate limiting accuracy degrades
- Need to add a fail-open metric to prometheus
- Bottleneck: Redis RTT under write contention, not Sluice application layer

##### How to Reproduce

  settings

  | setting | value |
  | - | -|
  | auth | enabled |
  | circuitBreaker | disabled |

  ```bash
  cd loadtest/
  ./run.sh --server-url <url> --namespace <namespace> --key-name <key> --k8s-secret <secret> --policy-id <policy> --script sustained.ts
  ```

  Script: [`sustained.ts`](../loadtest/sustained.ts)

  policy

  ```yaml
    policies:
      - id: fixed-window-example
        limit: 100000 # open
        # limit: 100 # deny
        window: "PT1M"
        algorithmType: fixed_window
        failType: open
  ```

---

### Burst (ramping-arrival-rate executor)

#### fixed-window-example

Ramps from baseline to target rate, holds, drops back. Looking for the point where latency degrades or errors appear.

##### Denial Path

Policy: `fixed-window-example` (100 req/min). Almost everything denied.

| Target | Achieved | Med | P95 | Max | Errors |
|--------|----------|-----|-----|-----|--------|
| 10000 req/s | 5594 req/s | 5.02ms | 5.5ms | 123ms | 0% |
| 20000 req/s | 2984 req/s | 8.25ms | 1.15s | 11.07s | 6.7% |

##### Allow Path

Policy limit set to 10,000,000 so every request runs the full Redis Lua script.

| Target | Achieved | Med | P95 | Max | Errors |
|--------|----------|-----|-----|-----|--------|
| 10000 req/s | 5589 req/s | 5.03ms | 6.0ms | 220ms | 0% |

##### Observations

- All tests ran with `maxConcurrentRequests` disabled (no load shedding). See "Burst with load shedding" below for behaviour under saturation with shedding enabled.
- Ceiling is ~5500 req/s on this hardware. Both allow and deny paths perform identically.
- Lua script execution is not a factor. Deny doesn't short-circuit faster than allow at scale.
- At 20000 target, CPU saturates on the node. Requests queue, latency goes mental, 6.7% get errors (503 from load shedding or timeouts).
- The service doesn't degrade gracefully between 5500 and saturation — it falls off a cliff. One moment P95 is 5ms, next it's over a second.
- Max VUs at 10000 target: 415 (deny), 247 (allow). Service completes requests fast enough that k6 doesn't need many.
- At 20000 target: all 1000 VUs saturated, 688k iterations dropped. k6 couldn't send them because every VU was blocked waiting.
- Bottleneck: node CPU, not Redis, not Sluice application layer, not network.

##### How to Reproduce

  settings

  | setting | value |
  | - | -|
  | auth | enabled |
  | circuitBreaker | disabled |

  ```bash
  cd loadtest/
  ./run.sh --server-url <url> --namespace <namespace> --key-name <key> --k8s-secret <secret> --policy-id <policy> --script burst.ts
  ```

  Script: [`burst.ts`](../loadtest/burst.ts)

  policy

  ```yaml
    policies:
      - id: fixed-window-example
        limit: 10000000 # open
        # limit: 100 # deny
        window: "PT1M"
        algorithmType: fixed_window
        failType: open
  ```

---

### Burst with load shedding (ramping-arrival-rate executor)

Same 20000 req/s target as above. `maxConcurrentRequests` enabled to see whether shedding helps under saturation.

#### Denial Path

| Shedding Limit | Achieved | Med | P95 | Max | Check Failures |
|----------------|----------|-----|-----|-----|----------------|
| off | 2984 req/s | 8.25ms | 1.15s | 11.07s | 6.7% |
| 1000 | 3339 req/s | 10.23ms | 1.03s | 9.56s | 6.2% |
| 100 | 3841 req/s | 15.8ms | 932ms | 9.92s | 37.9% |

##### Observations

- Shedding improves throughput. At limit 100, achieved 3841 req/s vs 2984 without — requests get rejected fast instead of queuing for seconds.
- But it doesn't fix CPU exhaustion. P95 still nearly a second because the node itself is maxed. Even the rejection path costs cycles.
- At limit 1000, shedding barely fires. Normal in-flight at 5ms/req is ~28. By the time you hit 1000 concurrent, the server is already drowning.
- At limit 100, 37.9% of requests shed. That's correct behaviour — the service is protecting itself. Callers get a fast 503 with `Retry-After` instead of waiting 11 seconds.
- Load shedding protects Redis (fewer requests make it through), but can't protect against node-level resource exhaustion.
- Horizontal scaling is the answer beyond ~5500 req/s on this hardware.

---

## Chaos testing

run with

```bash
gradle chaosTest
```

### Redis unreachable

Circuit breaker is broken. A caller shouldn't see 503 then 429 then 503 again because the circuit breaker is cycling
through open/half-open internally. That's implementation detail leaking into the API. Redis is down, policy says
fail-closed, give them one answer until it comes back.

### connection refused

We didn't catch socket exceptions and were returning 500 errors to clients

## Gaps Identified

- ~~Circuit breaker leaks internal state (open/half-open cycling) into the API — caller sees 503→429→503 instead of a consistent answer per policy fail stance~~
- ~~Socket exceptions from Redis connection refused were not caught — returning raw 500s to clients~~
- ~~`sluice_request_outcomes_total` needs a `result="failed_open"` & a `result="failed_closed"` label~~
- ~~Connection timeout has been missed as a user configurable property~~
- ~~Lettuce client does not reconnect after Redis restart — requires Sluice pod restart to recover~~
- ~~Burst test needs higher target rate (1000+ req/s) to find the actual breaking point~~
