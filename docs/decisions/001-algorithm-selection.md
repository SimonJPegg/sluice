# 001 — Algorithm Selection

**Date:** 2026-07-12

## Context

Rate limiting has multiple well-known algorithms, each with different tradeoffs around precision,
memory usage, and burst tolerance. We need to pick which to support and in what order.

The four candidates:

- **Fixed window** — simplest. One counter, one TTL. Vulnerable to 2x burst at window edges.
- **Sliding window counter** — weighted approximation across two fixed windows. Good precision,
  bounded memory. The "sensible default" in most literature.
- **Sliding window log** — stores every request timestamp. Precise but memory grows linearly
  with request rate.
- **Token bucket** — capacity-based, refills at a steady rate. Allows bursts up to bucket size.
  Different mental model (capacity vs count).

The question isn't which one is "best" — it's whether we pick one or support all four.

## Decision

Implement all four. The algorithm is an enum on the policy config — the consumer picks the
strategy that fits their use case. Fixed window first because it's the simplest implementation
and proves the interface works before we build the harder ones.

The `CounterStore` dispatches to the correct Lua script based on `policy.algorithmType`. The
interface is the same regardless of algorithm — `evaluate(key, policy) → RateLimitResponse`.

If the interface survives all four without modification, the design is correct. If it doesn't,
we fix it when it breaks rather than over-engineering up front.

## Consequences

- Counter store implementations need four Lua scripts (one per algorithm). More code, but each
  script is small and independently testable.
- Adding a fifth algorithm later means: add an enum value, write the script, add a branch in
  the store. Low ceremony.
- Consumers must explicitly choose an algorithm in their policy config. No default — forces them
  to think about whether they want burst tolerance or strict counting.
- Fixed window going first means we'll have a working end-to-end system quickly. The other three
  slot in without changing anything above the store layer. If they don't, that's the signal the
  abstraction is wrong.
