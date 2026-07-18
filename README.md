# Sluice

A standalone rate limiting service. You ask "can I do this?" over HTTP, it tells you yes or no with a bit of context.

## Status

Work in progress. Building this to learn Kotlin properly.

Currently implemented:
- Fixed window algorithm (in-memory + Redis Lua script)
- Sliding window counter algorithm (in-memory + Redis Lua script)
- Sliding window log algorithm (in-memory + Redis Lua script)
- Token bucket algorithm (in-memory + Redis Lua script)
- Strategy pattern — algorithms are pluggable, stores are thin dispatchers
- Concurrent access tested and proven (ConcurrentHashMap.compute + Redis atomicity)
- Fail-open/fail-closed error handling when Redis is unavailable
- YAML-based policy registry (loaded at startup, validated, no runtime mutation)
- Ktor HTTP server with health endpoints (`/health/live`, `/health/ready`)
- Correlation ID propagation (X-Request-ID — read or generate, always echoed)
- YAML application config (Helm-friendly, no HOCON)

Coming: rate limit endpoint, metrics, structured logging, Helm chart.

## Roadmap

### v1.0.0 (current target)
- Rate limit endpoint with typed request pipeline
- Structured logging, Prometheus metrics, dependency health status
- Helm chart, CI pipeline, container image with SBOM

### v1.1.0 — Resilience
- Circuit breaker on Redis
- Connection pooling
- Backpressure / load shedding under degraded Redis

### v1.2.0 — Security
- Authentication on `/check` endpoint
- Input hardening (key length, encoding, injection)

### v1.3.0 — Confidence
- Load testing with k6 (published baselines)
- Chaos testing (Redis failure, network partitions, latency injection)

## Tech

Kotlin 2.x, Ktor, Lettuce (Redis), Micrometer. Runs on JVM 21. Will deploy to a homelab Kubernetes cluster eventually.

## Why

Most rate limiters are either a library or a gateway feature (not testable). This is a service that owns its decisions and exposes them as observable metrics.

Also I wanted a project where concurrency, atomicity, and time semantics matter.
