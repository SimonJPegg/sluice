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
- Ktor HTTP server with health endpoints (`/health/live`, `/health/ready`, `/health/status`)
- `POST /check` — typed request pipeline (receive → validate → evaluate → respond)
- Response headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`
- Input hardening (regex whitelist, configurable max key length, strict JSON parsing)
- Correlation ID propagation (X-Request-ID — read or generate, always echoed)
- YAML application config (validated at startup, Helm-friendly)
- Prometheus metrics (`/metrics`) — request outcomes, latency, validation errors, store health
- Structured logging (logback, JSON format, env-configurable level)
- Dependency status endpoint (`/health/status`) — live Redis ping with latency
- Config validation at startup (missing fields, invalid URIs, bad values — all reported before failing)

Coming: Helm chart, CI pipeline, container image.

## Roadmap

### v1.0.0 (current target)
- Helm chart, CI pipeline, container image with SBOM

### v1.1.0 — Resilience
- Connection pooling
- Backpressure / load shedding under degraded Redis

### v1.2.0 — Security
- Authentication on `/check` endpoint

### v1.3.0 — Confidence
- Load testing with k6 (published baselines)
- Chaos testing (Redis failure, network partitions, latency injection)

## Tech

Kotlin 2.x, Ktor, Lettuce (Redis), Micrometer. Runs on JVM 21. Will deploy to a homelab Kubernetes cluster eventually.

## Why

Most rate limiters are either a library or a gateway feature (not testable). This is a service that owns its decisions and exposes them as observable metrics.

Also I wanted a project where concurrency, atomicity, and time semantics matter.
