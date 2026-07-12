# Session Plan: Sluice

Target: 2-3 sessions per week, ~1 hour each. Ship when it's done, not before it's good.

---

## Phase 1: Foundation

### Session 1 — Project scaffold
- [x] Gradle project with Kotlin 2.x, Java 21 target
- [x] Dependencies: Ktor (HTTP), Lettuce (Redis), Micrometer (metrics), kotlinx-serialization
- [x] Test dependencies: JUnit 5, Testcontainers (Redis), MockK
- [x] Dockerfile (multi-stage build)
- [x] .editorconfig, .gitignore, detekt config (Kotlin linter)
- [x] First commit: empty project that compiles and runs detekt clean

### Session 2 — Domain model
- [ ] Sealed interface `RateLimitResult` → `Allowed`, `Denied`, `Error`
- [ ] Data classes for `Policy` (limit, window, algorithm, fail-open/closed)
- [ ] Data class for `RateLimitRequest` (key, policy ID)
- [ ] Data class for `RateLimitResponse` (result, remaining, retry-after, reset-at)
- [ ] Unit tests: construction, validation, edge cases
- [ ] ADR 001: Algorithm choice (all four, fixed window first — simplest proves the interface)

### Session 3 — Counter store interface + in-memory implementation
- [ ] `CounterStore` interface: `increment(key, limit, window) → CounterResult`
- [ ] In-memory implementation (ConcurrentHashMap, for testing)
- [ ] Unit tests: basic increment, window expiry, at-limit behaviour
- [ ] Tests for concurrent access (coroutines hammering the same key)

### Session 4 — Redis counter store: fixed window (Lua script)
- [ ] Lua script for atomic fixed window (increment + TTL in one call)
- [ ] `RedisCounterStore` implementation via Lettuce
- [ ] Integration tests with Testcontainers (real Redis)
- [ ] Test: Redis unavailable → graceful failure
- [ ] Test: key expiry mid-operation

### Session 5 — Sliding window counter algorithm
- [ ] Lua script: weighted previous window + current window count
- [ ] Tests: mid-window accuracy, window boundary behaviour
- [ ] Verify the interface didn't need changing (if it did, that's a design smell — fix it)

### Session 6 — Sliding window log algorithm
- [ ] Lua script: sorted set of timestamps, ZRANGEBYSCORE to count within window
- [ ] Tests: precision, memory behaviour with high-traffic keys
- [ ] Test: cleanup of expired entries

### Session 7 — Token bucket algorithm
- [ ] Lua script: refill tokens based on elapsed time, consume one
- [ ] Tests: burst capacity, steady-state refill, empty bucket behaviour
- [ ] ADR 002: Interface survived four algorithms — document what worked and any compromises

### Session 8 — Policy registry
- [ ] YAML config format for policies (limit, window, algorithm, failure mode — all required)
- [ ] `PolicyRegistry` loads and validates on startup
- [ ] Tests: valid config, missing fields, invalid values (negative limit, zero window)
- [ ] Test: unknown policy ID returns clear error

---

## Phase 2: HTTP API

### Session 9 — Ktor server + health endpoints
- [ ] `/health/live` — always 200
- [ ] `/health/ready` — checks Redis connectivity
- [ ] Application entry point wiring (DI via constructor injection, no framework magic)
- [ ] Test: health endpoints respond correctly

### Session 10 — Rate limit endpoint
- [ ] `POST /check` — accepts key + policy ID, returns decision
- [ ] Response headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`
- [ ] Input validation with proper error responses (400, 404 for unknown policy)
- [ ] Integration test: full request→response cycle via Testcontainers

### Session 11 — Error handling + observability
- [ ] Structured logging (logback + JSON format)
- [ ] Prometheus metrics: `sluice_requests_total{result=allowed|denied|error}`, latency histogram
- [ ] `/metrics` endpoint (Micrometer Prometheus registry)
- [ ] Test: metrics increment correctly on allow/deny/error

---

## Phase 3: Deployment

### Session 12 — Container + Helm chart
- [ ] Finalise Dockerfile (distroless base, non-root user, health check)
- [ ] Helm chart: Deployment, Service, ConfigMap (policies), ServiceMonitor (Prometheus)
- [ ] Values file: Redis connection, replica count, resource limits
- [ ] Chart README: what it deploys, required values, assumptions

### Session 13 — CI pipeline + deploy to homelab
- [ ] GitHub Actions: lint (detekt), test, build image, push to GHCR
- [ ] Tag-based releases (semver)
- [ ] Deploy to homelab namespace, verify metrics in Prometheus
- [ ] Smoke test against live service

### Session 14 — README + polish
- [ ] README: what it is, architecture diagram, how to run locally, how to deploy
- [ ] API documentation (OpenAPI spec or clear markdown)
- [ ] Review all ADRs are written
- [ ] Review test coverage, fill any gaps
- [ ] Final commit: v1.0.0 tag

---

## Phase 4: Extensions (optional, if it sparks)

### Session 12+ — Pick one:
- [ ] gRPC interface alongside HTTP
- [ ] Policy CRUD API (Postgres-backed, control plane)
- [ ] Kafka consumer: rate limit decisions as an event stream
- [ ] Grafana dashboard JSON in repo
- [ ] Multi-replica coordination (consistent hashing for key sharding)

---

## Pace

At 2-3 sessions/week, Phase 1-3 is ~5-6 weeks. No rush. If a session needs to spill into two
because you want to understand something properly, that's the right call. The goal is a repo
you can explain every line of, not a deadline.

If a session stalls for more than 15 minutes on a blocker, write down what's blocking and move
to the next session's work. Come back to the blocker fresh.
