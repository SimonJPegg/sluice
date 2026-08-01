# Developer Guide

## Prerequisites

- JDK 21
- Docker (for Testcontainers and local Redis)
- Gradle (wrapper included, no install needed)

## Run locally

```bash
docker compose up
```

Builds from source. Sluice + Redis, example policies mounted from `examples/policies/`.

Then:

```bash
curl -X POST http://localhost:8080/check \
  -H "Content-Type: application/json" \
  -d '{"key": "user-123", "policyId": "fixed-window-example"}'
```

## Run tests

```bash
./gradlew test
```

Unit and integration tests. Integration tests use Testcontainers (real Redis + Toxiproxy). Docker must be running.

Chaos tests are separate (tagged, excluded from default run):

```bash
./gradlew chaosTest
```

Each test class forks its own JVM (`forkEvery = 1`) because container resources accumulate and OOM otherwise.

## Add a new algorithm

Five files:

1. **Lua script** — `src/main/resources/lua/your_algorithm.lua`
   - Must return `{allowed (1/0), count, ttl_remaining}`
   - Same contract as all other scripts

2. **Redis implementation** — `src/main/kotlin/.../core/algorithm/RedisYourAlgorithm.kt`
   - Extend `RedisAlgorithm(scriptLoader)`
   - Override `fileLocation` pointing at your Lua script
   - Override `calculate` only if the base implementation doesn't fit (e.g. token bucket)

3. **In-memory implementation** — `src/main/kotlin/.../core/algorithm/InMemoryYourAlgorithm.kt`
   - Implement `InMemoryAlgorithm`
   - Use `ConcurrentHashMap.compute` for per-key atomicity

4. **Enum value** — add to `AlgorithmType` in `Policy.kt`

5. **Factory registration** — add cases to both `redisAlgorithm()` and `inMemoryAlgorithm()` in `AlgorithmFactory.kt`

The compiler will tell you if you missed a `when` branch.

## Add a new policy

YAML in your policies directory. All fields required:

```yaml
policies:
  - id: your-policy-name
    limit: 100
    window: "PT1M"
    algorithmType: fixed_window
    failType: open
```

- `window` — ISO 8601 duration
- `algorithmType` — `fixed_window`, `sliding_window_counter`, `sliding_window_log`, `token_bucket`
- `failType` — `open` (allow when Redis is dead) or `closed` (deny when Redis is dead)

With hot-reload enabled, drop a new file in the policy directory and the watcher picks it up. Invalid YAML is rejected silently (logged, old policies kept).

## Code style

Enforced by CI:

- **detekt** — Kotlin static analysis. Cyclomatic complexity threshold at 10. Run with `./gradlew detekt`
- **ktfmt** — Kotlin formatter. Run with `./gradlew ktfmtFormat`

Both run in CI before tests. Failures block the build.

## Project structure

```
src/main/kotlin/org/antipathy/sluice/
├── api/                    # HTTP layer (Ktor, routes, config, metrics)
│   ├── Application.kt      # Composition root
│   ├── config/             # Config parsing and validation
│   ├── health/             # Health check models
│   ├── metrics/            # Prometheus metrics
│   ├── model/              # Request/response types, validation, mapping
│   ├── routes/             # Route definitions
│   └── store/              # InstrumentedCounterStore (decorator)
└── core/                   # Rate limiting logic (no HTTP awareness)
    ├── algorithm/          # Algorithm implementations (Redis + in-memory)
    ├── exceptions/         # Domain exceptions
    ├── model/              # RateLimitResponse sealed hierarchy
    ├── policy/             # Policy loading, validation, registry
    └── store/              # CounterStore interface + decorators
```

`core` has no dependency on `api`. `api` consumes `core`.
