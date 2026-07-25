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

| Rate | P50 | P95 | Max | Denied % |
|------|-----|-----|-----|----------|
| 500 req/s | 3.98ms | 4.89ms | 71ms | 99.66% |
##### Allow Path
Policy limit set high so all requests execute the full Redis Lua script .

| Rate | P50 | P95 | Max | Denied % |
|------|-----|-----|-----|----------|
| 500 req/s | 4.01ms | 4.94ms | 99ms | 0% |

##### Observations
- Denial responses are faster than allows
- At 500 req/s all-allow traffic, Redis Lua script execution starts hitting the 50ms coroutine timeout
- Fail-open masks the problem: service remains available but rate limiting accuracy degrades
- Need to add a fail-open metric to prometheus 
- Bottleneck: Redis RTT under write contention, not Sluice application layer

##### Gaps Identified
- `sluice_request_outcomes_total` needs a `result="failed_open"` & a `result="failed_closed"` 
- Lettuce client does not reconnect after Redis restart — requires Sluice pod restart to recover
- connection timeout has been missed as a user configurable property

##### How to Reproduce
  settings
  | setting | value |
  | - | -|
  | auth | enabled |
  | circuitBreaker | disabled |


  ```bash
  cd loadtest/
  ./run.sh --server-url <url> --namespace <namespace> --key-name <key> --k8s-secret <secret> --policy-id <policy>
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


