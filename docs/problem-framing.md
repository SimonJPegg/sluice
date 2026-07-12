# Problem Framing: Sluice

## 1. What's the actual problem?

Services need to protect themselves and their dependencies from being overwhelmed by excessive
request volume. The standard answer is a rate limiter — but most implementations are either:

- A library (tied to one language/framework, not reusable across services)
- A feature of an API gateway (opaque, not testable independently, not controllable by the service owner)

Sluice is a **standalone rate limiting service**. Consumers ask "can I do this?" over HTTP and get
a definitive answer with enough context to act on (remaining budget, retry-after, reset time).
It owns its own state, deploys independently, and exposes its decisions as observable metrics.

This is a coordination problem: multiple concurrent callers, shared mutable counters, time-window
semantics, and correctness under contention.

## 2. What state transitions are involved?

```
┌─────────────┐
│  Key absent  │──── first request ────▶ Counter initialised (count=1, window starts)
└─────────────┘

┌─────────────┐
│ Under limit  │──── request ──────────▶ Counter incremented, ALLOWED
└─────────────┘

┌─────────────┐
│  At limit    │──── request ──────────▶ DENIED (retry-after returned)
└─────────────┘

┌─────────────┐
│ Window expired│──── next request ────▶ Counter reset, ALLOWED (new window)
└─────────────┘
```

The key insight: there is no explicit "reset" transition triggered by a timer. The window expiry
is detected lazily on the next request — or via Redis TTL, depending on algorithm choice.

### Algorithm-specific transitions

**Fixed window:** Counter increments within a calendar-aligned window. Resets hard at boundary.
Vulnerable to burst at window edges (2x burst possible across two adjacent windows).

**Sliding window log:** Stores each request timestamp. Count = timestamps within `[now - window, now]`.
Precise but memory-hungry at scale.

**Sliding window counter:** Weighted approximation — previous window's count × overlap percentage +
current window's count. Good tradeoff between precision and memory.

**Token bucket:** Tokens refill at a steady rate. Each request consumes a token. Allows bursts up to
bucket capacity. Different mental model: capacity-based rather than count-based.

Decision on which to implement first is an ADR. Sliding window counter is the likely starting point
(good precision, bounded memory, well-understood).

## 3. What are the error cases?

### Infrastructure failures
- **Redis unavailable** — fail open or fail closed? This is a policy decision per consumer.
  Default: fail open (allow). Reasoning: if the limiter is down, denying all traffic is worse
  than allowing some excess. But security-sensitive consumers (auth endpoints) should fail closed.
  This must be configurable per policy.
- **Redis returns unexpected data** — corrupted key, wrong type at key (WRONGTYPE error).
  Treat as "key absent", log, re-initialise. Don't crash the service.
- **Redis latency spike** — request to the limiter shouldn't be slower than the request it's
  protecting. Timeout must be aggressive (50ms?). On timeout, apply fail-open/closed policy.

### Concurrency
- **Race condition: two requests read "1 remaining" simultaneously** — both decrement, counter
  goes to -1. Solution: Redis Lua scripts for atomic read-check-increment. This is not optional.
- **Key expires mid-evaluation** — Redis TTL fires between the read and the write. Lua script
  must handle `nil` return from a key that existed moments ago.
- **Thundering herd on window reset** — many requests land at the exact moment a window resets.
  All see "counter = 0", all proceed. This is acceptable behaviour (they're within the new window).

### Input validation
- Missing rate limit key (who is the caller?)
- Policy ID references a policy that doesn't exist
- Negative limit or zero window in configuration
- Key too long (Redis key size limits, memory implications)
- Non-UTF8 or injection attempts in key names

### Operational
- Clock skew between service replicas — if using timestamps, replicas must agree on "now".
  Redis server time is the authority, not the application clock.
- Memory pressure — sliding window log with high-traffic keys could grow unbounded.
  Bounded by window size × request rate, but needs monitoring.
- Configuration reload — what happens to in-flight counters when a policy limit changes?
  Answer: existing windows complete with old limits. New windows use new config. No mid-window changes.

## 4. What's the composition?

Small pieces that do one thing:

```
┌──────────────────────────────────────────────────────────┐
│                        HTTP API                           │
│  (parse request, validate, return decision + headers)    │
└──────────────────────┬───────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────┐
│                   Rate Limiter Core                       │
│  (algorithm selection, policy lookup, decision logic)     │
└──────────────────────┬───────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────┐
│                   Counter Store                           │
│  (Redis operations — atomic increment, TTL, Lua scripts) │
└──────────────────────┬───────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────┐
│                   Policy Registry                         │
│  (load policies from config, validate, expose for lookup)│
└──────────────────────────────────────────────────────────┘
```

Plus cross-cutting:
- **Metrics** — Prometheus counters/histograms for allow/deny/error, latency
- **Health** — liveness (app running) and readiness (Redis reachable)
- **Configuration** — YAML/env-based, hot-reloadable policies (eventually)

Each component is independently testable. The core rate limiter doesn't know about HTTP.
The counter store doesn't know about policies. Composition at the edges.

## 5. How does the next person extend this?

- **New algorithm:** Implement the `RateLimiter` interface (or sealed hierarchy). Plug in via
  policy config. Existing tests act as a contract.
- **New storage backend:** Implement the `CounterStore` interface. Could swap Redis for DynamoDB,
  Postgres, or in-memory (for testing/single-node).
- **New transport:** The core is HTTP-agnostic. Could add gRPC, or embed as a library.
- **New policy source:** Policies come from a registry. Today it's YAML. Tomorrow it could be
  a database, a control plane API, or a ConfigMap watcher.
- **Multi-node coordination:** Phase 3 problem. Consistent hashing to shard keys across replicas,
  or accept approximate counting with local counters + periodic sync.
- **Distributed rate limiting:** Different problem entirely (global limits across data centres).
  Out of scope for v1 but the architecture doesn't prevent it.

The key principle: **interfaces at the boundaries, sealed types for decisions, configuration
drives behaviour.** A new engineer reads the sealed `RateLimitResult` type and knows immediately
what outcomes are possible. They follow the interface to see where to plug in.
