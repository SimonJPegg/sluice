# Performance Baselines

## Environment
| Component | Detail |
|-|-|
| Sluice | 1 replica, 512Mi request / 4Gi limit, 1000m / 4000m CPU |
| Redis | Single instance, 256Mi request / 512Mi limit, redis:8-alpine, no persistence |
| Network | Via Traefik ingress, TLS terminated at ingress |
| JVM | 21, default GC |
| k6 | v2.1.0, containerised |


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
##### Allow Path
Policy limit set high so all requests execute the full Redis Lua script.

| Rate | Med | P95 | Max | Denied % |
|------|-----|-----|-----|----------|
| 476 req/s | 3.97ms | 4.97ms | 80.85ms | 0% |

##### Denial Path
Policy: `fixed-window-example` (100 req/min). Majority denied after first window fills.

| Rate | Med | P95 | Max | Denied % |
|------|-----|-----|-----|----------|
| 476 req/s | 3.97ms | 4.99ms | 74.97ms | 99.62% |

##### Observations
- Max concurrency peaked at 5-6 VUs out of 50+ available — service isn't under pressure at this rate
- Denial path slightly faster than allow (consistent with sustained results)
- Max latency (80ms allow, 74ms deny) exceeds the 50ms coroutine timeout — some requests likely hitting fail-open
- 8-9 dropped iterations — k6 couldn't schedule fast enough, not service-side
- Need to push harder to find the cliff. Current burst script doesn't saturate anything.

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

  #policy
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

## Chaos testing

run with 

```bash
gradle chaosTest
```

### Redis unreachable
CircutBreaker is broken. A caller shouldn't see 503 then 429 then 503 again because the circuit breaker is cycling 
through open/half-open internally. That's implementation detail leaking into the API. Redis is down, policy says 
fail-closed, give them one answer until it comes back.

### connection refused
We didn't catch socket exceptions and were returning 500 errors to clients


## Gaps Identified
- ~~Circuit breaker leaks internal state (open/half-open cycling) into the API — caller sees 503→429→503 instead of a consistent answer per policy fail stance~~
- ~~Socket exceptions from Redis connection refused were not caught — returning raw 500s to clients~~
- ~~`sluice_request_outcomes_total` needs a `result="failed_open"` & a `result="failed_closed"` label~~
- ~~Connection timeout has been missed as a user configurable property~~
- Lettuce client does not reconnect after Redis restart — requires Sluice pod restart to recover
- Burst test needs higher target rate (1000+ req/s) to find the actual breaking point
