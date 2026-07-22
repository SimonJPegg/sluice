# Sluice Helm Chart

Deploys sluice and a single-node Redis to Kubernetes. That's it.

## What you get

- Sluice Deployment with ConfigMap-mounted policies and app config
- Redis Deployment + Service (optional — disable if you've got your own)
- ServiceMonitor for Prometheus (optional — off by default)
- Schema validation so bad config fails at `helm install`, not at 3AM
- Health probes on `/health/live` and `/health/ready`

## Install

From GHCR (OCI registry):

```bash
helm install sluice oci://ghcr.io/simonjpegg/charts/sluice --version 0.0.4
```

From source:

```bash
helm install sluice ./charts/sluice
```

With overrides:

```bash
helm install sluice oci://ghcr.io/simonjpegg/charts/sluice --version 0.0.4 \
  --set image.tag=0.0.4
```

## Test

```bash
helm test sluice
```

Hits the health endpoints from inside the cluster. Pass = service is up and Redis is reachable.

## Bring your own Redis

```yaml
redis:
  enabled: false
```

No Redis resources created. Wire sluice to your external instance via the generated app config or mount your own.

## Policies

Policies live under the `policies` key in `values.yaml`. Each key becomes a file in the ConfigMap:

```yaml
policies:
  my-policies.yaml: |
    policies:
      - id: api-global
        limit: 100
        window: "PT1M"
        algorithmType: fixed_window
        failType: open
```

You need at least one. Schema won't let you install without it.

## Circuit breaker

Off by default. Without it, every request that hits a dead Redis still applies the policy's `failType` (open = allow, closed = deny) — you just pay for the failed network call every time. With it on, sluice stops trying after repeated failures, applies the same `failType` without the round-trip, and probes periodically to see if Redis is back.

```yaml
rateLimiting:
  circuitBreaker:
    enabled: true
    errorThreshold: 5
    timeoutMs: 30000
```

Both values required when enabled. Schema enforces it.

## Metrics

If you're running prometheus-operator (or anything that watches ServiceMonitor CRDs):

```yaml
serviceMonitor:
  enabled: true
  interval: 15s
  labels: {}
```

Scrapes `/metrics` on the service port. Add labels if your Prometheus uses label-based discovery to find ServiceMonitors.

## Values

| Key | Default                     | Description |
|-----|-----------------------------|-------------|
| `replicaCount` | `1`                         | Sluice replicas |
| `image.repository` | `ghcr.io/simonjpegg/sluice` | Container image |
| `image.tag` | `""` (uses appVersion)      | Image tag |
| `image.pullPolicy` | `IfNotPresent`              | Pull policy |
| `service.type` | `ClusterIP`                 | Service type |
| `service.port` | `8080`                      | Service port |
| `resources.requests.cpu` | `100m`                      | CPU request |
| `resources.requests.memory` | `256Mi`                     | Memory request |
| `resources.limits.cpu` | `500m`                      | CPU limit |
| `resources.limits.memory` | `512Mi`                     | Memory limit |
| `rateLimiting.maxIdentifierLength` | `256`                       | Max key length |
| `rateLimiting.circuitBreaker.enabled` | `false`                     | Enable circuit breaker |
| `rateLimiting.circuitBreaker.errorThreshold` | `5`                         | Failures before opening |
| `rateLimiting.circuitBreaker.timeoutMs` | `30000`                     | Reset timeout (ms) |
| `redis.enabled` | `true`                      | Deploy bundled Redis |
| `redis.image` | `redis:8-alpine`            | Redis image |
| `redis.port` | `6379`                      | Redis port |
| `serviceMonitor.enabled` | `false`                     | Enable Prometheus ServiceMonitor |
| `serviceMonitor.interval` | `15s`                       | Scrape interval |
| `serviceMonitor.labels` | `{}`                        | Extra labels for discovery |

## Assumptions

- You have a cluster. Any flavour.
- Bundled Redis is single-node, no persistence. Fine — counters are ephemeral by design. Worst case on restart: one window of requests gets allowed twice.
- JVM startup takes a few seconds. Probes have `initialDelaySeconds` set accordingly. Don't lower them unless you enjoy watching CrashLoopBackOff.
